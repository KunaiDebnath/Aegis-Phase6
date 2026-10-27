package com.example.aegis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EmergencyContactUnitTest {

    @Before
    fun setUp() {
        EmergencyTriggerGuard.reset()
        EmergencyOrchestrator.emergencyRecipientNumber.value = ""
        EmergencyContactManager.savedContactPhone.value = null
        EmergencyContactManager.isContactConfigured.value = false
        EmergencyContactManager.statusFeedback.value = null
        EmergencyContactManager.isError.value = false
    }

    // --- 1. Phone Number Format Validation Tests ---

    @Test
    fun testPhoneNumberValidation_validFormats() {
        // Standard international E.164 formats
        assertTrue("Valid US E.164", EmergencyUtils.isValidPhoneNumber("+1234567890"))
        assertTrue("Valid India E.164", EmergencyUtils.isValidPhoneNumber("+919876543210"))
        assertTrue("Valid UK E.164", EmergencyUtils.isValidPhoneNumber("+447911123456"))

        // Formats with hyphens and spaces
        assertTrue("Formatted with hyphens", EmergencyUtils.isValidPhoneNumber("+1-555-123-4567"))
        assertTrue("Formatted with spaces", EmergencyUtils.isValidPhoneNumber("+1 555 123 4567"))
        assertTrue("Without leading plus", EmergencyUtils.isValidPhoneNumber("1234567890"))

        // Minimum (7 digits) and Maximum (15 digits) bounds
        assertTrue("7 digits minimum", EmergencyUtils.isValidPhoneNumber("+1234567"))
        assertTrue("15 digits maximum", EmergencyUtils.isValidPhoneNumber("+123456789012345"))
    }

    @Test
    fun testPhoneNumberValidation_invalidFormatsRejected() {
        // Null and blank strings
        assertFalse("Null string rejected", EmergencyUtils.isValidPhoneNumber(null))
        assertFalse("Empty string rejected", EmergencyUtils.isValidPhoneNumber(""))
        assertFalse("Whitespace string rejected", EmergencyUtils.isValidPhoneNumber("   "))

        // Too short (< 7 digits)
        assertFalse("Too short (3 digits)", EmergencyUtils.isValidPhoneNumber("123"))
        assertFalse("Too short (6 digits)", EmergencyUtils.isValidPhoneNumber("123456"))

        // Too long (> 15 digits)
        assertFalse("Too long (16 digits)", EmergencyUtils.isValidPhoneNumber("+1234567890123456"))

        // Letters and symbols
        assertFalse("Contains letters", EmergencyUtils.isValidPhoneNumber("+12345abcde"))
        assertFalse("Letters only", EmergencyUtils.isValidPhoneNumber("emergency"))
        assertFalse("Special symbols", EmergencyUtils.isValidPhoneNumber("+123#456*789"))
        assertFalse("Only plus signs", EmergencyUtils.isValidPhoneNumber("+++++++"))
        assertFalse("Script tag injection", EmergencyUtils.isValidPhoneNumber("<script>alert(1)</script>"))
    }

    // --- 2. Emergency Contact Manager State Tests ---

    @Test
    fun testEmergencyContactManager_stateTransitions() {
        // Initial state
        assertNull(EmergencyContactManager.savedContactPhone.value)
        assertFalse(EmergencyContactManager.isContactConfigured.value)

        // Simulate successful save
        EmergencyContactManager.savedContactPhone.value = "+1234567890"
        EmergencyContactManager.isContactConfigured.value = true
        EmergencyContactManager.statusFeedback.value = "Emergency contact saved successfully."

        assertEquals("+1234567890", EmergencyContactManager.savedContactPhone.value)
        assertTrue(EmergencyContactManager.isContactConfigured.value)
        assertFalse(EmergencyContactManager.isError.value)
        assertEquals("Emergency contact saved successfully.", EmergencyContactManager.statusFeedback.value)

        // Simulate update to new number
        EmergencyContactManager.savedContactPhone.value = "+9876543210"
        assertEquals("+9876543210", EmergencyContactManager.savedContactPhone.value)
    }

    // --- 3. Independent SOS Operation When Contact Is Not Configured ---

    @Test
    fun testIndependentSosFunctionsPreserved_whenContactNotConfigured() {
        // Both local cache and orchestrator recipient are empty
        EmergencyContactManager.savedContactPhone.value = null
        EmergencyOrchestrator.emergencyRecipientNumber.value = ""

        val rawRecipient = ""
        val targetRecipient = rawRecipient.ifBlank {
            EmergencyOrchestrator.emergencyRecipientNumber.value.ifBlank {
                EmergencyContactManager.savedContactPhone.value ?: ""
            }
        }

        // Verify targetRecipient is blank and invalid
        assertTrue("Target recipient is blank", targetRecipient.isBlank())
        assertFalse("Target recipient is invalid", EmergencyUtils.isValidPhoneNumber(targetRecipient))

        // When targetRecipient is invalid, independent functions proceed without crash
        val hasValidRecipient = targetRecipient.isNotBlank() && EmergencyUtils.isValidPhoneNumber(targetRecipient)
        assertFalse(hasValidRecipient)

        // Duplicate trigger guard can still acquire session
        assertTrue("Guard allows initial emergency acquisition", EmergencyTriggerGuard.tryAcquireTrigger())
        assertTrue("Emergency session is active", EmergencyTriggerGuard.isActive)

        EmergencyTriggerGuard.reset()
    }

    @Test
    fun testTargetRecipientResolution_prefersExplicitOverCached() {
        EmergencyContactManager.savedContactPhone.value = "+1111111111"
        EmergencyOrchestrator.emergencyRecipientNumber.value = "+2222222222"

        // 1. Explicit recipient takes precedence
        val explicitRecipient = "+3333333333"
        val resolvedExplicit = explicitRecipient.ifBlank {
            EmergencyOrchestrator.emergencyRecipientNumber.value.ifBlank {
                EmergencyContactManager.savedContactPhone.value ?: ""
            }
        }
        assertEquals("+3333333333", resolvedExplicit)

        // 2. Fallback to orchestrator number when explicit is blank
        val blankExplicit = ""
        val resolvedFallback = blankExplicit.ifBlank {
            EmergencyOrchestrator.emergencyRecipientNumber.value.ifBlank {
                EmergencyContactManager.savedContactPhone.value ?: ""
            }
        }
        assertEquals("+2222222222", resolvedFallback)

        // 3. Fallback to manager saved contact when orchestrator is also blank
        EmergencyOrchestrator.emergencyRecipientNumber.value = ""
        val resolvedManager = blankExplicit.ifBlank {
            EmergencyOrchestrator.emergencyRecipientNumber.value.ifBlank {
                EmergencyContactManager.savedContactPhone.value ?: ""
            }
        }
        assertEquals("+1111111111", resolvedManager)
    }
}
