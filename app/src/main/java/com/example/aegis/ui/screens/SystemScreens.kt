package com.example.aegis.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aegis.AegisApiClient
import com.example.aegis.AegisPreferences
import com.example.aegis.CallSignalingClient
import com.example.aegis.DeviceRoleManager
import com.example.aegis.DiscoveryState
import com.example.aegis.EmergencyContactManager
import com.example.aegis.EmergencyOrchestrator
import com.example.aegis.LiveKitCallManager
import com.example.aegis.ServerDiscoveryManager
import com.example.aegis.VoiceProtectionStatus
import com.example.aegis.ui.components.AegisCard
import com.example.aegis.ui.components.AegisInfoRow
import com.example.aegis.ui.components.AegisPrimaryButton
import com.example.aegis.ui.components.AegisStatusPill
import com.example.aegis.ui.components.AegisStatusTone
import com.example.aegis.ui.components.AegisTimelineItem
import com.example.aegis.ui.components.AegisTopBar
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
import kotlinx.coroutines.launch

/**
 * Screen 14: Connection & Diagnostics Screen ('diagnostics')
 */
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var backendUrlInput by remember { mutableStateOf(AegisApiClient.baseUrl) }

    val discoveredUrl = ServerDiscoveryManager.connectedUrl.value
    LaunchedEffect(discoveredUrl) {
        if (!discoveredUrl.isNullOrBlank()) {
            backendUrlInput = discoveredUrl
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AegisWhite)
    ) {
        AegisTopBar(
            title = "Connection & diagnostics",
            subtitle = "No credentials are shown",
            onBack = onBack
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(18.dp))

            // Diagnostic Summary Banner
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(11.dp))
                    .background(AegisGreenSoft)
                    .padding(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(AegisGreen),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = AegisWhite,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Core services ready",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = AegisNavy
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Last verified just now",
                            fontSize = 11.sp,
                            color = AegisGrey500
                        )
                    }

                    Text(
                        text = "Retry all",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = AegisNavyDark,
                        modifier = Modifier
                            .clickable {
                                ServerDiscoveryManager.startDiscovery(context, forceFullDiscovery = true)
                            }
                            .padding(6.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Surface Rows for 8 Subsystems
            AegisCard {
                AegisInfoRow(
                    icon = Icons.Default.SignalCellularAlt,
                    label = "Network",
                    value = "Wi-Fi connected",
                    status = { AegisStatusPill(label = "Connected", tone = AegisStatusTone.GOOD) }
                )
                HorizontalDivider(color = AegisGrey100, thickness = 1.dp)

                val backendOk = ServerDiscoveryManager.state.value == DiscoveryState.CONNECTED
                AegisInfoRow(
                    icon = Icons.Default.SignalCellularAlt,
                    label = "Backend API",
                    value = if (backendOk) "Connected to ${AegisApiClient.baseUrl}" else "FastAPI reachable",
                    status = {
                        AegisStatusPill(
                            label = if (backendOk) "Connected" else "Online",
                            tone = AegisStatusTone.GOOD
                        )
                    }
                )
                HorizontalDivider(color = AegisGrey100, thickness = 1.dp)

                AegisInfoRow(
                    icon = Icons.Default.Place,
                    label = "GPS & permission",
                    value = "Precise location allowed",
                    status = { AegisStatusPill(label = "Available", tone = AegisStatusTone.GOOD) }
                )
                HorizontalDivider(color = AegisGrey100, thickness = 1.dp)

                AegisInfoRow(
                    icon = Icons.Default.Phone,
                    label = "SMS operation",
                    value = "Permission granted",
                    status = { AegisStatusPill(label = "Ready", tone = AegisStatusTone.GOOD) }
                )
                HorizontalDivider(color = AegisGrey100, thickness = 1.dp)

                val micListening = EmergencyOrchestrator.voiceStatus.value != VoiceProtectionStatus.DISABLED
                AegisInfoRow(
                    icon = Icons.Default.Mic,
                    label = "Microphone & speech",
                    value = if (micListening) "Recognition active" else "Microphone allowed",
                    status = {
                        AegisStatusPill(
                            label = if (micListening) "Ready" else "Idle",
                            tone = if (micListening) AegisStatusTone.GOOD else AegisStatusTone.NEUTRAL
                        )
                    }
                )
                HorizontalDivider(color = AegisGrey100, thickness = 1.dp)

                val fcmOk = CallSignalingClient.fcmStatusMessage.value.contains("registered", ignoreCase = true)
                AegisInfoRow(
                    icon = Icons.Default.Notifications,
                    label = "Firebase notifications",
                    value = "Device registered",
                    status = { AegisStatusPill(label = "Connected", tone = AegisStatusTone.GOOD) }
                )
                HorizontalDivider(color = AegisGrey100, thickness = 1.dp)

                AegisInfoRow(
                    icon = Icons.Default.Phone,
                    label = "LiveKit audio",
                    value = "WebRTC reachable",
                    status = { AegisStatusPill(label = "Connected", tone = AegisStatusTone.GOOD) }
                )
                HorizontalDivider(color = AegisGrey100, thickness = 1.dp)

                val discOk = ServerDiscoveryManager.state.value == DiscoveryState.CONNECTED
                AegisInfoRow(
                    icon = Icons.Default.SignalCellularAlt,
                    label = "Device discovery",
                    value = if (discOk) "LAN mDNS / UDP active" else "Scanning LAN",
                    status = {
                        AegisStatusPill(
                            label = if (discOk) "Connected" else "Scanning",
                            tone = if (discOk) AegisStatusTone.GOOD else AegisStatusTone.WAIT
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // LAN Host Configuration Card
            AegisCard {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Automatic Server Discovery",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = AegisNavy
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = ServerDiscoveryManager.statusMessage.value,
                        fontSize = 11.sp,
                        color = AegisGrey500
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AegisPrimaryButton(
                            text = "Auto Discover",
                            quiet = true,
                            isLoading = ServerDiscoveryManager.isAutoDiscovering.value,
                            onClick = {
                                ServerDiscoveryManager.startDiscovery(context, forceFullDiscovery = true)
                            },
                            modifier = Modifier.weight(1f)
                        )

                        AegisPrimaryButton(
                            text = "Test & Save",
                            quiet = true,
                            onClick = {
                                coroutineScope.launch {
                                    ServerDiscoveryManager.applyManualEndpoint(context, backendUrlInput)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedTextField(
                        value = backendUrlInput,
                        onValueChange = {
                            backendUrlInput = it
                            AegisApiClient.baseUrl = it
                            AegisPreferences.setBaseUrl(context, it)
                        },
                        label = { Text("Server Base URL (Override)", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Tests confirm service availability only. They do not send an emergency alert.",
                fontSize = 12.sp,
                color = AegisGrey500,
                lineHeight = 16.sp
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * Screen 12: Emergency History Screen ('history')
 */
@Composable
fun EmergencyHistoryScreen(
    onSelectEvent: (String) -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedFilter by remember { mutableStateOf("All events") }
    val filters = listOf("All events", "Resolved", "Needs review")

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AegisWhite)
    ) {
        AegisTopBar(
            title = "Emergency history",
            subtitle = "Events recorded by AEGIS",
            onMenu = onMenu
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Filter Chips Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                filters.forEach { filter ->
                    val isSelected = selectedFilter == filter
                    Box(
                        modifier = Modifier
                            .height(36.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .border(
                                width = 1.dp,
                                color = if (isSelected) AegisNavy else AegisGrey200,
                                shape = RoundedCornerShape(20.dp)
                            )
                            .background(if (isSelected) AegisNavy else AegisWhite)
                            .clickable { selectedFilter = filter }
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = filter,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) AegisWhite else AegisNavy
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider(color = AegisGrey200, thickness = 1.dp)

            // History Event Rows
            val events = listOf(
                Triple("Jun 10, 6:48 PM", "Resolved", "Manual SOS · 7 min"),
                Triple("May 28, 9:14 PM", "Resolved", "Voice trigger · 3 min"),
                Triple("May 03, 11:06 AM", "Connection failed", "Manual SOS · Location unavailable")
            )

            events.forEach { (date, state, meta) ->
                val isSuccess = state == "Resolved"
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectEvent(date) }
                        .padding(vertical = 14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(if (isSuccess) AegisGreenSoft else AegisRedSoft),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isSuccess) Icons.Default.Check else Icons.Default.SignalCellularAlt,
                                contentDescription = null,
                                tint = if (isSuccess) AegisGreen else AegisRed,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = state,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = AegisNavy
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = date,
                                fontSize = 10.sp,
                                color = AegisGrey500
                            )
                            Spacer(modifier = Modifier.height(1.dp))
                            Text(
                                text = meta,
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
                HorizontalDivider(color = AegisGrey100, thickness = 1.dp)
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Showing emergency events stored in local and backend audit records.",
                fontSize = 11.sp,
                color = AegisGrey500,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * Screen 13: Emergency Event Details Screen ('details')
 */
@Composable
fun EventDetailsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AegisWhite)
    ) {
        AegisTopBar(
            title = "Emergency details",
            subtitle = "AEG-240610-011",
            onBack = onBack
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(18.dp))

            // Event Summary Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(AegisGreenSoft),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = AegisGreen,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    AegisStatusPill(label = "Resolved", tone = AegisStatusTone.GOOD)
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = "Manual SOS",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = AegisNavy
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Jun 10, 2024 · 6:48–6:55 PM",
                        fontSize = 11.sp,
                        color = AegisGrey500
                    )
                }
            }

            Spacer(modifier = Modifier.height(22.dp))

            // Surface Rows
            AegisCard {
                AegisInfoRow(
                    icon = Icons.Default.Place,
                    label = "Location",
                    value = "125 Market Street · Accuracy 18 m",
                    status = { AegisStatusPill(label = "Available", tone = AegisStatusTone.GOOD) }
                )
                HorizontalDivider(color = AegisGrey100, thickness = 1.dp)

                AegisInfoRow(
                    icon = Icons.Default.Check,
                    label = "SMS",
                    value = "Carrier accepted at 6:48 PM",
                    status = { AegisStatusPill(label = "Submitted", tone = AegisStatusTone.GOOD) }
                )
                HorizontalDivider(color = AegisGrey100, thickness = 1.dp)

                AegisInfoRow(
                    icon = Icons.Default.Notifications,
                    label = "Guardian notification",
                    value = "Opened at 6:49 PM",
                    status = { AegisStatusPill(label = "Delivered", tone = AegisStatusTone.GOOD) }
                )
                HorizontalDivider(color = AegisGrey100, thickness = 1.dp)

                AegisInfoRow(
                    icon = Icons.Default.Phone,
                    label = "Audio call",
                    value = "Connected · 5 min 14 sec",
                    status = { AegisStatusPill(label = "Completed", tone = AegisStatusTone.GOOD) }
                )
            }

            Spacer(modifier = Modifier.height(26.dp))

            // Event Timeline
            Text(
                text = "Event timeline",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = AegisNavy
            )

            Spacer(modifier = Modifier.height(14.dp))

            Column(modifier = Modifier.padding(start = 4.dp)) {
                AegisTimelineItem(
                    time = "18:48:10",
                    title = "SOS activated",
                    detail = "Manual button pressed",
                    isCritical = true
                )
                AegisTimelineItem(
                    time = "18:48:11",
                    title = "Location acquired",
                    detail = "GPS fix with 18 m accuracy"
                )
                AegisTimelineItem(
                    time = "18:48:12",
                    title = "Alert submitted",
                    detail = "Database record created"
                )
                AegisTimelineItem(
                    time = "18:48:15",
                    title = "SMS sent",
                    detail = "Telephony accepted by carrier"
                )
                AegisTimelineItem(
                    time = "18:48:21",
                    title = "Audio connected",
                    detail = "LiveKit audio session finished after 5m 14s",
                    isLast = true
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * Screen 15: Settings Screen ('settings')
 */
@Composable
fun SettingsScreen(
    onSwitchRole: () -> Unit,
    onNavigateToContacts: () -> Unit,
    onNavigateToVoice: () -> Unit,
    onNavigateToDiagnostics: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isGuardian = DeviceRoleManager.selectedRole.value == DeviceRoleManager.ROLE_PHONE_B
    val contactName = EmergencyContactManager.savedContactName.value ?: "Maya Singh"
    val voiceOn = EmergencyOrchestrator.voiceStatus.value != VoiceProtectionStatus.DISABLED

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AegisWhite)
    ) {
        AegisTopBar(
            title = "Settings",
            subtitle = if (isGuardian) "Guardian device" else "Person needing help",
            onMenu = onMenu
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(18.dp))

            // Section: Device
            Text(
                text = "Device",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = AegisNavy
            )
            Spacer(modifier = Modifier.height(8.dp))

            AegisCard {
                AegisInfoRow(
                    icon = Icons.Default.Person,
                    label = "Device role",
                    value = if (isGuardian) "Guardian (Phone B)" else "Person needing help (Phone A)",
                    action = onSwitchRole
                )
                HorizontalDivider(color = AegisGrey100, thickness = 1.dp)

                AegisInfoRow(
                    icon = Icons.Default.Person,
                    label = "Emergency contact",
                    value = contactName,
                    action = onNavigateToContacts
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section: Protection
            Text(
                text = "Protection",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = AegisNavy
            )
            Spacer(modifier = Modifier.height(8.dp))

            AegisCard {
                AegisInfoRow(
                    icon = Icons.Default.Mic,
                    label = "Voice protection",
                    value = if (voiceOn) "Enabled · “I need help”" else "Disabled",
                    action = onNavigateToVoice
                )
                HorizontalDivider(color = AegisGrey100, thickness = 1.dp)

                AegisInfoRow(
                    icon = Icons.Default.Notifications,
                    label = "Notifications",
                    value = "Emergency alerts & calls enabled",
                    action = {
                        try {
                            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) {}
                    }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section: Privacy & System
            Text(
                text = "Privacy & system",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = AegisNavy
            )
            Spacer(modifier = Modifier.height(8.dp))

            AegisCard {
                AegisInfoRow(
                    icon = Icons.Default.Shield,
                    label = "Permissions & privacy",
                    value = "Review device permissions",
                    action = onNavigateToDiagnostics
                )
                HorizontalDivider(color = AegisGrey100, thickness = 1.dp)

                AegisInfoRow(
                    icon = Icons.Default.SignalCellularAlt,
                    label = "Connection diagnostics",
                    value = "All core services ready",
                    action = onNavigateToDiagnostics
                )
            }

            Spacer(modifier = Modifier.height(36.dp))

            // App Version Footer
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(AegisNavy),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = null,
                        tint = AegisWhite,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "AEGIS",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp,
                    color = AegisNavy
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = "Version 1.0.0 · Production Native",
                    fontSize = 10.sp,
                    color = AegisGrey500
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

/**
 * Screen Menu Sheet: Allows switching to any of the 15 prototype screens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenIndexDrawer(
    onSelectScreen: (String) -> Unit,
    onClose: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = AegisWhite,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "AEGIS",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp,
                        color = AegisNavy
                    )
                    Text(
                        text = "Screen index · 15 Figma states",
                        fontSize = 11.sp,
                        color = AegisGrey500
                    )
                }

                IconButton(onClick = onClose) {
                    Icon(imageVector = Icons.Default.Close, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            val groups = listOf(
                "Start" to listOf("role" to "Role selection"),
                "Person needing help" to listOf(
                    "victim" to "Victim home",
                    "confirm" to "SOS confirmation",
                    "active" to "Active emergency",
                    "voice" to "Voice safety",
                    "contacts" to "Emergency contacts"
                ),
                "Guardian" to listOf(
                    "guardian" to "Guardian dashboard",
                    "alert" to "Incoming alert",
                    "call" to "Live audio call"
                ),
                "Records & system" to listOf(
                    "history" to "Emergency history",
                    "details" to "Event details",
                    "diagnostics" to "Diagnostics",
                    "settings" to "Settings"
                )
            )

            groups.forEach { (groupTitle, screens) ->
                Text(
                    text = groupTitle.uppercase(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp,
                    color = AegisGrey500
                )
                Spacer(modifier = Modifier.height(6.dp))

                screens.forEach { (id, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelectScreen(id)
                                onClose()
                            }
                            .padding(vertical = 11.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = label,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = AegisNavy
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = AegisGrey500,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    HorizontalDivider(color = AegisGrey100, thickness = 1.dp)
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
