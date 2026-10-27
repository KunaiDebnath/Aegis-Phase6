package com.example.aegis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class SmsEmergencyUnitTest {

    @Before
    fun setUp() {
        EmergencyTriggerGuard.reset()
    }

    // --- Phase 4: Voice Phrase Normalization & Matching Tests ---

    @Test
    fun testPhraseNormalization_variousFormats() {
        assertEquals("i need help", EmergencyUtils.normalizePhrase("I need help"))
        assertEquals("i need help", EmergencyUtils.normalizePhrase("  I   need   help  "))
        assertEquals("i need help", EmergencyUtils.normalizePhrase("I NEED HELP!!!"))
        assertEquals("i need help", EmergencyUtils.normalizePhrase("I, need help..."))
        assertEquals("", EmergencyUtils.normalizePhrase(""))
        assertEquals("", EmergencyUtils.normalizePhrase("   "))
        assertEquals("", EmergencyUtils.normalizePhrase(null))
    }

    @Test
    fun testMatchesEmergencyPhrase_exactAndCaseInsensitive() {
        val target = "I need help"

        assertTrue("Exact match should trigger", EmergencyUtils.matchesEmergencyPhrase("I need help", target))
        assertTrue("Lowercase should trigger", EmergencyUtils.matchesEmergencyPhrase("i need help", target))
        assertTrue("Uppercase should trigger", EmergencyUtils.matchesEmergencyPhrase("I NEED HELP", target))
        assertTrue("Mixed case should trigger", EmergencyUtils.matchesEmergencyPhrase("i NeEd HeLp", target))
    }

    @Test
    fun testMatchesEmergencyPhrase_whitespaceAndPunctuationNormalization() {
        val target = "I need help"

        assertTrue("Extra whitespace should trigger", EmergencyUtils.matchesEmergencyPhrase("   I    need   help   ", target))
        assertTrue("Exclamation mark should trigger", EmergencyUtils.matchesEmergencyPhrase("I need help!", target))
        assertTrue("Question mark should trigger", EmergencyUtils.matchesEmergencyPhrase("I need help?", target))
        assertTrue("Ellipsis should trigger", EmergencyUtils.matchesEmergencyPhrase("I need help...", target))
    }

    @Test
    fun testMatchesEmergencyPhrase_embeddedInSentence() {
        val target = "I need help"

        assertTrue("Phrase embedded in sentence should trigger", EmergencyUtils.matchesEmergencyPhrase("Please I need help now", target))
        assertTrue("Phrase at start of sentence should trigger", EmergencyUtils.matchesEmergencyPhrase("I need help please answer me", target))
        assertTrue("Phrase with punctuation inside sentence", EmergencyUtils.matchesEmergencyPhrase("Oh god, I need help, hurry!", target))
    }

    @Test
    fun testMatchesEmergencyPhrase_unrelatedSpeechRejected() {
        val target = "I need help"

        assertFalse("Unrelated conversation should not trigger", EmergencyUtils.matchesEmergencyPhrase("The weather is nice today", target))
        assertFalse("Random speech should not trigger", EmergencyUtils.matchesEmergencyPhrase("Hello how are you doing", target))
        assertFalse("Partial word overlap should not trigger", EmergencyUtils.matchesEmergencyPhrase("I need a helicopter", target))
    }

    @Test
    fun testMatchesEmergencyPhrase_partialAndAmbiguousPhrasesRejected() {
        val target = "I need help"

        // Sub-phrase alone must NOT trigger
        assertFalse("Single word 'help' should not trigger multi-word target", EmergencyUtils.matchesEmergencyPhrase("help", target))
        assertFalse("Sub-phrase 'I need' should not trigger", EmergencyUtils.matchesEmergencyPhrase("I need", target))
        assertFalse("Word 'need help' alone should not trigger", EmergencyUtils.matchesEmergencyPhrase("need help", target))

        // Partial word containment must NOT trigger
        assertFalse("Word 'helpful' should not trigger", EmergencyUtils.matchesEmergencyPhrase("I need helpful advice", target))
        assertFalse("Word 'helpless' should not trigger", EmergencyUtils.matchesEmergencyPhrase("I feel helpless", target))
    }

    // --- Phase 4: Thread-Safe Trigger Guard Tests ---

    @Test
    fun testEmergencyTriggerGuard_singleAcquisitionAndDebounce() {
        // First trigger acquires
        assertTrue("First trigger attempt must succeed", EmergencyTriggerGuard.tryAcquireTrigger())
        assertTrue("Emergency should be marked active", EmergencyTriggerGuard.isActive)
        assertTrue("Workflow should be marked executing", EmergencyTriggerGuard.isExecuting)

        // Subsequent concurrent callbacks must be blocked
        assertFalse("Concurrent trigger callback must be blocked while active", EmergencyTriggerGuard.tryAcquireTrigger())
        assertFalse("Another rapid callback must be blocked", EmergencyTriggerGuard.tryAcquireTrigger())

        // Mark workflow complete (e.g. backend/SMS finished)
        EmergencyTriggerGuard.markWorkflowComplete()
        assertFalse("Workflow executing should now be false", EmergencyTriggerGuard.isExecuting)
        assertTrue("Emergency remains active until session reset", EmergencyTriggerGuard.isActive)

        // Still blocked because emergency is active
        assertFalse("Trigger should still be blocked while session is active", EmergencyTriggerGuard.tryAcquireTrigger())

        // Reset guard (e.g. user taps "NEW SOS EVENT")
        EmergencyTriggerGuard.reset()
        assertFalse("Emergency should no longer be active after reset", EmergencyTriggerGuard.isActive)

        // Now a new emergency can be triggered
        assertTrue("New trigger must succeed after reset", EmergencyTriggerGuard.tryAcquireTrigger())
    }

    // --- Phase 3 & 2 Verified Features Regression Tests ---

    @Test
    fun testPhoneNumberValidation_validNumbers() {
        assertTrue(EmergencyUtils.isValidPhoneNumber("+1234567890"))
        assertTrue(EmergencyUtils.isValidPhoneNumber("+919876543210"))
        assertTrue(EmergencyUtils.isValidPhoneNumber("1234567890"))
        assertTrue(EmergencyUtils.isValidPhoneNumber("+44 20 7946 0958"))
        assertTrue(EmergencyUtils.isValidPhoneNumber("+1-800-555-0199"))
    }

    @Test
    fun testPhoneNumberValidation_invalidNumbers() {
        assertFalse("Empty string should be invalid", EmergencyUtils.isValidPhoneNumber(""))
        assertFalse("Whitespace should be invalid", EmergencyUtils.isValidPhoneNumber("   "))
        assertFalse("Null should be invalid", EmergencyUtils.isValidPhoneNumber(null))
        assertFalse("Too short should be invalid", EmergencyUtils.isValidPhoneNumber("12345"))
        assertFalse("Characters should be invalid", EmergencyUtils.isValidPhoneNumber("1234abc567"))
        assertFalse("Special chars should be invalid", EmergencyUtils.isValidPhoneNumber("!@#$%^&*()"))
        assertFalse("Too long should be invalid", EmergencyUtils.isValidPhoneNumber("+123456789012345678901"))
    }

    @Test
    fun testCoordinateValidation_validAndInvalidBounds() {
        assertTrue(EmergencyUtils.isValidCoordinate(0.0, 0.0))
        assertTrue(EmergencyUtils.isValidCoordinate(37.7749, -122.4194))
        assertTrue(EmergencyUtils.isValidCoordinate(90.0, 180.0))
        assertTrue(EmergencyUtils.isValidCoordinate(-90.0, -180.0))

        assertFalse(EmergencyUtils.isValidCoordinate(90.1, 0.0))
        assertFalse(EmergencyUtils.isValidCoordinate(-90.1, 0.0))
        assertFalse(EmergencyUtils.isValidCoordinate(0.0, 180.1))
        assertFalse(EmergencyUtils.isValidCoordinate(0.0, -180.1))
        assertFalse(EmergencyUtils.isValidCoordinate(null, 10.0))
        assertFalse(EmergencyUtils.isValidCoordinate(10.0, null))
        assertFalse(EmergencyUtils.isValidCoordinate(null, null))
    }

    @Test
    fun testBuildEmergencySmsMessage_withEventIdAndCoordinates() {
        val eventId = "3fa85f64-5717-4562-b3fc-2c963f66afa6"
        val idempotencyKey = "uuid-key-123"
        val location = EmergencyLocation(
            latitude = 37.774929,
            longitude = -122.419416,
            accuracyMeters = 8.5f
        )
        val message = EmergencyUtils.buildEmergencySmsMessage(eventId, idempotencyKey, location)

        assertTrue(message.contains("AEGIS EMERGENCY ALERT"))
        assertTrue(message.contains("An emergency has been triggered"))
        assertTrue(message.contains("Event: $eventId"))
        assertTrue(message.contains("Location: https://www.google.com/maps?q=37.77493,-122.41942"))
        assertFalse(message.contains("Location unavailable"))
        assertFalse(message.contains("Ref:"))
        assertTrue("SMS length within standard GSM single SMS (160 characters)", message.length <= 160)
    }

    @Test
    fun testBuildEmergencySmsMessage_withEventIdAndLocationUnavailable() {
        val eventId = "3fa85f64-5717-4562-b3fc-2c963f66afa6"
        val idempotencyKey = "uuid-key-123"
        val message = EmergencyUtils.buildEmergencySmsMessage(eventId, idempotencyKey, null)

        assertTrue(message.contains("AEGIS EMERGENCY ALERT"))
        assertTrue(message.contains("An emergency has been triggered"))
        assertTrue(message.contains("Event: $eventId"))
        assertTrue(message.contains("(Location unavailable)"))
        assertFalse(message.contains("google.com/maps"))
        assertTrue("SMS length within standard GSM single SMS", message.length <= 160)
    }

    @Test
    fun testBuildEmergencySmsMessage_backendOfflineWithCoordinates() {
        val idempotencyKey = "d8b0213b-15f1-4fc3-a55e-045a557b4478"
        val location = EmergencyLocation(
            latitude = 12.9716,
            longitude = 77.5946
        )
        val message = EmergencyUtils.buildEmergencySmsMessage(null, idempotencyKey, location)

        assertTrue(message.contains("AEGIS EMERGENCY ALERT"))
        assertTrue(message.contains("An emergency has been triggered"))
        assertTrue(message.contains("Ref: d8b0213b"))
        assertTrue(message.contains("Location: https://www.google.com/maps?q=12.97160,77.59460"))
        assertFalse(message.contains("Event:"))
        assertTrue(message.length <= 160)
    }

    @Test
    fun testBuildTestSmsMessage_distinguishedFromEmergency() {
        val testMessage = EmergencyUtils.buildTestSmsMessage()

        assertTrue(testMessage.contains("NON-EMERGENCY TEST"))
        assertTrue(testMessage.contains("No emergency has occurred"))
        assertFalse(testMessage.contains("Emergency has been triggered"))
        assertTrue("Single SMS length within limit", testMessage.length <= 160)
    }

    @Test
    fun testIdempotencyKeyGeneration_uniqueAcrossEvents() {
        val key1 = UUID.randomUUID().toString()
        val key2 = UUID.randomUUID().toString()

        assertNotEquals(key1, key2)
        assertEquals(36, key1.length)
        assertEquals(36, key2.length)
    }

    @Test
    fun testStatusEnums_completenessAndDistinction() {
        val smsStates = SmsDeliveryStatus.values().toSet()
        assertEquals(7, smsStates.size)

        val locStates = LocationAcquisitionStatus.values().toSet()
        assertEquals(6, locStates.size)

        val voiceStates = VoiceProtectionStatus.values().toSet()
        assertEquals(6, voiceStates.size)
        assertTrue(voiceStates.contains(VoiceProtectionStatus.DISABLED))
        assertTrue(voiceStates.contains(VoiceProtectionStatus.STARTING))
        assertTrue(voiceStates.contains(VoiceProtectionStatus.LISTENING))
        assertTrue(voiceStates.contains(VoiceProtectionStatus.PAUSED_FOR_CALL))
        assertTrue(voiceStates.contains(VoiceProtectionStatus.ERROR))
        assertTrue(voiceStates.contains(VoiceProtectionStatus.TRIGGERED))

        val backendStates = BackendSyncStatus.values().toSet()
        assertEquals(4, backendStates.size)
    }
}
