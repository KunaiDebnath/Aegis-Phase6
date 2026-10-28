package com.example.aegis

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
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aegis.ui.components.AegisCard
import com.example.aegis.ui.components.AegisPrimaryButton
import com.example.aegis.ui.components.AegisStatusPill
import com.example.aegis.ui.components.AegisStatusTone
import com.example.aegis.ui.components.AegisTopBar
import com.example.aegis.ui.theme.AegisBlueSoft
import com.example.aegis.ui.theme.AegisGrey100
import com.example.aegis.ui.theme.AegisGrey200
import com.example.aegis.ui.theme.AegisGrey500
import com.example.aegis.ui.theme.AegisNavy
import com.example.aegis.ui.theme.AegisNavyDark
import com.example.aegis.ui.theme.AegisWhite
import kotlinx.coroutines.launch

/**
 * Dedicated Role Selection Screen matching Figma 'role' design with native Jetpack Compose.
 * Remembers selected role locally on this physical device.
 */
@Composable
fun RoleSelectionScreen(
    onRoleSelected: (String) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isSelecting by remember { mutableStateOf(false) }
    var selectedRole by remember { mutableStateOf(DeviceRoleManager.ROLE_PHONE_A) }
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
            .background(AegisWhite)
    ) {
        AegisTopBar(
            title = "Choose device role",
            subtitle = "You can change this later"
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 22.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "How will you use AEGIS?",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = AegisNavy
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "This selection configures this device. It is not an account sign-in.",
                fontSize = 14.sp,
                color = AegisGrey500,
                lineHeight = 20.sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Role Card 1: Person Needing Help (Phone A)
            val isSelectedA = selectedRole == DeviceRoleManager.ROLE_PHONE_A
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(
                        width = if (isSelectedA) 2.dp else 1.dp,
                        color = if (isSelectedA) AegisNavyDark else AegisGrey200,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .background(if (isSelectedA) AegisBlueSoft else AegisWhite)
                    .clickable { selectedRole = DeviceRoleManager.ROLE_PHONE_A }
                    .padding(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (isSelectedA) AegisWhite else AegisBlueSoft),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = AegisNavyDark,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Person Needing Help",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = AegisNavy
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Send SOS alerts to your trusted guardian.",
                            fontSize = 11.sp,
                            color = AegisGrey500,
                            lineHeight = 16.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Radio circle
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .border(
                                width = if (isSelectedA) 5.dp else 2.dp,
                                color = if (isSelectedA) AegisNavyDark else AegisGrey200,
                                shape = CircleShape
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Role Card 2: Guardian (Phone B)
            val isSelectedB = selectedRole == DeviceRoleManager.ROLE_PHONE_B
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(
                        width = if (isSelectedB) 2.dp else 1.dp,
                        color = if (isSelectedB) AegisNavyDark else AegisGrey200,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .background(if (isSelectedB) AegisBlueSoft else AegisWhite)
                    .clickable { selectedRole = DeviceRoleManager.ROLE_PHONE_B }
                    .padding(14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(if (isSelectedB) AegisWhite else AegisBlueSoft),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = AegisNavyDark,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Guardian",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = AegisNavy
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Receive alerts and calls from a connected person.",
                            fontSize = 11.sp,
                            color = AegisGrey500,
                            lineHeight = 16.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Radio circle
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .border(
                                width = if (isSelectedB) 5.dp else 2.dp,
                                color = if (isSelectedB) AegisNavyDark else AegisGrey200,
                                shape = CircleShape
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            AegisPrimaryButton(
                text = "Continue",
                isLoading = isSelecting,
                onClick = {
                    if (!isSelecting) {
                        isSelecting = true
                        coroutineScope.launch {
                            DeviceRoleManager.selectRole(context, selectedRole)
                            isSelecting = false
                            onRoleSelected(selectedRole)
                        }
                    }
                }
            )

            Spacer(modifier = Modifier.height(28.dp))

            // LAN Server Discovery Card (Clean & Preserved)
            AegisCard {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Automatic Server Discovery",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = AegisNavy
                        )

                        val discState = ServerDiscoveryManager.state.value
                        val pillTone = when (discState) {
                            DiscoveryState.CONNECTED -> AegisStatusTone.GOOD
                            DiscoveryState.DISCOVERING -> AegisStatusTone.WAIT
                            DiscoveryState.VALIDATING -> AegisStatusTone.WAIT
                            DiscoveryState.FAILED -> AegisStatusTone.BAD
                            DiscoveryState.IDLE -> AegisStatusTone.NEUTRAL
                        }
                        val pillLabel = when (discState) {
                            DiscoveryState.CONNECTED -> "Found"
                            DiscoveryState.DISCOVERING -> "Scanning"
                            DiscoveryState.VALIDATING -> "Validating"
                            DiscoveryState.FAILED -> "Not found"
                            DiscoveryState.IDLE -> "Idle"
                        }
                        AegisStatusPill(label = pillLabel, tone = pillTone)
                    }

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
                            isLoading = ServerDiscoveryManager.isAutoDiscovering.value,
                            quiet = true,
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
                        label = { Text("Backend URL (Manual Override)", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
