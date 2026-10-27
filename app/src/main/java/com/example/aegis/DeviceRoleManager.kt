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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

val Context.deviceRoleDataStore: DataStore<Preferences> by preferencesDataStore(name = "aegis_device_role_prefs")

/**
 * Central manager for device-specific role selection ("phone-a" or "phone-b").
 * Persists the role locally per physical device using DataStore Preferences & SharedPreferences.
 * Ensures zero-flicker frame-0 startup restoration, completely independent role storage per device,
 * and safe, clean session teardown upon switching roles.
 */
object DeviceRoleManager {

    private const val TAG = "AEGIS_DeviceRole"

    const val ROLE_PHONE_A = "phone-a"
    const val ROLE_PHONE_B = "phone-b"

    val KEY_DEVICE_ROLE = stringPreferencesKey("device_selected_role")

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Observable Compose States
    val selectedRole = mutableStateOf<String?>(null)
    val isRoleSelected = mutableStateOf(false)

    /**
     * Initializes the role manager synchronously at startup (in Activity.onCreate).
     * Loads the saved role from SharedPreferences on frame 0 to avoid flashing the selection screen.
     * Reconciles asynchronously with DataStore Preferences.
     */
    fun initialize(context: Context): String? {
        val appContext = context.applicationContext

        // 1. Frame-0 instant synchronous read
        val initialRole = AegisPreferences.getSavedRole(appContext)
        if (!initialRole.isNullOrBlank()) {
            selectedRole.value = initialRole
            isRoleSelected.value = true
            Log.d(TAG, "Frame-0 restored saved role from SharedPreferences: $initialRole")
        } else {
            selectedRole.value = null
            isRoleSelected.value = false
            Log.d(TAG, "No role selected on this device yet.")
        }

        // 2. Async reconciliation with DataStore Preferences
        scope.launch(Dispatchers.IO) {
            try {
                val prefs = appContext.deviceRoleDataStore.data.first()
                val dsRole = prefs[KEY_DEVICE_ROLE]?.trim()?.lowercase()

                withContext(Dispatchers.Main) {
                    if (!dsRole.isNullOrBlank()) {
                        if (selectedRole.value != dsRole) {
                            selectedRole.value = dsRole
                            isRoleSelected.value = true
                            AegisPreferences.setSavedRole(appContext, dsRole)
                        }
                    } else if (!initialRole.isNullOrBlank()) {
                        // Populate DataStore from existing prefs
                        saveToDataStore(appContext, initialRole)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "DataStore read error: ${e.message}")
            }
        }

        return selectedRole.value
    }

    /**
     * Persists the newly selected role locally on this physical device.
     */
    suspend fun selectRole(context: Context, role: String) {
        val cleanRole = role.trim().lowercase()
        require(cleanRole == ROLE_PHONE_A || cleanRole == ROLE_PHONE_B) {
            "Invalid role: $role. Must be '$ROLE_PHONE_A' or '$ROLE_PHONE_B'."
        }

        val appContext = context.applicationContext

        // 1. Save synchronously in SharedPreferences
        AegisPreferences.setSavedRole(appContext, cleanRole)

        // 2. Save in DataStore Preferences
        withContext(Dispatchers.IO) {
            saveToDataStore(appContext, cleanRole)
        }

        // 3. Update Compose state
        selectedRole.value = cleanRole
        isRoleSelected.value = true

        // 4. Update Signaling and FCM
        CallSignalingClient.setDeviceId(cleanRole)

        // 5. Update EmergencyContactManager for this device
        EmergencyContactManager.initialize(appContext, cleanRole)

        Log.i(TAG, "Device role persisted as '$cleanRole'.")
    }

    /**
     * Safely switches the device to a new role without leaving stale listeners,
     * active audio sessions, or dangling notifications.
     */
    suspend fun switchRoleSafely(context: Context, newRole: String) {
        val appContext = context.applicationContext

        // Clean up any active voice protection foreground service
        try {
            VoiceSafetyService.stop(appContext)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping voice service: ${e.message}")
        }

        // End any active LiveKit audio call
        try {
            if (LiveKitCallManager.isCallActive) {
                LiveKitCallManager.endCall(appContext)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error ending LiveKit call: ${e.message}")
        }

        // Dismiss incoming call notifications if any
        try {
            val activeId = CallSignalingClient.activeIncomingInvitation.value?.invitationId
            if (!activeId.isNullOrBlank()) {
                EmergencyCallNotificationManager.cancelNotification(appContext, activeId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error canceling notifications: ${e.message}")
        }

        // Clear active signaling invitations & feedback
        CallSignalingClient.activeIncomingInvitation.value = null
        CallSignalingClient.callerFeedbackMessage.value = null
        CallSignalingClient.activeOutgoingInvitationId.value = null

        // Reset Emergency Orchestrator session
        try {
            EmergencyOrchestrator.startNewSosEvent()
        } catch (e: Exception) {
            Log.w(TAG, "Error resetting SOS event: ${e.message}")
        }

        // Persist and apply the new role
        selectRole(appContext, newRole)
    }

    /**
     * Resets role selection so the device returns to the initial role selection screen.
     */
    fun clearRoleSelection(context: Context) {
        val appContext = context.applicationContext
        AegisPreferences.clearSavedRole(appContext)
        selectedRole.value = null
        isRoleSelected.value = false

        scope.launch(Dispatchers.IO) {
            try {
                appContext.deviceRoleDataStore.edit { prefs ->
                    prefs.remove(KEY_DEVICE_ROLE)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error clearing role from DataStore: ${e.message}")
            }
        }
    }

    private suspend fun saveToDataStore(context: Context, role: String) {
        try {
            context.deviceRoleDataStore.edit { prefs ->
                prefs[KEY_DEVICE_ROLE] = role
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error saving role to DataStore: ${e.message}")
        }
    }
}
