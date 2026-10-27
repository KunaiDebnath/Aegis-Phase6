package com.example.aegis

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.participant.Participant
import io.livekit.android.room.participant.RemoteParticipant
import io.livekit.android.room.track.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * State of the emergency audio call session.
 */
enum class CallState {
    IDLE,
    REQUESTING_TOKEN,
    CONNECTING,
    WAITING_FOR_PARTICIPANT,
    CONNECTED,
    RECONNECTING,
    ENDED,
    FAILED
}

/**
 * Central manager for LiveKit WebRTC two-way audio calling.
 * Handles token fetching, room connection, track publishing, mute/unmute,
 * participant state tracking, clean resource destruction, and mutual exclusion
 * coordination with Android's SpeechRecognizer via MicrophoneCoordinator.
 */
object LiveKitCallManager {

    private const val TAG = "AEGIS_LiveKitCall"

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var activeRoom: Room? = null
    private var eventsJob: Job? = null
    private var connectJob: Job? = null
    private var applicationContext: Context? = null

    // Compose Observable States
    val callState = mutableStateOf(CallState.IDLE)
    val callStatusMessage = mutableStateOf("No active call.")
    val isMicMuted = mutableStateOf(false)
    val remoteParticipantIdentity = mutableStateOf<String?>(null)
    val remoteParticipantName = mutableStateOf<String?>(null)
    val activeRoomName = mutableStateOf<String?>(null)
    val activeEventId = mutableStateOf<String?>(null)
    val currentIdentity = mutableStateOf<String?>(null)

    val isCallActive: Boolean
        get() = callState.value == CallState.CONNECTING ||
                callState.value == CallState.WAITING_FOR_PARTICIPANT ||
                callState.value == CallState.CONNECTED ||
                callState.value == CallState.RECONNECTING

    /**
     * Starts or joins an emergency audio call session for a given event ID.
     */
    fun startCall(
        context: Context?,
        eventId: String,
        deviceId: String,
        displayName: String
    ) {
        if (isCallActive || callState.value == CallState.REQUESTING_TOKEN) {
            Log.w(TAG, "Call start ignored: call is already active or requesting token.")
            return
        }

        val canonicalEventId = EmergencyUtils.sanitizeEventIdInput(eventId)
        if (canonicalEventId == null) {
            callState.value = CallState.FAILED
            callStatusMessage.value = "Call aborted: Invalid event ID format. Expected a single 36-character UUID."
            Log.e(TAG, "Call aborted: Invalid event ID format for input: $eventId")
            return
        }

        val appCtx = context?.applicationContext
        applicationContext = appCtx

        // Verify RECORD_AUDIO permission before initiating call
        if (appCtx != null) {
            val hasMicPerm = ContextCompat.checkSelfPermission(
                appCtx,
                android.Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasMicPerm) {
                Log.e(TAG, "Call aborted: RECORD_AUDIO permission is not granted on this device.")
                callState.value = CallState.FAILED
                callStatusMessage.value = "Call aborted: RECORD_AUDIO permission missing."
                MicrophoneCoordinator.onCallEnded(appCtx, "RECORD_AUDIO permission missing")
                return
            }
        }

        // Notify MicrophoneCoordinator immediately to stop and pause SpeechRecognizer
        MicrophoneCoordinator.onCallStarting("LiveKit emergency call initiating for event: $canonicalEventId")

        activeEventId.value = canonicalEventId
        currentIdentity.value = deviceId
        callState.value = CallState.REQUESTING_TOKEN
        callStatusMessage.value = "Requesting authorized LiveKit call token..."
        remoteParticipantIdentity.value = null
        remoteParticipantName.value = null
        isMicMuted.value = false

        connectJob?.cancel()
        connectJob = scope.launch {
            try {
                // 1. Request signed LiveKit JWT token from FastAPI backend
                Log.d(TAG, "Requesting token from backend for event $canonicalEventId...")
                val tokenResponse = withContext(Dispatchers.IO) {
                    AegisApiClient.api.getCallToken(
                        CallTokenRequest(
                            event_id = canonicalEventId,
                            device_id = deviceId,
                            participant_name = displayName
                        )
                    )
                }

                if (!tokenResponse.isSuccessful || tokenResponse.body() == null) {
                    val code = tokenResponse.code()
                    val errDetail = tokenResponse.errorBody()?.string() ?: "Authorization rejected"
                    Log.e(TAG, "Token authorization failed (HTTP $code): $errDetail")
                    callState.value = CallState.FAILED
                    callStatusMessage.value = "Token authorization failed (HTTP $code): $errDetail"
                    MicrophoneCoordinator.onCallEnded(appCtx, "Token authorization failed")
                    return@launch
                }

                val body = tokenResponse.body()!!
                joinRoomWithAuthorizedToken(appCtx, body, deviceId)
            } catch (e: Exception) {
                Log.e(TAG, "Call connection exception: ${e.message}", e)
                callState.value = CallState.FAILED
                callStatusMessage.value = "Call connection error: ${e.message ?: "Network unreachable"}"
                if (context != null) {
                    EmergencyCallService.stop(context.applicationContext)
                }
                cleanupRoom()
                MicrophoneCoordinator.onCallEnded(appCtx, "Call connection exception: ${e.message}")
            }
        }
    }

    /**
     * Connects directly using a pre-authorized CallTokenResponse (e.g. from answerInvitation).
     */
    fun startCallWithAuthorizedToken(
        context: Context?,
        tokenResponse: CallTokenResponse,
        deviceId: String
    ) {
        if (isCallActive || callState.value == CallState.REQUESTING_TOKEN) {
            Log.w(TAG, "Call start with token ignored: call already active.")
            return
        }

        val appCtx = context?.applicationContext
        applicationContext = appCtx

        if (appCtx != null) {
            val hasMicPerm = ContextCompat.checkSelfPermission(
                appCtx,
                android.Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasMicPerm) {
                callState.value = CallState.FAILED
                callStatusMessage.value = "Call aborted: RECORD_AUDIO permission missing."
                MicrophoneCoordinator.onCallEnded(appCtx, "RECORD_AUDIO permission missing")
                return
            }
        }

        MicrophoneCoordinator.onCallStarting("LiveKit incoming call accepted for room: ${tokenResponse.room_name}")
        currentIdentity.value = deviceId
        activeRoomName.value = tokenResponse.room_name
        callState.value = CallState.CONNECTING
        callStatusMessage.value = "Connecting to LiveKit room: ${tokenResponse.room_name}..."
        isMicMuted.value = false
        remoteParticipantIdentity.value = null
        remoteParticipantName.value = null

        connectJob?.cancel()
        connectJob = scope.launch {
            try {
                joinRoomWithAuthorizedToken(appCtx, tokenResponse, deviceId)
            } catch (e: Exception) {
                Log.e(TAG, "Call connection exception: ${e.message}", e)
                callState.value = CallState.FAILED
                callStatusMessage.value = "Call connection error: ${e.message ?: "Network unreachable"}"
                if (context != null) {
                    EmergencyCallService.stop(context.applicationContext)
                }
                cleanupRoom()
                MicrophoneCoordinator.onCallEnded(appCtx, "Call connection exception: ${e.message}")
            }
        }
    }

    private suspend fun joinRoomWithAuthorizedToken(
        appCtx: Context?,
        body: CallTokenResponse,
        deviceId: String
    ) {
        activeRoomName.value = body.room_name
        callState.value = CallState.CONNECTING
        callStatusMessage.value = "Connecting to LiveKit room: ${body.room_name}..."
        Log.i(TAG, "Authorized token received. Room: ${body.room_name}")

        // 2. Start Foreground Service so call remains active in background
        if (appCtx != null) {
            EmergencyCallService.start(appCtx, body.room_name)
        }

        // 3. Instantiate LiveKit room
        if (appCtx == null) {
            callStatusMessage.value = "Call initialized (test mode, no Android context provided)."
            Log.d(TAG, "Test mode: appCtx is null, skipping native WebRTC connect.")
            return
        }

        val room = LiveKit.create(appCtx)
        activeRoom = room

        // 4. Observe room events
        eventsJob?.cancel()
        eventsJob = scope.launch {
            room.events.collect { event ->
                handleRoomEvent(event, room)
            }
        }

        // 5. Connect to LiveKit server with audio enabled
        val connectOptions = ConnectOptions(
            audio = true,
            video = false
        )

        Log.d(TAG, "Connecting to LiveKit server: ${body.server_url} (room: ${body.room_name})...")
        room.connect(
            url = body.server_url,
            token = body.token,
            options = connectOptions
        )
        Log.i(TAG, "LiveKit room.connect() completed.")

        // 6. Ensure exactly one local microphone track is created and published
        try {
            Log.d(TAG, "Creating and publishing local microphone track...")
            room.localParticipant.setMicrophoneEnabled(true)
            isMicMuted.value = false
            val trackSid = room.localParticipant.audioTrackPublications.firstOrNull()?.first?.sid ?: "local_audio_track"
            Log.i(TAG, "LiveKit microphone track created. Local microphone track published: $trackSid. Local microphone mute state: false.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enable local microphone: ${e.message}", e)
            callStatusMessage.value = "Connected, but microphone could not be enabled: ${e.message}"
        }

        updateParticipantState(room)
    }

    private fun handleRoomEvent(event: RoomEvent, room: Room) {
        when (event) {
            is RoomEvent.Connected -> {
                Log.i(TAG, "LiveKit room connected: ${room.name}")
                updateParticipantState(room)
            }
            is RoomEvent.ParticipantConnected -> {
                val participant = event.participant
                val idStr = participant.identity?.value ?: participant.sid?.value ?: "user"
                val nameStr = participant.name?.ifBlank { idStr } ?: idStr
                Log.i(TAG, "Remote participant connected: $nameStr ($idStr)")
                remoteParticipantIdentity.value = idStr
                remoteParticipantName.value = nameStr
                callState.value = CallState.CONNECTED
                callStatusMessage.value = "Connected. Two-way audio active with: $nameStr"
            }
            is RoomEvent.ParticipantDisconnected -> {
                val participant = event.participant
                val idStr = participant.identity?.value ?: participant.sid?.value ?: ""
                Log.i(TAG, "Remote participant disconnected: $idStr")
                if (remoteParticipantIdentity.value == idStr) {
                    remoteParticipantIdentity.value = null
                    remoteParticipantName.value = null
                }
                updateParticipantState(room)
            }
            is RoomEvent.TrackPublished -> {
                val isLocal = event.participant == room.localParticipant
                val kind = event.publication.kind
                val trackSid = event.publication.sid
                Log.i(TAG, "${if (isLocal) "Local" else "Remote"} track published: sid=$trackSid, kind=$kind, muted=${event.publication.muted}")
                if (isLocal && kind == Track.Kind.AUDIO) {
                    isMicMuted.value = event.publication.muted
                    Log.i(TAG, "Local microphone track published. Local microphone mute state: ${isMicMuted.value}")
                }
            }
            is RoomEvent.TrackSubscribed -> {
                val kind = event.track.kind
                val pId = event.participant.identity?.value ?: "user"
                Log.i(TAG, "Remote audio track received and subscribed: sid=${event.track.sid}, kind=$kind, participant=$pId")
                updateParticipantState(room)
            }
            is RoomEvent.TrackMuted -> {
                if (event.participant == room.localParticipant) {
                    isMicMuted.value = true
                    Log.i(TAG, "Local microphone mute state: MUTED")
                }
            }
            is RoomEvent.TrackUnmuted -> {
                if (event.participant == room.localParticipant) {
                    isMicMuted.value = false
                    Log.i(TAG, "Local microphone mute state: UNMUTED")
                }
            }
            is RoomEvent.TrackPublicationFailed -> {
                Log.e(TAG, "Track publication failed for track: ${event.track.sid}")
            }
            is RoomEvent.TrackSubscriptionFailed -> {
                Log.e(TAG, "Track subscription failed: sid=${event.sid}, error=${event.exception.message}")
            }
            is RoomEvent.Reconnecting -> {
                Log.w(TAG, "Network interrupted. Reconnecting to call...")
                callState.value = CallState.RECONNECTING
                callStatusMessage.value = "Network interrupted. Reconnecting to call..."
            }
            is RoomEvent.Reconnected -> {
                Log.i(TAG, "LiveKit room reconnected.")
                updateParticipantState(room)
            }
            is RoomEvent.Disconnected -> {
                val reason = event.reason?.name ?: "Disconnected"
                Log.i(TAG, "LiveKit room disconnected: $reason")
                if (callState.value != CallState.FAILED && callState.value != CallState.ENDED) {
                    callState.value = CallState.ENDED
                    callStatusMessage.value = "Call ended: $reason"
                }
                val ctx = applicationContext
                if (ctx != null) {
                    EmergencyCallService.stop(ctx)
                }
                cleanupRoom()
                MicrophoneCoordinator.onCallEnded(ctx, "LiveKit room disconnected ($reason)")
            }
            is RoomEvent.FailedToConnect -> {
                val err = event.error.message ?: "Unknown error"
                Log.e(TAG, "Failed to connect to LiveKit server: $err")
                callState.value = CallState.FAILED
                callStatusMessage.value = "Failed to connect to LiveKit server: $err"
                val ctx = applicationContext
                if (ctx != null) {
                    EmergencyCallService.stop(ctx)
                }
                cleanupRoom()
                MicrophoneCoordinator.onCallEnded(ctx, "Failed to connect to LiveKit server ($err)")
            }
            else -> {}
        }
    }

    private fun updateParticipantState(room: Room) {
        val remotes = room.remoteParticipants.values
        if (remotes.isNotEmpty()) {
            val firstRemote = remotes.first()
            val idStr = firstRemote.identity?.value ?: firstRemote.sid?.value ?: "user"
            val nameStr = firstRemote.name?.ifBlank { idStr } ?: idStr
            remoteParticipantIdentity.value = idStr
            remoteParticipantName.value = nameStr
            callState.value = CallState.CONNECTED
            callStatusMessage.value = "Connected. Two-way audio active with: $nameStr"
        } else {
            remoteParticipantIdentity.value = null
            remoteParticipantName.value = null
            callState.value = CallState.WAITING_FOR_PARTICIPANT
            callStatusMessage.value = "In room. Waiting for other party to join..."
        }
    }

    fun toggleMute() {
        val room = activeRoom ?: return
        val currentlyMuted = isMicMuted.value
        scope.launch {
            try {
                // If currently muted -> unmuting means setMicrophoneEnabled(true)
                // If currently unmuted -> muting means setMicrophoneEnabled(false)
                val targetEnabled = currentlyMuted
                room.localParticipant.setMicrophoneEnabled(targetEnabled)
                isMicMuted.value = !targetEnabled
                Log.i(TAG, "Local microphone mute state: ${if (isMicMuted.value) "MUTED" else "UNMUTED"}")
            } catch (e: Exception) {
                Log.e(TAG, "Could not toggle microphone: ${e.message}", e)
                callStatusMessage.value = "Could not toggle microphone: ${e.message}"
            }
        }
    }

    fun endCall(context: Context? = null) {
        Log.i(TAG, "endCall requested.")
        connectJob?.cancel()
        connectJob = null

        eventsJob?.cancel()
        eventsJob = null

        cleanupRoom()

        callState.value = CallState.ENDED
        callStatusMessage.value = "Call ended."
        remoteParticipantIdentity.value = null
        remoteParticipantName.value = null
        activeRoomName.value = null
        isMicMuted.value = false

        val ctx = context?.applicationContext ?: applicationContext
        if (ctx != null) {
            EmergencyCallService.stop(ctx)
        }
        MicrophoneCoordinator.onCallEnded(ctx, "User left call")
    }

    private fun cleanupRoom() {
        try {
            activeRoom?.disconnect()
            activeRoom?.release()
            Log.d(TAG, "LiveKit room disconnected and WebRTC audio resources released.")
        } catch (e: Exception) {
            Log.w(TAG, "Exception during cleanupRoom: ${e.message}")
        }
        activeRoom = null
    }
}
