package com.example.aegis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground Service for continuous voice safety protection.
 * Runs Android's SpeechRecognizer in a managed listening loop with:
 * - Persistent foreground notification (FOREGROUND_SERVICE_TYPE_MICROPHONE on API 34+)
 * - Resilient error recovery with backoff to prevent tight CPU-spinning restart loops
 * - Centralized mutual-exclusion coordination via MicrophoneCoordinator during LiveKit calls
 * - Automatic pause and resumption when emergency WebRTC audio calls begin and end
 * - Phrase matching without continuous wake-word engines or external third-party servers
 * - Direct invocation of the shared EmergencyOrchestrator when configured phrase is detected
 */
class VoiceSafetyService : Service(), VoiceProtectionController {

    companion object {
        private const val TAG = "AEGIS_VoiceSafety"
        const val CHANNEL_ID = "aegis_voice_protection_channel"
        const val NOTIFICATION_ID = 2001
        const val ACTION_START = "com.example.aegis.ACTION_START_VOICE_PROTECTION"
        const val ACTION_STOP = "com.example.aegis.ACTION_STOP_VOICE_PROTECTION"

        fun start(context: Context) {
            val intent = Intent(context, VoiceSafetyService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, VoiceSafetyService::class.java).apply {
                action = ACTION_STOP
            }
            context.stopService(intent)
        }
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isListeningActive = false
    private var consecutiveErrors = 0
    private var lastErrorTime = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        MicrophoneCoordinator.registerController(this)
        Log.d(TAG, "VoiceSafetyService created and registered with MicrophoneCoordinator.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVoiceProtection()
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundWithNotification()
        startVoiceProtection()
        return START_STICKY
    }

    // =========================================================================
    // VoiceProtectionController Implementation
    // =========================================================================

    override fun pauseListening(reason: String) {
        mainHandler.post {
            Log.d(TAG, "Executing pauseListening on main thread ($reason)...")
            mainHandler.removeCallbacksAndMessages(null)
            destroyRecognizer()
            EmergencyOrchestrator.voiceStatus.value = VoiceProtectionStatus.PAUSED_FOR_CALL
            EmergencyOrchestrator.voiceStatusMessage.value =
                "Voice protection paused: Emergency audio call active."
            updateNotification(isPaused = true)
        }
    }

    override fun resumeListening(reason: String) {
        mainHandler.post {
            if (MicrophoneCoordinator.isCallActive || !isListeningActive) {
                Log.d(TAG, "Recognition restart suppressed because a call is active or service stopped.")
                return@post
            }

            mainHandler.removeCallbacksAndMessages(null)
            // Allow 350ms for native WebRTC AudioRecord to completely release hardware microphone
            mainHandler.postDelayed({
                if (MicrophoneCoordinator.isCallActive || !isListeningActive) {
                    Log.d(TAG, "Recognition restart suppressed after delay because a call is active.")
                    return@postDelayed
                }
                Log.d(TAG, "Reinitializing SpeechRecognizer after call ended ($reason)...")
                initRecognizer()
                startListening()
                updateNotification(isPaused = false)
            }, 350L)
        }
    }

    override fun isServiceActive(): Boolean = isListeningActive

    // =========================================================================
    // Notification & Foreground Management
    // =========================================================================

    private fun buildNotification(isPaused: Boolean): Notification {
        val stopIntent = Intent(this, VoiceSafetyService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            101,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            102,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val phrase = EmergencyOrchestrator.configuredPhrase.value
        val title = if (isPaused) "AEGIS Voice Protection (Paused for Call)" else "AEGIS Voice Protection Active"
        val text = if (isPaused) "Microphone in use by emergency call. Will resume when call ends." else "Listening for emergency phrase: \"$phrase\""

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(openAppPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Protection", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification(isPaused = MicrophoneCoordinator.isRecognitionPaused)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(isPaused: Boolean) {
        val notification = buildNotification(isPaused)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AEGIS Voice Emergency Protection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifies that AEGIS is actively monitoring for the emergency voice trigger phrase."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    // =========================================================================
    // SpeechRecognizer Lifecycle
    // =========================================================================

    private fun startVoiceProtection() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            EmergencyOrchestrator.voiceStatus.value = VoiceProtectionStatus.ERROR
            EmergencyOrchestrator.voiceStatusMessage.value = "Speech recognition is not available on this device."
            stopSelf()
            return
        }

        isListeningActive = true

        if (MicrophoneCoordinator.isCallActive) {
            Log.d(TAG, "Call is active. Voice protection starting in paused state.")
            EmergencyOrchestrator.voiceStatus.value = VoiceProtectionStatus.PAUSED_FOR_CALL
            EmergencyOrchestrator.voiceStatusMessage.value = "Voice protection paused: Emergency call active."
            updateNotification(isPaused = true)
            return
        }

        EmergencyOrchestrator.voiceStatus.value = VoiceProtectionStatus.STARTING
        EmergencyOrchestrator.voiceStatusMessage.value = "Initializing speech recognizer..."

        initRecognizer()
        startListening()
    }

    private fun initRecognizer() {
        destroyRecognizer()
        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(createListener())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create SpeechRecognizer: ${e.message}", e)
        }
    }

    private fun destroyRecognizer() {
        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
    }

    private fun startListening() {
        if (!isListeningActive || MicrophoneCoordinator.isCallActive || MicrophoneCoordinator.isRecognitionPaused) {
            Log.d(TAG, "startListening suppressed: isListeningActive=$isListeningActive, callActive=${MicrophoneCoordinator.isCallActive}, paused=${MicrophoneCoordinator.isRecognitionPaused}")
            return
        }

        mainHandler.post {
            if (!isListeningActive || MicrophoneCoordinator.isCallActive || MicrophoneCoordinator.isRecognitionPaused) {
                Log.d(TAG, "Recognition restart suppressed because a call is active.")
                return@post
            }

            try {
                if (speechRecognizer == null) {
                    initRecognizer()
                }

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                }

                speechRecognizer?.startListening(intent)
                EmergencyOrchestrator.voiceStatus.value = VoiceProtectionStatus.LISTENING
                EmergencyOrchestrator.voiceStatusMessage.value =
                    "Active. Listening for: \"${EmergencyOrchestrator.configuredPhrase.value}\""
            } catch (e: Exception) {
                Log.w(TAG, "startListening failed: ${e.message}. Scheduling retry.")
                scheduleRestart(1000L)
            }
        }
    }

    private fun scheduleRestart(delayMillis: Long) {
        if (!isListeningActive || MicrophoneCoordinator.isCallActive || MicrophoneCoordinator.isRecognitionPaused) {
            Log.d(TAG, "Recognition restart suppressed because a call is active.")
            return
        }

        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (isListeningActive && !MicrophoneCoordinator.isCallActive && !MicrophoneCoordinator.isRecognitionPaused) {
                startListening()
            } else {
                Log.d(TAG, "Recognition restart suppressed because a call is active.")
            }
        }, delayMillis)
    }

    private fun createListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                if (isListeningActive && !MicrophoneCoordinator.isCallActive && !MicrophoneCoordinator.isRecognitionPaused && !EmergencyTriggerGuard.isActive) {
                    EmergencyOrchestrator.voiceStatus.value = VoiceProtectionStatus.LISTENING
                    EmergencyOrchestrator.voiceStatusMessage.value =
                        "Active. Listening for: \"${EmergencyOrchestrator.configuredPhrase.value}\""
                }
            }

            override fun onBeginningOfSpeech() {
                if (isListeningActive && !MicrophoneCoordinator.isCallActive && !MicrophoneCoordinator.isRecognitionPaused) {
                    EmergencyOrchestrator.voiceStatusMessage.value = "Speech detected. Analyzing..."
                }
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {} // Strict privacy: no raw audio retained
            override fun onEndOfSpeech() {}

            override fun onError(errorCode: Int) {
                if (!isListeningActive || MicrophoneCoordinator.isCallActive || MicrophoneCoordinator.isRecognitionPaused) {
                    Log.d(TAG, "Recognition restart suppressed because a call is active (errorCode: $errorCode).")
                    return
                }

                val now = System.currentTimeMillis()
                if (now - lastErrorTime < 2000L) {
                    consecutiveErrors++
                } else {
                    consecutiveErrors = 1
                }
                lastErrorTime = now

                val isSilenceTimeout = errorCode == SpeechRecognizer.ERROR_NO_MATCH ||
                        errorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT

                // Prevent tight restart loops on persistent errors
                val restartDelay = when {
                    isSilenceTimeout -> 300L
                    consecutiveErrors >= 4 -> 3000L
                    else -> 1000L
                }

                if (!isSilenceTimeout) {
                    val errorDesc = when (errorCode) {
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                        SpeechRecognizer.ERROR_CLIENT -> "Client recognition error"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Record audio permission missing"
                        SpeechRecognizer.ERROR_NETWORK -> "Network connectivity error"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network recognition timeout"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
                        SpeechRecognizer.ERROR_SERVER -> "Server error"
                        else -> "Recognition error ($errorCode)"
                    }
                    EmergencyOrchestrator.voiceStatus.value = VoiceProtectionStatus.ERROR
                    EmergencyOrchestrator.voiceStatusMessage.value = "$errorDesc. Recovering..."
                    initRecognizer()
                }

                scheduleRestart(restartDelay)
            }

            override fun onResults(results: Bundle?) {
                if (!isListeningActive || MicrophoneCoordinator.isCallActive || MicrophoneCoordinator.isRecognitionPaused) {
                    Log.d(TAG, "Recognition results ignored because a call is active or recognition paused.")
                    return
                }

                consecutiveErrors = 0
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val triggered = evaluateMatchesAndTrigger(matches)

                if (!triggered && isListeningActive && !MicrophoneCoordinator.isCallActive && !MicrophoneCoordinator.isRecognitionPaused) {
                    scheduleRestart(300L)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!isListeningActive || MicrophoneCoordinator.isCallActive || MicrophoneCoordinator.isRecognitionPaused) {
                    return
                }
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                evaluateMatchesAndTrigger(matches)
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    /**
     * Checks hypotheses against the configured phrase.
     * If matched, triggers emergency orchestration with triggerSource="voice".
     */
    private fun evaluateMatchesAndTrigger(matches: List<String>?): Boolean {
        if (matches.isNullOrEmpty()) return false
        val targetPhrase = EmergencyOrchestrator.configuredPhrase.value

        for (candidate in matches) {
            if (EmergencyUtils.matchesEmergencyPhrase(candidate, targetPhrase)) {
                // If an emergency is already active or executing, guard prevents duplicates
                if (!EmergencyTriggerGuard.isActive && !EmergencyTriggerGuard.isExecuting) {
                    EmergencyOrchestrator.voiceStatus.value = VoiceProtectionStatus.TRIGGERED
                    EmergencyOrchestrator.voiceStatusMessage.value = "Emergency phrase detected: \"$candidate\"!"

                    EmergencyOrchestrator.triggerEmergency(
                        context = this,
                        recipient = EmergencyOrchestrator.emergencyRecipientNumber.value,
                        source = "voice",
                        onFinished = {
                            if (isListeningActive && !MicrophoneCoordinator.isCallActive && !MicrophoneCoordinator.isRecognitionPaused) {
                                scheduleRestart(2000L)
                            } else {
                                Log.d(TAG, "Recognition restart suppressed because a call is active.")
                            }
                        }
                    )
                    return true
                } else {
                    return true // Already triggered, avoid reprocessing
                }
            }
        }
        return false
    }

    private fun stopVoiceProtection() {
        isListeningActive = false
        mainHandler.removeCallbacksAndMessages(null)
        destroyRecognizer()
        MicrophoneCoordinator.unregisterController(this)
        EmergencyOrchestrator.voiceStatus.value = VoiceProtectionStatus.DISABLED
        EmergencyOrchestrator.voiceStatusMessage.value = "Voice protection disabled."
    }

    override fun onDestroy() {
        stopVoiceProtection()
        super.onDestroy()
    }
}
