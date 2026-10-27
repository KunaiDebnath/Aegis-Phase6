package com.example.aegis

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BroadcastReceiver for handling Answer and Dismiss actions from incoming emergency call notifications.
 */
class CallActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AEGIS_CallAction"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val action = intent.action
        val invitationId = intent.getStringExtra(EmergencyCallNotificationManager.EXTRA_INVITATION_ID)

        if (invitationId.isNullOrBlank()) {
            Log.w(TAG, "CallActionReceiver received intent with blank invitation ID.")
            return
        }

        when (action) {
            EmergencyCallNotificationManager.ACTION_ANSWER_CALL -> {
                Log.i(TAG, "User tapped ANSWER for invitation: $invitationId")
                val deviceId = CallSignalingClient.currentDeviceId.value

                CallSignalingClient.answerInvitation(
                    context = context,
                    invitationId = invitationId,
                    deviceId = deviceId,
                    displayName = "Phone B (Trusted Contact)"
                ) { success, errorMsg ->
                    if (success) {
                        Log.i(TAG, "Call answered. Opening MainActivity.")
                    } else {
                        Log.e(TAG, "Failed to answer call: $errorMsg")
                    }
                }

                // Bring MainActivity to foreground
                val openIntent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(EmergencyCallNotificationManager.EXTRA_INVITATION_ID, invitationId)
                }
                context.startActivity(openIntent)
            }

            EmergencyCallNotificationManager.ACTION_DISMISS_CALL -> {
                Log.i(TAG, "User tapped DISMISS for invitation: $invitationId")
                val deviceId = CallSignalingClient.currentDeviceId.value
                CallSignalingClient.dismissInvitation(
                    context = context,
                    invitationId = invitationId,
                    deviceId = deviceId
                )
            }
        }
    }
}
