package com.example.aegis

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Manages the high-priority Android notification channel and alerts for
 * incoming AEGIS emergency calls on Phone B (Trusted Contact).
 * Supports heads-up notifications, lock-screen visibility, and full-screen intents.
 */
object EmergencyCallNotificationManager {

    private const val TAG = "AEGIS_Notif"

    const val CHANNEL_ID = "aegis_emergency_call_channel_v2"
    private const val CHANNEL_NAME = "AEGIS Emergency Incoming Calls"

    const val ACTION_ANSWER_CALL = "com.example.aegis.ACTION_ANSWER_CALL"
    const val ACTION_DISMISS_CALL = "com.example.aegis.ACTION_DISMISS_CALL"
    const val ACTION_FULL_SCREEN_CALL = "com.example.aegis.ACTION_FULL_SCREEN_CALL"

    const val EXTRA_INVITATION_ID = "com.example.aegis.EXTRA_INVITATION_ID"
    const val EXTRA_EVENT_ID = "com.example.aegis.EXTRA_EVENT_ID"
    const val EXTRA_CALLER_NAME = "com.example.aegis.EXTRA_CALLER_NAME"

    fun canUseFullScreenIntent(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.canUseFullScreenIntent() ?: true
        } else {
            true
        }
    }

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Remove legacy channel with restrictive lockscreen visibility if present
            try {
                notificationManager.deleteNotificationChannel("aegis_incoming_call_channel")
            } catch (_: Exception) {}

            val existing = notificationManager.getNotificationChannel(CHANNEL_ID)
            if (existing != null) {
                return
            }

            val ringtoneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .build()

            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High-priority notifications for incoming AEGIS emergency audio calls."
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 1000, 500, 1000, 500, 1000)
                setSound(ringtoneUri, audioAttributes)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                enableLights(true)
                lightColor = Color.RED
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    setBypassDnd(true)
                }
            }

            notificationManager.createNotificationChannel(channel)
            Log.i(TAG, "Notification channel $CHANNEL_ID created with IMPORTANCE_HIGH and VISIBILITY_PUBLIC.")
        }
    }

    fun getNotificationId(invitationId: String): Int {
        return 4000 + (invitationId.hashCode() and 0x7FFF)
    }

    fun showIncomingCallNotification(
        context: Context,
        invitationId: String,
        emergencyEventId: String,
        callerDisplayName: String?
    ) {
        createNotificationChannel(context)
        val notifId = getNotificationId(invitationId)

        val callerLabel = callerDisplayName?.ifBlank { "Phone A" } ?: "Phone A"

        // 1. Content Intent (tap on notification body -> opens MainActivity)
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_INVITATION_ID, invitationId)
            putExtra(EXTRA_EVENT_ID, emergencyEventId)
            putExtra(EXTRA_CALLER_NAME, callerLabel)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            notifId,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 2. Full-Screen Intent (Wakes screen and presents incoming call UI over lock screen)
        val fullScreenIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_FULL_SCREEN_CALL
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_INVITATION_ID, invitationId)
            putExtra(EXTRA_EVENT_ID, emergencyEventId)
            putExtra(EXTRA_CALLER_NAME, callerLabel)
        }
        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            notifId + 3,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 3. Answer Action Intent (Launches MainActivity directly to comply with background restrictions)
        val answerIntent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_ANSWER_CALL
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_INVITATION_ID, invitationId)
            putExtra(EXTRA_EVENT_ID, emergencyEventId)
            putExtra(EXTRA_CALLER_NAME, callerLabel)
        }
        val answerPendingIntent = PendingIntent.getActivity(
            context,
            notifId + 1,
            answerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 4. Dismiss Action Intent (Handled by background BroadcastReceiver)
        val dismissIntent = Intent(context, CallActionReceiver::class.java).apply {
            action = ACTION_DISMISS_CALL
            putExtra(EXTRA_INVITATION_ID, invitationId)
            putExtra(EXTRA_EVENT_ID, emergencyEventId)
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            context,
            notifId + 2,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 5. Public version of notification (visible on lock-screen with Answer and Dismiss buttons)
        val publicNotification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("🚨 EMERGENCY CALL: $callerLabel")
            .setContentText("Emergency call available. Tap Answer to connect.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentPendingIntent)
            .addAction(0, "ANSWER", answerPendingIntent)
            .addAction(0, "DISMISS", dismissPendingIntent)
            .build()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("🚨 EMERGENCY CALL: $callerLabel")
            .setContentText("Emergency call invitation available. Tap Answer to connect.")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(contentPendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .setPublicVersion(publicNotification)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(0, "ANSWER", answerPendingIntent)
            .addAction(0, "DISMISS", dismissPendingIntent)
            .build()

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notifId, notification)
        Log.i(TAG, "Posted incoming call notification for $invitationId (channel: $CHANNEL_ID, fullScreenIntent: true, visibility: VISIBILITY_PUBLIC)")
    }

    fun cancelNotification(context: Context, invitationId: String) {
        val notifId = getNotificationId(invitationId)
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(notifId)
        Log.i(TAG, "Cancelled notification for $invitationId (notifId: $notifId)")
    }
}
