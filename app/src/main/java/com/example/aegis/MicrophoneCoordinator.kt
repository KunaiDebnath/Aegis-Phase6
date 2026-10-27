package com.example.aegis

import android.content.Context
import android.content.pm.PackageManager
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Controller interface allowing MicrophoneCoordinator to pause and resume
 * speech recognition in VoiceSafetyService without tight coupling.
 */
interface VoiceProtectionController {
    fun pauseListening(reason: String)
    fun resumeListening(reason: String)
    fun isServiceActive(): Boolean
}

/**
 * Centralized thread-safe lifecycle coordinator between Android's SpeechRecognizer
 * (used for continuous emergency phrase detection) and LiveKit WebRTC audio calling.
 *
 * Guarantees mutual exclusion:
 * - When an authorized LiveKit call is starting or active, SpeechRecognizer is stopped,
 *   its resources released, and all restart callbacks suppressed.
 * - When the call ends and audio resources are fully released, SpeechRecognizer
 *   is resumed only if voice protection remains enabled, permissions are granted,
 *   and Android permits speech recognition.
 */
object MicrophoneCoordinator {

    private const val TAG = "AEGIS_MicCoordinator"

    // Thread-safe state flags
    private val callActive = AtomicBoolean(false)
    private val voiceProtectionRegistered = AtomicBoolean(false)
    private val recognitionPaused = AtomicBoolean(false)

    @Volatile
    private var controller: VoiceProtectionController? = null

    val isCallActive: Boolean
        get() = callActive.get() || LiveKitCallManager.isCallActive

    val isVoiceProtectionActive: Boolean
        get() = voiceProtectionRegistered.get() && (controller?.isServiceActive() == true)

    val isRecognitionPaused: Boolean
        get() = recognitionPaused.get()

    /**
     * Registers the active VoiceProtectionController (VoiceSafetyService).
     */
    fun registerController(ctrl: VoiceProtectionController) {
        controller = ctrl
        voiceProtectionRegistered.set(true)
        Log.d(TAG, "VoiceProtectionController registered with MicrophoneCoordinator.")
        if (isCallActive) {
            pauseSpeechRecognition("Call is already active at registration time")
        }
    }

    /**
     * Unregisters the VoiceProtectionController when service is stopped or destroyed.
     */
    fun unregisterController(ctrl: VoiceProtectionController) {
        if (controller == ctrl) {
            controller = null
            voiceProtectionRegistered.set(false)
            recognitionPaused.set(false)
            Log.d(TAG, "VoiceProtectionController unregistered from MicrophoneCoordinator.")
        }
    }

    /**
     * Called when an authorized LiveKit call starts (token request or room join).
     * Enforces immediate graceful pause of SpeechRecognizer to guarantee
     * uncontested microphone hardware capture for LiveKit WebRTC.
     */
    fun onCallStarting(reason: String) {
        callActive.set(true)
        Log.i(TAG, "LiveKit call starting ($reason). Marking call active.")
        pauseSpeechRecognition(reason)
    }

    /**
     * Pauses speech recognition without stopping the foreground service.
     */
    fun pauseSpeechRecognition(reason: String) {
        recognitionPaused.set(true)
        val ctrl = controller
        if (ctrl != null && ctrl.isServiceActive()) {
            Log.i(TAG, "Speech recognition paused for call: $reason")
            ctrl.pauseListening(reason)
        } else {
            Log.d(TAG, "Speech recognition pause requested, but no active controller is registered ($reason).")
        }
    }

    /**
     * Called when a LiveKit call disconnects, ends, or fails.
     * Evaluates whether to resume SpeechRecognizer based on voice protection state,
     * permissions, and recognizer availability.
     */
    fun onCallEnded(context: Context?, reason: String) {
        callActive.set(false)
        Log.i(TAG, "LiveKit call ended ($reason). Marking call inactive.")

        val ctrl = controller
        val isRegistered = voiceProtectionRegistered.get() && ctrl != null && ctrl.isServiceActive()

        if (!isRegistered) {
            recognitionPaused.set(false)
            Log.i(TAG, "Call ended and speech recognition not resumed: Voice protection is disabled.")
            return
        }

        if (context != null) {
            // 1. Verify RECORD_AUDIO permission
            val hasMicPermission = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasMicPermission) {
                recognitionPaused.set(false)
                EmergencyOrchestrator.voiceStatus.value = VoiceProtectionStatus.ERROR
                EmergencyOrchestrator.voiceStatusMessage.value = "Microphone permission missing after call."
                Log.w(TAG, "Call ended and speech recognition not resumed: RECORD_AUDIO permission missing.")
                return
            }

            // 2. Verify SpeechRecognizer availability
            try {
                if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                    recognitionPaused.set(false)
                    EmergencyOrchestrator.voiceStatus.value = VoiceProtectionStatus.ERROR
                    EmergencyOrchestrator.voiceStatusMessage.value = "Speech recognition is not available on this device."
                    Log.w(TAG, "Call ended and speech recognition not resumed: SpeechRecognizer not available.")
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "SpeechRecognizer.isRecognitionAvailable check threw: ${e.message}")
            }
        }

        // 3. Resume SpeechRecognizer
        recognitionPaused.set(false)
        Log.i(TAG, "Call ended and speech recognition resuming for phrase detection: $reason")
        ctrl?.resumeListening(reason)
    }

    /**
     * Resets internal coordinator state for testing.
     */
    fun resetForTesting() {
        callActive.set(false)
        voiceProtectionRegistered.set(false)
        recognitionPaused.set(false)
        controller = null
    }
}
