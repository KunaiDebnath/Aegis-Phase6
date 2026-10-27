package com.example.aegis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class IntegrationFailureHandlingUnitTest {

    @Before
    fun setUp() {
        EmergencyTriggerGuard.reset()
        CallSignalingClient.cancelExpiryTimer()
        CallSignalingClient.activeIncomingInvitation.value = null
        CallSignalingClient.remainingSeconds.value = 0
        CallSignalingClient.callerFeedbackMessage.value = null
        CallSignalingClient.activeOutgoingInvitationId.value = null
        LiveKitCallManager.callState.value = CallState.IDLE
        LiveKitCallManager.callStatusMessage.value = "No active call."
        MicrophoneCoordinator.resetForTesting()
    }

    // --- 1. Duplicate Trigger Guard & Cooldown Tests ---

    @Test
    fun testDuplicateTriggerGuard_rapidConcurrentTriggersBlocked() {
        // First trigger acquires
        assertTrue("Initial trigger must succeed", EmergencyTriggerGuard.tryAcquireTrigger())
        assertTrue("Emergency session must be marked active", EmergencyTriggerGuard.isActive)
        assertTrue("Workflow execution must be marked in progress", EmergencyTriggerGuard.isExecuting)

        // Rapid subsequent triggers within cooldown are blocked
        for (i in 1..10) {
            assertFalse("Rapid trigger $i must be blocked by EmergencyTriggerGuard", EmergencyTriggerGuard.tryAcquireTrigger())
        }

        // Even after workflow execution completes, emergency session remains active until explicit reset
        EmergencyTriggerGuard.markWorkflowComplete()
        assertFalse(EmergencyTriggerGuard.isExecuting)
        assertTrue("Emergency session must remain active to prevent re-triggering", EmergencyTriggerGuard.isActive)
        assertFalse("New trigger must be blocked while session is active", EmergencyTriggerGuard.tryAcquireTrigger())

        // Reset clears guard
        EmergencyTriggerGuard.reset()
        assertFalse(EmergencyTriggerGuard.isActive)
        assertFalse(EmergencyTriggerGuard.isExecuting)
        assertTrue("Trigger should be acquirable after session reset", EmergencyTriggerGuard.tryAcquireTrigger())
    }

    // --- 2. Fallback SMS Formatting When Subsystems Fail ---

    @Test
    fun testEmergencySmsFormatting_whenBackendAndGpsBothFail() {
        // Backend offline -> eventId is null
        // GPS offline -> location is null
        val idempotencyKey = "abcdef12-3456-7890-abcd-ef1234567890"
        val message = EmergencyUtils.buildEmergencySmsMessage(
            eventId = null,
            idempotencyKey = idempotencyKey,
            location = null
        )

        assertNotNull(message)
        assertTrue("Must include emergency alert header", message.contains("AEGIS EMERGENCY ALERT"))
        assertTrue("Must fallback to idempotency reference prefix", message.contains("Ref: abcdef12"))
        assertTrue("Must indicate location unavailable", message.contains("(Location unavailable)"))
        assertFalse("Must not contain a broken google maps link", message.contains("https://www.google.com/maps"))
    }

    @Test
    fun testEmergencySmsFormatting_whenBackendFailsButGpsSucceeds() {
        val idempotencyKey = "abcdef12-3456-7890-abcd-ef1234567890"
        val loc = EmergencyLocation(latitude = 37.774929, longitude = -122.419416, accuracyMeters = 5.0f)
        val message = EmergencyUtils.buildEmergencySmsMessage(
            eventId = null,
            idempotencyKey = idempotencyKey,
            location = loc
        )

        assertTrue(message.contains("Ref: abcdef12"))
        assertTrue("Must contain formatted Google Maps link", message.contains("https://www.google.com/maps?q=37.77493,-122.41942"))
    }

    @Test
    fun testEmergencySmsFormatting_whenBackendSucceedsButGpsFails() {
        val eventId = UUID.randomUUID().toString()
        val idempotencyKey = UUID.randomUUID().toString()
        val message = EmergencyUtils.buildEmergencySmsMessage(
            eventId = eventId,
            idempotencyKey = idempotencyKey,
            location = null
        )

        assertTrue("Must contain canonical Event ID", message.contains("Event: $eventId"))
        assertTrue("Must report location unavailable", message.contains("(Location unavailable)"))
    }

    // --- 3. Duplicate Incoming Notification Timer Stability ---

    @Test
    fun testIncomingInvitation_duplicatePayloadDoesNotResetCountdownTimer() {
        val invId = UUID.randomUUID().toString()
        val eventId = UUID.randomUUID().toString()
        val inv = IncomingCallInvitation(
            invitationId = invId,
            emergencyEventId = eventId,
            callerDeviceId = "phone-a",
            callerDisplayName = "Phone A (Requester)",
            createdAtIso = "2026-10-27T10:00:00Z",
            expiresAtIso = "2026-10-27T10:01:00Z",
            status = "pending"
        )

        // 1. Initial invitation arrives via WebSocket
        CallSignalingClient.handleIncomingInvitation(inv)
        assertEquals(invId, CallSignalingClient.activeIncomingInvitation.value?.invitationId)
        assertEquals(60, CallSignalingClient.remainingSeconds.value)

        // 2. Cancel background ticker and simulate timer counting down to 45 seconds
        CallSignalingClient.cancelExpiryTimer()
        CallSignalingClient.remainingSeconds.value = 45

        // 3. Duplicate payload arrives (e.g. concurrent FCM push for same invitation)
        CallSignalingClient.handleIncomingInvitation(inv)

        // Timer must NOT have been bounced back to 60!
        assertEquals("Countdown timer must be preserved across duplicate payloads", 45, CallSignalingClient.remainingSeconds.value)
        assertEquals(invId, CallSignalingClient.activeIncomingInvitation.value?.invitationId)
    }

    @Test
    fun testIncomingInvitation_newInvitationReplacesExpiredOne() {
        val oldId = UUID.randomUUID().toString()
        val oldInv = IncomingCallInvitation(
            invitationId = oldId,
            emergencyEventId = "event-old",
            callerDeviceId = "phone-a",
            callerDisplayName = "Phone A",
            createdAtIso = "",
            expiresAtIso = "",
            status = "pending"
        )
        CallSignalingClient.handleIncomingInvitation(oldInv)
        assertEquals(oldId, CallSignalingClient.activeIncomingInvitation.value?.invitationId)

        // New invitation arrives with different ID
        val newId = UUID.randomUUID().toString()
        val newInv = IncomingCallInvitation(
            invitationId = newId,
            emergencyEventId = "event-new",
            callerDeviceId = "phone-a",
            callerDisplayName = "Phone A",
            createdAtIso = "",
            expiresAtIso = "",
            status = "pending"
        )
        CallSignalingClient.handleIncomingInvitation(newInv)
        assertEquals(newId, CallSignalingClient.activeIncomingInvitation.value?.invitationId)
        assertEquals(60, CallSignalingClient.remainingSeconds.value)
    }

    // --- 4. Event ID Input Sanitization and Validation ---

    @Test
    fun testSanitizeEventId_robustHandlingOfCopiedText() {
        val validUuid = UUID.randomUUID().toString()

        // Exact match
        assertEquals(validUuid.lowercase(), EmergencyUtils.sanitizeEventIdInput(validUuid))

        // Copied from SMS with prefix and trailing period
        assertEquals(validUuid.lowercase(), EmergencyUtils.sanitizeEventIdInput("Event: $validUuid."))
        assertEquals(validUuid.lowercase(), EmergencyUtils.sanitizeEventIdInput("Event: $validUuid,"))
        assertEquals(validUuid.lowercase(), EmergencyUtils.sanitizeEventIdInput("event:  $validUuid  "))

        // Wrapped in quotes
        assertEquals(validUuid.lowercase(), EmergencyUtils.sanitizeEventIdInput("\"$validUuid\""))
        assertEquals(validUuid.lowercase(), EmergencyUtils.sanitizeEventIdInput("'$validUuid'"))

        // Invalid garbage rejected
        assertNull(EmergencyUtils.sanitizeEventIdInput(null))
        assertNull(EmergencyUtils.sanitizeEventIdInput(""))
        assertNull(EmergencyUtils.sanitizeEventIdInput("   "))
        assertNull(EmergencyUtils.sanitizeEventIdInput("invalid-event-id"))
        assertNull(EmergencyUtils.sanitizeEventIdInput("12345"))
        // Doubled UUID (copy-paste error) rejected
        assertNull(EmergencyUtils.sanitizeEventIdInput("$validUuid$validUuid"))
    }

    // --- 5. Phone Number Input Validation ---

    @Test
    fun testIsValidPhoneNumber_validFormats() {
        assertTrue(EmergencyUtils.isValidPhoneNumber("+1234567890"))
        assertTrue(EmergencyUtils.isValidPhoneNumber("9876543210"))
        assertTrue(EmergencyUtils.isValidPhoneNumber("+91 98765 43210"))
        assertTrue(EmergencyUtils.isValidPhoneNumber("+1-800-555-0199"))
    }

    @Test
    fun testIsValidPhoneNumber_invalidFormats() {
        assertFalse(EmergencyUtils.isValidPhoneNumber(null))
        assertFalse(EmergencyUtils.isValidPhoneNumber(""))
        assertFalse(EmergencyUtils.isValidPhoneNumber("   "))
        assertFalse(EmergencyUtils.isValidPhoneNumber("12345")) // Too short (<7 digits)
        assertFalse(EmergencyUtils.isValidPhoneNumber("abcdefghij")) // Non-digits
        assertFalse(EmergencyUtils.isValidPhoneNumber("123456789012345678901")) // Too long (>15 digits)
    }
}
