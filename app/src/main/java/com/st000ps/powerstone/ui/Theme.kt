package com.st000ps.powerstone.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily

// Colours taken from the site: near-black background with the rose accent square.
val Ink = Color(0xFF0A0A0A)
val Panel = Color(0xFF141414)
val Edge = Color(0xFF2A2A2A)
val Rose = Color(0xFFC04A5E)
val Dim = Color(0xFF9A9A9A)

private val scheme = darkColorScheme(
    primary = Rose,
    onPrimary = Color.White,
    secondary = Rose,
    background = Ink,
    onBackground = Color(0xFFEDEDED),
    surface = Ink,
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Panel,
    onSurfaceVariant = Dim,
    surfaceContainer = Panel,
    outline = Edge,
)

private fun TextStyle.mono() = copy(fontFamily = FontFamily.Monospace)

private val typography = Typography().run {
    Typography(
        displaySmall = displaySmall.mono(),
        headlineMedium = headlineMedium.mono(),
        headlineSmall = headlineSmall.mono(),
        titleLarge = titleLarge.mono(),
        titleMedium = titleMedium.mono(),
        titleSmall = titleSmall.mono(),
        bodyLarge = bodyLarge.mono(),
        bodyMedium = bodyMedium.mono(),
        bodySmall = bodySmall.mono(),
        labelLarge = labelLarge.mono(),
        labelMedium = labelMedium.mono(),
        labelSmall = labelSmall.mono(),
    )
}

@Composable
fun PowerstoneTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
