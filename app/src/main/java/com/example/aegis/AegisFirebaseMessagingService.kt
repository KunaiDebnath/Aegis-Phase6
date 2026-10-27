package com.example.aegis

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles incoming Firebase Cloud Messaging (FCM) push notifications
 * when the app is backgrounded or device screen is locked.
 */
class AegisFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "AEGIS_FCM"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "New FCM Registration Token: ${token.take(15)}...")

        AegisPreferences.setFcmToken(applicationContext, token)
        CallSignalingClient.fcmTokenState.value = token
        val tokenPreview = if (token.length > 12) "${token.take(6)}...${token.takeLast(4)}" else token
        CallSignalingClient.fcmStatusMessage.value = "FCM: Registered ($tokenPreview)"

        val deviceId = AegisPreferences.getDeviceId(applicationContext)
        scope.launch {
            try {
                AegisApiClient.baseUrl = AegisPreferences.getBaseUrl(applicationContext)
                AegisApiClient.api.registerFcmToken(
                    RegisterFcmTokenRequest(
                        device_id = deviceId,
                        fcm_token = token
                    )
                )
                Log.i(TAG, "FCM token registered with backend for $deviceId")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register FCM token with backend: ${e.message}")
            }
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.i(TAG, "Stage 1: FCM message received from Google Play Services. Data payload size: ${remoteMessage.data.size}")

        val data = remoteMessage.data
        if (data.isEmpty()) {
            Log.w(TAG, "Stage 1 Warning: FCM message received with empty data payload. Ignoring.")
            return
        }

        val type = data["type"]
        if (type == "incoming_call_invitation") {
            val invitationId = data["invitation_id"] ?: return
            val eventId = data["emergency_event_id"] ?: return
            val callerDevice = data["caller_device_id"] ?: "phone-a"
            val callerName = data["caller_display_name"] ?: "Phone A (Requester)"
            val expiresAt = data["expires_at"] ?: ""

            Log.i(TAG, "Stage 2: Parsing invitation $invitationId from caller $callerName. Acquiring 5s temporary wake lock.")

            // Acquire short-lived wake lock so CPU does not sleep during notification / full-screen intent dispatch
            try {
                val pm = getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
                val wakeLock = pm?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "aegis:fcm_incoming_call")
                wakeLock?.acquire(5000L)
            } catch (e: Exception) {
                Log.w(TAG, "WakeLock acquisition non-fatal error: ${e.message}")
            }

            // Ensure baseUrl and deviceId are restored from persistent preferences
            AegisApiClient.baseUrl = AegisPreferences.getBaseUrl(applicationContext)
            CallSignalingClient.currentDeviceId.value = AegisPreferences.getDeviceId(applicationContext)

            // Show high-priority notification with full-screen intent and Answer/Dismiss actions
            Log.i(TAG, "Stage 3: Dispatching incoming call notification to EmergencyCallNotificationManager.")
            EmergencyCallNotificationManager.showIncomingCallNotification(
                context = applicationContext,
                invitationId = invitationId,
                emergencyEventId = eventId,
                callerDisplayName = callerName
            )

            // Update in-memory state and trigger local 60s countdown timer
            val inv = IncomingCallInvitation(
                invitationId = invitationId,
                emergencyEventId = eventId,
                callerDeviceId = callerDevice,
                callerDisplayName = callerName,
                createdAtIso = "",
                expiresAtIso = expiresAt,
                status = "pending"
            )
            CallSignalingClient.handleIncomingInvitation(inv)
            Log.i(TAG, "Stage 4: Incoming call invitation state activated in memory.")
        }
    }
}
