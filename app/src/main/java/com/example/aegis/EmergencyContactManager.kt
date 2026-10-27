package com.example.aegis

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

val Context.emergencyContactDataStore: DataStore<Preferences> by preferencesDataStore(name = "aegis_emergency_contact_prefs")

/**
 * Manages persistent storage, synchronization, and caching of Phone B's emergency contact number.
 * Ensures the contact is loaded early at startup, persists across app restarts via DataStore & SharedPreferences,
 * synchronizes with PostgreSQL backend as the authoritative source, and ensures immediate offline availability.
 */
object EmergencyContactManager {

    private const val TAG = "AEGIS_EmergencyContact"

    val KEY_PHONE = stringPreferencesKey("phone_b_emergency_number")
    val KEY_NAME = stringPreferencesKey("phone_b_emergency_name")

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Observable Compose UI State
    val savedContactPhone = mutableStateOf<String?>(null)
    val savedContactName = mutableStateOf("Phone B")
    val isContactConfigured = mutableStateOf(false)
    val isSyncing = mutableStateOf(false)
    val statusFeedback = mutableStateOf<String?>(null)
    val isError = mutableStateOf(false)

    /**
     * Initializes the contact manager as early as possible (e.g. Activity onCreate).
     * Populates in-memory and Compose state immediately from local persistent storage (offline-first),
     * and triggers a background sync to refresh against the authoritative backend.
     */
    fun initialize(context: Context, deviceId: String) {
        val appContext = context.applicationContext

        // 1. Synchronous instant read from AegisPreferences (frame 0 availability)
        val initialPhone = AegisPreferences.getEmergencyContactPhone(appContext)
        val initialName = AegisPreferences.getEmergencyContactName(appContext)

        if (!initialPhone.isNullOrBlank()) {
            savedContactPhone.value = initialPhone
            savedContactName.value = initialName
            isContactConfigured.value = true
            EmergencyOrchestrator.emergencyRecipientNumber.value = initialPhone
            Log.d(TAG, "Loaded cached contact from SharedPreferences on frame 0.")
        }

        // 2. Async read from DataStore Preferences to confirm / reconcile
        scope.launch(Dispatchers.IO) {
            try {
                val prefs = appContext.emergencyContactDataStore.data.first()
                val dsPhone = prefs[KEY_PHONE]
                val dsName = prefs[KEY_NAME] ?: "Phone B"

                withContext(Dispatchers.Main) {
                    if (!dsPhone.isNullOrBlank()) {
                        savedContactPhone.value = dsPhone
                        savedContactName.value = dsName
                        isContactConfigured.value = true
                        EmergencyOrchestrator.emergencyRecipientNumber.value = dsPhone
                        // Keep AegisPreferences aligned
                        AegisPreferences.setEmergencyContactPhone(appContext, dsPhone)
                        AegisPreferences.setEmergencyContactName(appContext, dsName)
                    } else if (!initialPhone.isNullOrBlank()) {
                        // Populate DataStore from existing prefs
                        saveToDataStore(appContext, initialPhone, initialName)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "DataStore read exception: ${e.message}")
            }

            // 3. Early authoritative sync from backend
            refreshAuthoritativeContact(appContext, deviceId)
        }
    }

    /**
     * Queries the backend API for the authoritative emergency contact record.
     * Updates local persistent cache if changed. If offline, retains local cache without crashing.
     */
    suspend fun refreshAuthoritativeContact(context: Context, deviceId: String): Result<String?> {
        val appContext = context.applicationContext
        isSyncing.value = true

        return withContext(Dispatchers.IO) {
            try {
                val cleanId = deviceId.trim().ifBlank { "phone-a" }
                val response = AegisApiClient.api.getEmergencyContact(cleanId)

                withContext(Dispatchers.Main) {
                    isSyncing.value = false
                    if (response.isSuccessful && response.body() != null) {
                        val body = response.body()!!
                        val phone = body.phone_number.trim()
                        val name = body.contact_name?.trim() ?: "Phone B"

                        savedContactPhone.value = phone
                        savedContactName.value = name
                        isContactConfigured.value = true
                        isError.value = false
                        EmergencyOrchestrator.emergencyRecipientNumber.value = phone

                        // Persist to local caches
                        AegisPreferences.setEmergencyContactPhone(appContext, phone)
                        AegisPreferences.setEmergencyContactName(appContext, name)
                        scope.launch(Dispatchers.IO) {
                            saveToDataStore(appContext, phone, name)
                        }

                        Log.d(TAG, "Authoritative emergency contact refreshed from backend.")
                        Result.success(phone)
                    } else if (response.code() == 404) {
                        Log.i(TAG, "No emergency contact configured on backend for device '$cleanId'.")
                        // If local cache had a number, we retain local cache but don't mark error
                        if (savedContactPhone.value.isNullOrBlank()) {
                            isContactConfigured.value = false
                        }
                        Result.success(null)
                    } else {
                        val errMsg = "Backend returned HTTP ${response.code()}"
                        statusFeedback.value = errMsg
                        isError.value = true
                        Result.failure(IOException(errMsg))
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isSyncing.value = false
                    val errMsg = "Server unreachable (${e.message ?: "Network error"}). Using local cache."
                    Log.w(TAG, errMsg)
                    // If we have a local cached number, preserve it!
                    if (!savedContactPhone.value.isNullOrBlank()) {
                        statusFeedback.value = "Offline mode: using cached emergency number."
                        isError.value = false
                    } else {
                        statusFeedback.value = "Emergency contact unavailable (offline)."
                        isError.value = true
                    }
                    Result.failure(e)
                }
            }
        }
    }

    /**
     * Validates and saves Phone B's emergency contact number.
     * The backend database is the authoritative saved record.
     * Only updates local persistent cache on backend success. Never falsely reports success.
     */
    suspend fun saveEmergencyContact(
        context: Context,
        deviceId: String,
        phoneNumber: String,
        contactName: String = "Phone B"
    ): Result<String> {
        val appContext = context.applicationContext
        val cleanPhone = phoneNumber.trim()
        val cleanName = contactName.trim().ifBlank { "Phone B" }
        val cleanOwner = deviceId.trim().ifBlank { "phone-a" }

        // 1. Client-side format validation
        if (!EmergencyUtils.isValidPhoneNumber(cleanPhone)) {
            isError.value = true
            val errorMsg = "Invalid phone number format. Must contain 7–15 digits (e.g. +1234567890)."
            statusFeedback.value = errorMsg
            return Result.failure(IllegalArgumentException(errorMsg))
        }

        isSyncing.value = true
        isError.value = false
        statusFeedback.value = "Saving emergency contact to backend..."

        return withContext(Dispatchers.IO) {
            try {
                val request = SaveEmergencyContactRequest(
                    owner_device_id = cleanOwner,
                    phone_number = cleanPhone,
                    contact_name = cleanName
                )
                val response = AegisApiClient.api.saveEmergencyContact(cleanOwner, request)

                withContext(Dispatchers.Main) {
                    isSyncing.value = false
                    if (response.isSuccessful && response.body() != null) {
                        val body = response.body()!!
                        val savedPhone = body.phone_number.trim()
                        val savedName = body.contact_name?.trim() ?: cleanName

                        // Update in-memory state
                        savedContactPhone.value = savedPhone
                        savedContactName.value = savedName
                        isContactConfigured.value = true
                        EmergencyOrchestrator.emergencyRecipientNumber.value = savedPhone

                        // Consistent local persistent cache update
                        AegisPreferences.setEmergencyContactPhone(appContext, savedPhone)
                        AegisPreferences.setEmergencyContactName(appContext, savedName)
                        scope.launch(Dispatchers.IO) {
                            saveToDataStore(appContext, savedPhone, savedName)
                        }

                        isError.value = false
                        statusFeedback.value = "Emergency contact saved successfully."
                        Log.i(TAG, "Emergency contact successfully saved to backend and local cache.")
                        Result.success(savedPhone)
                    } else {
                        val errMsg = "Failed to save contact (HTTP ${response.code()}). Changes not saved."
                        isError.value = true
                        statusFeedback.value = errMsg
                        Log.e(TAG, errMsg)
                        Result.failure(IOException(errMsg))
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isSyncing.value = false
                    isError.value = true
                    val errMsg = "Network error: Failed to save to backend (${e.message ?: "connection error"})."
                    statusFeedback.value = errMsg
                    Log.e(TAG, "Error saving emergency contact: ${e.message}", e)
                    Result.failure(e)
                }
            }
        }
    }

    private suspend fun saveToDataStore(context: Context, phone: String, name: String) {
        try {
            context.emergencyContactDataStore.edit { prefs ->
                prefs[KEY_PHONE] = phone
                prefs[KEY_NAME] = name
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed writing to DataStore: ${e.message}")
        }
    }
}
