package com.example.aegis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class IncomingCallNotificationUnitTest {

    @Before
    fun setUp() {
        CallSignalingClient.activeIncomingInvitation.value = null
        CallSignalingClient.callerFeedbackMessage.value = null
        CallSignalingClient.remainingSeconds.value = 0
        LiveKitCallManager.callState.value = CallState.IDLE
        LiveKitCallManager.callStatusMessage.value = "No active call."
        LiveKitCallManager.activeRoomName.value = null
        LiveKitCallManager.remoteParticipantName.value = null
        MicrophoneCoordinator.resetForTesting()
    }

    @Test
    fun testIncomingCallInvitationDataModel() {
        val invId = UUID.randomUUID().toString()
        val eventId = UUID.randomUUID().toString()
        val invitation = IncomingCallInvitation(
            invitationId = invId,
            emergencyEventId = eventId,
            callerDeviceId = "phone-a",
            callerDisplayName = "Phone A (Requester)",
            createdAtIso = "2026-10-27T09:00:00Z",
            expiresAtIso = "2026-10-27T09:01:00Z",
            status = "pending"
        )

        assertEquals(invId, invitation.invitationId)
        assertEquals(eventId, invitation.emergencyEventId)
        assertEquals("phone-a", invitation.callerDeviceId)
        assertEquals("Phone A (Requester)", invitation.callerDisplayName)
        assertEquals("pending", invitation.status)
    }

    @Test
    fun testNotificationId_deterministicAndUnique() {
        val id1 = UUID.randomUUID().toString()
        val id2 = UUID.randomUUID().toString()

        val notifId1 = EmergencyCallNotificationManager.getNotificationId(id1)
        val notifId1Repeat = EmergencyCallNotificationManager.getNotificationId(id1)
        val notifId2 = EmergencyCallNotificationManager.getNotificationId(id2)

        // Deterministic
        assertEquals(notifId1, notifId1Repeat)
        // Unique across different invitation IDs
        assertNotEquals(notifId1, notifId2)
        // Positive integer in expected range
        assertTrue(notifId1 >= 4000)
    }

    @Test
    fun testSignalingClient_parsesIncomingInvitationCorrectly() {
        val invId = UUID.randomUUID().toString()
        val eventId = UUID.randomUUID().toString()

        val payload = """
            {
                "type": "incoming_call_invitation",
                "invitation_id": "$invId",
                "emergency_event_id": "$eventId",
                "caller_device_id": "phone-a",
                "caller_display_name": "Phone A (Requester)",
                "created_at": "2026-10-27T10:00:00Z",
                "expires_at": "2026-10-27T10:01:00Z",
                "status": "pending"
            }
        """.trimIndent()

        CallSignalingClient.handleIncomingMessage(payload)

        val active = CallSignalingClient.activeIncomingInvitation.value
        assertNotNull(active)
        assertEquals(invId, active?.invitationId)
        assertEquals(eventId, active?.emergencyEventId)
        assertEquals("phone-a", active?.callerDeviceId)
        assertEquals("Phone A (Requester)", active?.callerDisplayName)
        assertEquals(60, CallSignalingClient.remainingSeconds.value)
    }

    @Test
    fun testSignalingClient_parsesCallAnsweredFeedback() {
        val payload = """
            {
                "type": "call_invitation_answered",
                "invitation_id": "inv-123",
                "emergency_event_id": "ev-123",
                "recipient_device_id": "phone-b",
                "recipient_name": "Phone B (Trusted Contact)",
                "status": "accepted"
            }
        """.trimIndent()

        CallSignalingClient.handleIncomingMessage(payload)

        val feedback = CallSignalingClient.callerFeedbackMessage.value
        assertNotNull(feedback)
        assertTrue(feedback!!.contains("Phone B (Trusted Contact) answered"))
        assertTrue(feedback.contains("connected"))
    }

    @Test
    fun testSignalingClient_parsesCallDismissedFeedback() {
        val payload = """
            {
                "type": "call_invitation_dismissed",
                "invitation_id": "inv-123",
                "emergency_event_id": "ev-123",
                "recipient_device_id": "phone-b",
                "status": "dismissed"
            }
        """.trimIndent()

        CallSignalingClient.handleIncomingMessage(payload)

        val feedback = CallSignalingClient.callerFeedbackMessage.value
        assertNotNull(feedback)
        assertTrue(feedback!!.contains("phone-b dismissed"))
        assertTrue(feedback.contains("Emergency alert remains active"))
    }

    @Test
    fun testSignalingClient_handlesCallCancelled() {
        val invId = UUID.randomUUID().toString()
        CallSignalingClient.activeIncomingInvitation.value = IncomingCallInvitation(
            invitationId = invId,
            emergencyEventId = "ev-123",
            callerDeviceId = "phone-a",
            callerDisplayName = "Phone A",
            createdAtIso = "",
            expiresAtIso = "",
            status = "pending"
        )

        val cancelPayload = """
            {
                "type": "call_invitation_cancelled",
                "invitation_id": "$invId",
                "emergency_event_id": "ev-123",
                "status": "cancelled"
            }
        """.trimIndent()

        CallSignalingClient.handleIncomingMessage(cancelPayload)

        assertNull(CallSignalingClient.activeIncomingInvitation.value)
    }

    @Test
    fun testLiveKitCallManager_startCallWithAuthorizedToken_initiatesCallState() {
        val eventId = UUID.randomUUID().toString()
        val tokenResponse = CallTokenResponse(
            server_url = "ws://192.168.1.4:7880",
            token = "dummy.jwt.token",
            room_name = "emergency-$eventId",
            identity = "phone-b",
            expires_in_seconds = 600
        )

        LiveKitCallManager.startCallWithAuthorizedToken(
            context = null, // In unit tests, null skips Android foreground service and WebRTC native bindings
            tokenResponse = tokenResponse,
            deviceId = "phone-b"
        )

        Thread.sleep(100)

        assertEquals("emergency-$eventId", LiveKitCallManager.activeRoomName.value)
        assertEquals("phone-b", LiveKitCallManager.currentIdentity.value)
        // In test mode without Context, it marks test initialized
        assertTrue(
            LiveKitCallManager.callState.value == CallState.CONNECTING ||
                    LiveKitCallManager.callStatusMessage.value.contains("test mode")
        )

        // End call cleanly
        LiveKitCallManager.endCall(context = null)
        assertEquals(CallState.ENDED, LiveKitCallManager.callState.value)
        assertNull(LiveKitCallManager.activeRoomName.value)
    }

    @Test
    fun testMicrophoneCoordination_speechRecognitionPausedDuringCall() {
        var recognitionPaused = false
        val dummyController = object : VoiceProtectionController {
            override fun pauseListening(reason: String) {
                recognitionPaused = true
            }

            override fun resumeListening(reason: String) {
                recognitionPaused = false
            }

            override fun isServiceActive(): Boolean = true
        }

        MicrophoneCoordinator.registerController(dummyController)

        // Call starting
        MicrophoneCoordinator.onCallStarting("Incoming emergency call accepted")
        assertTrue(recognitionPaused)
        assertTrue(MicrophoneCoordinator.isCallActive)

        // Call ended
        MicrophoneCoordinator.onCallEnded(context = null, reason = "User hung up")
        assertFalse(recognitionPaused)
        assertFalse(MicrophoneCoordinator.isCallActive)
    }
}
