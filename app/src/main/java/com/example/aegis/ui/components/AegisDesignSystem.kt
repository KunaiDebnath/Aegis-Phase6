package com.example.aegis.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.aegis.ui.theme.AegisAmber
import com.example.aegis.ui.theme.AegisBlueSoft
import com.example.aegis.ui.theme.AegisGreen
import com.example.aegis.ui.theme.AegisGrey100
import com.example.aegis.ui.theme.AegisGrey200
import com.example.aegis.ui.theme.AegisGrey50
import com.example.aegis.ui.theme.AegisGrey500
import com.example.aegis.ui.theme.AegisNavy
import com.example.aegis.ui.theme.AegisNavyDark
import com.example.aegis.ui.theme.AegisRed
import com.example.aegis.ui.theme.AegisRedDark
import com.example.aegis.ui.theme.AegisRedSoft
import com.example.aegis.ui.theme.AegisWhite

enum class AegisStatusTone {
    GOOD,
    WAIT,
    BAD,
    NEUTRAL
}

enum class AegisNavTab(val label: String) {
    HOME("Home"),
    VOICE("Voice"),
    CONTACTS("Contacts"),
    ALERTS("Alerts"),
    HISTORY("History"),
    SETTINGS("Settings")
}

/**
 * TopBar: 72dp min height, with left menu/back action, title + subtitle, and right avatar.
 */
@Composable
fun AegisTopBar(
    title: String,
    subtitle: String? = null,
    onMenu: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    avatarInitials: String = "AS",
    isDark: Boolean = false,
    modifier: Modifier = Modifier
) {
    val bgColor = if (isDark) AegisNavy else AegisWhite
    val contentColor = if (isDark) AegisWhite else AegisNavy
    val subColor = if (isDark) Color(0xFFBAC6D2) else AegisGrey500
    val dividerColor = if (isDark) Color(0x1AFFFFFF) else AegisGrey100

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(bgColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 68.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(42.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Go back",
                        tint = contentColor
                    )
                }
            } else if (onMenu != null) {
                IconButton(
                    onClick = onMenu,
                    modifier = Modifier.size(42.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = "Open screen index",
                        tint = contentColor
                    )
                }
            } else {
                Spacer(modifier = Modifier.width(8.dp))
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = subColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (isDark) Color(0x26FFFFFF) else AegisBlueSoft),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = avatarInitials,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 12.sp,
                    color = if (isDark) AegisWhite else AegisNavyDark
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(dividerColor)
        )
    }
}

/**
 * Status Pill with 7dp dot and 11sp bold text.
 */
@Composable
fun AegisStatusPill(
    label: String,
    tone: AegisStatusTone = AegisStatusTone.GOOD,
    modifier: Modifier = Modifier
) {
    val color = when (tone) {
        AegisStatusTone.GOOD -> AegisGreen
        AegisStatusTone.WAIT -> AegisAmber
        AegisStatusTone.BAD -> AegisRed
        AegisStatusTone.NEUTRAL -> AegisGrey500
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

/**
 * Primary Button with Navy, Danger (Red), or Quiet (Outlined White) style.
 */
@Composable
fun AegisPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    quiet: Boolean = false,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    icon: ImageVector? = null,
    darkContainer: Boolean = false
) {
    val heightModifier = modifier
        .fillMaxWidth()
        .height(52.dp)

    if (quiet) {
        val borderColor = if (darkContainer) Color(0x3DFFFFFF) else AegisGrey200
        val textColor = if (darkContainer) AegisWhite else AegisNavy
        val bgColor = if (darkContainer) Color.Transparent else AegisWhite

        OutlinedButton(
            onClick = onClick,
            enabled = enabled && !isLoading,
            shape = RoundedCornerShape(10.dp),
            modifier = heightModifier,
            border = androidx.compose.foundation.BorderStroke(1.dp, borderColor),
            colors = ButtonDefaults.outlinedButtonColors(
                containerColor = bgColor,
                contentColor = textColor,
                disabledContentColor = textColor.copy(alpha = 0.5f)
            )
        ) {
            ButtonContent(text = text, icon = icon, isLoading = isLoading, textColor = textColor)
        }
    } else {
        val containerColor = when {
            darkContainer && !danger -> AegisWhite
            danger -> AegisRed
            else -> AegisNavy
        }
        val contentColor = when {
            darkContainer && !danger -> AegisNavy
            else -> AegisWhite
        }

        Button(
            onClick = onClick,
            enabled = enabled && !isLoading,
            shape = RoundedCornerShape(10.dp),
            modifier = heightModifier,
            colors = ButtonDefaults.buttonColors(
                containerColor = containerColor,
                contentColor = contentColor,
                disabledContainerColor = containerColor.copy(alpha = 0.5f)
            )
        ) {
            ButtonContent(text = text, icon = icon, isLoading = isLoading, textColor = contentColor)
        }
    }
}

@Composable
private fun ButtonContent(
    text: String,
    icon: ImageVector?,
    isLoading: Boolean,
    textColor: Color
) {
    if (isLoading) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = textColor
        )
        Spacer(modifier = Modifier.width(8.dp))
    } else if (icon != null) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = textColor,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
    }
    Text(
        text = text,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp
    )
}

/**
 * Standard Info Row: 68dp min height, with 36dp rounded square icon, title, subtitle, and status/arrow.
 */
@Composable
fun AegisInfoRow(
    icon: ImageVector,
    label: String,
    value: String? = null,
    status: (@Composable () -> Unit)? = null,
    action: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val rowModifier = if (action != null) {
        modifier
            .fillMaxWidth()
            .clickable(onClick = action)
            .padding(horizontal = 14.dp, vertical = 13.dp)
    } else {
        modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 13.dp)
    }

    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(AegisBlueSoft),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = AegisNavyDark,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = AegisNavy,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!value.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = value,
                    fontSize = 11.sp,
                    color = AegisGrey500,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (status != null) {
            Spacer(modifier = Modifier.width(8.dp))
            status()
        } else if (action != null) {
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = AegisGrey500,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * White Surface Card: 12dp rounded corners, 1dp border, crisp background.
 */
@Composable
fun AegisCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = AegisWhite,
    borderColor: Color = AegisGrey200,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, RoundedCornerShape(12.dp)),
        shape = RoundedCornerShape(12.dp),
        color = backgroundColor,
        shadowElevation = 0.dp
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            content()
        }
    }
}

/**
 * Section Header with Title and optional right action button.
 */
@Composable
fun AegisSectionHeader(
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = AegisNavy
        )
        if (actionLabel != null && onAction != null) {
            Text(
                text = actionLabel,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = AegisNavyDark,
                modifier = Modifier.clickable(onClick = onAction)
            )
        }
    }
}

/**
 * Floating Circular SOS Button:
 * 188dp diameter, concentric 8dp soft-red border, shadow, bold text.
 */
@Composable
fun AegisFloatingSosButton(
    onClick: () -> Unit,
    isLoading: Boolean = false,
    label: String = "SEND SOS",
    subLabel: String = "Press for emergency help",
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(188.dp)
            .shadow(16.dp, CircleShape, spotColor = AegisRed.copy(alpha = 0.35f))
            .border(8.dp, AegisRedSoft, CircleShape)
            .border(9.dp, Color(0xFFF2C2C6), CircleShape)
            .clip(CircleShape)
            .background(AegisRed)
            .clickable(
                enabled = !isLoading,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = AegisWhite,
                modifier = Modifier.size(36.dp),
                strokeWidth = 3.dp
            )
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = label,
                    fontSize = 23.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.5.sp,
                    color = AegisWhite
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subLabel,
                    fontSize = 10.sp,
                    color = AegisWhite.copy(alpha = 0.9f)
                )
            }
        }
    }
}

/**
 * Bottom Navigation Bar:
 * 70dp height, white background, top border, active tab highlighted with soft blue and navy.
 */
@Composable
fun AegisBottomNav(
    currentTab: AegisNavTab,
    onSelectTab: (AegisNavTab) -> Unit,
    isGuardian: Boolean = false,
    modifier: Modifier = Modifier
) {
    val tabs = if (isGuardian) {
        listOf(AegisNavTab.HOME, AegisNavTab.ALERTS, AegisNavTab.HISTORY, AegisNavTab.SETTINGS)
    } else {
        listOf(AegisNavTab.HOME, AegisNavTab.VOICE, AegisNavTab.CONTACTS, AegisNavTab.HISTORY, AegisNavTab.SETTINGS)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(AegisWhite)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(AegisGrey200)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(68.dp)
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            tabs.forEach { tab ->
                val isSelected = currentTab == tab
                val icon = when (tab) {
                    AegisNavTab.HOME -> Icons.Default.Shield
                    AegisNavTab.VOICE -> Icons.Default.Mic
                    AegisNavTab.CONTACTS -> Icons.Default.Person
                    AegisNavTab.ALERTS -> Icons.Default.Notifications
                    AegisNavTab.HISTORY -> Icons.Default.Schedule
                    AegisNavTab.SETTINGS -> Icons.Default.Settings
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(54.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isSelected) AegisBlueSoft else Color.Transparent)
                        .clickable { onSelectTab(tab) },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = tab.label,
                            tint = if (isSelected) AegisNavyDark else AegisGrey500,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = tab.label,
                            fontSize = 9.sp,
                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Normal,
                            color = if (isSelected) AegisNavyDark else AegisGrey500
                        )
                    }
                }
            }
        }
    }
}

/**
 * Timeline Item Component for Emergency Progress and History.
 */
@Composable
fun AegisTimelineItem(
    time: String,
    title: String,
    detail: String,
    isCritical: Boolean = false,
    isLast: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(26.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(if (isCritical) AegisRed else AegisGreen),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = AegisWhite,
                    modifier = Modifier.size(13.dp)
                )
            }
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(32.dp)
                        .background(AegisGrey200)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.padding(bottom = if (isLast) 0.dp else 14.dp)) {
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = AegisNavy
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "$time · $detail",
                fontSize = 10.sp,
                color = AegisGrey500
            )
        }
    }
}
