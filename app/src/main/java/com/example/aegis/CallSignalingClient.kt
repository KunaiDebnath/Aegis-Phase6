package com.example.aegis

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

data class IncomingCallInvitation(
    val invitationId: String,
    val emergencyEventId: String,
    val callerDeviceId: String,
    val callerDisplayName: String?,
    val createdAtIso: String,
    val expiresAtIso: String,
    val status: String
)

/**
 * Real-time bidirectional WebSocket signaling client for incoming emergency call invitations.
 * Manages active invitations, notification triggers, call answer/dismiss dispatch,
 * and caller feedback when Phone B responds.
 */
object CallSignalingClient {

    private const val TAG = "AEGIS_Signaling"

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var webSocket: WebSocket? = null
    private var okHttpClient: OkHttpClient? = null
    private var appContext: Context? = null
    private var reconnectJob: Job? = null
    private var expiryJob: Job? = null
    private var isStarted = false

    // Observable Compose States
    val currentDeviceId = mutableStateOf("phone-b")
    val signalingConnected = mutableStateOf(false)
    val signalingStatusMessage = mutableStateOf("Signaling: Disconnected")
    val fcmTokenState = mutableStateOf<String?>(null)
    val fcmStatusMessage = mutableStateOf("FCM: Standby")
    val activeIncomingInvitation = mutableStateOf<IncomingCallInvitation?>(null)
    val remainingSeconds = mutableStateOf(0)
    val callerFeedbackMessage = mutableStateOf<String?>(null)
    val activeOutgoingInvitationId = mutableStateOf<String?>(null)

    fun start(context: Context, deviceId: String = currentDeviceId.value) {
        appContext = context.applicationContext
        val savedId = AegisPreferences.getDeviceId(context)
        val targetId = if (deviceId.isNotBlank()) deviceId else savedId
        currentDeviceId.value = targetId
        AegisApiClient.baseUrl = AegisPreferences.getBaseUrl(context)
        isStarted = true
        EmergencyCallNotificationManager.createNotificationChannel(context)
        syncFcmToken(targetId)
        connect(targetId)
    }

    fun stop() {
        isStarted = false
        reconnectJob?.cancel()
        reconnectJob = null
        expiryJob?.cancel()
        expiryJob = null
        try {
            webSocket?.close(1000, "Client stopped")
        } catch (_: Exception) {}
        webSocket = null
        signalingConnected.value = false
        signalingStatusMessage.value = "Signaling: Disconnected"
    }

    fun setDeviceId(deviceId: String) {
        val trimmed = deviceId.trim()
        if (trimmed.isNotBlank()) {
            val isChanged = (trimmed != currentDeviceId.value)
            currentDeviceId.value = trimmed
            appContext?.let { AegisPreferences.setDeviceId(it, trimmed) }
            syncFcmToken(trimmed)
            if (isChanged && isStarted) {
                stop()
                appContext?.let { start(it, trimmed) }
            }
        }
    }

    /**
     * Proactively retrieves the Firebase Cloud Messaging registration token
     * and synchronizes it with the FastAPI backend for the current device_id.
     */
    fun syncFcmToken(deviceId: String = currentDeviceId.value) {
        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnCompleteListener { task ->
                    if (!task.isSuccessful) {
                        Log.w(TAG, "Fetching FCM registration token failed", task.exception)
                        fcmStatusMessage.value = "FCM: Token retrieval failed"
                        return@addOnCompleteListener
                    }
                    val token = task.result
                    if (!token.isNullOrBlank()) {
                        fcmTokenState.value = token
                        appContext?.let { ctx -> AegisPreferences.setFcmToken(ctx, token) }
                        val tokenPreview = if (token.length > 12) "${token.take(6)}...${token.takeLast(4)}" else token
                        fcmStatusMessage.value = "FCM: Registering..."
                        Log.i(TAG, "Fetched FCM token for $deviceId: $tokenPreview. Registering with backend...")
                        scope.launch {
                            try {
                                val resp = withContext(Dispatchers.IO) {
                                    AegisApiClient.api.registerFcmToken(
                                        RegisterFcmTokenRequest(
                                            device_id = deviceId,
                                            fcm_token = token
                                        )
                                    )
                                }
                                if (resp.isSuccessful) {
                                    fcmStatusMessage.value = "FCM: Registered ($tokenPreview)"
                                    Log.i(TAG, "FCM token successfully registered with backend for $deviceId")
                                } else {
                                    fcmStatusMessage.value = "FCM: Server error (${resp.code()})"
                                    Log.w(TAG, "Failed to register FCM token with backend: HTTP ${resp.code()}")
                                }
                            } catch (e: Exception) {
                                fcmStatusMessage.value = "FCM: Network offline"
                                Log.w(TAG, "Failed to register FCM token with backend: ${e.message}")
                            }
                        }
                    }
                }
        } catch (t: Throwable) {
            // Graceful fallback when running in JVM unit tests or if Google Play Services is unavailable
            fcmStatusMessage.value = "FCM: Standby / Offline"
            Log.w(TAG, "FirebaseMessaging uninitialized: ${t.message}")
        }
    }

    fun connect(deviceId: String = currentDeviceId.value) {
        reconnectJob?.cancel()
        if (webSocket != null) {
            try {
                webSocket?.close(1000, "Reconnecting")
            } catch (_: Exception) {}
            webSocket = null
        }

        val baseHttpUrl = AegisApiClient.baseUrl.trimEnd('/')
        val wsUrl = if (baseHttpUrl.startsWith("https://", ignoreCase = true)) {
            "wss://" + baseHttpUrl.substring("https://".length) + "/api/ws/signaling/$deviceId"
        } else if (baseHttpUrl.startsWith("http://", ignoreCase = true)) {
            "ws://" + baseHttpUrl.substring("http://".length) + "/api/ws/signaling/$deviceId"
        } else {
            "ws://$baseHttpUrl/api/ws/signaling/$deviceId"
        }

        signalingStatusMessage.value = "Connecting to signaling ($deviceId)..."
        Log.i(TAG, "Connecting WebSocket signaling: $wsUrl")

        if (okHttpClient == null) {
            okHttpClient = OkHttpClient.Builder()
                .pingInterval(15, TimeUnit.SECONDS)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build()
        }

        val request = Request.Builder().url(wsUrl).build()

        webSocket = okHttpClient!!.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Signaling WebSocket connected as $deviceId")
                scope.launch {
                    signalingConnected.value = true
                    signalingStatusMessage.value = "Signaling: Connected ($deviceId)"
                    // Check for any pending invitation immediately upon connection
                    checkPendingInvitations()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Signaling message received: $text")
                scope.launch {
                    handleIncomingMessage(text)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Signaling WebSocket closing: $reason (code $code)")
                scope.launch {
                    signalingConnected.value = false
                    signalingStatusMessage.value = "Signaling: Disconnecting..."
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Signaling WebSocket closed: $reason")
                scope.launch {
                    signalingConnected.value = false
                    signalingStatusMessage.value = "Signaling: Disconnected"
                    scheduleReconnect()
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Signaling WebSocket failure: ${t.message}")
                scope.launch {
                    signalingConnected.value = false
                    signalingStatusMessage.value = "Signaling offline (${t.message ?: "Network error"})"
                    scheduleReconnect()
                }
            }
        })
    }

    private fun scheduleReconnect() {
        if (!isStarted) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(5000L) // Safe 5-second backoff
            if (isStarted && !signalingConnected.value) {
                Log.i(TAG, "Attempting signaling reconnection...")
                connect(currentDeviceId.value)
            }
        }
    }

    fun checkPendingInvitations() {
        try {
            val ping = JsonObject().apply { addProperty("type", "check_pending") }
            webSocket?.send(ping.toString())
        } catch (_: Exception) {}
    }

    fun handleIncomingMessage(text: String) {
        try {
            val json = JsonParser.parseString(text).asJsonObject
            val type = json.get("type")?.asString ?: ""
            when (type) {
                "incoming_call_invitation" -> {
                    val invId = json.get("invitation_id")?.asString ?: return
                    val eventId = json.get("emergency_event_id")?.asString ?: return
                    val callerDev = json.get("caller_device_id")?.asString ?: "phone-a"
                    val callerName = json.get("caller_display_name")?.asString ?: "Phone A (Requester)"
                    val created = json.get("created_at")?.asString ?: ""
                    val expires = json.get("expires_at")?.asString ?: ""
                    val status = json.get("status")?.asString ?: "pending"

                    val inv = IncomingCallInvitation(
                        invitationId = invId,
                        emergencyEventId = eventId,
                        callerDeviceId = callerDev,
                        callerDisplayName = callerName,
                        createdAtIso = created,
                        expiresAtIso = expires,
                        status = status
                    )

                    // Display notification on Phone B
                    appContext?.let { ctx ->
                        EmergencyCallNotificationManager.showIncomingCallNotification(
                            context = ctx,
                            invitationId = invId,
                            emergencyEventId = eventId,
                            callerDisplayName = callerName
                        )
                    }

                    // Update in-memory state and start countdown timer
                    handleIncomingInvitation(inv)
                }

                "call_invitation_answered" -> {
                    val recipient = json.get("recipient_device_id")?.asString ?: "Phone B"
                    val name = json.get("recipient_name")?.asString ?: recipient
                    Log.i(TAG, "Call invitation answered by: $name")
                    callerFeedbackMessage.value = "$name answered! Emergency call connected."
                }

                "call_invitation_dismissed" -> {
                    val recipient = json.get("recipient_device_id")?.asString ?: "Phone B"
                    Log.i(TAG, "Call invitation dismissed by: $recipient")
                    callerFeedbackMessage.value = "$recipient dismissed the call invitation. (Emergency alert remains active)."
                }

                "call_invitation_cancelled" -> {
                    val invId = json.get("invitation_id")?.asString ?: ""
                    Log.i(TAG, "Call invitation cancelled: $invId")
                    if (activeIncomingInvitation.value?.invitationId == invId) {
                        activeIncomingInvitation.value = null
                    }
                    appContext?.let { ctx ->
                        EmergencyCallNotificationManager.cancelNotification(ctx, invId)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing incoming signaling message: ${e.message}", e)
        }
    }

    fun handleIncomingInvitation(inv: IncomingCallInvitation) {
        activeIncomingInvitation.value = inv
        remainingSeconds.value = 60
        startExpiryTimer(inv.invitationId)
    }

    fun setActiveIncomingInvitation(
        invitationId: String,
        emergencyEventId: String,
        callerDeviceId: String,
        callerDisplayName: String?
    ) {
        val inv = IncomingCallInvitation(
            invitationId = invitationId,
            emergencyEventId = emergencyEventId,
            callerDeviceId = callerDeviceId,
            callerDisplayName = callerDisplayName,
            createdAtIso = "",
            expiresAtIso = "",
            status = "pending"
        )
        handleIncomingInvitation(inv)
    }

    private fun startExpiryTimer(invitationId: String) {
        expiryJob?.cancel()
        expiryJob = scope.launch {
            for (sec in 60 downTo 1) {
                remainingSeconds.value = sec
                delay(1000L)
                if (activeIncomingInvitation.value?.invitationId != invitationId) {
                    return@launch
                }
            }
            // Timeout reached
            remainingSeconds.value = 0
            if (activeIncomingInvitation.value?.invitationId == invitationId) {
                activeIncomingInvitation.value = null
                appContext?.let { ctx ->
                    EmergencyCallNotificationManager.cancelNotification(ctx, invitationId)
                }
                Log.i(TAG, "Call invitation $invitationId expired due to timeout.")
            }
        }
    }

    /**
     * Called when Phone B answers the incoming invitation.
     */
    fun answerInvitation(
        context: Context,
        invitationId: String,
        deviceId: String = currentDeviceId.value,
        displayName: String = "Phone B (Trusted Contact)",
        onResult: ((Boolean, String?) -> Unit)? = null
    ) {
        scope.launch {
            try {
                // Cancel notification and stop expiry timer immediately
                expiryJob?.cancel()
                EmergencyCallNotificationManager.cancelNotification(context, invitationId)
                activeIncomingInvitation.value = null

                val response = withContext(Dispatchers.IO) {
                    AegisApiClient.api.answerInvitation(
                        id = invitationId,
                        request = AnswerInvitationRequest(
                            device_id = deviceId,
                            participant_name = displayName
                        )
                    )
                }

                if (response.isSuccessful && response.body() != null) {
                    val body = response.body()!!
                    Log.i(TAG, "Invitation answered successfully. Room: ${body.room_name}")

                    // Connect to LiveKit room with the authorized token
                    val callTokenResponse = CallTokenResponse(
                        server_url = body.server_url,
                        token = body.token,
                        room_name = body.room_name,
                        identity = body.identity,
                        expires_in_seconds = body.expires_in_seconds
                    )

                    LiveKitCallManager.startCallWithAuthorizedToken(
                        context = context,
                        tokenResponse = callTokenResponse,
                        deviceId = deviceId
                    )
                    onResult?.invoke(true, null)
                } else {
                    val err = response.errorBody()?.string() ?: "Answer failed (HTTP ${response.code()})"
                    Log.e(TAG, "Failed to answer invitation: $err")
                    onResult?.invoke(false, err)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception answering invitation: ${e.message}", e)
                onResult?.invoke(false, e.message ?: "Network error")
            }
        }
    }

    /**
     * Called when Phone B dismisses the incoming invitation.
     */
    fun dismissInvitation(
        context: Context,
        invitationId: String,
        deviceId: String = currentDeviceId.value,
        onComplete: (() -> Unit)? = null
    ) {
        scope.launch {
            try {
                expiryJob?.cancel()
                EmergencyCallNotificationManager.cancelNotification(context, invitationId)
                activeIncomingInvitation.value = null

                withContext(Dispatchers.IO) {
                    AegisApiClient.api.dismissInvitation(
                        id = invitationId,
                        request = DismissInvitationRequest(device_id = deviceId)
                    )
                }
                Log.i(TAG, "Invitation $invitationId dismissed.")
            } catch (e: Exception) {
                Log.w(TAG, "Error dismissing invitation on backend: ${e.message}")
            } finally {
                onComplete?.invoke()
            }
        }
    }

    /**
     * Called by Phone A (Caller) to create and dispatch an invitation to Phone B.
     */
    fun createInvitation(
        emergencyEventId: String,
        callerDeviceId: String,
        recipientDeviceId: String,
        callerDisplayName: String? = null,
        onCreated: ((CreateInvitationResponse?) -> Unit)? = null
    ) {
        scope.launch {
            try {
                callerFeedbackMessage.value = "Calling trusted contact ($recipientDeviceId)..."
                val response = withContext(Dispatchers.IO) {
                    AegisApiClient.api.createInvitation(
                        CreateInvitationRequest(
                            emergency_event_id = emergencyEventId,
                            caller_device_id = callerDeviceId,
                            recipient_device_id = recipientDeviceId,
                            caller_display_name = callerDisplayName,
                            timeout_seconds = 60
                        )
                    )
                }

                if (response.isSuccessful && response.body() != null) {
                    val inv = response.body()!!
                    activeOutgoingInvitationId.value = inv.id
                    val fcmNote = if (inv.fcm_status != null && inv.fcm_status != "dispatched") " [FCM: ${inv.fcm_status}]" else ""
                    callerFeedbackMessage.value = "Incoming call notification sent to $recipientDeviceId. Waiting for answer...$fcmNote"
                    Log.i(TAG, "Invitation created: ${inv.id} for recipient: $recipientDeviceId (fcm: ${inv.fcm_status})")
                    onCreated?.invoke(inv)
                } else {
                    val err = response.errorBody()?.string() ?: "HTTP ${response.code()}"
                    callerFeedbackMessage.value = "Could not notify $recipientDeviceId: $err"
                    Log.w(TAG, "Could not create invitation: $err")
                    onCreated?.invoke(null)
                }
            } catch (e: Exception) {
                callerFeedbackMessage.value = "Signaling unavailable: ${e.message ?: "Network error"}"
                Log.e(TAG, "Exception creating invitation: ${e.message}", e)
                onCreated?.invoke(null)
            }
        }
    }
}
