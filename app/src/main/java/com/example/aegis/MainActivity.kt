package com.example.aegis

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.aegis.ui.components.AegisBottomNav
import com.example.aegis.ui.components.AegisNavTab
import com.example.aegis.ui.screens.ActiveEmergencyScreen
import com.example.aegis.ui.screens.DiagnosticsScreen
import com.example.aegis.ui.screens.EmergencyContactScreen
import com.example.aegis.ui.screens.EmergencyHistoryScreen
import com.example.aegis.ui.screens.EventDetailsScreen
import com.example.aegis.ui.screens.GuardianDashboardScreen
import com.example.aegis.ui.screens.IncomingAlertScreen
import com.example.aegis.ui.screens.LiveAudioCallScreen
import com.example.aegis.ui.screens.ScreenIndexDrawer
import com.example.aegis.ui.screens.SettingsScreen
import com.example.aegis.ui.screens.VictimHomeScreen
import com.example.aegis.ui.screens.VoiceSafetyScreen
import com.example.aegis.ui.theme.AEGISTheme
import com.example.aegis.ui.theme.AegisNavy
import com.example.aegis.ui.theme.AegisRed
import com.example.aegis.ui.theme.AegisWhite
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val smsStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val isTest = intent.getBooleanExtra(EmergencyOrchestrator.EXTRA_IS_TEST, false)

            when (intent.action) {
                EmergencyOrchestrator.ACTION_SMS_SENT -> {
                    EmergencyOrchestrator.onSmsSentResult(resultCode, isTest)
                }
                EmergencyOrchestrator.ACTION_SMS_DELIVERED -> {
                    EmergencyOrchestrator.onSmsDeliveredResult(resultCode, isTest)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Wake screen & display over lock screen for incoming emergency calls
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        // Initialize persistent device-specific role manager (frame-0 synchronous restoration)
        val initialRole = DeviceRoleManager.initialize(this)

        // Automatically discover AEGIS backend server and LiveKit signaling on LAN
        ServerDiscoveryManager.startDiscovery(this)

        if (initialRole != null) {
            // Initialize persistent Phone B emergency contact & restore cached preferences
            EmergencyContactManager.initialize(this, initialRole)

            // Initialize real-time incoming call signaling with the saved device role
            CallSignalingClient.start(this, initialRole)
        } else {
            EmergencyCallNotificationManager.createNotificationChannel(this)
        }

        handleIncomingCallIntent(intent)

        val filter = IntentFilter().apply {
            addAction(EmergencyOrchestrator.ACTION_SMS_SENT)
            addAction(EmergencyOrchestrator.ACTION_SMS_DELIVERED)
        }
        ContextCompat.registerReceiver(
            this,
            smsStatusReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )

        setContent {
            AEGISTheme {
                val currentRole = DeviceRoleManager.selectedRole.value
                if (currentRole == null) {
                    RoleSelectionScreen(
                        onRoleSelected = { role ->
                            android.util.Log.i("AEGIS_Main", "Device role chosen: $role")
                        }
                    )
                } else {
                    AegisMainScreen(activeRole = currentRole)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        handleIncomingCallIntent(intent)
    }

    private fun handleIncomingCallIntent(intent: Intent?) {
        if (intent == null) return
        val invitationId = intent.getStringExtra(EmergencyCallNotificationManager.EXTRA_INVITATION_ID)
        val eventId = intent.getStringExtra(EmergencyCallNotificationManager.EXTRA_EVENT_ID)
        val callerName = intent.getStringExtra(EmergencyCallNotificationManager.EXTRA_CALLER_NAME) ?: "Alex Smith"
        val action = intent.action

        if (!invitationId.isNullOrBlank()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            if (action == EmergencyCallNotificationManager.ACTION_ANSWER_CALL) {
                android.util.Log.i("AEGIS_Main", "Notification ANSWER action for: $invitationId")
                CallSignalingClient.answerInvitation(
                    context = this,
                    invitationId = invitationId,
                    deviceId = CallSignalingClient.currentDeviceId.value,
                    displayName = "Phone B (Guardian)"
                )
            } else {
                android.util.Log.i("AEGIS_Main", "Incoming call full-screen alert tap for: $invitationId")
                CallSignalingClient.setActiveIncomingInvitation(
                    invitationId = invitationId,
                    emergencyEventId = eventId ?: "",
                    callerDeviceId = "phone-a",
                    callerDisplayName = callerName
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(smsStatusReceiver)
        } catch (_: Exception) {}
    }
}

@Composable
fun AegisMainScreen(
    activeRole: String = DeviceRoleManager.selectedRole.value ?: DeviceRoleManager.ROLE_PHONE_B
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val coroutineScope = rememberCoroutineScope()
    val isGuardian = activeRole == DeviceRoleManager.ROLE_PHONE_B

    // Tab & Custom Screen Navigation State
    var currentTab by remember { mutableStateOf(AegisNavTab.HOME) }
    var activeCustomScreen by remember { mutableStateOf<String?>(null) }
    var showMenuDrawer by remember { mutableStateOf(false) }

    // Role Switch Dialog States
    var showRoleSwitchWarningDialog by remember { mutableStateOf(false) }
    var showRoleChangeDialog by remember { mutableStateOf(false) }
    var pendingRoleSwitchTarget by remember { mutableStateOf<String?>(null) }

    // Permission Launchers
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val sosPermissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val smsOk = perms[Manifest.permission.SEND_SMS] == true
        if (smsOk) {
            pendingAction?.invoke()
        } else {
            Toast.makeText(context, "SEND_SMS permission is required to send emergency alerts.", Toast.LENGTH_LONG).show()
        }
        pendingAction = null
    }

    val voicePermissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val micOk = perms[Manifest.permission.RECORD_AUDIO] == true
        if (micOk) {
            pendingAction?.invoke()
        } else {
            Toast.makeText(context, "Microphone permission is required for voice safety detection.", Toast.LENGTH_LONG).show()
        }
        pendingAction = null
    }

    fun executeWithSosPermissions(action: () -> Unit) {
        val hasSms = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        if (hasSms && (hasFine || hasCoarse) && hasMic) {
            action()
        } else {
            pendingAction = action
            sosPermissionsLauncher.launch(
                arrayOf(
                    Manifest.permission.SEND_SMS,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.RECORD_AUDIO
                )
            )
        }
    }

    fun executeWithVoicePermissions(action: () -> Unit) {
        val hasMic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val hasNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true

        if (hasMic && hasNotif) {
            action()
        } else {
            pendingAction = action
            val list = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                list.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            voicePermissionsLauncher.launch(list.toTypedArray())
        }
    }

    // Role switcher function
    fun promptRoleSwitch(targetRole: String) {
        val isSessionActive = EmergencyOrchestrator.isSosActive.value ||
                LiveKitCallManager.isCallActive ||
                CallSignalingClient.activeIncomingInvitation.value != null
        pendingRoleSwitchTarget = targetRole

        if (isSessionActive) {
            showRoleSwitchWarningDialog = true
        } else {
            showRoleChangeDialog = true
        }
    }

    // Reactive State Routing:
    // If WebRTC call is active, display LiveKit Call Screen.
    // Else if SOS is active on Phone A, display Active Emergency Screen.
    val isCallActive = LiveKitCallManager.isCallActive
    val isSosActive = EmergencyOrchestrator.isSosActive.value && !isGuardian

    val isFullscreenCallOrAlert = isCallActive || activeCustomScreen == "call" || activeCustomScreen == "alert"

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (!isFullscreenCallOrAlert && !isSosActive && activeCustomScreen == null) {
                AegisBottomNav(
                    currentTab = currentTab,
                    onSelectTab = { currentTab = it },
                    isGuardian = isGuardian
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(AegisWhite)
        ) {
            when {
                // Priority 1: Live LiveKit Audio Call
                isCallActive || activeCustomScreen == "call" -> {
                    LiveAudioCallScreen(
                        onEndCall = {
                            LiveKitCallManager.endCall(context)
                            activeCustomScreen = null
                        },
                        onMenu = { showMenuDrawer = true }
                    )
                }

                // Priority 2: Active Emergency Requester Screen
                isSosActive || activeCustomScreen == "active" -> {
                    ActiveEmergencyScreen(
                        onResolveEmergency = {
                            EmergencyOrchestrator.startNewSosEvent()
                            activeCustomScreen = null
                        },
                        onMenu = { showMenuDrawer = true }
                    )
                }

                // Custom Figma state: Incoming Alert
                activeCustomScreen == "alert" -> {
                    IncomingAlertScreen(
                        onAnswerCall = {
                            val incomingInv = CallSignalingClient.activeIncomingInvitation.value
                            if (incomingInv != null) {
                                executeWithVoicePermissions {
                                    CallSignalingClient.answerInvitation(
                                        context = context,
                                        invitationId = incomingInv.invitationId,
                                        deviceId = CallSignalingClient.currentDeviceId.value,
                                        displayName = "Phone B (Guardian)"
                                    )
                                }
                            }
                            activeCustomScreen = "call"
                        },
                        onViewMap = {
                            val loc = EmergencyOrchestrator.currentLocation.value
                            if (loc != null && loc.isValid()) {
                                try {
                                    uriHandler.openUri(loc.toGoogleMapsUrl())
                                } catch (_: Exception) {}
                            }
                        },
                        onDismiss = {
                            activeCustomScreen = null
                        }
                    )
                }

                // Custom Figma state: Event Details
                activeCustomScreen == "details" -> {
                    EventDetailsScreen(
                        onBack = { activeCustomScreen = null }
                    )
                }

                // Custom Figma state: Diagnostics
                activeCustomScreen == "diagnostics" -> {
                    DiagnosticsScreen(
                        onBack = { activeCustomScreen = null }
                    )
                }

                // Phone A Requester Flow
                !isGuardian -> {
                    when (currentTab) {
                        AegisNavTab.HOME -> {
                            VictimHomeScreen(
                                onTriggerSos = {
                                    val activeNum = EmergencyContactManager.savedContactPhone.value
                                        ?: EmergencyOrchestrator.emergencyRecipientNumber.value
                                    executeWithSosPermissions {
                                        EmergencyOrchestrator.triggerEmergency(context, activeNum, "manual")
                                    }
                                },
                                onNavigateToVoice = { currentTab = AegisNavTab.VOICE },
                                onNavigateToDiagnostics = { activeCustomScreen = "diagnostics" },
                                onNavigateToContacts = { currentTab = AegisNavTab.CONTACTS },
                                onNavigateToHistory = { currentTab = AegisNavTab.HISTORY },
                                onMenu = { showMenuDrawer = true }
                            )
                        }

                        AegisNavTab.VOICE -> {
                            VoiceSafetyScreen(
                                onTriggerVoiceTest = {
                                    val activeNum = EmergencyContactManager.savedContactPhone.value
                                        ?: EmergencyOrchestrator.emergencyRecipientNumber.value
                                    executeWithSosPermissions {
                                        EmergencyOrchestrator.triggerEmergency(context, activeNum, "voice")
                                    }
                                },
                                onMenu = { showMenuDrawer = true }
                            )
                        }

                        AegisNavTab.CONTACTS -> {
                            EmergencyContactScreen(
                                onContactSaved = { currentTab = AegisNavTab.HOME },
                                onMenu = { showMenuDrawer = true }
                            )
                        }

                        AegisNavTab.HISTORY -> {
                            EmergencyHistoryScreen(
                                onSelectEvent = { activeCustomScreen = "details" },
                                onMenu = { showMenuDrawer = true }
                            )
                        }

                        AegisNavTab.SETTINGS -> {
                            SettingsScreen(
                                onSwitchRole = {
                                    promptRoleSwitch(DeviceRoleManager.ROLE_PHONE_B)
                                },
                                onNavigateToContacts = { currentTab = AegisNavTab.CONTACTS },
                                onNavigateToVoice = { currentTab = AegisNavTab.VOICE },
                                onNavigateToDiagnostics = { activeCustomScreen = "diagnostics" },
                                onMenu = { showMenuDrawer = true }
                            )
                        }

                        else -> {
                            VictimHomeScreen(
                                onTriggerSos = {
                                    val activeNum = EmergencyContactManager.savedContactPhone.value
                                        ?: EmergencyOrchestrator.emergencyRecipientNumber.value
                                    executeWithSosPermissions {
                                        EmergencyOrchestrator.triggerEmergency(context, activeNum, "manual")
                                    }
                                },
                                onNavigateToVoice = { currentTab = AegisNavTab.VOICE },
                                onNavigateToDiagnostics = { activeCustomScreen = "diagnostics" },
                                onNavigateToContacts = { currentTab = AegisNavTab.CONTACTS },
                                onNavigateToHistory = { currentTab = AegisNavTab.HISTORY },
                                onMenu = { showMenuDrawer = true }
                            )
                        }
                    }
                }

                // Phone B Guardian Flow
                else -> {
                    when (currentTab) {
                        AegisNavTab.HOME -> {
                            GuardianDashboardScreen(
                                onOpenIncomingAlert = { activeCustomScreen = "alert" },
                                onJoinCallByEventId = { eventId ->
                                    val sanitized = EmergencyUtils.sanitizeEventIdInput(eventId) ?: eventId.trim()
                                    executeWithVoicePermissions {
                                        LiveKitCallManager.startCall(
                                            context = context,
                                            eventId = sanitized,
                                            deviceId = "phone-b",
                                            displayName = "Phone B (Guardian)"
                                        )
                                    }
                                },
                                onNavigateToHistory = { currentTab = AegisNavTab.HISTORY },
                                onMenu = { showMenuDrawer = true }
                            )
                        }

                        AegisNavTab.ALERTS -> {
                            IncomingAlertScreen(
                                onAnswerCall = {
                                    val incomingInv = CallSignalingClient.activeIncomingInvitation.value
                                    if (incomingInv != null) {
                                        executeWithVoicePermissions {
                                            CallSignalingClient.answerInvitation(
                                                context = context,
                                                invitationId = incomingInv.invitationId,
                                                deviceId = CallSignalingClient.currentDeviceId.value,
                                                displayName = "Phone B (Guardian)"
                                            )
                                        }
                                    }
                                    activeCustomScreen = "call"
                                },
                                onViewMap = {
                                    val loc = EmergencyOrchestrator.currentLocation.value
                                    if (loc != null && loc.isValid()) {
                                        try {
                                            uriHandler.openUri(loc.toGoogleMapsUrl())
                                        } catch (_: Exception) {}
                                    }
                                },
                                onDismiss = { currentTab = AegisNavTab.HOME }
                            )
                        }

                        AegisNavTab.HISTORY -> {
                            EmergencyHistoryScreen(
                                onSelectEvent = { activeCustomScreen = "details" },
                                onMenu = { showMenuDrawer = true }
                            )
                        }

                        AegisNavTab.SETTINGS -> {
                            SettingsScreen(
                                onSwitchRole = {
                                    promptRoleSwitch(DeviceRoleManager.ROLE_PHONE_A)
                                },
                                onNavigateToContacts = {
                                    Toast.makeText(context, "Emergency contacts are configured by Phone A.", Toast.LENGTH_SHORT).show()
                                },
                                onNavigateToVoice = {
                                    Toast.makeText(context, "Voice protection is managed by Phone A.", Toast.LENGTH_SHORT).show()
                                },
                                onNavigateToDiagnostics = { activeCustomScreen = "diagnostics" },
                                onMenu = { showMenuDrawer = true }
                            )
                        }

                        else -> {
                            GuardianDashboardScreen(
                                onOpenIncomingAlert = { activeCustomScreen = "alert" },
                                onJoinCallByEventId = { eventId ->
                                    val sanitized = EmergencyUtils.sanitizeEventIdInput(eventId) ?: eventId.trim()
                                    executeWithVoicePermissions {
                                        LiveKitCallManager.startCall(
                                            context = context,
                                            eventId = sanitized,
                                            deviceId = "phone-b",
                                            displayName = "Phone B (Guardian)"
                                        )
                                    }
                                },
                                onNavigateToHistory = { currentTab = AegisNavTab.HISTORY },
                                onMenu = { showMenuDrawer = true }
                            )
                        }
                    }
                }
            }
        }
    }

    // Modal Screen Index Drawer
    if (showMenuDrawer) {
        ScreenIndexDrawer(
            onSelectScreen = { screenId ->
                showMenuDrawer = false
                when (screenId) {
                    "role" -> {
                        promptRoleSwitch(if (isGuardian) DeviceRoleManager.ROLE_PHONE_A else DeviceRoleManager.ROLE_PHONE_B)
                    }
                    "victim" -> {
                        coroutineScope.launch {
                            DeviceRoleManager.switchRoleSafely(context, DeviceRoleManager.ROLE_PHONE_A)
                            currentTab = AegisNavTab.HOME
                            activeCustomScreen = null
                        }
                    }
                    "confirm" -> {
                        // Handled natively via SOS button
                        currentTab = AegisNavTab.HOME
                        activeCustomScreen = null
                    }
                    "active" -> {
                        activeCustomScreen = "active"
                    }
                    "voice" -> {
                        currentTab = AegisNavTab.VOICE
                        activeCustomScreen = null
                    }
                    "contacts" -> {
                        currentTab = AegisNavTab.CONTACTS
                        activeCustomScreen = null
                    }
                    "guardian" -> {
                        coroutineScope.launch {
                            DeviceRoleManager.switchRoleSafely(context, DeviceRoleManager.ROLE_PHONE_B)
                            currentTab = AegisNavTab.HOME
                            activeCustomScreen = null
                        }
                    }
                    "alert" -> {
                        activeCustomScreen = "alert"
                    }
                    "call" -> {
                        activeCustomScreen = "call"
                    }
                    "history" -> {
                        currentTab = AegisNavTab.HISTORY
                        activeCustomScreen = null
                    }
                    "details" -> {
                        activeCustomScreen = "details"
                    }
                    "diagnostics" -> {
                        activeCustomScreen = "diagnostics"
                    }
                    "settings" -> {
                        currentTab = AegisNavTab.SETTINGS
                        activeCustomScreen = null
                    }
                }
            },
            onClose = { showMenuDrawer = false }
        )
    }

    // Role Switch Confirmation Dialog
    if (showRoleChangeDialog) {
        val targetRole = pendingRoleSwitchTarget ?: (if (isGuardian) DeviceRoleManager.ROLE_PHONE_A else DeviceRoleManager.ROLE_PHONE_B)
        val targetLabel = if (targetRole == DeviceRoleManager.ROLE_PHONE_A) "Phone A (Emergency Requester)" else "Phone B (Trusted Contact Guardian)"
        val desc = if (targetRole == DeviceRoleManager.ROLE_PHONE_A) {
            "This device will trigger SOS alerts (voice & manual), dispatch emergency SIM SMS with GPS coordinates, and initiate emergency audio calls."
        } else {
            "This device will receive emergency alert notifications, display incoming call screens, and answer audio calls as the trusted contact."
        }

        AlertDialog(
            onDismissRequest = {
                showRoleChangeDialog = false
                pendingRoleSwitchTarget = null
            },
            containerColor = AegisWhite,
            title = {
                Text(
                    text = "Switch Device Role",
                    fontWeight = FontWeight.Bold,
                    color = AegisNavy
                )
            },
            text = {
                Text(
                    text = "Switch role to $targetLabel?\n\n$desc\n\nThis role is saved persistently on this device.",
                    color = AegisNavy,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRoleChangeDialog = false
                        coroutineScope.launch {
                            DeviceRoleManager.switchRoleSafely(context, targetRole)
                            Toast.makeText(context, "Role updated to $targetLabel", Toast.LENGTH_SHORT).show()
                            pendingRoleSwitchTarget = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AegisNavy)
                ) {
                    Text("Switch Role", color = AegisWhite)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showRoleChangeDialog = false
                        pendingRoleSwitchTarget = null
                    }
                ) {
                    Text("Cancel", color = AegisNavy)
                }
            }
        )
    }

    // Active Emergency Session Role Switch Warning Dialog
    if (showRoleSwitchWarningDialog) {
        val targetRole = pendingRoleSwitchTarget ?: (if (isGuardian) DeviceRoleManager.ROLE_PHONE_A else DeviceRoleManager.ROLE_PHONE_B)
        val targetLabel = if (targetRole == DeviceRoleManager.ROLE_PHONE_A) "PHONE A" else "PHONE B"

        AlertDialog(
            onDismissRequest = {
                showRoleSwitchWarningDialog = false
                pendingRoleSwitchTarget = null
            },
            containerColor = AegisWhite,
            title = {
                Text(
                    text = "Active Emergency Alert",
                    fontWeight = FontWeight.Bold,
                    color = AegisRed
                )
            },
            text = {
                Text(
                    text = "An emergency session or audio call is currently active. " +
                            "Switching to $targetLabel will safely terminate the active call, " +
                            "cancel ongoing notifications, stop background voice detection, and reconfigure device signaling.\n\n" +
                            "Do you wish to proceed?",
                    color = AegisNavy,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRoleSwitchWarningDialog = false
                        coroutineScope.launch {
                            DeviceRoleManager.switchRoleSafely(context, targetRole)
                            Toast.makeText(context, "Role switched to $targetLabel", Toast.LENGTH_SHORT).show()
                            pendingRoleSwitchTarget = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = AegisRed)
                ) {
                    Text("Terminate & Switch", color = AegisWhite)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showRoleSwitchWarningDialog = false
                        pendingRoleSwitchTarget = null
                    }
                ) {
                    Text("Cancel", color = AegisNavy)
                }
            }
        )
    }
}
