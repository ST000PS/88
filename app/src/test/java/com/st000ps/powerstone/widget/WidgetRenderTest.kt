package com.st000ps.powerstone.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import com.st000ps.powerstone.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Inflates the real widget layouts for each state the widget can be in and draws them, so the
 * design can be checked without a phone. Pictures land in app/build/widget-shots (CI uploads them).
 * Animations show their first frame.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h915dp-xxhdpi")
class WidgetRenderTest {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()
    private val out = File("build/widget-shots").apply { mkdirs() }
    private val shots = mutableListOf<Pair<String, Bitmap>>()

    @Before fun setUp() {
        PlayerWidget.artUri = { c, name -> Uri.fromFile(File(WidgetStore.dir(c), name)) }
        // Robolectric's clock starts near zero; move it on so "102 s into the track" is a real time.
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMinutes(30))
    }

    @After fun tearDown() {
        PlayerWidget.live = { com.st000ps.powerstone.playback.PlaybackService.isRunning }
    }

    /** A stand-in cover: a dusky gradient with a sun, like a lo-fi sleeve. */
    private fun cover(): Bitmap {
        val b = Bitmap.createBitmap(360, 360, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, 0f, 360f, 0xFF2E5E7E.toInt(), 0xFFE0A26A.toInt(), Shader.TileMode.CLAMP) })
        c.drawCircle(250f, 140f, 60f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFF4E3C1.toInt() })
        c.drawRect(0f, 270f, 360f, 360f, Paint().apply { color = 0xFF1B2A33.toInt() })
        return b
    }

    private fun playing(title: String = "Bstlove", bpm: Int? = 92): WidgetState {
        val art = cover()
        val (a, l) = WidgetStore.saveArt(ctx, "test-cover", art, PlayerWidget.labelFrom(art))
        return WidgetState(
            title = title, subtitle = "Knx. · HX.26", detail = "92 BPM · Am · track 4/12", upNext = "next › Wrkn",
            playing = true, artName = a, labelName = l, tint = PlayerWidget.tintFrom(art), hasTrack = true,
            fav = true, canFav = true, bpm = bpm, artist = "Knx.", release = "HX.26", trackNo = 4, startedAt = SystemClock.elapsedRealtime() - 102_000,
            durationMs = 188_000,
        )
    }

    private fun shot(name: String, layout: Int, wDp: Int, hDp: Int, s: WidgetState, o: WidgetOptions = WidgetOptions(), live: Boolean = true) {
        PlayerWidget.live = { live }
        val rv = PlayerWidget.build(ctx, layout, o, s)
        val v = rv.apply(ctx, FrameLayout(ctx))
        // Robolectric can't always read content from file links; set the pictures directly.
        if (s.artName != null && live) fill(v, s)
        val d = ctx.resources.displayMetrics.density
        val w = (wDp * d).toInt(); val h = (hDp * d).toInt()
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        // Run the animations ~1.6 s in (software drawing runs them on the main looper), so playing
        // states show lit pads and a needle that has locked on, not their first frame.
        if (s.playing && live) {
            val scratch = Canvas(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888))
            v.draw(scratch)
            repeat(40) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(40)); v.draw(scratch) }
        }
        val pad = (16 * d).toInt()
        val bmp = Bitmap.createBitmap(w + pad * 2, h + pad * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat(), 0xFF5B7F95.toInt(), 0xFFB98D6A.toInt(), Shader.TileMode.CLAMP) })
        c.translate(pad.toFloat(), pad.toFloat())
        v.draw(c)
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        shots += name to bmp
    }

    private fun fill(v: View, s: WidgetState) {
        val dir = WidgetStore.dir(ctx)
        val art = android.graphics.BitmapFactory.decodeFile(File(dir, s.artName!!).path)
        val label = s.labelName?.let { android.graphics.BitmapFactory.decodeFile(File(dir, it).path) }
        listOf(R.id.w_art, R.id.w_cover, R.id.w_lcd_art).forEach { (v.findViewById<View>(it) as? ImageView)?.setImageBitmap(art) }
        (v.findViewById<View>(R.id.w_label) as? ImageView)?.setImageBitmap(label)
    }

    private fun sheet() {
        val gap = 24
        val w = shots.maxOf { it.second.width }
        val h = shots.sumOf { it.second.height + gap }
        val sheet = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        c.drawColor(Color.rgb(40, 40, 40))
        var y = 0f
        shots.forEach { (_, b) -> c.drawBitmap(b, 0f, y, null); y += b.height + gap }
        File(out, "all.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun drawEveryState() {
        val p = playing()
        val hero = WidgetOptions.Hero.entries
        shot("0_wide_pads_playing", R.layout.widget_tall_wide, 430, 440, p)
        shot("1_tall_pads_playing", R.layout.widget_tall, 380, 400, p)
        shot("2_tall_record_playing", R.layout.widget_tall, 380, 400, p, WidgetOptions(hero = hero[1]))
        shot("3_tall_cover_paused_long_title", R.layout.widget_tall, 380, 400,
            playing(title = "Never Been Better (Interlude for the Late Night Drive)").copy(playing = false, startedAt = 0, positionMs = 61_000),
            WidgetOptions(hero = hero[2]))
        shot("4_tall_pads_paused_sleep", R.layout.widget_tall, 380, 400,
            p.copy(playing = false, startedAt = 0, positionMs = 61_000, sleepAt = System.currentTimeMillis() + 15 * 60_000))
        shot("5_tall_closed", R.layout.widget_tall, 380, 400, p.copy(playing = false, todayMinutes = 47), live = false)
        shot("6_tall_first_run", R.layout.widget_tall, 380, 400, WidgetState(), live = false)
        shot("7_card_playing", R.layout.widget_large, 380, 200, p)
        shot("8_card_rose_closed", R.layout.widget_large, 380, 200, p.copy(playing = false), WidgetOptions(coverColour = false), live = false)
        shot("9_row_playing", R.layout.widget_small, 380, 84, p)
        shot("10_row_closed", R.layout.widget_small, 380, 84, p.copy(playing = false), live = false)
        sheet()
        assertTrue(File(out, "all.png").length() > 0)
    }

    @Test fun tempoPicksTheNearestMeter() {
        assertEquals(2, PlayerWidget.tempoBand(null)) // unknown: a relaxed 90
        assertEquals(2, PlayerWidget.tempoBand(92))
        assertEquals(0, PlayerWidget.tempoBand(72))
        assertEquals(5, PlayerWidget.tempoBand(140))
        assertEquals(3, PlayerWidget.tempoBand(50)) // half-time read as double
        assertEquals(2, PlayerWidget.tempoBand(180)) // double-time read as half
    }

    @Test fun progressFollowsTheClockWhilePlaying() {
        val now = 1_000_000L
        assertEquals(500, WidgetState(startedAt = now - 90_000, durationMs = 180_000).progress(now))
        assertEquals(250, WidgetState(positionMs = 45_000, durationMs = 180_000).progress(now))
        assertEquals(1000, WidgetState(startedAt = now - 999_000, durationMs = 180_000).progress(now))
        assertEquals(0, WidgetState(startedAt = now, durationMs = 0).progress(now))
    }
}
