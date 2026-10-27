package com.example.aegis

import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telephony.SmsManager
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Central Emergency Orchestrator.
 * Single source of truth for both manual and voice-triggered emergency events.
 * Guarantees that voice and manual triggers invoke the exact same orchestration pipeline.
 */
object EmergencyOrchestrator {

    const val ACTION_SMS_SENT = "com.example.aegis.ACTION_SMS_SENT"
    const val ACTION_SMS_DELIVERED = "com.example.aegis.ACTION_SMS_DELIVERED"
    const val EXTRA_IS_TEST = "com.example.aegis.EXTRA_IS_TEST"
    const val EXTRA_ATTEMPT_ID = "com.example.aegis.EXTRA_ATTEMPT_ID"

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val pendingIntentCounter = AtomicInteger(100)

    // Observable Compose States
    val isSosActive = mutableStateOf(false)
    val isInitiating = mutableStateOf(false)
    val currentIdempotencyKey = mutableStateOf("")
    val backendEventId = mutableStateOf<String?>(null)
    val activeTriggerSource = mutableStateOf("manual")

    // Location States
    val locationStatus = mutableStateOf(LocationAcquisitionStatus.IDLE)
    val locationMessage = mutableStateOf("Location ready. Awaiting SOS trigger.")
    val currentLocation = mutableStateOf<EmergencyLocation?>(null)
    val isLocationStale = mutableStateOf(false)

    // Backend States
    val backendStatus = mutableStateOf(BackendSyncStatus.IDLE)
    val backendMessage = mutableStateOf("Backend ready for emergency monitoring.")

    // SMS States
    val smsStatus = mutableStateOf(SmsDeliveryStatus.IDLE)
    val smsMessage = mutableStateOf("SMS ready. No emergency sent.")
    val lastSmsContent = mutableStateOf("")

    // Diagnostic SIM Test States
    val testSmsStatus = mutableStateOf(SmsDeliveryStatus.IDLE)
    val testSmsMessage = mutableStateOf("SIM test ready.")

    // Voice Protection States
    val voiceStatus = mutableStateOf(VoiceProtectionStatus.DISABLED)
    val voiceStatusMessage = mutableStateOf("Voice protection disabled.")
    val configuredPhrase = mutableStateOf(EmergencyUtils.DEFAULT_EMERGENCY_PHRASE)
    val emergencyRecipientNumber = mutableStateOf("")

    /**
     * Unified Emergency Workflow invoked by both Manual SOS and Voice Detection.
     * Enforces the thread-safe EmergencyTriggerGuard.
     */
    fun triggerEmergency(
        context: Context,
        recipient: String = "",
        source: String = "manual",
        onFinished: (() -> Unit)? = null
    ): Boolean {
        // Enforce thread-safe duplicate trigger guard
        if (!EmergencyTriggerGuard.tryAcquireTrigger()) {
            return false
        }

        val targetRecipient = recipient.ifBlank {
            emergencyRecipientNumber.value.ifBlank {
                EmergencyContactManager.savedContactPhone.value ?: AegisPreferences.getEmergencyContactPhone(context) ?: ""
            }
        }
        val hasValidRecipient = targetRecipient.isNotBlank() && EmergencyUtils.isValidPhoneNumber(targetRecipient)

        isInitiating.value = true
        isSosActive.value = true
        activeTriggerSource.value = source

        val newIdempotencyKey = UUID.randomUUID().toString()
        currentIdempotencyKey.value = newIdempotencyKey
        backendEventId.value = null

        locationStatus.value = LocationAcquisitionStatus.ACQUIRING
        locationMessage.value = "Acquiring GPS location fix..."
        currentLocation.value = null
        isLocationStale.value = false

        backendStatus.value = BackendSyncStatus.SENDING
        backendMessage.value = "Sending emergency event to backend ($source)..."

        if (hasValidRecipient) {
            smsStatus.value = SmsDeliveryStatus.INITIATED
            smsMessage.value = "Preparing emergency SMS alert ($source)..."
        } else {
            smsStatus.value = SmsDeliveryStatus.SENT_FAILED
            smsMessage.value = "Emergency contact is not configured. SMS alert could not be dispatched."
        }

        scope.launch {
            val locationProvider = EmergencyLocationProvider(context.applicationContext)
            var acquiredLocation: EmergencyLocation? = null

            // 1. Attempt GPS Location acquisition (7-second timeout)
            try {
                when (val result = locationProvider.getCurrentLocation(timeoutMillis = 7000L)) {
                    is LocationResult.Success -> {
                        acquiredLocation = result.location
                        currentLocation.value = acquiredLocation
                        isLocationStale.value = !result.isFresh
                        locationStatus.value = LocationAcquisitionStatus.SUCCESS
                        val accuracyText = acquiredLocation.accuracyMeters?.let { " (±${it.toInt()}m)" } ?: ""
                        val fixType = if (result.isFresh) "Fresh GPS fix" else "Recent location (<5m)"
                        locationMessage.value = "Lat: ${acquiredLocation.latitude}, Lng: ${acquiredLocation.longitude}$accuracyText [$fixType]"
                    }
                    is LocationResult.Timeout -> {
                        locationStatus.value = LocationAcquisitionStatus.TIMEOUT
                        locationMessage.value = "GPS acquisition timed out (7s). Proceeding without location."
                    }
                    is LocationResult.Unavailable -> {
                        locationStatus.value = LocationAcquisitionStatus.UNAVAILABLE
                        locationMessage.value = "Location unavailable: ${result.reason} Proceeding without location."
                    }
                    is LocationResult.PermissionDenied -> {
                        locationStatus.value = LocationAcquisitionStatus.PERMISSION_DENIED
                        locationMessage.value = "Location permission denied. Proceeding without location."
                    }
                }
            } catch (e: Exception) {
                locationStatus.value = LocationAcquisitionStatus.UNAVAILABLE
                locationMessage.value = "Location error: ${e.message ?: "Unknown error"}. Proceeding without location."
            }

            // 2. Attempt Backend Synchronization (with location if available)
            var capturedEventId: String? = null
            try {
                val timestampIso = acquiredLocation?.let {
                    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                    sdf.timeZone = TimeZone.getTimeZone("UTC")
                    sdf.format(Date(it.timestamp))
                }

                val response = withContext(Dispatchers.IO) {
                    AegisApiClient.api.createEmergency(
                        SOSRequest(
                            device_id = "phone-a",
                            idempotency_key = newIdempotencyKey,
                            trigger_source = source,
                            latitude = acquiredLocation?.latitude,
                            longitude = acquiredLocation?.longitude,
                            location_accuracy_meters = acquiredLocation?.accuracyMeters,
                            location_timestamp = timestampIso
                        )
                    )
                }

                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    val canonicalEventId = EmergencyUtils.sanitizeEventIdInput(body.event_id) ?: body.event_id.trim()
                    capturedEventId = canonicalEventId
                    backendEventId.value = canonicalEventId
                    backendStatus.value = BackendSyncStatus.RECORDED
                    val locNote = if (acquiredLocation != null) " (GPS coordinates stored)" else " (without location)"
                    backendMessage.value = "Backend: ${body.message}$locNote [Trigger: $source]"

                    // Automatically initiate authorized emergency audio call on Phone A
                    val activeCallerId = CallSignalingClient.currentDeviceId.value.ifBlank { "phone-a" }
                    try {
                        LiveKitCallManager.startCall(
                            context = context.applicationContext,
                            eventId = canonicalEventId,
                            deviceId = activeCallerId,
                            displayName = "Phone A (Requester)"
                        )
                    } catch (_: Exception) {}

                    // Phase 6: Dispatch real-time incoming emergency call invitation to Phone B
                    try {
                        CallSignalingClient.createInvitation(
                            emergencyEventId = canonicalEventId,
                            callerDeviceId = activeCallerId,
                            recipientDeviceId = "phone-b",
                            callerDisplayName = "Phone A (Requester)"
                        )
                    } catch (_: Exception) {}
                } else {
                    backendStatus.value = BackendSyncStatus.FAILED
                    backendMessage.value = "Backend returned HTTP ${response.code()}"
                }
            } catch (e: Exception) {
                backendStatus.value = BackendSyncStatus.FAILED
                backendMessage.value = "Backend unavailable: ${e.message ?: "Network error"}"
            }

            // 3. Attempt SMS dispatch independently (only if recipient number is configured)
            if (hasValidRecipient) {
                try {
                    val emergencyText = EmergencyUtils.buildEmergencySmsMessage(
                        capturedEventId,
                        newIdempotencyKey,
                        acquiredLocation
                    )
                    sendSmsViaTelephony(
                        context = context.applicationContext,
                        recipient = targetRecipient,
                        text = emergencyText,
                        isTest = false
                    )
                } catch (e: Exception) {
                    smsStatus.value = SmsDeliveryStatus.SENT_FAILED
                    smsMessage.value = "SMS dispatch failed: ${e.message ?: "Unknown error"}"
                } finally {
                    isInitiating.value = false
                    EmergencyTriggerGuard.markWorkflowComplete()
                    onFinished?.invoke()
                }
            } else {
                isInitiating.value = false
                EmergencyTriggerGuard.markWorkflowComplete()
                onFinished?.invoke()
            }
        }

        return true
    }

    /**
     * Retries backend event synchronization reusing the SAME idempotency key.
     */
    fun retryBackend(context: Context) {
        val currentKey = currentIdempotencyKey.value
        if (isInitiating.value || currentKey.isBlank()) return

        isInitiating.value = true
        backendStatus.value = BackendSyncStatus.SENDING
        backendMessage.value = "Retrying backend event synchronization..."

        val loc = currentLocation.value
        val source = activeTriggerSource.value

        scope.launch {
            try {
                val timestampIso = loc?.let {
                    val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                    sdf.timeZone = TimeZone.getTimeZone("UTC")
                    sdf.format(Date(it.timestamp))
                }

                val response = withContext(Dispatchers.IO) {
                    AegisApiClient.api.createEmergency(
                        SOSRequest(
                            device_id = "phone-a",
                            idempotency_key = currentKey, // REUSING SAME KEY
                            trigger_source = source,
                            latitude = loc?.latitude,
                            longitude = loc?.longitude,
                            location_accuracy_meters = loc?.accuracyMeters,
                            location_timestamp = timestampIso
                        )
                    )
                }

                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    val canonicalEventId = EmergencyUtils.sanitizeEventIdInput(body.event_id) ?: body.event_id.trim()
                    backendEventId.value = canonicalEventId
                    backendStatus.value = BackendSyncStatus.RECORDED
                    val locNote = if (loc != null) " (GPS coordinates stored)" else ""
                    backendMessage.value = "Backend: ${body.message}$locNote [Trigger: $source]"

                    val activeCallerId = CallSignalingClient.currentDeviceId.value.ifBlank { "phone-a" }
                    if (!LiveKitCallManager.isCallActive) {
                        try {
                            LiveKitCallManager.startCall(
                                context = context.applicationContext,
                                eventId = canonicalEventId,
                                deviceId = activeCallerId,
                                displayName = "Phone A (Requester)"
                            )
                        } catch (_: Exception) {}
                    }

                    // Phase 7: Ensure incoming emergency call invitation is dispatched to Phone B upon backend recovery
                    if (CallSignalingClient.activeOutgoingInvitationId.value == null) {
                        try {
                            CallSignalingClient.createInvitation(
                                emergencyEventId = canonicalEventId,
                                callerDeviceId = activeCallerId,
                                recipientDeviceId = "phone-b",
                                callerDisplayName = "Phone A (Requester)"
                            )
                        } catch (_: Exception) {}
                    }
                } else {
                    backendStatus.value = BackendSyncStatus.FAILED
                    backendMessage.value = "Backend returned HTTP ${response.code()}"
                }
            } catch (e: Exception) {
                backendStatus.value = BackendSyncStatus.FAILED
                backendMessage.value = "Backend unavailable: ${e.message ?: "Network error"}"
            } finally {
                isInitiating.value = false
            }
        }
    }

    /**
     * Explicit deliberate retry of the emergency SMS alert.
     */
    fun retrySms(context: Context, recipient: String = "") {
        if (isInitiating.value) return
        val currentKey = currentIdempotencyKey.value
        val eventId = backendEventId.value
        val loc = currentLocation.value
        val targetRecipient = recipient.ifBlank {
            emergencyRecipientNumber.value.ifBlank {
                EmergencyContactManager.savedContactPhone.value ?: AegisPreferences.getEmergencyContactPhone(context) ?: ""
            }
        }

        if (targetRecipient.isBlank() || !EmergencyUtils.isValidPhoneNumber(targetRecipient)) {
            smsStatus.value = SmsDeliveryStatus.SENT_FAILED
            smsMessage.value = "Emergency contact is not configured. Configure Phone B's number first."
            return
        }

        smsStatus.value = SmsDeliveryStatus.INITIATED
        smsMessage.value = "Deliberate SMS retry initiated..."

        try {
            val text = EmergencyUtils.buildEmergencySmsMessage(eventId, currentKey, loc)
            sendSmsViaTelephony(
                context = context.applicationContext,
                recipient = targetRecipient,
                text = text,
                isTest = false
            )
        } catch (e: Exception) {
            smsStatus.value = SmsDeliveryStatus.SENT_FAILED
            smsMessage.value = "SMS retry failed: ${e.message ?: "Unknown error"}"
        }
    }

    /**
     * Sends non-emergency diagnostic test SMS.
     */
    fun sendTestSms(context: Context, recipient: String = "") {
        if (isInitiating.value) return

        val targetRecipient = recipient.ifBlank {
            emergencyRecipientNumber.value.ifBlank {
                EmergencyContactManager.savedContactPhone.value ?: AegisPreferences.getEmergencyContactPhone(context) ?: ""
            }
        }

        if (targetRecipient.isBlank() || !EmergencyUtils.isValidPhoneNumber(targetRecipient)) {
            testSmsStatus.value = SmsDeliveryStatus.SENT_FAILED
            testSmsMessage.value = "Emergency contact is not configured. Configure Phone B's number first."
            return
        }

        testSmsStatus.value = SmsDeliveryStatus.INITIATED
        testSmsMessage.value = "Initiating test SMS..."

        try {
            val testText = EmergencyUtils.buildTestSmsMessage()
            sendSmsViaTelephony(
                context = context.applicationContext,
                recipient = targetRecipient,
                text = testText,
                isTest = true
            )
        } catch (e: Exception) {
            testSmsStatus.value = SmsDeliveryStatus.SENT_FAILED
            testSmsMessage.value = "Test SMS failed: ${e.message ?: "Unknown error"}"
        }
    }

    /**
     * Resets the active emergency session and clears states for a fresh event.
     */
    fun startNewSosEvent() {
        try {
            LiveKitCallManager.endCall()
        } catch (_: Exception) {}

        CallSignalingClient.activeOutgoingInvitationId.value = null
        CallSignalingClient.callerFeedbackMessage.value = null

        EmergencyTriggerGuard.reset()
        isSosActive.value = false
        isInitiating.value = false
        currentIdempotencyKey.value = ""
        backendEventId.value = null
        activeTriggerSource.value = "manual"

        locationStatus.value = LocationAcquisitionStatus.IDLE
        locationMessage.value = "Location ready. Awaiting SOS trigger."
        currentLocation.value = null
        isLocationStale.value = false

        backendStatus.value = BackendSyncStatus.IDLE
        backendMessage.value = "Backend ready for emergency monitoring."

        smsStatus.value = SmsDeliveryStatus.IDLE
        smsMessage.value = "SMS ready. No emergency sent."
        lastSmsContent.value = ""
    }

    /**
     * Handles sent status updates from BroadcastReceiver.
     */
    fun onSmsSentResult(resultCode: Int, isTest: Boolean) {
        val (status, msg) = when (resultCode) {
            Activity.RESULT_OK -> {
                SmsDeliveryStatus.SENT_SUCCESS to "SMS sent successfully reported by Android carrier network."
            }
            SmsManager.RESULT_ERROR_NO_SERVICE -> {
                SmsDeliveryStatus.SENT_FAILED to "SMS send failed: No cellular service available."
            }
            SmsManager.RESULT_ERROR_RADIO_OFF -> {
                SmsDeliveryStatus.SENT_FAILED to "SMS send failed: Radio is turned off (airplane mode)."
            }
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> {
                SmsDeliveryStatus.SENT_FAILED to "SMS send failed: Telephony generic failure."
            }
            SmsManager.RESULT_ERROR_NULL_PDU -> {
                SmsDeliveryStatus.SENT_FAILED to "SMS send failed: Null PDU error."
            }
            else -> {
                SmsDeliveryStatus.SENT_FAILED to "SMS send failed with Android error code: $resultCode."
            }
        }

        if (isTest) {
            testSmsStatus.value = status
            testSmsMessage.value = "[Test] $msg"
        } else {
            smsStatus.value = status
            smsMessage.value = msg
        }
    }

    /**
     * Handles delivery status updates from BroadcastReceiver.
     */
    fun onSmsDeliveredResult(resultCode: Int, isTest: Boolean) {
        if (resultCode == Activity.RESULT_OK) {
            if (isTest) {
                testSmsStatus.value = SmsDeliveryStatus.DELIVERED
                testSmsMessage.value = "[Test] SMS delivered to recipient (delivery confirmed)."
            } else {
                smsStatus.value = SmsDeliveryStatus.DELIVERED
                smsMessage.value = "SMS delivered to recipient (delivery confirmed)."
            }
        } else {
            val failMsg = "Delivery report: Not delivered or unconfirmed by network (code: $resultCode)."
            if (isTest) {
                testSmsStatus.value = SmsDeliveryStatus.DELIVERY_FAILED
                testSmsMessage.value = "[Test] $failMsg"
            } else {
                smsStatus.value = SmsDeliveryStatus.DELIVERY_FAILED
                smsMessage.value = failMsg
            }
        }
    }

    private fun sendSmsViaTelephony(context: Context, recipient: String, text: String, isTest: Boolean) {
        val cleanRecipient = recipient.trim()
        val attemptId = pendingIntentCounter.incrementAndGet()

        val sentIntent = PendingIntent.getBroadcast(
            context,
            attemptId * 2,
            Intent(ACTION_SMS_SENT).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_ATTEMPT_ID, attemptId)
                putExtra(EXTRA_IS_TEST, isTest)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val deliveryIntent = PendingIntent.getBroadcast(
            context,
            attemptId * 2 + 1,
            Intent(ACTION_SMS_DELIVERED).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_ATTEMPT_ID, attemptId)
                putExtra(EXTRA_IS_TEST, isTest)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
                ?: throw IllegalStateException("SMS service is unavailable on this device.")
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        }

        val parts = smsManager.divideMessage(text)
        if (parts.size > 1) {
            val sentIntents = ArrayList<PendingIntent>()
            val deliveryIntents = ArrayList<PendingIntent>()
            for (i in parts.indices) {
                sentIntents.add(sentIntent)
                deliveryIntents.add(deliveryIntent)
            }
            smsManager.sendMultipartTextMessage(
                cleanRecipient,
                null,
                parts,
                sentIntents,
                deliveryIntents
            )
        } else {
            smsManager.sendTextMessage(
                cleanRecipient,
                null,
                text,
                sentIntent,
                deliveryIntent
            )
        }

        if (isTest) {
            testSmsStatus.value = SmsDeliveryStatus.ACCEPTED_BY_TELEPHONY
            testSmsMessage.value = "[Test] SMS accepted by telephony service. Radio transmitting..."
        } else {
            smsStatus.value = SmsDeliveryStatus.ACCEPTED_BY_TELEPHONY
            smsMessage.value = "SMS accepted by telephony service. Waiting for radio transmission..."
            lastSmsContent.value = text
        }
    }
}
