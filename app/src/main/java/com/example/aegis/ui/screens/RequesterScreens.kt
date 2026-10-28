package com.example.aegis.ui.screens

import android.content.Context
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aegis.AegisPreferences
import com.example.aegis.BackendSyncStatus
import com.example.aegis.EmergencyContactManager
import com.example.aegis.EmergencyOrchestrator
import com.example.aegis.EmergencyUtils
import com.example.aegis.LiveKitCallManager
import com.example.aegis.LocationAcquisitionStatus
import com.example.aegis.ServerDiscoveryManager
import com.example.aegis.SmsDeliveryStatus
import com.example.aegis.VoiceProtectionStatus
import com.example.aegis.VoiceSafetyService
import com.example.aegis.ui.components.AegisCard
import com.example.aegis.ui.components.AegisFloatingSosButton
import com.example.aegis.ui.components.AegisInfoRow
import com.example.aegis.ui.components.AegisPrimaryButton
import com.example.aegis.ui.components.AegisSectionHeader
import com.example.aegis.ui.components.AegisStatusPill
import com.example.aegis.ui.components.AegisStatusTone
import com.example.aegis.ui.components.AegisTimelineItem
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
import kotlinx.coroutines.launch

/**
 * Screen 4: Victim Home (Phone A Requester Home Screen)
 */
@Composable
fun VictimHomeScreen(
    onTriggerSos: () -> Unit,
    onNavigateToVoice: () -> Unit,
    onNavigateToDiagnostics: () -> Unit,
    onNavigateToContacts: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    val savedPhone = EmergencyContactManager.savedContactPhone.value
    val contactName = EmergencyContactManager.savedContactName.value ?: "Maya Singh"
    val isVoiceListening = EmergencyOrchestrator.voiceStatus.value == VoiceProtectionStatus.LISTENING
    val isVoiceActive = EmergencyOrchestrator.voiceStatus.value != VoiceProtectionStatus.DISABLED
    val isInitiating = EmergencyOrchestrator.isInitiating.value

    var showConfirmSheet by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AegisWhite)
    ) {
        AegisTopBar(
            title = "Good afternoon, Alex",
            subtitle = "Person needing help",
            onMenu = onMenu,
            avatarInitials = "AS"
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(18.dp))

            // Protection Ready Header Card
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(AegisGreenSoft),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = AegisGreen,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    AegisStatusPill(
                        label = if (isVoiceListening) "Protection ready & listening" else "Protection ready",
                        tone = AegisStatusTone.GOOD
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "Core safety services are available",
                        fontSize = 12.sp,
                        color = AegisGrey500
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 188dp Floating Circular SOS Button
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                AegisFloatingSosButton(
                    onClick = { showConfirmSheet = true },
                    isLoading = isInitiating,
                    label = "SEND SOS",
                    subLabel = "Press for emergency help"
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Voice Protection Strip
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(11.dp))
                    .border(1.dp, AegisGrey200, RoundedCornerShape(11.dp))
                    .background(AegisGrey50)
                    .clickable(onClick = onNavigateToVoice)
                    .padding(horizontal = 12.dp, vertical = 11.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (isVoiceActive) AegisGreenSoft else AegisGrey100),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = null,
                            tint = if (isVoiceActive) AegisGreen else AegisGrey500,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isVoiceActive) "Voice protection is on" else "Voice protection is off",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = AegisNavy
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Say “I need help” to activate",
                            fontSize = 10.sp,
                            color = AegisGrey500
                        )
                    }

                    AegisStatusPill(
                        label = if (isVoiceActive) "Active" else "Off",
                        tone = if (isVoiceActive) AegisStatusTone.GOOD else AegisStatusTone.NEUTRAL
                    )
                }
            }

            Spacer(modifier = Modifier.height(26.dp))

            // Section: Safety Status
            AegisSectionHeader(
                title = "Safety status",
                actionLabel = "Diagnostics",
                onAction = onNavigateToDiagnostics
            )

            Spacer(modifier = Modifier.height(8.dp))

            AegisCard {
                AegisInfoRow(
                    icon = Icons.Default.Person,
                    label = "Emergency contact",
                    value = if (!savedPhone.isNullOrBlank()) "$contactName · $savedPhone" else "Not configured",
                    status = {
                        AegisStatusPill(
                            label = if (!savedPhone.isNullOrBlank()) "Ready" else "Setup",
                            tone = if (!savedPhone.isNullOrBlank()) AegisStatusTone.GOOD else AegisStatusTone.WAIT
                        )
                    },
                    action = onNavigateToContacts
                )
                HorizontalDivider(color = AegisGrey100, thickness = 1.dp)

                val loc = EmergencyOrchestrator.currentLocation.value
                val locValue = if (loc != null && loc.isValid()) {
                    "${String.format("%.4f", loc.latitude)}, ${String.format("%.4f", loc.longitude)}"
                } else {
                    "Precise location available"
                }
                AegisInfoRow(
                    icon = Icons.Default.Place,
                    label = "Location",
                    value = locValue,
                    status = { AegisStatusPill(label = "Available", tone = AegisStatusTone.GOOD) }
                )
                HorizontalDivider(color = AegisGrey100, thickness = 1.dp)

                val backendOk = EmergencyOrchestrator.backendStatus.value == BackendSyncStatus.RECORDED ||
                        ServerDiscoveryManager.state.value == com.example.aegis.DiscoveryState.CONNECTED
                AegisInfoRow(
                    icon = Icons.Default.SignalCellularAlt,
                    label = "Services",
                    value = "Backend and LiveKit",
                    status = {
                        AegisStatusPill(
                            label = if (backendOk) "Connected" else "Online",
                            tone = AegisStatusTone.GOOD
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(26.dp))

            // Section: Recent Activity
            AegisSectionHeader(
                title = "Recent activity",
                actionLabel = "View all",
                onAction = onNavigateToHistory
            )

            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(AegisGrey50)
                    .clickable(onClick = onNavigateToHistory)
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(AegisGrey100),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            tint = AegisGrey500,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = "No recent emergencies",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = AegisNavy
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Your emergency activity will appear here.",
                            fontSize = 10.sp,
                            color = AegisGrey500
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    if (showConfirmSheet) {
        SosConfirmBottomSheet(
            onDismiss = { showConfirmSheet = false },
            onConfirmSos = {
                showConfirmSheet = false
                onTriggerSos()
            }
        )
    }
}

/**
 * Screen 5: SOS Confirmation Bottom Sheet Modal ('confirm')
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SosConfirmBottomSheet(
    onDismiss: () -> Unit,
    onConfirmSos: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = AegisWhite,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(AegisRedSoft),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = null,
                    tint = AegisRed,
                    modifier = Modifier.size(26.dp)
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "Send emergency alert?",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = AegisNavy
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Your guardian will receive your alert and latest location. A call will start when available.",
                fontSize = 13.sp,
                color = AegisGrey500,
                lineHeight = 18.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Warning Notice Box
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(AegisRedSoft)
                    .border(
                        width = 1.dp,
                        color = Color(0xFFF1C4C8),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .padding(12.dp)
            ) {
                Column {
                    Text(
                        text = "Use this for a genuine emergency.",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = AegisRed
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "You can cancel after activation if you are safe.",
                        fontSize = 11.sp,
                        color = AegisGrey500
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            AegisPrimaryButton(
                text = "Send SOS now",
                danger = true,
                onClick = onConfirmSos
            )

            Spacer(modifier = Modifier.height(8.dp))

            AegisPrimaryButton(
                text = "Cancel",
                quiet = true,
                onClick = onDismiss
            )

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * Screen 6: Active Emergency Screen ('active')
 */
@Composable
fun ActiveEmergencyScreen(
    onResolveEmergency: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    val eventId = EmergencyOrchestrator.backendEventId.value ?: "AEG-LIVE"
    val loc = EmergencyOrchestrator.currentLocation.value
    val smsStatus = EmergencyOrchestrator.smsStatus.value
    val callState = LiveKitCallManager.callState.value

    // Live elapsed timer
    var elapsedSeconds by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            elapsedSeconds++
        }
    }
    val minutes = elapsedSeconds / 60
    val seconds = elapsedSeconds % 60
    val elapsedString = String.format("%02d:%02d elapsed", minutes, seconds)

    // Pulsing animation for active banner
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AegisWhite)
    ) {
        AegisTopBar(
            title = "Emergency in progress",
            subtitle = "Event ${eventId.take(16)}",
            onMenu = onMenu
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Active Pulsing Banner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(11.dp))
                    .border(1.dp, Color(0xFFF1C4C8), RoundedCornerShape(11.dp))
                    .background(AegisRedSoft)
                    .padding(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .scale(pulseScale)
                            .clip(CircleShape)
                            .background(AegisRed)
                    )

                    Spacer(modifier = Modifier.width(14.dp))

                    Column {
                        Text(
                            text = "SOS ACTIVE",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            letterSpacing = 0.5.sp,
                            color = AegisRed
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Triggered · $elapsedString",
                            fontSize = 11.sp,
                            color = AegisNavy
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Current Location Box with Google Maps Link
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(11.dp))
                    .border(1.dp, AegisGrey200, RoundedCornerShape(11.dp))
                    .background(AegisWhite)
                    .padding(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "CURRENT LOCATION",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp,
                            color = AegisGrey500
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (loc != null && loc.isValid()) "${String.format("%.5f", loc.latitude)}, ${String.format("%.5f", loc.longitude)}" else "Acquiring GPS...",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = AegisNavy
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (loc != null && loc.isValid()) "Accuracy ${loc.accuracyMeters?.toInt() ?: 0} m" else "Emergency location requested",
                            fontSize = 10.sp,
                            color = AegisGrey500
                        )
                    }

                    if (loc != null && loc.isValid()) {
                        Text(
                            text = "View map",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = AegisNavyDark,
                            modifier = Modifier
                                .clickable {
                                    try {
                                        uriHandler.openUri(loc.toGoogleMapsUrl())
                                    } catch (_: Exception) {}
                                }
                                .padding(8.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // 2x2 Status Grid
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(11.dp))
                    .border(1.dp, AegisGrey200, RoundedCornerShape(11.dp))
                    .background(AegisWhite)
            ) {
                Column {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        // Cell 1: Location
                        GridCell(
                            icon = Icons.Default.Place,
                            status = if (loc != null && loc.isValid()) "Sharing" else "Acquiring",
                            label = "Location",
                            tone = if (loc != null && loc.isValid()) AegisStatusTone.GOOD else AegisStatusTone.WAIT,
                            modifier = Modifier.weight(1f)
                        )
                        Box(modifier = Modifier.width(1.dp).height(74.dp).background(AegisGrey100))
                        // Cell 2: Guardian
                        GridCell(
                            icon = Icons.Default.Notifications,
                            status = "Delivered",
                            label = "Guardian",
                            tone = AegisStatusTone.GOOD,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    HorizontalDivider(color = AegisGrey100, thickness = 1.dp)

                    Row(modifier = Modifier.fillMaxWidth()) {
                        // Cell 3: Audio Call
                        val callTone = if (LiveKitCallManager.isCallActive) AegisStatusTone.GOOD else AegisStatusTone.WAIT
                        val callLabel = if (LiveKitCallManager.isCallActive) "Connected" else "Connecting"
                        GridCell(
                            icon = Icons.Default.Phone,
                            status = callLabel,
                            label = "Audio call",
                            tone = callTone,
                            modifier = Modifier.weight(1f)
                        )
                        Box(modifier = Modifier.width(1.dp).height(74.dp).background(AegisGrey100))
                        // Cell 4: SMS
                        val smsTone = when (smsStatus) {
                            SmsDeliveryStatus.DELIVERED, SmsDeliveryStatus.SENT_SUCCESS -> AegisStatusTone.GOOD
                            SmsDeliveryStatus.SENT_FAILED, SmsDeliveryStatus.DELIVERY_FAILED -> AegisStatusTone.BAD
                            else -> AegisStatusTone.WAIT
                        }
                        val smsLabel = when (smsStatus) {
                            SmsDeliveryStatus.DELIVERED -> "Delivered"
                            SmsDeliveryStatus.SENT_SUCCESS -> "Sent"
                            SmsDeliveryStatus.INITIATED, SmsDeliveryStatus.ACCEPTED_BY_TELEPHONY -> "Submitted"
                            SmsDeliveryStatus.SENT_FAILED -> "Failed"
                            else -> "Submitted"
                        }
                        GridCell(
                            icon = Icons.Default.Check,
                            status = smsLabel,
                            label = "SMS",
                            tone = smsTone,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Emergency Timeline
            Text(
                text = "Emergency timeline",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = AegisNavy
            )

            Spacer(modifier = Modifier.height(14.dp))

            Column(modifier = Modifier.padding(start = 4.dp)) {
                AegisTimelineItem(
                    time = "14:32:08",
                    title = "SOS activated",
                    detail = "${EmergencyOrchestrator.activeTriggerSource.value.replaceFirstChar { it.uppercase() }} trigger",
                    isCritical = true
                )
                AegisTimelineItem(
                    time = "14:32:09",
                    title = "Location acquired",
                    detail = if (loc != null) "Accuracy ${loc.accuracyMeters?.toInt() ?: 0} m" else "Cached location"
                )
                AegisTimelineItem(
                    time = "14:32:11",
                    title = "Alert submitted",
                    detail = "Guardian notified via backend"
                )
                AegisTimelineItem(
                    time = "14:32:15",
                    title = "SMS sent",
                    detail = "Carrier telephony accepted"
                )
                AegisTimelineItem(
                    time = "14:32:21",
                    title = "Audio call session",
                    detail = if (LiveKitCallManager.isCallActive) "LiveKit 2-way call connected" else "Signaling established",
                    isLast = true
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            AegisPrimaryButton(
                text = "Resolve emergency",
                quiet = true,
                onClick = onResolveEmergency
            )

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
private fun GridCell(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    status: String,
    label: String,
    tone: AegisStatusTone,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .height(74.dp)
            .padding(11.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = AegisNavyDark,
                modifier = Modifier.size(16.dp)
            )
            AegisStatusPill(label = status, tone = tone)
        }
        Spacer(modifier = Modifier.height(3.dp))
        Text(
            text = label,
            fontSize = 10.sp,
            color = AegisGrey500
        )
    }
}

/**
 * Screen 7: Voice Safety Screen ('voice')
 */
@Composable
fun VoiceSafetyScreen(
    onTriggerVoiceTest: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val voiceStatus = EmergencyOrchestrator.voiceStatus.value
    val isEnabled = voiceStatus != VoiceProtectionStatus.DISABLED

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AegisWhite)
    ) {
        AegisTopBar(
            title = "Voice safety",
            subtitle = "Person needing help",
            onMenu = onMenu
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(18.dp))

            // Voice Hero Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isEnabled) AegisGreenSoft else AegisGrey100)
                    .padding(vertical = 24.dp, horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(60.dp)
                            .clip(CircleShape)
                            .background(AegisWhite),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = null,
                            tint = if (isEnabled) AegisGreen else AegisGrey500,
                            modifier = Modifier.size(30.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = if (isEnabled) "Voice protection is on" else "Voice protection is off",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = AegisNavy
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = if (isEnabled) "Speech recognition is active & listening." else "The microphone is not listening.",
                        fontSize = 12.sp,
                        color = AegisGrey500
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    AegisStatusPill(
                        label = if (isEnabled) "Active" else "Disabled",
                        tone = if (isEnabled) AegisStatusTone.GOOD else AegisStatusTone.NEUTRAL
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Activation Phrase Section
            Text(
                text = "Activation phrase",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = AegisNavy
            )

            Spacer(modifier = Modifier.height(10.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, AegisGrey200, RoundedCornerShape(10.dp))
                    .background(AegisWhite)
                    .padding(vertical = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "“I need help”",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = AegisNavy
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "When protection is enabled and this exact phrase is recognized, AEGIS starts the SOS workflow. Recognition occurs on this device.",
                fontSize = 13.sp,
                color = AegisGrey500,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Surface info rows for Permissions and Service
            AegisCard {
                AegisInfoRow(
                    icon = Icons.Default.Mic,
                    label = "Microphone permission",
                    value = "Allowed while using the app",
                    status = { AegisStatusPill(label = "Allowed", tone = AegisStatusTone.GOOD) }
                )
                HorizontalDivider(color = AegisGrey100, thickness = 1.dp)
                AegisInfoRow(
                    icon = Icons.Default.SignalCellularAlt,
                    label = "Speech recognition",
                    value = EmergencyOrchestrator.voiceStatusMessage.value.ifBlank { "Service available" },
                    status = {
                        AegisStatusPill(
                            label = if (isEnabled) "Ready" else "Idle",
                            tone = if (isEnabled) AegisStatusTone.GOOD else AegisStatusTone.NEUTRAL
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(22.dp))

            AegisPrimaryButton(
                text = if (isEnabled) "Disable protection" else "Enable protection",
                danger = isEnabled,
                onClick = {
                    if (isEnabled) {
                        VoiceSafetyService.stop(context)
                    } else {
                        VoiceSafetyService.start(context)
                    }
                }
            )

            Spacer(modifier = Modifier.height(10.dp))

            AegisPrimaryButton(
                text = "Simulate recognized phrase",
                quiet = true,
                enabled = isEnabled,
                onClick = onTriggerVoiceTest
            )

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

/**
 * Screen 11: Emergency Contact Screen ('contacts')
 */
@Composable
fun EmergencyContactScreen(
    onContactSaved: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val currentPhone = EmergencyContactManager.savedContactPhone.value ?: "+1 415 555 0138"
    val currentName = EmergencyContactManager.savedContactName.value ?: "Maya Singh"

    var nameInput by remember { mutableStateOf(currentName) }
    var phoneInput by remember { mutableStateOf(currentPhone) }
    var validationError by remember { mutableStateOf<String?>(null) }
    val isSyncing = EmergencyContactManager.isSyncing.value
    val feedbackMessage = EmergencyContactManager.statusFeedback.value

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AegisWhite)
    ) {
        AegisTopBar(
            title = "Emergency contact",
            subtitle = "Used for SOS alerts",
            onMenu = onMenu
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(18.dp))

            // Selected Contact Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(AegisBlueSoft),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "MS",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.sp,
                        color = AegisNavyDark
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    AegisStatusPill(label = "Selected contact", tone = AegisStatusTone.GOOD)
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = nameInput.ifBlank { "Maya Singh" },
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = AegisNavy
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Receives SOS alerts, location, and calls.",
                        fontSize = 11.sp,
                        color = AegisGrey500
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Field: Full Name
            Text(
                text = "Full name",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = AegisNavy
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = nameInput,
                onValueChange = { nameInput = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Field: Phone Number
            Text(
                text = "Phone number",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = AegisNavy
            )
            Spacer(modifier = Modifier.height(6.dp))
            OutlinedTextField(
                value = phoneInput,
                onValueChange = {
                    phoneInput = it
                    validationError = null
                },
                placeholder = { Text("+1 415 555 0138") },
                singleLine = true,
                isError = validationError != null,
                supportingText = {
                    Text(
                        text = validationError ?: "Include country code (e.g. +14155550138).",
                        color = if (validationError != null) AegisRed else AegisGrey500,
                        fontSize = 10.sp
                    )
                },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Backend Sync Row
            AegisCard {
                AegisInfoRow(
                    icon = Icons.Default.SignalCellularAlt,
                    label = "Backend sync",
                    value = feedbackMessage.orEmpty().ifBlank { "Contact matches stored record" },
                    status = {
                        AegisStatusPill(
                            label = if (EmergencyContactManager.isError.value) "Error" else "Synced",
                            tone = if (EmergencyContactManager.isError.value) AegisStatusTone.BAD else AegisStatusTone.GOOD
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Buttons: Cancel & Save Contact
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AegisPrimaryButton(
                    text = "Cancel",
                    quiet = true,
                    onClick = onContactSaved,
                    modifier = Modifier.weight(1f)
                )

                AegisPrimaryButton(
                    text = "Save contact",
                    isLoading = isSyncing,
                    onClick = {
                        val trimmed = phoneInput.trim()
                        if (trimmed.isBlank()) {
                            validationError = "Please enter a valid phone number."
                        } else if (!EmergencyUtils.isValidPhoneNumber(trimmed)) {
                            validationError = "Invalid phone number format. Must contain 7–15 digits."
                        } else {
                            coroutineScope.launch {
                                val devId = AegisPreferences.getDeviceId(context)
                                EmergencyContactManager.saveEmergencyContact(
                                    context = context,
                                    deviceId = devId,
                                    phoneNumber = trimmed,
                                    contactName = nameInput.trim()
                                )
                                onContactSaved()
                            }
                        }
                    },
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}
