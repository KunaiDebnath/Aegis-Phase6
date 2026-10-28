package com.example.aegis.ui.screens

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aegis.CallSignalingClient
import com.example.aegis.CallState
import com.example.aegis.EmergencyContactManager
import com.example.aegis.EmergencyOrchestrator
import com.example.aegis.EmergencyUtils
import com.example.aegis.LiveKitCallManager
import com.example.aegis.ui.components.AegisCard
import com.example.aegis.ui.components.AegisInfoRow
import com.example.aegis.ui.components.AegisPrimaryButton
import com.example.aegis.ui.components.AegisSectionHeader
import com.example.aegis.ui.components.AegisStatusPill
import com.example.aegis.ui.components.AegisStatusTone
import com.example.aegis.ui.components.AegisTopBar
import com.example.aegis.ui.theme.AegisAmber
import com.example.aegis.ui.theme.AegisBlueSoft
import com.example.aegis.ui.theme.AegisGreen
import com.example.aegis.ui.theme.AegisGreenSoft
import com.example.aegis.ui.theme.AegisGrey100
import com.example.aegis.ui.theme.AegisGrey200
import com.example.aegis.ui.theme.AegisGrey50
import com.example.aegis.ui.theme.AegisGrey500
import com.example.aegis.ui.theme.AegisNavy
import com.example.aegis.ui.theme.AegisNavyDark
import com.example.aegis.ui.theme.AegisRed
import com.example.aegis.ui.theme.AegisRedSoft
import com.example.aegis.ui.theme.AegisWhite
import kotlinx.coroutines.delay

/**
 * Screen 8: Guardian Dashboard Screen ('guardian')
 */
@Composable
fun GuardianDashboardScreen(
    onOpenIncomingAlert: () -> Unit,
    onJoinCallByEventId: (String) -> Unit,
    onNavigateToHistory: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val incomingInv = CallSignalingClient.activeIncomingInvitation.value
    val requesterName = "Alex Smith"

    var manualEventIdInput by remember { mutableStateOf("") }
    var manualInputError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AegisWhite)
    ) {
        AegisTopBar(
            title = "Guardian home",
            subtitle = "Guardian device",
            onMenu = onMenu,
            avatarInitials = "MS"
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(18.dp))

            // Availability Status Card
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "YOUR AVAILABILITY",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp,
                        color = AegisGrey500
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Ready to receive alerts",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = AegisNavy
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Notifications and emergency calls are available.",
                        fontSize = 11.sp,
                        color = AegisGrey500
                    )
                }

                val signalingOk = CallSignalingClient.signalingConnected.value
                AegisStatusPill(
                    label = if (signalingOk) "Online" else "Connecting",
                    tone = if (signalingOk) AegisStatusTone.GOOD else AegisStatusTone.WAIT
                )
            }

            Spacer(modifier = Modifier.height(20.dp))
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(AegisGrey100))
            Spacer(modifier = Modifier.height(20.dp))

            // Section: Connected Person
            Text(
                text = "Connected person",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = AegisNavy
            )

            Spacer(modifier = Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(11.dp))
                    .border(1.dp, AegisGrey200, RoundedCornerShape(11.dp))
                    .background(AegisWhite)
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(AegisBlueSoft),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "AS",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 14.sp,
                            color = AegisNavyDark
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = requesterName,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = AegisNavy
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Primary emergency requester",
                            fontSize = 11.sp,
                            color = AegisGrey500
                        )
                    }

                    AegisStatusPill(label = "Connected", tone = AegisStatusTone.GOOD)
                }
            }

            Spacer(modifier = Modifier.height(26.dp))

            // Section: Latest Emergency / Active Incoming Call
            Text(
                text = "Latest emergency",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = AegisNavy
            )

            Spacer(modifier = Modifier.height(10.dp))

            if (incomingInv != null) {
                // Active Incoming Call Card
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0xFFF1C4C8), RoundedCornerShape(12.dp))
                        .background(AegisRedSoft)
                        .padding(16.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Notifications,
                                contentDescription = null,
                                tint = AegisRed,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "INCOMING EMERGENCY ALERT",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Black,
                                color = AegisRed
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = "${incomingInv.callerDisplayName ?: requesterName} needs immediate assistance!",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = AegisNavy
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "Auto-expires in ${CallSignalingClient.remainingSeconds.value}s",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = AegisRed
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            AegisPrimaryButton(
                                text = "Answer call",
                                onClick = onOpenIncomingAlert,
                                modifier = Modifier.weight(1f)
                            )

                            AegisPrimaryButton(
                                text = "Dismiss",
                                quiet = true,
                                onClick = {
                                    CallSignalingClient.dismissInvitation(
                                        context = context,
                                        invitationId = incomingInv.invitationId,
                                        deviceId = CallSignalingClient.currentDeviceId.value
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            } else {
                // Calm Empty State
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(11.dp))
                        .background(AegisGrey50)
                        .padding(22.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = AegisGrey500,
                            modifier = Modifier.size(30.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No active emergency",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = AegisNavy
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "When $requesterName sends an SOS, the alert and available location will appear here.",
                            fontSize = 11.sp,
                            color = AegisGrey500,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Preview incoming alert",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = AegisNavyDark,
                            modifier = Modifier
                                .clickable(onClick = onOpenIncomingAlert)
                                .padding(6.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Join Emergency Audio via SMS Event ID Card (Preserved!)
            AegisCard {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Join Call with Event ID",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = AegisNavy
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Join an active emergency call using the Event ID from the emergency SMS.",
                        fontSize = 11.sp,
                        color = AegisGrey500
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = manualEventIdInput,
                        onValueChange = {
                            manualEventIdInput = it
                            manualInputError = null
                        },
                        placeholder = { Text("Paste Event ID from SMS", fontSize = 12.sp) },
                        singleLine = true,
                        isError = manualInputError != null,
                        supportingText = if (manualInputError != null) {
                            { Text(manualInputError!!, color = AegisRed, fontSize = 10.sp) }
                        } else null,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AegisPrimaryButton(
                            text = "Paste ID",
                            quiet = true,
                            onClick = {
                                val clip = clipboardManager.getText()?.text
                                if (!clip.isNullOrBlank()) {
                                    val sanitized = EmergencyUtils.sanitizeEventIdInput(clip)
                                    manualEventIdInput = sanitized ?: clip.trim()
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )

                        AegisPrimaryButton(
                            text = "Join Call",
                            onClick = {
                                val trimmed = manualEventIdInput.trim()
                                if (trimmed.isBlank()) {
                                    manualInputError = "Please enter or paste the Event ID."
                                } else {
                                    onJoinCallByEventId(trimmed)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(26.dp))

            // Section: Emergency History Preview
            AegisSectionHeader(
                title = "Emergency history",
                actionLabel = "View all",
                onAction = onNavigateToHistory
            )

            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onNavigateToHistory)
                    .padding(vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(AegisGreenSoft),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = AegisGreen,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Resolved emergency",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = AegisNavy
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Jun 10 · 6:48 PM · 7 min",
                            fontSize = 10.sp,
                            color = AegisGrey500
                        )
                    }

                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = AegisGrey500,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * Screen 9: Full-Screen Incoming Alert Screen ('alert')
 */
@Composable
fun IncomingAlertScreen(
    onAnswerCall: () -> Unit,
    onViewMap: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val incomingInv = CallSignalingClient.activeIncomingInvitation.value
    val callerName = incomingInv?.callerDisplayName ?: "Alex Smith"
    val loc = EmergencyOrchestrator.currentLocation.value

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AegisNavy)
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Red Alert Badge
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(AegisRed),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Notifications,
                contentDescription = null,
                tint = AegisWhite,
                modifier = Modifier.size(26.dp)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = "EMERGENCY ALERT",
            fontSize = 18.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp,
            color = AegisWhite
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Received now",
            fontSize = 11.sp,
            color = Color(0xFFBAC6D2)
        )

        Spacer(modifier = Modifier.height(44.dp))

        // Large Caller Avatar
        Box(
            modifier = Modifier
                .size(82.dp)
                .clip(CircleShape)
                .background(AegisWhite),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "AS",
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                color = AegisNavy
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "$callerName needs help",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = AegisWhite
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Primary connected person",
            fontSize = 12.sp,
            color = Color(0xFFBAC6D2)
        )

        Spacer(modifier = Modifier.height(36.dp))

        // Location Box on Navy Screen
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(11.dp))
                .border(1.dp, Color(0x29FFFFFF), RoundedCornerShape(11.dp))
                .background(Color(0x17FFFFFF))
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Color(0x1FFFFFFF)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Place,
                        contentDescription = null,
                        tint = AegisWhite,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = "Latest available location",
                        fontSize = 11.sp,
                        color = Color(0xFFBAC6D2)
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = if (loc != null && loc.isValid()) "${String.format("%.4f", loc.latitude)}, ${String.format("%.4f", loc.longitude)}" else "125 Market Street, San Francisco",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = AegisWhite
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (loc != null && loc.isValid()) "Accuracy ${loc.accuracyMeters?.toInt() ?: 12} m" else "Updated just now · Accuracy 12 m",
                        fontSize = 10.sp,
                        color = Color(0xFFBAC6D2)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Action Buttons
        AegisPrimaryButton(
            text = "Answer call",
            icon = Icons.Default.Call,
            darkContainer = true,
            onClick = onAnswerCall
        )

        Spacer(modifier = Modifier.height(10.dp))

        AegisPrimaryButton(
            text = "View location on map",
            icon = Icons.Default.Place,
            quiet = true,
            darkContainer = true,
            onClick = onViewMap
        )

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = "Dismiss to dashboard",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFBAC6D2),
            modifier = Modifier
                .clickable(onClick = onDismiss)
                .padding(8.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * Screen 10: LiveKit Encrypted Audio Call Screen ('call')
 */
@Composable
fun LiveAudioCallScreen(
    onEndCall: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    val callState = LiveKitCallManager.callState.value
    val isMuted = LiveKitCallManager.isMicMuted.value
    val isConnected = callState == CallState.CONNECTED
    val isEnded = callState == CallState.ENDED

    var callDurationSeconds by remember { mutableStateOf(0) }
    LaunchedEffect(isConnected) {
        if (isConnected) {
            while (true) {
                delay(1000)
                callDurationSeconds++
            }
        }
    }
    val min = callDurationSeconds / 60
    val sec = callDurationSeconds % 60
    val durationText = String.format("%02d:%02d", min, sec)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AegisNavy),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        AegisTopBar(
            title = "Emergency audio",
            subtitle = if (isEnded) "Call ended" else "Encrypted LiveKit session",
            isDark = true,
            onBack = onEndCall
        )

        Spacer(modifier = Modifier.height(48.dp))

        // Large 112dp Avatar
        Box(
            modifier = Modifier
                .size(112.dp)
                .clip(CircleShape)
                .background(AegisWhite),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "AS",
                fontSize = 32.sp,
                fontWeight = FontWeight.Black,
                color = AegisNavy
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Alex Smith",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = AegisWhite
        )

        Spacer(modifier = Modifier.height(8.dp))

        val statusLabel = when {
            isEnded -> "Call ended"
            isConnected -> "Connected · $durationText"
            callState == CallState.CONNECTING || callState == CallState.REQUESTING_TOKEN -> "Connecting…"
            else -> "Incoming call"
        }
        val statusTone = when {
            isConnected -> AegisStatusTone.GOOD
            isEnded -> AegisStatusTone.NEUTRAL
            else -> AegisStatusTone.WAIT
        }
        AegisStatusPill(label = statusLabel, tone = statusTone)

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (isConnected) "Connection quality: Good" else if (isEnded) "Duration $durationText" else "Establishing secure audio…",
            fontSize = 11.sp,
            color = Color(0xFFBAC6D2)
        )

        Spacer(modifier = Modifier.height(44.dp))

        // Call Controls: Mute and Speaker
        Row(
            horizontalArrangement = Arrangement.spacedBy(30.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Mute Button
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .border(1.dp, Color(0x29FFFFFF), CircleShape)
                        .background(if (isMuted) AegisRed else Color(0x1FFFFFFF))
                        .clickable(enabled = isConnected) { LiveKitCallManager.toggleMute() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = "Mute",
                        tint = AegisWhite,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = if (isMuted) "Unmute" else "Mute",
                    fontSize = 10.sp,
                    color = Color(0xFFBAC6D2)
                )
            }

            // Speaker Button
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .border(1.dp, Color(0x29FFFFFF), CircleShape)
                        .background(Color(0x1FFFFFFF)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = "Speaker",
                        tint = AegisWhite,
                        modifier = Modifier.size(26.dp)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Speaker",
                    fontSize = 10.sp,
                    color = Color(0xFFBAC6D2)
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Primary Action: Answer (green circle), End (red circle), or Return to dashboard button
        if (isEnded) {
            AegisPrimaryButton(
                text = "Return to dashboard",
                darkContainer = true,
                onClick = onEndCall,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 32.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(AegisRed)
                        .clickable(onClick = onEndCall),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CallEnd,
                        contentDescription = "End Call",
                        tint = AegisWhite,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "End call",
                    fontSize = 11.sp,
                    color = Color(0xFFBAC6D2)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
