package com.example.aegis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class LiveKitCallingUnitTest {

    @Before
    fun setUp() {
        LiveKitCallManager.callState.value = CallState.IDLE
        LiveKitCallManager.callStatusMessage.value = "No active call."
        LiveKitCallManager.remoteParticipantIdentity.value = null
        LiveKitCallManager.remoteParticipantName.value = null
        LiveKitCallManager.activeRoomName.value = null
        LiveKitCallManager.activeEventId.value = null
        LiveKitCallManager.isMicMuted.value = false
    }

    @Test
    fun testCallStateEnum_completeness() {
        val states = CallState.values().toSet()
        assertEquals(8, states.size)
        assertTrue(states.contains(CallState.IDLE))
        assertTrue(states.contains(CallState.REQUESTING_TOKEN))
        assertTrue(states.contains(CallState.CONNECTING))
        assertTrue(states.contains(CallState.WAITING_FOR_PARTICIPANT))
        assertTrue(states.contains(CallState.CONNECTED))
        assertTrue(states.contains(CallState.RECONNECTING))
        assertTrue(states.contains(CallState.ENDED))
        assertTrue(states.contains(CallState.FAILED))
    }

    @Test
    fun testIsCallActive_stateTransitions() {
        LiveKitCallManager.callState.value = CallState.IDLE
        assertFalse(LiveKitCallManager.isCallActive)

        LiveKitCallManager.callState.value = CallState.REQUESTING_TOKEN
        assertFalse(LiveKitCallManager.isCallActive)

        LiveKitCallManager.callState.value = CallState.CONNECTING
        assertTrue(LiveKitCallManager.isCallActive)

        LiveKitCallManager.callState.value = CallState.WAITING_FOR_PARTICIPANT
        assertTrue(LiveKitCallManager.isCallActive)

        LiveKitCallManager.callState.value = CallState.CONNECTED
        assertTrue(LiveKitCallManager.isCallActive)

        LiveKitCallManager.callState.value = CallState.RECONNECTING
        assertTrue(LiveKitCallManager.isCallActive)

        LiveKitCallManager.callState.value = CallState.ENDED
        assertFalse(LiveKitCallManager.isCallActive)

        LiveKitCallManager.callState.value = CallState.FAILED
        assertFalse(LiveKitCallManager.isCallActive)
    }

    @Test
    fun testCallTokenDataModels() {
        val eventId = UUID.randomUUID().toString()
        val request = CallTokenRequest(
            event_id = eventId,
            device_id = "phone-a",
            participant_name = "Phone A (Requester)"
        )

        assertEquals(eventId, request.event_id)
        assertEquals("phone-a", request.device_id)
        assertEquals("Phone A (Requester)", request.participant_name)

        val response = CallTokenResponse(
            server_url = "ws://192.168.1.4:7880",
            token = "jwt.token.string",
            room_name = "emergency-$eventId",
            identity = "phone-a",
            expires_in_seconds = 600
        )

        assertEquals("ws://192.168.1.4:7880", response.server_url)
        assertEquals("jwt.token.string", response.token)
        assertEquals("emergency-$eventId", response.room_name)
        assertEquals("phone-a", response.identity)
        assertEquals(600, response.expires_in_seconds)
    }

    @Test
    fun testEndCall_resetsState() {
        LiveKitCallManager.callState.value = CallState.CONNECTED
        LiveKitCallManager.activeRoomName.value = "emergency-test-room"
        LiveKitCallManager.remoteParticipantName.value = "Phone B"
        LiveKitCallManager.isMicMuted.value = true

        LiveKitCallManager.endCall(context = null)

        assertEquals(CallState.ENDED, LiveKitCallManager.callState.value)
        assertEquals("Call ended.", LiveKitCallManager.callStatusMessage.value)
        assertFalse(LiveKitCallManager.isMicMuted.value)
        assertEquals(null, LiveKitCallManager.remoteParticipantName.value)
        assertEquals(null, LiveKitCallManager.activeRoomName.value)
    }

    @Test
    fun testIsValidUuid_canonicalValid() {
        val validId = UUID.randomUUID().toString()
        assertTrue(EmergencyUtils.isValidUuid(validId))
        assertTrue(EmergencyUtils.isValidUuid(validId.uppercase()))
    }

    @Test
    fun testIsValidUuid_invalidFormats() {
        assertFalse(EmergencyUtils.isValidUuid(null))
        assertFalse(EmergencyUtils.isValidUuid(""))
        assertFalse(EmergencyUtils.isValidUuid("    "))
        assertFalse(EmergencyUtils.isValidUuid("not-a-uuid"))
        assertFalse(EmergencyUtils.isValidUuid("12345678-1234-1234-1234")) // too short

        val validId = UUID.randomUUID().toString()
        // Doubled / repeated UUID (<uuid><uuid>) = 72 chars
        assertFalse(EmergencyUtils.isValidUuid("$validId$validId"))
        assertFalse(EmergencyUtils.isValidUuid("$validId.")) // Trailing period
        assertFalse(EmergencyUtils.isValidUuid("Event: $validId")) // With prefix
    }

    @Test
    fun testSanitizeEventIdInput_cleanUuid() {
        val validId = UUID.randomUUID().toString()
        val sanitized = EmergencyUtils.sanitizeEventIdInput(validId)
        assertEquals(validId.lowercase(), sanitized)
    }

    @Test
    fun testSanitizeEventIdInput_smsExtractedVariations() {
        val validId = UUID.randomUUID().toString()

        // With "Event:" prefix and trailing period (from SMS "Event: <uuid>. Location:...")
        val smsCopied = "Event: $validId."
        assertEquals(validId.lowercase(), EmergencyUtils.sanitizeEventIdInput(smsCopied))

        // Lowercase prefix and whitespace
        val lowerPrefix = "event:   $validId   "
        assertEquals(validId.lowercase(), EmergencyUtils.sanitizeEventIdInput(lowerPrefix))

        // In quotes
        val quoted = "\"$validId\""
        assertEquals(validId.lowercase(), EmergencyUtils.sanitizeEventIdInput(quoted))
    }

    @Test
    fun testSanitizeEventIdInput_doubledUuidRejected() {
        val validId = UUID.randomUUID().toString()
        val doubledId = "$validId$validId"
        // Repeated UUID must NOT be silently trimmed to first half; it must return null to trigger UI validation error
        val result = EmergencyUtils.sanitizeEventIdInput(doubledId)
        assertEquals(null, result)
    }

    @Test
    fun testStartCall_withInvalidUuid_failsImmediately() {
        val invalidId = "invalid-uuid-repeated-or-malformed"
        LiveKitCallManager.startCall(
            context = null,
            eventId = invalidId,
            deviceId = "phone-b",
            displayName = "Phone B"
        )
        assertEquals(CallState.FAILED, LiveKitCallManager.callState.value)
        assertTrue(LiveKitCallManager.callStatusMessage.value.contains("Invalid event ID"))
    }
}
