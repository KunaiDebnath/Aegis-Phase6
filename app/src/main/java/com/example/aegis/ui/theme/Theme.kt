package com.example.aegis.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val AegisLightColorScheme = lightColorScheme(
    primary = AegisNavy,
    onPrimary = AegisWhite,
    primaryContainer = AegisBlueSoft,
    onPrimaryContainer = AegisNavyDark,

    secondary = AegisNavyDark,
    onSecondary = AegisWhite,
    secondaryContainer = AegisBlueSoft,
    onSecondaryContainer = AegisNavy,

    tertiary = AegisGreen,
    onTertiary = AegisWhite,
    tertiaryContainer = AegisGreenSoft,
    onTertiaryContainer = AegisGreen,

    error = AegisRed,
    onError = AegisWhite,
    errorContainer = AegisRedSoft,
    onErrorContainer = AegisRedDark,

    background = AegisGrey50,
    onBackground = AegisNavy,
    surface = AegisWhite,
    onSurface = AegisNavy,
    surfaceVariant = AegisGrey100,
    onSurfaceVariant = AegisGrey500,
    outline = AegisGrey200,
    outlineVariant = AegisGrey100
)

@Composable
fun AEGISTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Preserve crisp Figma identity consistently
    content: @Composable () -> Unit
) {
    val colorScheme = AegisLightColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                window.statusBarColor = AegisWhite.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
