package com.example.aegis

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages persistent storage of device role, backend URL, and FCM tokens.
 * Ensures settings survive app restarts, process termination, and background wakeups.
 */
object AegisPreferences {
    private const val PREFS_NAME = "aegis_settings_prefs"
    private const val KEY_DEVICE_ID = "pref_device_id"
    private const val KEY_DEVICE_ROLE = "pref_device_role"
    private const val KEY_BASE_URL = "pref_base_url"
    private const val KEY_FCM_TOKEN = "pref_fcm_token"
    private const val KEY_EMERGENCY_CONTACT_PHONE = "pref_emergency_contact_phone"
    private const val KEY_EMERGENCY_CONTACT_NAME = "pref_emergency_contact_name"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Checks whether the user on this physical device has explicitly selected a role.
     * Returns false on initial app install before role selection.
     */
    fun hasSelectedRole(context: Context): Boolean {
        val role = getPrefs(context).getString(KEY_DEVICE_ROLE, null)
        return !role.isNullOrBlank()
    }

    /**
     * Retrieves the explicitly saved role for this device ("phone-a" or "phone-b").
     * Returns null if no role has been chosen yet.
     */
    fun getSavedRole(context: Context): String? {
        val role = getPrefs(context).getString(KEY_DEVICE_ROLE, null)?.trim()
        return if (!role.isNullOrBlank()) role.lowercase() else null
    }

    /**
     * Persists the selected role on this device and syncs device_id.
     */
    fun setSavedRole(context: Context, role: String) {
        val clean = role.trim().lowercase()
        getPrefs(context).edit()
            .putString(KEY_DEVICE_ROLE, clean)
            .putString(KEY_DEVICE_ID, clean)
            .apply()
    }

    /**
     * Clears the saved role on this device, returning to unconfigured state.
     */
    fun clearSavedRole(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_DEVICE_ROLE)
            .remove(KEY_DEVICE_ID)
            .apply()
    }

    fun getDeviceId(context: Context): String {
        return getSavedRole(context) ?: getPrefs(context).getString(KEY_DEVICE_ID, "phone-b") ?: "phone-b"
    }

    fun setDeviceId(context: Context, deviceId: String) {
        setSavedRole(context, deviceId)
    }

    fun getBaseUrl(context: Context): String {
        return getPrefs(context).getString(KEY_BASE_URL, AegisApiClient.DEFAULT_BASE_URL)
            ?: AegisApiClient.DEFAULT_BASE_URL
    }

    fun setBaseUrl(context: Context, url: String) {
        val trimmed = url.trim()
        val normalized = if (trimmed.endsWith("/")) trimmed else "$trimmed/"
        getPrefs(context).edit().putString(KEY_BASE_URL, normalized).apply()
    }

    fun getFcmToken(context: Context): String? {
        return getPrefs(context).getString(KEY_FCM_TOKEN, null)
    }

    fun setFcmToken(context: Context, token: String) {
        getPrefs(context).edit().putString(KEY_FCM_TOKEN, token.trim()).apply()
    }

    fun getEmergencyContactPhone(context: Context): String? {
        val saved = getPrefs(context).getString(KEY_EMERGENCY_CONTACT_PHONE, null)?.trim()
        return if (!saved.isNullOrBlank()) saved else null
    }

    fun setEmergencyContactPhone(context: Context, phone: String?) {
        val trimmed = phone?.trim()
        if (trimmed.isNullOrBlank()) {
            getPrefs(context).edit().remove(KEY_EMERGENCY_CONTACT_PHONE).apply()
        } else {
            getPrefs(context).edit().putString(KEY_EMERGENCY_CONTACT_PHONE, trimmed).apply()
        }
    }

    fun getEmergencyContactName(context: Context): String {
        return getPrefs(context).getString(KEY_EMERGENCY_CONTACT_NAME, "Phone B") ?: "Phone B"
    }

    fun setEmergencyContactName(context: Context, name: String?) {
        getPrefs(context).edit().putString(KEY_EMERGENCY_CONTACT_NAME, name?.trim() ?: "Phone B").apply()
    }
}
