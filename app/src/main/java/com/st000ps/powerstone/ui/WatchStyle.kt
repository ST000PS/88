package com.st000ps.powerstone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.st000ps.powerstone.data.EqState

// The watch app's building blocks, shared by the phone's player, EQ and radio pages.

val White = Color(0xFFF2F2F2)

/** A small pixel-type label. */
@Composable
fun PixelLabel(text: String, color: Color = Dim, size: TextUnit = 11.sp, modifier: Modifier = Modifier) {
    Text(text, color = color, fontSize = size, fontFamily = pixelFont(), letterSpacing = 0.08.sp, textAlign = TextAlign.Center, modifier = modifier)
}

/** A round button: outlined with a hairline, or (the main action) solid white with a black glyph. */
@Composable
fun RoundButton(
    icon: ImageVector, desc: String, size: Dp, filled: Boolean = false, tint: Color = White,
    enabled: Boolean = true, onClick: () -> Unit, content: (@Composable () -> Unit)? = null,
) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (filled) White else Color.Transparent)
            .border(1.dp, if (filled) White else Edge, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (content != null) content()
        else Icon(icon, desc, tint = (if (filled) Ink else tint).copy(alpha = if (enabled) 1f else 0.35f), modifier = Modifier.size(size * 0.5f))
    }
}

/** A watch chip: hairline outline, rose when on. */
@Composable
fun WatchChip(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(9.dp)).background(if (on) Rose else Color.Transparent)
            .border(1.dp, if (on) Rose else Edge, RoundedCornerShape(9.dp)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) { PixelLabel(text, White, 11.sp) }
}

/** The pill at the top of the player saying what's playing from ("SHUFFLE", "RADIO · 92 BPM"…). */
@Composable
fun KindPill(text: String, onClick: (() -> Unit)? = null) {
    Box(
        Modifier.clip(RoundedCornerShape(12.dp)).border(1.dp, Edge, RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) { PixelLabel(text, White, 10.sp) }
}

/** Square page dots, as on the watch. */
@Composable
fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i -> Box(Modifier.size(if (i == current) 6.dp else 5.dp).background(if (i == current) White else Edge)) }
    }
}

/**
 * The DJ EQ as the watch draws it: three columns of 15 pixel blocks (0–150 %, the top five rose).
 * Tap a band to select it; drag up and down on it (the phone's crown) or tap a block to set it.
 */
@Composable
fun PixelEq(eq: EqState, set: (EqState) -> Unit, modifier: Modifier = Modifier) {
    var band by remember { mutableStateOf("mid") }
    val labels = mapOf("lo" to "LO", "mid" to "MID", "hi" to "HI")
    val jp = mapOf("lo" to "低音", "mid" to "中音", "hi" to "高音")
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.Bottom) {
            EqState.BANDS.forEach { b ->
                val killed = b in eq.kill
                val solo = eq.solo == b
                val selected = b == band
                var drag by remember { mutableFloatStateOf(0f) }
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable { band = b }
                        .pointerInput(b, eq) {
                            detectVerticalDragGestures(onDragStart = { band = b; drag = eq.level(b) }) { _, dy ->
                                drag = (drag - dy / 260f).coerceIn(0f, 1.5f)
                                set(eq.with(b, (drag * 100).toInt() / 100f))
                            }
                        },
                ) {
                    PixelLabel("${(eq.level(b) * 100).toInt()}", if (selected) White else Dim, 14.sp)
                    Spacer(Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        val lit = (eq.level(b) * 10).toInt()
                        for (i in 14 downTo 0) {
                            val on = i < lit && !killed
                            Box(
                                Modifier.size(width = 52.dp, height = 7.dp)
                                    .background(when { !on -> Edge; i >= 10 -> Rose; else -> if (selected) White else Dim })
                                    .clickable { band = b; set(eq.with(b, (i + 1) / 10f)) },
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    PixelLabel(labels[b]!!, if (selected) White else Dim, 12.sp)
                    Text(jp[b]!!, color = Dim.copy(alpha = 0.6f), fontSize = 9.sp)
                    PixelLabel(when { killed -> "KILL"; solo -> "ISO"; else -> " " }, Rose, 10.sp)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            WatchChip("KILL ${labels[band]}", band in eq.kill) { set(eq.copy(kill = if (band in eq.kill) eq.kill - band else eq.kill + band)) }
            WatchChip("ISO ${labels[band]}", eq.solo == band) { set(eq.copy(solo = if (eq.solo == band) null else band)) }
            WatchChip("RESET", false) { set(EqState()) }
        }
    }
}

/** Taps → BPM: the average of the last few intervals, ignoring a pause longer than 2 s. */
class TapTempo {
    private val taps = ArrayDeque<Long>()

    fun tap(now: Long = System.currentTimeMillis()): Int? {
        if (taps.isNotEmpty() && now - taps.last() > 2000) taps.clear()
        taps.addLast(now)
        while (taps.size > 8) taps.removeFirst()
        if (taps.size < 3) return null
        val gaps = taps.zipWithNext { a, b -> b - a }
        return (60_000.0 / gaps.average()).toInt().coerceIn(40, 220)
    }

    val count get() = taps.size
}

@Composable
fun Gap(w: Dp = 0.dp, h: Dp = 0.dp) = Spacer(Modifier.width(w).height(h))

/** Full-width centred column helper. */
@Composable
fun Centered(modifier: Modifier = Modifier, content: @Composable () -> Unit) =
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) { content() }
