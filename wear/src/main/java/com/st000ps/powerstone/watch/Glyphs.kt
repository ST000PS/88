package com.st000ps.powerstone.watch

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The few Material icons the watch uses (path data from Material Icons, Apache 2.0), so the app
 * doesn't ship the whole icon library (~60 MB of code).
 */
object Glyphs {
    private fun icon(name: String, path: String) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(PathParser().parsePathString(path).toNodes(), fill = SolidColor(Color.Black))
        .build()

    val Play = icon("Play", "M8,5v14l11,-7z")
    val Pause = icon("Pause", "M6,19h4V5H6v14zM14,5v14h4V5h-4z")
    val Next = icon("Next", "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z")
    val Previous = icon("Previous", "M6,6h2v12H6zM9.5,12l8.5,6V6z")
    val Shuffle = icon(
        "Shuffle",
        "M10.59,9.17L5.41,4L4,5.41l5.17,5.17l1.42,-1.41zM14.5,4l2.04,2.04L4,18.59L5.41,20L17.96,7.46L20,9.5V4h-5.5z" +
            "M14.83,13.41l-1.41,1.41l3.13,3.13L14.5,20H20v-5.5l-2.04,2.04l-3.13,-3.13z",
    )
    val Heart = icon(
        "Heart",
        "M12,21.35l-1.45,-1.32C5.4,15.36 2,12.28 2,8.5C2,5.42 4.42,3 7.5,3c1.74,0 3.41,0.81 4.5,2.09" +
            "C13.09,3.81 14.76,3 16.5,3C19.58,3 22,5.42 22,8.5c0,3.78 -3.4,6.86 -8.55,11.54L12,21.35z",
    )
    val HeartOutline = icon(
        "HeartOutline",
        "M16.5,3c-1.74,0 -3.41,0.81 -4.5,2.09C10.91,3.81 9.24,3 7.5,3C4.42,3 2,5.42 2,8.5c0,3.78 3.4,6.86 8.55,11.54" +
            "L12,21.35l1.45,-1.32C18.6,15.36 22,12.28 22,8.5C22,5.42 19.58,3 16.5,3zM12.1,18.55l-0.1,0.1l-0.1,-0.1" +
            "C7.14,14.24 4,11.39 4,8.5C4,6.5 5.5,5 7.5,5c1.54,0 3.04,0.99 3.57,2.36h1.87C13.46,5.99 14.96,5 16.5,5" +
            "c2,0 3.5,1.5 3.5,3.5c0,2.89 -3.14,5.74 -7.9,10.05z",
    )
}
