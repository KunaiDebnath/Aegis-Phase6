package com.example.aegis

import java.util.Locale

/**
 * Represents the status of the emergency backend synchronization.
 */
enum class BackendSyncStatus {
    IDLE,
    SENDING,
    RECORDED,
    FAILED
}

/**
 * Represents the 4-phase status of the SMS emergency alert delivery:
 * 1. INITIATED: App has initiated the SMS send request.
 * 2. ACCEPTED_BY_TELEPHONY: SmsManager accepted the request without immediate error.
 * 3. SENT_SUCCESS / SENT_FAILED: Android radio/carrier network reported transmission result.
 * 4. DELIVERED / DELIVERY_FAILED: Android carrier SMSC reported recipient delivery status (if supported).
 */
enum class SmsDeliveryStatus {
    IDLE,
    INITIATED,
    ACCEPTED_BY_TELEPHONY,
    SENT_SUCCESS,
    SENT_FAILED,
    DELIVERED,
    DELIVERY_FAILED
}

/**
 * Represents the status of the GPS emergency location acquisition.
 */
enum class LocationAcquisitionStatus {
    IDLE,
    ACQUIRING,
    SUCCESS,
    UNAVAILABLE,
    TIMEOUT,
    PERMISSION_DENIED
}

/**
 * Represents the status of voice-triggered protection.
 */
enum class VoiceProtectionStatus {
    DISABLED,
    STARTING,
    LISTENING,
    PAUSED_FOR_CALL,
    ERROR,
    TRIGGERED
}

/**
 * Encapsulates an emergency geographic coordinate fix with optional accuracy and timestamp.
 */
data class EmergencyLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun isValid(): Boolean {
        return EmergencyUtils.isValidCoordinate(latitude, longitude)
    }

    /**
     * Google Maps link with coordinates formatted to 5 decimal places (~1.1 meter precision),
     * ensuring high accuracy while keeping SMS messages concise.
     */
    fun toGoogleMapsUrl(): String {
        val latStr = String.format(Locale.US, "%.5f", latitude)
        val lngStr = String.format(Locale.US, "%.5f", longitude)
        return "https://www.google.com/maps?q=$latStr,$lngStr"
    }
}

data class SaveEmergencyContactRequest(
    val owner_device_id: String,
    val phone_number: String,
    val contact_name: String? = "Phone B"
)

data class EmergencyContactResponse(
    val owner_device_id: String,
    val contact_device_id: String,
    val contact_name: String? = null,
    val phone_number: String,
    val is_configured: Boolean = true,
    val message: String? = null,
    val updated_at: String? = null
)

object EmergencyUtils {
    const val DEFAULT_EMERGENCY_PHRASE = "I need help"

    /**
     * Validates whether a given recipient string is a plausible phone number.
     * Requires between 7 and 20 characters, allowing digits, an optional leading '+',
     * spaces, and hyphens. At least 7 numeric digits required.
     */
    fun isValidPhoneNumber(number: String?): Boolean {
        if (number.isNullOrBlank()) return false
        val trimmed = number.trim()
        val regex = Regex("^\\+?[0-9\\s\\-]{7,20}$")
        if (!regex.matches(trimmed)) return false
        val digitCount = trimmed.count { it.isDigit() }
        return digitCount in 7..15
    }

    /**
     * Validates that latitude is between -90 and 90, and longitude is between -180 and 180.
     */
    fun isValidCoordinate(latitude: Double?, longitude: Double?): Boolean {
        if (latitude == null || longitude == null) return false
        return latitude in -90.0..90.0 && longitude in -180.0..180.0
    }

    /**
     * Normalizes a phrase for reliable speech recognition comparison:
     * - Trims leading/trailing whitespace
     * - Converts to lowercase
     * - Strips non-alphanumeric characters (punctuation, special symbols)
     * - Collapses multiple spaces into a single space
     */
    fun normalizePhrase(phrase: String?): String {
        if (phrase.isNullOrBlank()) return ""
        return phrase.trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Matches a spoken recognition hypothesis against the configured emergency phrase.
     * Rule:
     * 1. Case-insensitive and punctuation-insensitive.
     * 2. Matches exact normalized phrase OR whole-phrase sequence within a sentence with word boundaries.
     * 3. Rejects partial word matches (e.g., 'help' will not match 'helpful').
     * 4. Rejects sub-phrase matches (e.g., 'I need' will not trigger when target is 'I need help').
     * 5. Rejects unrelated speech.
     */
    fun matchesEmergencyPhrase(spokenHypothesis: String?, targetPhrase: String?): Boolean {
        val normalizedSpoken = normalizePhrase(spokenHypothesis)
        val normalizedTarget = normalizePhrase(targetPhrase)

        if (normalizedSpoken.isBlank() || normalizedTarget.isBlank()) {
            return false
        }

        // Exact match
        if (normalizedSpoken == normalizedTarget) {
            return true
        }

        // Whole-phrase word-boundary containment (e.g. "please I need help now" contains "I need help")
        val pattern = "\\b" + Regex.escape(normalizedTarget) + "\\b"
        return Regex(pattern).containsMatchIn(normalizedSpoken)
    }

    /**
     * Builds the emergency SMS alert message.
     * When valid coordinates are present: includes Google Maps link.
     * When location is unavailable: explicitly states (Location unavailable) without a map link.
     * When backend event ID is present: references Event ID.
     * When backend is offline: references idempotency key prefix.
     */
    fun buildEmergencySmsMessage(
        eventId: String?,
        idempotencyKey: String,
        location: EmergencyLocation?
    ): String {
        val eventRef = if (!eventId.isNullOrBlank()) {
            "Event: $eventId"
        } else {
            "Ref: ${idempotencyKey.take(8)}"
        }

        val hasValidLocation = location != null && isValidCoordinate(location.latitude, location.longitude)
        val locationPart = if (hasValidLocation) {
            "Location: ${location!!.toGoogleMapsUrl()}"
        } else {
            "(Location unavailable)"
        }

        return "AEGIS EMERGENCY ALERT: An emergency has been triggered. $eventRef. $locationPart"
    }

    /**
     * Builds a clearly labeled non-emergency test SMS message.
     */
    fun buildTestSmsMessage(): String {
        return "AEGIS [NON-EMERGENCY TEST]: This is a test message from Phone A to verify SMS connectivity. No emergency has occurred."
    }

    private val UUID_REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /**
     * Checks if a string is a valid canonical 36-character UUID.
     */
    fun isValidUuid(uuidStr: String?): Boolean {
        if (uuidStr.isNullOrBlank()) return false
        val trimmed = uuidStr.trim()
        if (trimmed.length != 36) return false
        return UUID_REGEX.matches(trimmed)
    }

    /**
     * Sanitizes and validates an event ID input (e.g. copied from SMS or user input).
     * Handles:
     * - Trimming leading/trailing whitespace
     * - Stripping optional "Event:" or "event:" prefix from SMS text
     * - Stripping trailing punctuation like '.' or ',' from sentences
     * 
     * Returns the canonical lowercase UUID string if valid.
     * Returns null if the input is blank, invalid, or contains repeated/extra characters,
     * without silently altering legitimate identifiers.
     */
    fun sanitizeEventIdInput(rawInput: String?): String? {
        if (rawInput.isNullOrBlank()) return null
        var cleaned = rawInput.trim()

        // Strip leading "Event:" or "event:" prefix if copied from SMS alert
        if (cleaned.startsWith("Event:", ignoreCase = true)) {
            cleaned = cleaned.substring("Event:".length).trim()
        } else if (cleaned.startsWith("Event", ignoreCase = true) && cleaned.length > 5 && cleaned[5].isWhitespace()) {
            cleaned = cleaned.substring(5).trim()
        }

        // Strip trailing sentence punctuation commonly copied from SMS text (e.g. "uuid.")
        cleaned = cleaned.trimEnd('.', ',', ';', ':', ' ')

        // Strip wrapping quotes if present
        if ((cleaned.startsWith("\"") && cleaned.endsWith("\"")) ||
            (cleaned.startsWith("'") && cleaned.endsWith("'"))) {
            cleaned = cleaned.substring(1, cleaned.length - 1).trim()
        }

        // Must strictly match canonical UUID format (exactly 36 characters)
        if (!isValidUuid(cleaned)) {
            return null
        }

        return cleaned.lowercase(Locale.ROOT)
    }
}
