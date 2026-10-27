package com.example.aegis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class SecurityValidationUnitTest {

    @Before
    fun setUp() {
        EmergencyTriggerGuard.reset()
    }

    // --- 1. Injection & Sanitization Tests ---

    @Test
    fun testSanitizeEventId_rejectsInjectionAttempts() {
        // SQL injection patterns
        assertNull(EmergencyUtils.sanitizeEventIdInput("'; DROP TABLE emergency_events; --"))
        assertNull(EmergencyUtils.sanitizeEventIdInput("' OR '1'='1"))
        assertNull(EmergencyUtils.sanitizeEventIdInput("SELECT * FROM emergency_events"))

        // Path traversal
        assertNull(EmergencyUtils.sanitizeEventIdInput("../../etc/passwd"))
        assertNull(EmergencyUtils.sanitizeEventIdInput("..\\windows\\system32"))

        // XSS / Script injection
        assertNull(EmergencyUtils.sanitizeEventIdInput("<script>alert('xss')</script>"))
        assertNull(EmergencyUtils.sanitizeEventIdInput("javascript:void(0)"))

        // Null bytes and control characters
        assertNull(EmergencyUtils.sanitizeEventIdInput("uuid\u0000injected"))
        assertNull(EmergencyUtils.sanitizeEventIdInput("\n\r\t"))
    }

    @Test
    fun testSanitizeEventId_strictlyRequiresCanonicalUuidFormat() {
        // Valid 36-character UUID must pass
        val validUuid = UUID.randomUUID().toString()
        assertEquals(validUuid.lowercase(), EmergencyUtils.sanitizeEventIdInput(validUuid))

        // 35 characters (one missing) -> rejected
        assertNull(EmergencyUtils.sanitizeEventIdInput(validUuid.substring(0, 35)))

        // 37 characters (extra character) -> rejected
        assertNull(EmergencyUtils.sanitizeEventIdInput(validUuid + "a"))

        // Non-hex character (e.g. 'g', 'z') -> rejected
        val nonHex = validUuid.replaceFirst(validUuid[0], 'z')
        assertNull(EmergencyUtils.sanitizeEventIdInput(nonHex))
    }

    // --- 2. Coordinate Range & Robustness ---

    @Test
    fun testIsValidCoordinate_boundaryEnforcement() {
        // Valid boundaries
        assertTrue(EmergencyUtils.isValidCoordinate(0.0, 0.0))
        assertTrue(EmergencyUtils.isValidCoordinate(90.0, 180.0))
        assertTrue(EmergencyUtils.isValidCoordinate(-90.0, -180.0))
        assertTrue(EmergencyUtils.isValidCoordinate(37.774929, -122.419416))

        // Latitude out of bounds
        assertFalse(EmergencyUtils.isValidCoordinate(90.0001, 0.0))
        assertFalse(EmergencyUtils.isValidCoordinate(-90.0001, 0.0))
        assertFalse(EmergencyUtils.isValidCoordinate(180.0, 0.0))

        // Longitude out of bounds
        assertFalse(EmergencyUtils.isValidCoordinate(0.0, 180.0001))
        assertFalse(EmergencyUtils.isValidCoordinate(0.0, -180.0001))
        assertFalse(EmergencyUtils.isValidCoordinate(0.0, 360.0))

        // Nulls
        assertFalse(EmergencyUtils.isValidCoordinate(null, 0.0))
        assertFalse(EmergencyUtils.isValidCoordinate(0.0, null))
        assertFalse(EmergencyUtils.isValidCoordinate(null, null))

        // Special floats (NaN, Infinity)
        assertFalse(EmergencyUtils.isValidCoordinate(Double.NaN, 0.0))
        assertFalse(EmergencyUtils.isValidCoordinate(Double.POSITIVE_INFINITY, 0.0))
        assertFalse(EmergencyUtils.isValidCoordinate(0.0, Double.NEGATIVE_INFINITY))
    }

    // --- 3. Phone Number Validation & Sanitization ---

    @Test
    fun testIsValidPhoneNumber_rejectsMaliciousAndMalformedInputs() {
        // Valid phone numbers
        assertTrue(EmergencyUtils.isValidPhoneNumber("+1234567890"))
        assertTrue(EmergencyUtils.isValidPhoneNumber("9876543210"))
        assertTrue(EmergencyUtils.isValidPhoneNumber("+91-98765-43210"))
        assertTrue(EmergencyUtils.isValidPhoneNumber("  +18005550199  "))

        // Injection characters
        assertFalse(EmergencyUtils.isValidPhoneNumber("9876543210; rm -rf /"))
        assertFalse(EmergencyUtils.isValidPhoneNumber("9876543210' OR '1'='1"))
        assertFalse(EmergencyUtils.isValidPhoneNumber("<script>9876543210</script>"))

        // Empty / Null / Whitespace
        assertFalse(EmergencyUtils.isValidPhoneNumber(null))
        assertFalse(EmergencyUtils.isValidPhoneNumber(""))
        assertFalse(EmergencyUtils.isValidPhoneNumber("   "))

        // Length limits
        assertFalse(EmergencyUtils.isValidPhoneNumber("123")) // Too short
        assertFalse(EmergencyUtils.isValidPhoneNumber("1234567890123456789012345")) // Too long
    }

    // --- 4. Token Masking Safety ---

    @Test
    fun testTokenMasking_preventsCredentialExposure() {
        val longToken = "fcm_token_super_secret_registration_key_1234567890_abcdefghij"
        val tokenPreview = if (longToken.length > 12) "${longToken.take(6)}...${longToken.takeLast(4)}" else longToken

        // Must show start and end for correlation
        assertTrue(tokenPreview.startsWith("fcm_to"))
        assertTrue(tokenPreview.endsWith("ghij"))

        // Must NOT expose the sensitive body of the token
        assertFalse(tokenPreview.contains("super_secret_registration_key"))
        assertEquals("fcm_to...ghij", tokenPreview)
    }

    // --- 5. Atomic Trigger Session Guard ---

    @Test
    fun testSessionGuard_atomicImmutability() {
        assertTrue(EmergencyTriggerGuard.tryAcquireTrigger())
        assertTrue(EmergencyTriggerGuard.isActive)

        // Simultaneous trigger from another thread or callback cannot alter or re-acquire
        assertFalse(EmergencyTriggerGuard.tryAcquireTrigger())
        assertFalse(EmergencyTriggerGuard.tryAcquireTrigger())

        // Session remains locked even after execution completes
        EmergencyTriggerGuard.markWorkflowComplete()
        assertFalse(EmergencyTriggerGuard.tryAcquireTrigger())
        assertTrue(EmergencyTriggerGuard.isActive)
    }
}
