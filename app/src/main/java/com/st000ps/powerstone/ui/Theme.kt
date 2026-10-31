package com.st000ps.powerstone.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.em
import androidx.compose.runtime.remember

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

/** Silkscreen, the pixel font knxwledge.com and the watch app use: for every label in the app. */
@Composable
fun pixelFont(): FontFamily {
    val assets = LocalContext.current.assets
    return remember { FontFamily(Font("fonts/Silkscreen-Regular.ttf", assets), Font("fonts/Silkscreen-Bold.ttf", assets, FontWeight.Bold)) }
}

private fun typography(pixel: FontFamily) = Typography().run {
    fun TextStyle.px() = copy(fontFamily = pixel, letterSpacing = 0.06.em)
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
        // Labels (section heads, chips, tags, status) are pixel type, like the watch and the site.
        labelLarge = labelLarge.px(),
        labelMedium = labelMedium.px(),
        labelSmall = labelSmall.px(),
    )
}

@Composable
fun PowerstoneTheme(content: @Composable () -> Unit) {
    val pixel = pixelFont()
    val type = remember(pixel) { typography(pixel) }
    MaterialTheme(colorScheme = scheme, typography = type, content = content)
}
