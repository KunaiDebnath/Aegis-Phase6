package com.example.aegis

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Dedicated Role Selection Screen shown when no role has been chosen yet on this physical device.
 * Once a selection is made, it is persistently remembered on this phone.
 */
@Composable
fun RoleSelectionScreen(
    onRoleSelected: (String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isSelecting by remember { mutableStateOf(false) }
    var selectedTarget by remember { mutableStateOf<String?>(null) }
    var backendUrlInput by remember { mutableStateOf(AegisApiClient.baseUrl) }

    val discoveredUrl = ServerDiscoveryManager.connectedUrl.value
    LaunchedEffect(Unit) {
        ServerDiscoveryManager.startDiscovery(context)
    }
    LaunchedEffect(discoveredUrl) {
        if (!discoveredUrl.isNullOrBlank()) {
            backendUrlInput = discoveredUrl
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // App Logo & Header
        Text(
            text = "AEGIS",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 36.sp
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Your Voice. Your Safety. Your Lifeline.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Device Role Setup",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "Select how this phone will be used. Each device stores its own role locally and will remember it on future launches.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Role Card: Phone A (Emergency Requester)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 2.dp,
                    color = Color(0xFFD32F2F).copy(alpha = 0.6f),
                    shape = RoundedCornerShape(14.dp)
                ),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFFFFEBEE).copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Phone A — Requester",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFB71C1C)
                    )

                    Box(
                        modifier = Modifier
                            .background(Color(0xFFD32F2F), shape = RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "EMERGENCY USER",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "For the person who may need emergency protection and assistance.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("• Hands-free voice emergency detection (\"I need help\")", style = MaterialTheme.typography.bodySmall)
                    Text("• Big red one-tap Manual SOS trigger", style = MaterialTheme.typography.bodySmall)
                    Text("• Automatic GPS coordinate fix & Google Maps link", style = MaterialTheme.typography.bodySmall)
                    Text("• SIM-based cellular SMS alert broadcast", style = MaterialTheme.typography.bodySmall)
                    Text("• Two-way LiveKit WebRTC audio calling", style = MaterialTheme.typography.bodySmall)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        if (!isSelecting) {
                            isSelecting = true
                            selectedTarget = DeviceRoleManager.ROLE_PHONE_A
                            coroutineScope.launch {
                                DeviceRoleManager.selectRole(context, DeviceRoleManager.ROLE_PHONE_A)
                                isSelecting = false
                                onRoleSelected(DeviceRoleManager.ROLE_PHONE_A)
                            }
                        }
                    },
                    enabled = !isSelecting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFD32F2F),
                        contentColor = Color.White
                    )
                ) {
                    if (isSelecting && selectedTarget == DeviceRoleManager.ROLE_PHONE_A) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("SELECT THIS PHONE AS PHONE A", fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Role Card: Phone B (Trusted Contact Responder)
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 2.dp,
                    color = Color(0xFF1976D2).copy(alpha = 0.6f),
                    shape = RoundedCornerShape(14.dp)
                ),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFFE3F2FD).copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Phone B — Responder",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0D47A1)
                    )

                    Box(
                        modifier = Modifier
                            .background(Color(0xFF1976D2), shape = RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = "TRUSTED CONTACT",
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "For the trusted contact, guardian, or family member responding to alerts.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("• High-priority real-time emergency call notifications", style = MaterialTheme.typography.bodySmall)
                    Text("• Lock-screen wake and full-screen incoming alert ringing", style = MaterialTheme.typography.bodySmall)
                    Text("• One-tap Call Answer & Dismiss action buttons", style = MaterialTheme.typography.bodySmall)
                    Text("• Join emergency calls using Event ID from SMS alert", style = MaterialTheme.typography.bodySmall)
                    Text("• Live two-way WebRTC audio connection to help requester", style = MaterialTheme.typography.bodySmall)
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        if (!isSelecting) {
                            isSelecting = true
                            selectedTarget = DeviceRoleManager.ROLE_PHONE_B
                            coroutineScope.launch {
                                DeviceRoleManager.selectRole(context, DeviceRoleManager.ROLE_PHONE_B)
                                isSelecting = false
                                onRoleSelected(DeviceRoleManager.ROLE_PHONE_B)
                            }
                        }
                    },
                    enabled = !isSelecting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1976D2),
                        contentColor = Color.White
                    )
                ) {
                    if (isSelecting && selectedTarget == DeviceRoleManager.ROLE_PHONE_B) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("SELECT THIS PHONE AS PHONE B", fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Backend URL Settings for Setup Verification
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
                        text = "Automatic Server Discovery",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    val discState = ServerDiscoveryManager.state.value
                    Box(
                        modifier = Modifier
                            .background(
                                color = when (discState) {
                                    DiscoveryState.CONNECTED -> Color(0xFF2E7D32)
                                    DiscoveryState.DISCOVERING -> Color(0xFF1976D2)
                                    DiscoveryState.VALIDATING -> Color(0xFFF57C00)
                                    DiscoveryState.FAILED -> Color(0xFFC62828)
                                    DiscoveryState.IDLE -> Color(0xFF757575)
                                },
                                shape = RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = when (discState) {
                                DiscoveryState.CONNECTED -> "FOUND"
                                DiscoveryState.DISCOVERING -> "SCANNING..."
                                DiscoveryState.VALIDATING -> "VALIDATING"
                                DiscoveryState.FAILED -> "NOT FOUND"
                                DiscoveryState.IDLE -> "IDLE"
                            },
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = ServerDiscoveryManager.statusMessage.value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            ServerDiscoveryManager.startDiscovery(context, forceFullDiscovery = true)
                        },
                        enabled = !ServerDiscoveryManager.isAutoDiscovering.value,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        if (ServerDiscoveryManager.isAutoDiscovering.value) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text("AUTO DISCOVER", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                ServerDiscoveryManager.applyManualEndpoint(context, backendUrlInput)
                            }
                        },
                        enabled = !ServerDiscoveryManager.isAutoDiscovering.value,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("TEST & SAVE", fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = backendUrlInput,
                    onValueChange = {
                        backendUrlInput = it
                        AegisApiClient.baseUrl = it
                        AegisPreferences.setBaseUrl(context, it)
                    },
                    label = { Text("Backend URL (Manual Override)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
