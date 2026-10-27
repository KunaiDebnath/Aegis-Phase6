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
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import android.widget.Toast
import com.example.aegis.ui.theme.AEGISTheme

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

        // Configure window to display over lock screen and wake the screen for incoming emergency calls
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

        // Initialize real-time incoming call signaling & restore saved preferences
        CallSignalingClient.start(this)

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
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    AegisMainScreen(paddingValues = padding)
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
        val callerName = intent.getStringExtra(EmergencyCallNotificationManager.EXTRA_CALLER_NAME) ?: "Phone A (Requester)"
        val action = intent.action

        if (!invitationId.isNullOrBlank()) {
            // Keep screen illuminated while incoming call alert is active
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            if (action == EmergencyCallNotificationManager.ACTION_ANSWER_CALL) {
                android.util.Log.i("AEGIS_Main", "Notification ANSWER action received for invitation: $invitationId")
                CallSignalingClient.answerInvitation(
                    context = this,
                    invitationId = invitationId,
                    deviceId = CallSignalingClient.currentDeviceId.value,
                    displayName = "Phone B (Trusted Contact)"
                )
            } else {
                android.util.Log.i("AEGIS_Main", "Incoming call full-screen alert / body tap received for: $invitationId")
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
fun AegisMainScreen(paddingValues: androidx.compose.foundation.layout.PaddingValues) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val clipboardManager = LocalClipboardManager.current

    var recipientNumberInput by remember { mutableStateOf(EmergencyOrchestrator.emergencyRecipientNumber.value) }
    var configuredPhraseInput by remember { mutableStateOf(EmergencyOrchestrator.configuredPhrase.value) }
    var responderEventIdInput by remember { mutableStateOf("") }
    var responderValidationError by remember { mutableStateOf<String?>(null) }
    var backendUrlInput by remember { mutableStateOf(AegisApiClient.baseUrl) }
    var validationError by remember { mutableStateOf<String?>(null) }
    var showDeliberateRetryDialog by remember { mutableStateOf(false) }

    // Launcher for SOS permissions (SMS + GPS Location)
    var pendingSosAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val sosPermissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val smsOk = perms[Manifest.permission.SEND_SMS] == true
        if (smsOk) {
            pendingSosAction?.invoke()
        } else {
            validationError = "SEND_SMS permission is required to send emergency alerts."
        }
        pendingSosAction = null
    }

    // Launcher for Voice Protection permissions (RECORD_AUDIO + POST_NOTIFICATIONS)
    var pendingVoiceAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val voicePermissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val micOk = perms[Manifest.permission.RECORD_AUDIO] == true
        if (micOk) {
            pendingVoiceAction?.invoke()
        } else {
            validationError = "Microphone (RECORD_AUDIO) permission is required for voice emergency detection."
        }
        pendingVoiceAction = null
    }

    // Launcher for Notification Permission (Android 13+ / API 33+)
    var hasNotifPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasNotifPermission = isGranted
        if (!isGranted) {
            Toast.makeText(
                context,
                "Notification permission denied. Incoming calls will only show inside app.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun executeWithSosPermissions(action: () -> Unit) {
        val hasSms = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED

        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasMic = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasSms && (hasFine || hasCoarse) && hasMic) {
            action()
        } else {
            pendingSosAction = action
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
        val hasMic = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val hasNotif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        if (hasMic && hasNotif) {
            action()
        } else {
            pendingVoiceAction = action
            val permsList = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permsList.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            voicePermissionsLauncher.launch(permsList.toTypedArray())
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App Header
        Text(
            text = "AEGIS",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Your Voice. Your Safety. Your Lifeline.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(18.dp))

        // Notification Permission Alert (Android 13+)
        if (!hasNotifPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "⚠️ Notification Permission Required",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE65100),
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Notifications are disabled. Grant permission so incoming emergency calls can alert you when the screen is locked.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF5D4037)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF57C00))
                    ) {
                        Text("GRANT NOTIFICATION PERMISSION")
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
        }

        // Full-Screen Intent Permission Alert (Android 14+)
        val canFullScreen = remember(context) {
            EmergencyCallNotificationManager.canUseFullScreenIntent(context)
        }
        if (!canFullScreen && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "⚠️ Full-Screen Call Alert Disabled",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE65100),
                        fontSize = 14.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Full-screen alerts are restricted on this device. Enable 'Full Screen Intent' in App Settings to allow emergency calls to wake the locked screen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF5D4037)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            try {
                                val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                                context.startActivity(fallback)
                            }
                        },
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF57C00))
                    ) {
                        Text("OPEN SETTINGS TO ALLOW FULL-SCREEN")
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
        }

        // Active Incoming Emergency Call Card (Phone B Responder Alert)
        val incomingInv = CallSignalingClient.activeIncomingInvitation.value
        if (incomingInv != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "🚨 INCOMING EMERGENCY CALL",
                        color = Color(0xFFC62828),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "${incomingInv.callerDisplayName ?: incomingInv.callerDeviceId} needs immediate assistance!",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = Color(0xFFB71C1C)
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Event ID: ${incomingInv.emergencyEventId.take(12)}...",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color(0xFF546E7A)
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Auto-expires in ${CallSignalingClient.remainingSeconds.value} seconds",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = Color(0xFFD84315)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                executeWithVoicePermissions {
                                    CallSignalingClient.answerInvitation(
                                        context = context,
                                        invitationId = incomingInv.invitationId,
                                        deviceId = CallSignalingClient.currentDeviceId.value,
                                        displayName = "Phone B (Trusted Contact)"
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                        ) {
                            Text("ANSWER CALL", fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                CallSignalingClient.dismissInvitation(
                                    context = context,
                                    invitationId = incomingInv.invitationId,
                                    deviceId = CallSignalingClient.currentDeviceId.value
                                )
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                        ) {
                            Text("DISMISS")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))
        }

        // Device Role & Incoming Call Signaling Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            ),
            shape = RoundedCornerShape(10.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Device Role & Call Signaling",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    Box(
                        modifier = Modifier
                            .background(
                                color = if (CallSignalingClient.signalingConnected.value) Color(0xFF2E7D32) else Color(0xFFE65100),
                                shape = RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = if (CallSignalingClient.signalingConnected.value) "CONNECTED" else "OFFLINE",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = CallSignalingClient.signalingStatusMessage.value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = CallSignalingClient.fcmStatusMessage.value,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    OutlinedButton(
                        onClick = { CallSignalingClient.syncFcmToken() },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text("SYNC FCM", fontSize = 10.sp)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val currentDev = CallSignalingClient.currentDeviceId.value
                    Button(
                        onClick = { CallSignalingClient.setDeviceId("phone-a") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (currentDev == "phone-a") MaterialTheme.colorScheme.primary else Color(0xFF757575)
                        )
                    ) {
                        Text("ROLE: PHONE A")
                    }

                    Button(
                        onClick = { CallSignalingClient.setDeviceId("phone-b") },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (currentDev == "phone-b") MaterialTheme.colorScheme.primary else Color(0xFF757575)
                        )
                    ) {
                        Text("ROLE: PHONE B")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Recipient Configuration Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Emergency Recipient (Phone B)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = recipientNumberInput,
                    onValueChange = {
                        recipientNumberInput = it
                        EmergencyOrchestrator.emergencyRecipientNumber.value = it
                        validationError = null
                    },
                    label = { Text("Phone B Number (e.g. +1234567890)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (validationError != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = validationError!!,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Voice Protection Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            ),
            shape = RoundedCornerShape(10.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Voice Safety Protection",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    Box(
                        modifier = Modifier
                            .background(
                                color = when (EmergencyOrchestrator.voiceStatus.value) {
                                    VoiceProtectionStatus.DISABLED -> Color(0xFF757575)
                                    VoiceProtectionStatus.STARTING -> Color(0xFF0288D1)
                                    VoiceProtectionStatus.LISTENING -> Color(0xFF2E7D32)
                                    VoiceProtectionStatus.PAUSED_FOR_CALL -> Color(0xFFF57C00)
                                    VoiceProtectionStatus.ERROR -> Color(0xFFD32F2F)
                                    VoiceProtectionStatus.TRIGGERED -> Color(0xFFC62828)
                                },
                                shape = RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = when (EmergencyOrchestrator.voiceStatus.value) {
                                VoiceProtectionStatus.DISABLED -> "DISABLED"
                                VoiceProtectionStatus.STARTING -> "STARTING"
                                VoiceProtectionStatus.LISTENING -> "LISTENING"
                                VoiceProtectionStatus.PAUSED_FOR_CALL -> "PAUSED (IN CALL)"
                                VoiceProtectionStatus.ERROR -> "ERROR"
                                VoiceProtectionStatus.TRIGGERED -> "TRIGGERED"
                            },
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = configuredPhraseInput,
                    onValueChange = {
                        configuredPhraseInput = it
                        EmergencyOrchestrator.configuredPhrase.value = it
                    },
                    label = { Text("Configured Emergency Phrase") },
                    singleLine = true,
                    enabled = EmergencyOrchestrator.voiceStatus.value == VoiceProtectionStatus.DISABLED,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = EmergencyOrchestrator.voiceStatusMessage.value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(10.dp))

                if (EmergencyOrchestrator.voiceStatus.value == VoiceProtectionStatus.DISABLED ||
                    EmergencyOrchestrator.voiceStatus.value == VoiceProtectionStatus.ERROR
                ) {
                    Button(
                        onClick = {
                            if (recipientNumberInput.isBlank()) {
                                validationError = "Configure Phone B's number before enabling voice protection."
                            } else if (!EmergencyUtils.isValidPhoneNumber(recipientNumberInput)) {
                                validationError = "Invalid phone number format."
                            } else {
                                validationError = null
                                executeWithVoicePermissions {
                                    VoiceSafetyService.start(context)
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("ENABLE VOICE PROTECTION")
                    }
                } else {
                    OutlinedButton(
                        onClick = {
                            VoiceSafetyService.stop(context)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("DISABLE VOICE PROTECTION")
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Runs as foreground service with microphone access. Deep lock-screen listening may be paused by device battery management.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Manual SOS Trigger Area
        if (!EmergencyOrchestrator.isSosActive.value) {
            Button(
                onClick = {
                    if (recipientNumberInput.isBlank()) {
                        validationError = "Please enter Phone B's number."
                    } else if (!EmergencyUtils.isValidPhoneNumber(recipientNumberInput)) {
                        validationError = "Invalid phone number. Include 7 to 15 digits."
                    } else {
                        validationError = null
                        executeWithSosPermissions {
                            EmergencyOrchestrator.triggerEmergency(context, recipientNumberInput, "manual")
                        }
                    }
                },
                enabled = !EmergencyOrchestrator.isInitiating.value,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFD32F2F),
                    contentColor = Color.White
                )
            ) {
                if (EmergencyOrchestrator.isInitiating.value) {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("TRIGGERING SOS & GPS...", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                } else {
                    Text("TRIGGER MANUAL SOS", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            }
        } else {
            // Active Emergency Session Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFFFEBEE)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "EMERGENCY ALERT TRIGGERED",
                        color = Color(0xFFC62828),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Trigger Source: ${EmergencyOrchestrator.activeTriggerSource.value.uppercase()}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = Color(0xFFB71C1C)
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Idempotency Ref: ${EmergencyOrchestrator.currentIdempotencyKey.value.take(12)}...",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF37474F)
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { EmergencyOrchestrator.startNewSosEvent() },
                            enabled = !EmergencyOrchestrator.isInitiating.value,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("NEW SOS EVENT")
                        }

                        if (EmergencyOrchestrator.backendStatus.value == BackendSyncStatus.FAILED) {
                            OutlinedButton(
                                onClick = { EmergencyOrchestrator.retryBackend(context) },
                                enabled = !EmergencyOrchestrator.isInitiating.value,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("RETRY BACKEND")
                            }
                        }

                        if (EmergencyOrchestrator.smsStatus.value == SmsDeliveryStatus.SENT_FAILED ||
                            EmergencyOrchestrator.smsStatus.value == SmsDeliveryStatus.DELIVERY_FAILED
                        ) {
                            OutlinedButton(
                                onClick = { showDeliberateRetryDialog = true },
                                enabled = !EmergencyOrchestrator.isInitiating.value,
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("RETRY SMS")
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Independent Status 1: Location Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            ),
            shape = RoundedCornerShape(10.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "1. Emergency GPS Location",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    Box(
                        modifier = Modifier
                            .background(
                                color = when (EmergencyOrchestrator.locationStatus.value) {
                                    LocationAcquisitionStatus.IDLE -> Color(0xFF757575)
                                    LocationAcquisitionStatus.ACQUIRING -> Color(0xFF1976D2)
                                    LocationAcquisitionStatus.SUCCESS -> Color(0xFF2E7D32)
                                    LocationAcquisitionStatus.UNAVAILABLE -> Color(0xFFF57C00)
                                    LocationAcquisitionStatus.TIMEOUT -> Color(0xFFE65100)
                                    LocationAcquisitionStatus.PERMISSION_DENIED -> Color(0xFFC62828)
                                },
                                shape = RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = when (EmergencyOrchestrator.locationStatus.value) {
                                LocationAcquisitionStatus.IDLE -> "IDLE"
                                LocationAcquisitionStatus.ACQUIRING -> "ACQUIRING..."
                                LocationAcquisitionStatus.SUCCESS -> if (EmergencyOrchestrator.isLocationStale.value) "ACQUIRED (CACHED)" else "ACQUIRED (FRESH)"
                                LocationAcquisitionStatus.UNAVAILABLE -> "UNAVAILABLE"
                                LocationAcquisitionStatus.TIMEOUT -> "TIMEOUT"
                                LocationAcquisitionStatus.PERMISSION_DENIED -> "PERMISSION DENIED"
                            },
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = EmergencyOrchestrator.locationMessage.value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                val loc = EmergencyOrchestrator.currentLocation.value
                if (loc != null && loc.isValid()) {
                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Backend status: " + when (EmergencyOrchestrator.backendStatus.value) {
                            BackendSyncStatus.RECORDED -> "Coordinates stored in database"
                            BackendSyncStatus.FAILED -> "Local fix only (backend offline)"
                            BackendSyncStatus.SENDING -> "Transmitting to backend..."
                            BackendSyncStatus.IDLE -> "Not uploaded"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            try {
                                uriHandler.openUri(loc.toGoogleMapsUrl())
                            } catch (_: Exception) {}
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("OPEN COORDINATES IN GOOGLE MAPS")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Independent Status 2: Backend Status Card
        StatusCard(
            title = "2. Backend Database Status",
            badgeText = when (EmergencyOrchestrator.backendStatus.value) {
                BackendSyncStatus.IDLE -> "IDLE"
                BackendSyncStatus.SENDING -> "TRANSMITTING"
                BackendSyncStatus.RECORDED -> "SAVED (201)"
                BackendSyncStatus.FAILED -> "FAILED"
            },
            badgeColor = when (EmergencyOrchestrator.backendStatus.value) {
                BackendSyncStatus.IDLE -> Color(0xFF757575)
                BackendSyncStatus.SENDING -> Color(0xFF1976D2)
                BackendSyncStatus.RECORDED -> Color(0xFF2E7D32)
                BackendSyncStatus.FAILED -> Color(0xFFC62828)
            },
            detailMessage = EmergencyOrchestrator.backendMessage.value,
            additionalInfo = if (!EmergencyOrchestrator.backendEventId.value.isNullOrBlank()) "Event ID: ${EmergencyOrchestrator.backendEventId.value}" else null,
            actionButton = if (!EmergencyOrchestrator.backendEventId.value.isNullOrBlank()) {
                {
                    val id = EmergencyOrchestrator.backendEventId.value!!
                    clipboardManager.setText(AnnotatedString(id))
                    Toast.makeText(context, "Event ID copied to clipboard", Toast.LENGTH_SHORT).show()
                }
            } else null,
            actionButtonLabel = "COPY EVENT ID TO CLIPBOARD"
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Independent Status 3: SMS Status Card
        StatusCard(
            title = "3. Phone A -> Phone B SMS Alert",
            badgeText = when (EmergencyOrchestrator.smsStatus.value) {
                SmsDeliveryStatus.IDLE -> "IDLE"
                SmsDeliveryStatus.INITIATED -> "1. INITIATED"
                SmsDeliveryStatus.ACCEPTED_BY_TELEPHONY -> "2. ACCEPTED BY TELEPHONY"
                SmsDeliveryStatus.SENT_SUCCESS -> "3. SENT (CARRIER OK)"
                SmsDeliveryStatus.SENT_FAILED -> "SEND FAILED"
                SmsDeliveryStatus.DELIVERED -> "4. DELIVERED"
                SmsDeliveryStatus.DELIVERY_FAILED -> "DELIVERY UNCONFIRMED"
            },
            badgeColor = when (EmergencyOrchestrator.smsStatus.value) {
                SmsDeliveryStatus.IDLE -> Color(0xFF757575)
                SmsDeliveryStatus.INITIATED -> Color(0xFF0288D1)
                SmsDeliveryStatus.ACCEPTED_BY_TELEPHONY -> Color(0xFFF57C00)
                SmsDeliveryStatus.SENT_SUCCESS -> Color(0xFF388E3C)
                SmsDeliveryStatus.SENT_FAILED -> Color(0xFFD32F2F)
                SmsDeliveryStatus.DELIVERED -> Color(0xFF1B5E20)
                SmsDeliveryStatus.DELIVERY_FAILED -> Color(0xFFE65100)
            },
            detailMessage = EmergencyOrchestrator.smsMessage.value,
            additionalInfo = if (EmergencyOrchestrator.lastSmsContent.value.isNotBlank()) "Dispatched: \"${EmergencyOrchestrator.lastSmsContent.value}\"" else null
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Independent Status 4: Two-Way Audio Calling Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            ),
            shape = RoundedCornerShape(10.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "4. LiveKit Two-Way Audio Call",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    Box(
                        modifier = Modifier
                            .background(
                                color = when (LiveKitCallManager.callState.value) {
                                    CallState.IDLE -> Color(0xFF757575)
                                    CallState.REQUESTING_TOKEN -> Color(0xFF0288D1)
                                    CallState.CONNECTING -> Color(0xFF1976D2)
                                    CallState.WAITING_FOR_PARTICIPANT -> Color(0xFFF57C00)
                                    CallState.CONNECTED -> Color(0xFF2E7D32)
                                    CallState.RECONNECTING -> Color(0xFFE65100)
                                    CallState.ENDED -> Color(0xFF616161)
                                    CallState.FAILED -> Color(0xFFD32F2F)
                                },
                                shape = RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = when (LiveKitCallManager.callState.value) {
                                CallState.IDLE -> "IDLE"
                                CallState.REQUESTING_TOKEN -> "GETTING TOKEN"
                                CallState.CONNECTING -> "CONNECTING"
                                CallState.WAITING_FOR_PARTICIPANT -> "WAITING FOR PEER"
                                CallState.CONNECTED -> "CONNECTED (2-WAY)"
                                CallState.RECONNECTING -> "RECONNECTING"
                                CallState.ENDED -> "ENDED"
                                CallState.FAILED -> "FAILED"
                            },
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = LiveKitCallManager.callStatusMessage.value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )

                if (LiveKitCallManager.activeRoomName.value != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Room: ${LiveKitCallManager.activeRoomName.value}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (LiveKitCallManager.remoteParticipantName.value != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Connected with: ${LiveKitCallManager.remoteParticipantName.value}",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        color = Color(0xFF2E7D32)
                    )
                }

                if (CallSignalingClient.callerFeedbackMessage.value != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = CallSignalingClient.callerFeedbackMessage.value!!,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp,
                        color = Color(0xFF0277BD)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (LiveKitCallManager.isCallActive) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { LiveKitCallManager.toggleMute() },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (LiveKitCallManager.isMicMuted.value) Color(0xFFD32F2F) else Color(0xFF388E3C)
                            )
                        ) {
                            Text(if (LiveKitCallManager.isMicMuted.value) "UNMUTE MIC" else "MUTE MIC")
                        }

                        Button(
                            onClick = { LiveKitCallManager.endCall(context) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF757575)
                            )
                        ) {
                            Text("LEAVE CALL")
                        }
                    }
                } else if (EmergencyOrchestrator.backendEventId.value != null) {
                    OutlinedButton(
                        onClick = {
                            val rawId = EmergencyOrchestrator.backendEventId.value!!
                            val sanitizedId = EmergencyUtils.sanitizeEventIdInput(rawId) ?: rawId.trim()
                            executeWithVoicePermissions {
                                LiveKitCallManager.startCall(
                                    context = context,
                                    eventId = sanitizedId,
                                    deviceId = "phone-a",
                                    displayName = "Phone A (Requester)"
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("RECONNECT EMERGENCY CALL")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Trusted Contact Responder Mode (Phone B) Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
            ),
            shape = RoundedCornerShape(10.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Trusted Contact Mode (Phone B Responder)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Join an active emergency audio call using the Event ID from the emergency SMS alert.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = responderEventIdInput,
                    onValueChange = {
                        responderEventIdInput = it
                        responderValidationError = null
                    },
                    label = { Text("Emergency Event ID (from SMS)") },
                    singleLine = true,
                    enabled = !LiveKitCallManager.isCallActive,
                    isError = responderValidationError != null,
                    supportingText = if (responderValidationError != null) {
                        { Text(responderValidationError!!, color = MaterialTheme.colorScheme.error) }
                    } else null,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val clip = clipboardManager.getText()?.text
                            if (!clip.isNullOrBlank()) {
                                val sanitized = EmergencyUtils.sanitizeEventIdInput(clip)
                                if (sanitized != null) {
                                    responderEventIdInput = sanitized
                                    responderValidationError = null
                                } else {
                                    responderEventIdInput = clip.trim()
                                    if (clip.length > 36) {
                                        responderValidationError = "Pasted text contains multiple or malformed IDs. Input was replaced with clipboard contents."
                                    }
                                }
                            } else {
                                responderValidationError = "Clipboard is empty."
                            }
                        },
                        enabled = !LiveKitCallManager.isCallActive,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("PASTE FROM CLIPBOARD")
                    }

                    OutlinedButton(
                        onClick = {
                            responderEventIdInput = ""
                            responderValidationError = null
                        },
                        enabled = !LiveKitCallManager.isCallActive && responderEventIdInput.isNotEmpty(),
                        modifier = Modifier.weight(0.5f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("CLEAR")
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (!LiveKitCallManager.isCallActive) {
                    Button(
                        onClick = {
                            val trimmed = responderEventIdInput.trim()
                            if (trimmed.isBlank()) {
                                responderValidationError = "Paste or enter the Event ID from the SMS alert."
                            } else {
                                val sanitized = EmergencyUtils.sanitizeEventIdInput(trimmed)
                                if (sanitized == null) {
                                    if (trimmed.length > 36) {
                                        responderValidationError = "Invalid Event ID: Input is ${trimmed.length} chars (expected 36). Avoid repeating or appending IDs."
                                    } else {
                                        responderValidationError = "Invalid Event ID format. Expected a 36-character UUID (e.g. 12345678-1234-1234-1234-123456789abc)."
                                    }
                                } else {
                                    responderValidationError = null
                                    responderEventIdInput = sanitized
                                    executeWithVoicePermissions {
                                        LiveKitCallManager.startCall(
                                            context = context,
                                            eventId = sanitized,
                                            deviceId = "phone-b",
                                            displayName = "Phone B (Trusted Contact)"
                                        )
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1976D2)
                        )
                    ) {
                        Text("JOIN EMERGENCY CALL AS PHONE B")
                    }
                } else {
                    Text(
                        text = "Phone B is currently in an active audio call session above.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF2E7D32)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Host Network Configuration Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            shape = RoundedCornerShape(10.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "Backend & LiveKit Host Configuration",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Configure laptop LAN IPv4 address (default http://192.168.1.4:8000/). LiveKit server signaling runs on port 7880.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = backendUrlInput,
                    onValueChange = {
                        backendUrlInput = it
                        AegisApiClient.baseUrl = it
                        AegisPreferences.setBaseUrl(context, it)
                        CallSignalingClient.syncFcmToken()
                    },
                    label = { Text("FastAPI Backend URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(16.dp))

        // SIM Transmission Diagnostics (Test SMS)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "SIM Transmission Diagnostics",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = "Send a non-emergency test SMS to verify SIM connection without creating backend records or acquiring GPS.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedButton(
                    onClick = {
                        if (recipientNumberInput.isBlank()) {
                            validationError = "Enter Phone B's number for test SMS."
                        } else if (!EmergencyUtils.isValidPhoneNumber(recipientNumberInput)) {
                            validationError = "Invalid phone number format."
                        } else {
                            validationError = null
                            executeWithSosPermissions {
                                EmergencyOrchestrator.sendTestSms(context, recipientNumberInput)
                            }
                        }
                    },
                    enabled = !EmergencyOrchestrator.isInitiating.value,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("SEND NON-EMERGENCY TEST SMS")
                }

                if (EmergencyOrchestrator.testSmsStatus.value != SmsDeliveryStatus.IDLE) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = EmergencyOrchestrator.testSmsMessage.value,
                        style = MaterialTheme.typography.bodySmall,
                        color = when (EmergencyOrchestrator.testSmsStatus.value) {
                            SmsDeliveryStatus.SENT_FAILED, SmsDeliveryStatus.DELIVERY_FAILED -> Color(0xFFD32F2F)
                            SmsDeliveryStatus.DELIVERED -> Color(0xFF2E7D32)
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
        }
    }

    if (showDeliberateRetryDialog) {
        AlertDialog(
            onDismissRequest = { showDeliberateRetryDialog = false },
            title = { Text("Confirm Emergency SMS Resend") },
            text = {
                Text(
                    "Warning: The previous SMS may have already been transmitted over the carrier network. " +
                            "Resending may result in a duplicate emergency SMS delivered to Phone B. " +
                            "Do you wish to proceed with a deliberate resend?"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeliberateRetryDialog = false
                        executeWithSosPermissions {
                            EmergencyOrchestrator.retrySms(context, recipientNumberInput)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Text("Confirm Resend")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeliberateRetryDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun StatusCard(
    title: String,
    badgeText: String,
    badgeColor: Color,
    detailMessage: String,
    additionalInfo: String?,
    actionButton: (() -> Unit)? = null,
    actionButtonLabel: String = "ACTION"
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )

                Box(
                    modifier = Modifier
                        .background(badgeColor, RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = badgeText,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = detailMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (!additionalInfo.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = additionalInfo,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (actionButton != null) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = actionButton,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(actionButtonLabel)
                }
            }
        }
    }
}
