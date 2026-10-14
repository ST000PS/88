package com.st000ps.powerstone.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Pictures the widget draws itself, because widgets can only use system fonts:
 *  - text in Silkscreen, the pixel font knxwledge.com uses, drawn without smoothing so every
 *    pixel is square, at a whole-number pixel size for this screen;
 *  - each track's sample strip: a waveform with beat transients at its tempo and chop markers,
 *    the same picture every time for the same track.
 * Files are cached in [WidgetStore.dir] by content and reach the launcher like the covers do.
 */
object WidgetPixels {
    enum class Style(val dp: Float, val color: Int, val bold: Boolean) {
        TITLE(16f, 0xFFF2F2F2.toInt(), false),
        TITLE_IDLE(16f, 0xFF6B6B6B.toInt(), false),
        BODY(8f, 0xFF8A8A8A.toInt(), false),
        DIM(8f, 0xFF4A4A4A.toInt(), false),
        BRIGHT(8f, 0xFFF2F2F2.toInt(), false),
        LIVE(8f, 0xFFC04A5E.toInt(), false),
        BIG(16f, 0xFFF2F2F2.toInt(), false),
        BIG_LIVE(16f, 0xFFC04A5E.toInt(), false),
    }

    @Volatile private var regular: Typeface? = null
    @Volatile private var bold: Typeface? = null

    private fun face(context: Context, b: Boolean): Typeface =
        (if (b) bold else regular) ?: Typeface.createFromAsset(context.assets, if (b) "fonts/Silkscreen-Bold.ttf" else "fonts/Silkscreen-Regular.ttf")
            .also { if (b) bold = it else regular = it }

    /** Silkscreen is drawn on an 8-unit grid; one font pixel becomes a whole number of screen pixels. */
    private fun fontPx(context: Context, dp: Float): Int =
        ((dp / 8f) * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)

    /** The file for [text] in [style], at most [maxDp] wide (cut with ".." past that). */
    fun text(context: Context, text: String, style: Style, maxDp: Int = 320): String {
        val clean = text.trim().replace('·', '/').replace('›', '>').replace('…', '.').ifEmpty { " " }
        val name = "t_" + Integer.toHexString("$clean|$style|$maxDp|${context.resources.displayMetrics.density}".hashCode()) + ".png"
        val file = File(WidgetStore.dir(context), name)
        if (file.exists()) return name
        val k = fontPx(context, style.dp)
        val paint = Paint().apply {
            isAntiAlias = false
            isFilterBitmap = false
            typeface = face(context, style.bold)
            textSize = 8f * k
            color = style.color
            letterSpacing = 0.06f
        }
        // Silkscreen is Latin-only: a title in, say, Japanese falls back to the system font, smoothed.
        if (clean.any { !it.isWhitespace() && !paint.hasGlyph(it.toString()) }) {
            paint.typeface = Typeface.create(Typeface.DEFAULT, if (style.bold) Typeface.BOLD else Typeface.NORMAL)
            paint.isAntiAlias = true
            paint.letterSpacing = 0f
            paint.textSize = 8f * k * 0.95f
        }
        val maxPx = maxDp * context.resources.displayMetrics.density
        var s = clean
        if (paint.measureText(s) > maxPx) {
            while (s.length > 1 && paint.measureText("$s..") > maxPx) s = s.dropLast(1)
            s = s.trimEnd() + ".."
        }
        val fm = paint.fontMetricsInt
        val w = paint.measureText(s).roundToInt().coerceAtLeast(1) + k
        val h = (fm.descent - fm.ascent).coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(bmp).drawText(s, 0f, (-fm.ascent).toFloat(), paint)
        save(file, bmp)
        return name
    }

    /**
     * A track's sample strip: a mirrored waveform built from the track's id (so it's the same each
     * time) with a transient on every beat at its tempo, louder sections, and eight chop markers
     * like an MPC's slice screen. [widthDp] x [heightDp]; the widget stretches it to fit.
     */
    fun wave(context: Context, seed: String, bpm: Int?, durationMs: Long, widthDp: Int = 360, heightDp: Int = 40): String {
        val name = "w_" + Integer.toHexString("$seed|$bpm|$durationMs|$widthDp|$heightDp|${context.resources.displayMetrics.density}".hashCode()) + ".png"
        val file = File(WidgetStore.dir(context), name)
        if (file.exists()) return name
        val d = context.resources.displayMetrics.density
        val w = (widthDp * d).roundToInt(); val h = (heightDp * d).roundToInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val rnd = java.util.Random(seed.hashCode().toLong())
        val col = (2 * d).roundToInt().coerceAtLeast(2)       // one bar every 2 dp
        val bar = (col * 0.6f).roundToInt().coerceAtLeast(1)
        val beats = ((durationMs.takeIf { it > 0 } ?: 180_000L) / 60000.0 * (bpm ?: 90)).coerceAtLeast(16.0)
        // Sections: a few loud / quiet stretches, like a beat with breakdowns.
        val sections = DoubleArray(6) { 0.55 + rnd.nextDouble() * 0.45 }
        val phase = rnd.nextDouble() * 6.28
        val paint = Paint().apply { isAntiAlias = false; color = 0xFF8A8A8A.toInt() }
        val mid = h / 2f
        var x = 0
        while (x < w) {
            val t = x.toDouble() / w
            val beatPos = t * beats
            val inBeat = beatPos - kotlin.math.floor(beatPos)
            val kick = Math.exp(-inBeat * 7.0)                             // transient at each beat, decaying
            val offbeat = 0.35 * Math.exp(-abs(inBeat - 0.5) * 14.0)       // snare-ish on the "and"
            val section = sections[((t * sections.size).toInt()).coerceAtMost(sections.size - 1)]
            val wobble = 0.15 * sin(t * 37.0 + phase) + 0.1 * rnd.nextGaussian()
            val fade = (t / 0.03).coerceAtMost(1.0) * ((1 - t) / 0.04).coerceAtMost(1.0)
            val amp = ((0.28 + 0.55 * kick + offbeat + wobble) * section * fade).coerceIn(0.04, 1.0)
            val half = (amp * (h / 2f - d)).toFloat()
            c.drawRect(x.toFloat(), (mid - half).roundToInt().toFloat(), (x + bar).toFloat(), (mid + half).roundToInt().toFloat(), paint)
            x += col
        }
        // Centre line and eight chop markers (each with a little flag), placed on bar lines.
        paint.color = 0xFF2A2A2A.toInt()
        c.drawRect(0f, mid, w.toFloat(), mid + d.coerceAtLeast(1f), paint)
        val bars = (beats / 4).toInt().coerceAtLeast(8)
        val chops = (1 until 8).map { i -> ((i * bars / 8.0) + rnd.nextInt(3) - 1).coerceIn(1.0, bars - 1.0) }.sorted()
        paint.color = 0xFF4A4A4A.toInt()
        (listOf(0.0) + chops).forEach { b ->
            val cx = (b / bars * w).roundToInt().toFloat()
            c.drawRect(cx, 0f, cx + d.coerceAtLeast(1f), h.toFloat(), paint)
            c.drawRect(cx, 0f, cx + 4 * d, 3 * d, paint)
        }
        save(file, bmp)
        return name
    }

    private fun save(file: File, bmp: Bitmap) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        tmp.renameTo(file)
        bmp.recycle()
        // Keep the cache small: the newest few hundred text pieces and a handful of strips.
        val dir = file.parentFile ?: return
        listOf("t_" to 300, "w_" to 8).forEach { (prefix, keep) ->
            dir.listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(".png") }
                ?.sortedByDescending { it.lastModified() }?.drop(keep)?.forEach { it.delete() }
        }
    }
}
