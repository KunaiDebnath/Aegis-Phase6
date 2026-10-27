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
    private const val KEY_BASE_URL = "pref_base_url"
    private const val KEY_FCM_TOKEN = "pref_fcm_token"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getDeviceId(context: Context): String {
        return getPrefs(context).getString(KEY_DEVICE_ID, "phone-b") ?: "phone-b"
    }

    fun setDeviceId(context: Context, deviceId: String) {
        getPrefs(context).edit().putString(KEY_DEVICE_ID, deviceId.trim()).apply()
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
}
