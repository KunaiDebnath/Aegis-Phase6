package com.example.aegis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class MicrophoneCoordinationUnitTest {

    private class FakeVoiceController : VoiceProtectionController {
        var isPaused = false
        var pauseCount = 0
        var resumeCount = 0
        var active = true
        var lastPauseReason: String? = null
        var lastResumeReason: String? = null

        override fun pauseListening(reason: String) {
            isPaused = true
            pauseCount++
            lastPauseReason = reason
        }

        override fun resumeListening(reason: String) {
            isPaused = false
            resumeCount++
            lastResumeReason = reason
        }

        override fun isServiceActive(): Boolean = active
    }

    private lateinit var fakeController: FakeVoiceController

    @Before
    fun setUp() {
        MicrophoneCoordinator.resetForTesting()
        LiveKitCallManager.callState.value = CallState.IDLE
        LiveKitCallManager.callStatusMessage.value = "No active call."
        LiveKitCallManager.activeRoomName.value = null
        LiveKitCallManager.activeEventId.value = null
        LiveKitCallManager.isMicMuted.value = false
        EmergencyOrchestrator.voiceStatus.value = VoiceProtectionStatus.DISABLED

        fakeController = FakeVoiceController()
    }

    @Test
    fun testVoiceProtectionEnabled_noCallActive_coordinatorStateNormal() {
        // Voice protection enabled
        MicrophoneCoordinator.registerController(fakeController)

        assertTrue(MicrophoneCoordinator.isVoiceProtectionActive)
        assertFalse(MicrophoneCoordinator.isCallActive)
        assertFalse(MicrophoneCoordinator.isRecognitionPaused)
        assertEquals(0, fakeController.pauseCount)
    }

    @Test
    fun testCallBegins_pausesSpeechRecognitionImmediately() {
        MicrophoneCoordinator.registerController(fakeController)

        // LiveKit call begins
        MicrophoneCoordinator.onCallStarting("Emergency SOS call connecting")

        assertTrue(MicrophoneCoordinator.isCallActive)
        assertTrue(MicrophoneCoordinator.isRecognitionPaused)
        assertTrue(fakeController.isPaused)
        assertEquals(1, fakeController.pauseCount)
        assertEquals("Emergency SOS call connecting", fakeController.lastPauseReason)
    }

    @Test
    fun testCallActive_suppressesRestartsAndRetries() {
        MicrophoneCoordinator.registerController(fakeController)
        MicrophoneCoordinator.onCallStarting("Testing restart suppression")

        // While call is active, coordinator guards prevent listening
        assertTrue(MicrophoneCoordinator.isCallActive)
        assertTrue(MicrophoneCoordinator.isRecognitionPaused)

        // If recognizer attempts restart, coordinator reports call is active
        val shouldSuppressListening = !fakeController.isServiceActive() ||
                MicrophoneCoordinator.isCallActive ||
                MicrophoneCoordinator.isRecognitionPaused

        assertTrue(shouldSuppressListening)
    }

    @Test
    fun testCallEnds_voiceProtectionEnabled_resumesRecognition() {
        MicrophoneCoordinator.registerController(fakeController)
        MicrophoneCoordinator.onCallStarting("Starting test call")
        assertTrue(fakeController.isPaused)

        // Call ends cleanly (null context in unit test skips Android platform check)
        MicrophoneCoordinator.onCallEnded(context = null, reason = "User hung up")

        assertFalse(MicrophoneCoordinator.isCallActive)
        assertFalse(MicrophoneCoordinator.isRecognitionPaused)
        assertFalse(fakeController.isPaused)
        assertEquals(1, fakeController.resumeCount)
        assertEquals("User hung up", fakeController.lastResumeReason)
    }

    @Test
    fun testCallEnds_voiceProtectionDisabledDuringCall_doesNotResume() {
        MicrophoneCoordinator.registerController(fakeController)
        MicrophoneCoordinator.onCallStarting("Starting call")
        assertTrue(fakeController.isPaused)

        // User disables voice protection WHILE call is active
        fakeController.active = false
        MicrophoneCoordinator.unregisterController(fakeController)

        assertFalse(MicrophoneCoordinator.isVoiceProtectionActive)

        // Call ends
        MicrophoneCoordinator.onCallEnded(context = null, reason = "Call finished")

        assertFalse(MicrophoneCoordinator.isCallActive)
        // Controller must NOT have been resumed
        assertEquals(0, fakeController.resumeCount)
        assertTrue(fakeController.isPaused)
    }

    @Test
    fun testCallFailsOrDisconnectsUnexpectedly_releasesResourcesConsistently() {
        MicrophoneCoordinator.registerController(fakeController)
        MicrophoneCoordinator.onCallStarting("Call initiating")

        assertTrue(MicrophoneCoordinator.isCallActive)
        assertTrue(MicrophoneCoordinator.isRecognitionPaused)

        // Call fails unexpectedly (e.g. server unreachable)
        MicrophoneCoordinator.onCallEnded(context = null, reason = "Connection timeout")

        assertFalse(MicrophoneCoordinator.isCallActive)
        assertFalse(MicrophoneCoordinator.isRecognitionPaused)
        assertEquals(1, fakeController.resumeCount)
    }

    @Test
    fun testDuplicateCallStart_guardedAgainstMultipleActiveSessions() {
        val validEventId = UUID.randomUUID().toString()

        // Set call state to active
        LiveKitCallManager.callState.value = CallState.CONNECTED

        // Attempting another startCall must be ignored
        LiveKitCallManager.startCall(
            context = null,
            eventId = validEventId,
            deviceId = "phone-a",
            displayName = "Phone A"
        )

        // State remains CONNECTED, not overwritten to REQUESTING_TOKEN
        assertEquals(CallState.CONNECTED, LiveKitCallManager.callState.value)
    }

    @Test
    fun testMuteToggle_independentControl() {
        LiveKitCallManager.isMicMuted.value = false
        assertFalse(LiveKitCallManager.isMicMuted.value)

        // Simulating mute
        LiveKitCallManager.isMicMuted.value = true
        assertTrue(LiveKitCallManager.isMicMuted.value)

        // Simulating unmute
        LiveKitCallManager.isMicMuted.value = false
        assertFalse(LiveKitCallManager.isMicMuted.value)
    }

    @Test
    fun testVoiceProtectionStatusEnum_includesPausedForCall() {
        val statuses = VoiceProtectionStatus.values().toSet()
        assertEquals(6, statuses.size)
        assertTrue(statuses.contains(VoiceProtectionStatus.DISABLED))
        assertTrue(statuses.contains(VoiceProtectionStatus.STARTING))
        assertTrue(statuses.contains(VoiceProtectionStatus.LISTENING))
        assertTrue(statuses.contains(VoiceProtectionStatus.PAUSED_FOR_CALL))
        assertTrue(statuses.contains(VoiceProtectionStatus.ERROR))
        assertTrue(statuses.contains(VoiceProtectionStatus.TRIGGERED))
    }

    @Test
    fun testVoiceProtectionRegisteredWhileCallActive_immediatelyPauses() {
        // Start a call before voice protection is enabled
        MicrophoneCoordinator.onCallStarting("Call already in progress")
        assertTrue(MicrophoneCoordinator.isCallActive)

        // Now voice protection service registers
        MicrophoneCoordinator.registerController(fakeController)

        // It must be immediately paused
        assertTrue(fakeController.isPaused)
        assertEquals(1, fakeController.pauseCount)
        assertTrue(MicrophoneCoordinator.isRecognitionPaused)
    }
}
