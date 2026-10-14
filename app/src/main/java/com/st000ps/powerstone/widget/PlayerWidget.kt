package com.st000ps.powerstone.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import androidx.annotation.VisibleForTesting
import com.st000ps.powerstone.R
import com.st000ps.powerstone.playback.PlaybackService
import com.st000ps.powerstone.ui.MainActivity

/** What the widget shows. Lives in memory; when the app isn't running the widget shows the last track, paused. */
data class WidgetState(
    val title: String = "",
    val subtitle: String = "",
    /** Rotates with [subtitle] while playing: BPM / key / track number, and what's up next. */
    val detail: String = "",
    val upNext: String = "",
    val playing: Boolean = false,
    /** Cover and record-label files in [WidgetStore.dir], served by [WidgetArtProvider]. */
    val artName: String? = null,
    val labelName: String? = null,
    /** The dial's backlight colour, taken from the cover. */
    val tint: Int = DEFAULT_TINT,
    val hasTrack: Boolean = false,
    val fav: Boolean = false,
    val canFav: Boolean = false,
    val bpm: Int? = null,
    /** For the sampler's LCD: artist, release, and the track number as the sequence. */
    val artist: String = "",
    val release: String = "",
    val trackNo: Int? = null,
    /** Bumped on every track change: alternates which copy of the tuning animation shows, so it replays. */
    val tune: Int = 0,
    /** Track position: while playing, the elapsedRealtime at which the track was at 0:00; while paused, [positionMs]. */
    val startedAt: Long = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** Sleep timer: wall-clock end, or "at the end of this track". */
    val sleepAt: Long? = null,
    val sleepEndOfTrack: Boolean = false,
    /** Shown when nothing is loaded: today's listening. */
    val todayMinutes: Int = 0,
) {
    /** Same picture, ignoring how the clock drifted between two looks at the player. */
    fun sameAs(o: WidgetState?): Boolean =
        o != null && copy(startedAt = 0, positionMs = 0) == o.copy(startedAt = 0, positionMs = 0) &&
            kotlin.math.abs(startedAt - o.startedAt) < 400 && kotlin.math.abs(positionMs - o.positionMs) < 1000

    /** 0..1000 through the track, as of now. */
    fun progress(now: Long = SystemClock.elapsedRealtime()): Int {
        if (durationMs <= 0) return 0
        val pos = if (startedAt > 0) now - startedAt else positionMs
        return (pos * 1000 / durationMs).toInt().coerceIn(0, 1000)
    }

    companion object {
        val DEFAULT_TINT = Color.rgb(0x1C, 0x1C, 0x1E)
    }
}

/**
 * Home-screen player, in the site's look (monospace, 88.8 red on near-black, FM radio):
 *  - 4×1 row: art, title, a level meter beside the info line, previous / play / next, progress hairline.
 *  - Card: an ON AIR lamp, a ticking track timer, progress, and an FM tuner strip whose needle sweeps in
 *    and locks onto 88.8 (on every track, and every time the home screen comes back).
 *  - Big card: the cover becomes the label of a record that spins up, ringed by the track's progress;
 *    plus favourite and shuffle.
 * The level meter bounces at about the track's BPM. The card takes its colour from the cover.
 *
 * Everything that moves is an animated vector run by the launcher inside an indeterminate
 * ProgressBar (paused or closed swaps each for a still twin), timers are Chronometers, and on
 * Android 12+ play/pause and the heart are toggles whose drawables morph the instant they're
 * tapped. While the screen is on and music plays, the service re-sends the views once a second for
 * the progress ring; covers travel as content:// links, so that's a few hundred bytes.
 * Each widget has its own options ([WidgetOptions], via the widget's settings screen).
 */
class PlayerWidget : AppWidgetProvider() {
    companion object {
        private const val ACTION = "com.st000ps.powerstone.widget.ACTION"
        private const val EXTRA = "what"
        private const val RED = 0xFFC04A5E.toInt()
        private val TEMPOS = intArrayOf(70, 80, 90, 100, 115, 135)
        private val PADS = intArrayOf(R.id.w_pads_70, R.id.w_pads_80, R.id.w_pads_90, R.id.w_pads_100, R.id.w_pads_115, R.id.w_pads_135)
        private val BARS = intArrayOf(R.id.w_bars_70, R.id.w_bars_80, R.id.w_bars_90, R.id.w_bars_100, R.id.w_bars_115, R.id.w_bars_135)
        /** Passed to MainActivity when the widget opens the app: "resume" or "shuffle". */
        const val EXTRA_WIDGET_ACTION = "widget_action"

        @Volatile private var current: WidgetState? = null

        /** What's showing: the live player's state, or (app closed) the last track from disk. */
        fun state(context: Context): WidgetState = current ?: WidgetStore.loadLast(context).also { current = it }

        /** How cover files reach the launcher. Tests swap in plain file links. */
        @VisibleForTesting var artUri: (Context, String) -> Uri = { ctx, name ->
            WidgetArtProvider.uri(ctx, name).also { WidgetArtProvider.grant(ctx, it) }
        }

        /** Whether the playback service is up (the buttons then control it directly). Tests override. */
        @VisibleForTesting var live: () -> Boolean = { PlaybackService.isRunning }

        fun ids(context: Context): IntArray {
            val mgr = AppWidgetManager.getInstance(context) ?: return IntArray(0)
            return mgr.getAppWidgetIds(ComponentName(context, PlayerWidget::class.java))
        }

        fun update(context: Context, newState: WidgetState) {
            if (newState.hasTrack) WidgetStore.saveLast(context, newState)
            current = newState
            refresh(context)
        }

        /** Re-draws every widget from the current state (the progress ring moves on). */
        fun refresh(context: Context) {
            val mgr = AppWidgetManager.getInstance(context) ?: return
            ids(context).forEach { render(context, mgr, it) }
        }

        private fun render(context: Context, mgr: AppWidgetManager, id: Int) {
            val o = WidgetStore.options(context, id)
            val views = if (Build.VERSION.SDK_INT >= 31) {
                // Let the launcher pick the layout for whatever size the widget is.
                RemoteViews(
                    mapOf(
                        SizeF(180f, 40f) to build(context, R.layout.widget_small, o),
                        SizeF(250f, 165f) to build(context, R.layout.widget_large, o),
                        SizeF(300f, 300f) to build(context, R.layout.widget_tall, o),
                        SizeF(390f, 330f) to build(context, R.layout.widget_tall_wide, o),
                    ),
                )
            } else {
                val h = mgr.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 40)
                build(context, if (h >= 300) R.layout.widget_tall else if (h >= 165) R.layout.widget_large else R.layout.widget_small, o)
            }
            mgr.updateAppWidget(id, views)
        }

        @VisibleForTesting
        fun build(context: Context, layout: Int, o: WidgetOptions, s: WidgetState = state(context)): RemoteViews {
            val live = s.hasTrack && live()
            val playing = live && s.playing
            val moving = playing && o.motion
            val small = layout == R.layout.widget_small
            val tall = layout == R.layout.widget_tall || layout == R.layout.widget_tall_wide
            val art = s.artName?.let { artUri(context, it) }
            return RemoteViews(context.packageName, layout).apply {
                // Words.
                setTextViewText(R.id.w_title, if (s.hasTrack) s.title else "88.8FM")
                setTextColor(R.id.w_title, if (live) 0xFFF2EDE6.toInt() else 0xFFA9A49C.toInt())
                val idle = when {
                    s.hasTrack && !live && s.todayMinutes > 0 -> "Press play to resume · today ${s.todayMinutes} min"
                    s.hasTrack && !live -> "Press play to resume"
                    s.todayMinutes > 0 -> "Today · ${s.todayMinutes} min on 88.8 · press play"
                    else -> "Press play to tune in"
                }
                setTextViewText(R.id.w_sub, if (live && s.hasTrack) s.subtitle else idle)
                setViewVisibility(R.id.w_flip, if (playing) View.VISIBLE else View.GONE)
                setViewVisibility(R.id.w_sub, if (playing) View.GONE else View.VISIBLE)
                if (playing) {
                    setTextViewText(R.id.w_f1, s.subtitle)
                    setTextViewText(R.id.w_f2, s.detail.ifEmpty { s.subtitle })
                    setTextViewText(R.id.w_f3, s.upNext.ifEmpty { s.detail.ifEmpty { "thepowerstone.com" } })
                }

                // Cover thumbnail (the big card hides it when the record or cover is the hero).
                if (art != null) setImageViewUri(R.id.w_art, art) else setImageViewResource(R.id.w_art, R.mipmap.ic_launcher)
                setInt(R.id.w_art, "setImageAlpha", if (live) 255 else 170)

                // Level meter (row) and progress hairline.
                val band = tempoBand(s.bpm)
                BARS.forEachIndexed { i, v -> setViewVisibility(v, if (moving && i == band) View.VISIBLE else View.GONE) }
                setViewVisibility(R.id.w_bars_off, if (moving) View.GONE else View.VISIBLE)
                setProgressBar(R.id.w_line, 1000, if (live) s.progress() else 0, false)

                if (!small) {
                    // Header: lamp and the ON AIR sign.
                    pair(R.id.w_lamp_on, R.id.w_lamp_off, moving)
                    setTextViewText(R.id.w_onair, if (playing || !live) "ON AIR" else "PAUSED")
                    setInt(R.id.w_onair, "setBackgroundResource", if (playing) R.drawable.w_sign_on else R.drawable.w_sign_off)
                    setTextColor(R.id.w_onair, if (playing) 0xFF0B0B0B.toInt() else 0xFF5C5C5C.toInt())
                    setTextViewText(R.id.w_band, if (playing) "STEREO · 88.8 MHz" else if (live) "STEREO" else "")
                    setTextColor(R.id.w_band, if (playing) 0xFF8E8A84.toInt() else 0xFF4A4A4A.toInt())

                    // The dial window: backlight on while the app's up, needle seeking / resting / unlit.
                    setInt(R.id.w_glow, "setColorFilter", if (o.coverColour && s.hasTrack) s.tint else RED)
                    setInt(R.id.w_glow, "setImageAlpha", if (playing) 255 else if (live) 150 else 0)
                    pair(R.id.w_scale_lit, R.id.w_scale_off, live)
                    setViewVisibility(R.id.w_needle_a, if (moving && s.tune % 2 == 0) View.VISIBLE else View.GONE)
                    setViewVisibility(R.id.w_needle_b, if (moving && s.tune % 2 == 1) View.VISIBLE else View.GONE)
                    setViewVisibility(R.id.w_needle_on, if (live && !moving) View.VISIBLE else View.GONE)
                    setViewVisibility(R.id.w_needle_off, if (live) View.GONE else View.VISIBLE)
                    timer(s, live, playing)
                    setOnClickPendingIntent(R.id.w_shuffle, if (live) command(context, "shuffle", 4) else openApp(context, "shuffle", 4))
                }

                if (tall) {
                    val hero = o.hero
                    setViewVisibility(R.id.w_sampler, if (hero == WidgetOptions.Hero.PADS) View.VISIBLE else View.GONE)
                    setViewVisibility(R.id.w_record, if (hero == WidgetOptions.Hero.RECORD) View.VISIBLE else View.GONE)
                    setViewVisibility(R.id.w_cover, if (hero == WidgetOptions.Hero.COVER) View.VISIBLE else View.GONE)
                    // Every hero shows the cover big, so the title row drops its thumbnail.
                    setViewVisibility(R.id.w_art, View.GONE)
                    sampler(s, art, live, playing, moving, band)
                    // Record.
                    pair(R.id.w_sheen_on, R.id.w_sheen_off, moving)
                    setProgressBar(R.id.w_ring, 1000, if (live) s.progress() else 0, false)
                    val label = s.labelName?.let { artUri(context, it) }
                    if (label != null) setImageViewUri(R.id.w_label, label) else setImageViewResource(R.id.w_label, R.drawable.w_label_blank)
                    if (art != null) setImageViewUri(R.id.w_cover, art) else setImageViewResource(R.id.w_cover, R.mipmap.ic_launcher)

                    val canFav = live && s.canFav
                    setBoolean(R.id.w_fav, "setEnabled", canFav)
                    setFloat(R.id.w_fav, "setAlpha", if (canFav) 1f else 0.35f)
                    if (Build.VERSION.SDK_INT >= 31) {
                        setCompoundButtonChecked(R.id.w_fav, canFav && s.fav)
                        if (canFav) setOnCheckedChangeResponse(R.id.w_fav, RemoteViews.RemoteResponse.fromPendingIntent(command(context, "fav", 5)))
                    } else {
                        setImageViewResource(R.id.w_fav, if (canFav && s.fav) R.drawable.w_heart_on else R.drawable.w_heart_off)
                        if (canFav) setOnClickPendingIntent(R.id.w_fav, command(context, "fav", 5))
                    }
                }

                // Keys. App not running: playing needs the app (Android won't let a widget start
                // playback in the background), so they open it and resume the last session or shuffle.
                val open = openApp(context, null, 0)
                setOnClickPendingIntent(android.R.id.background, open)
                setOnClickPendingIntent(R.id.w_art, open)
                if (tall) {
                    setOnClickPendingIntent(R.id.w_record, open)
                    setOnClickPendingIntent(R.id.w_cover, open)
                    setOnClickPendingIntent(R.id.w_sampler, open)
                }
                val start = openApp(context, if (s.hasTrack) "resume" else "shuffle", 1)
                val playIntent = if (live) command(context, "play_pause", 1) else start
                if (Build.VERSION.SDK_INT >= 31) {
                    setCompoundButtonChecked(R.id.w_play, playing)
                    setOnCheckedChangeResponse(R.id.w_play, RemoteViews.RemoteResponse.fromPendingIntent(playIntent))
                } else {
                    setImageViewResource(R.id.w_play, if (playing) R.drawable.w_ic_pause else R.drawable.w_ic_play)
                    setOnClickPendingIntent(R.id.w_play, playIntent)
                }
                setOnClickPendingIntent(R.id.w_next, if (live) command(context, "next", 2) else start)
                setOnClickPendingIntent(R.id.w_prev, if (live) command(context, "previous", 3) else start)
            }
        }

        /**
         * The sampler: pads finger-drumming at about the track's tempo (one animation per tempo band),
         * dark when paused; the LCD shows the cover, sequence (track) number, BPM, release and time,
         * with its backlight off when the app is closed.
         */
        private fun RemoteViews.sampler(s: WidgetState, art: Uri?, live: Boolean, playing: Boolean, moving: Boolean, band: Int) {
            PADS.forEachIndexed { i, v -> setViewVisibility(v, if (moving && i == band) View.VISIBLE else View.GONE) }
            setViewVisibility(R.id.w_pads_rest, if (live && !moving) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.w_pads_off, if (live) View.GONE else View.VISIBLE)
            setInt(R.id.w_lcd, "setBackgroundResource", if (live) R.drawable.w_lcd_on else R.drawable.w_lcd_off)
            val ink = if (live) 0xFF1C2617.toInt() else 0xFF59644F.toInt()
            listOf(R.id.w_lcd_seq, R.id.w_lcd_bpm, R.id.w_lcd_rel, R.id.w_lcd_state, R.id.w_lcd_time).forEach { setTextColor(it, ink) }
            if (art != null) setImageViewUri(R.id.w_lcd_art, art) else setImageViewResource(R.id.w_lcd_art, R.mipmap.ic_launcher)
            setInt(R.id.w_lcd_art, "setImageAlpha", if (live) 255 else 110)
            setTextViewText(R.id.w_lcd_seq, "SEQ " + (s.trackNo?.let { "%02d".format(it) } ?: "--"))
            setTextViewText(R.id.w_lcd_bpm, "BPM " + (s.bpm?.let { "%03d".format(it) } ?: "---"))
            setTextViewText(R.id.w_lcd_rel, if (s.hasTrack) s.release.ifEmpty { s.artist }.uppercase() else "88.8FM")
            setTextViewText(R.id.w_lcd_state, if (playing) "▶ PLAY" else if (live) "❚❚ PAUSED" else if (s.hasTrack) "■ STOP" else "READY")
            setViewVisibility(R.id.w_lcd_time, if (live) View.VISIBLE else View.GONE)
            if (live) {
                val ticking = playing && s.startedAt > 0
                setChronometer(R.id.w_lcd_time, if (ticking) s.startedAt else SystemClock.elapsedRealtime() - s.positionMs, null, ticking)
            }
        }

        private fun RemoteViews.pair(on: Int, off: Int, moving: Boolean) {
            setViewVisibility(on, if (moving) View.VISIBLE else View.GONE)
            setViewVisibility(off, if (moving) View.GONE else View.VISIBLE)
        }

        /** Which level-meter animation fits this tempo (unknown tempo: a relaxed 90). */
        @VisibleForTesting
        fun tempoBand(bpm: Int?): Int {
            val b = bpm?.takeIf { it in 40..220 }?.let { if (it < 60) it * 2 else if (it > 160) it / 2 else it } ?: 90
            return TEMPOS.indices.minBy { kotlin.math.abs(TEMPOS[it] - b) }
        }

        /** 1:42 / 3:08 ticking by itself while playing, and the sleep countdown. */
        private fun RemoteViews.timer(s: WidgetState, live: Boolean, playing: Boolean) {
            val show = if (live) View.VISIBLE else View.GONE
            setViewVisibility(R.id.w_time, show)
            setViewVisibility(R.id.w_dur, show)
            if (live) {
                // startedAt is only set once audio is really moving (not while buffering).
                val ticking = playing && s.startedAt > 0
                val base = if (ticking) s.startedAt else SystemClock.elapsedRealtime() - s.positionMs
                setChronometer(R.id.w_time, base, null, ticking)
                setTextViewText(R.id.w_dur, if (s.durationMs > 0) " / ${clock(s.durationMs)}" else "")
            }
            val sleeping = live && (s.sleepAt != null || s.sleepEndOfTrack)
            setViewVisibility(R.id.w_sleep, if (sleeping) View.VISIBLE else View.GONE)
            val countdown = sleeping && s.sleepAt != null
            setViewVisibility(R.id.w_sleep_time, if (countdown) View.VISIBLE else View.GONE)
            if (sleeping) setTextViewText(R.id.w_sleep, if (countdown) "zz" else "zz end of track")
            if (countdown) {
                val left = (s.sleepAt!! - System.currentTimeMillis()).coerceAtLeast(0)
                setChronometer(R.id.w_sleep_time, SystemClock.elapsedRealtime() + left, null, true)
                setChronometerCountDown(R.id.w_sleep_time, true)
            }
        }

        private fun clock(ms: Long): String {
            val t = ms / 1000
            return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60)
        }

        private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
            (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
            (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
            (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt(),
        )

        /**
         * The dial's backlight colour from the cover: the most colourful part of it, lifted to a glow.
         * Grey or black-and-white covers fall back to 88.8 rose.
         */
        fun tintFrom(art: Bitmap): Int {
            val small = Bitmap.createScaledBitmap(art, 16, 16, true)
            val hsv = FloatArray(3)
            // Weight each pixel's hue by how colourful and bright it is.
            var x = 0.0; var y = 0.0; var weight = 0.0
            for (py in 0 until 16) for (px in 0 until 16) {
                Color.colorToHSV(small.getPixel(px, py), hsv)
                val wgt = (hsv[1] * hsv[2]).toDouble().let { it * it }
                val rad = Math.toRadians(hsv[0].toDouble())
                x += kotlin.math.cos(rad) * wgt; y += kotlin.math.sin(rad) * wgt; weight += wgt
            }
            if (small !== art) small.recycle()
            if (weight / 256 < 0.02) return RED
            val hue = ((Math.toDegrees(kotlin.math.atan2(y, x)) + 360) % 360).toFloat()
            return Color.HSVToColor(floatArrayOf(hue, 0.62f, 1f))
        }

        /** The cover cut into a circle with a spindle hole, for the record label. */
        fun labelFrom(art: Bitmap, size: Int = 180): Bitmap {
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val scaled = Bitmap.createScaledBitmap(art, size, size, true)
            val canvas = Canvas(out)
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) })
            canvas.drawCircle(size / 2f, size / 2f, size / 2f - 1.5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = 0x33000000 })
            canvas.drawCircle(size / 2f, size / 2f, size * 0.045f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0B0B0B.toInt() })
            return out
        }

        private fun command(context: Context, what: String, code: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context, code,
                Intent(context, PlayerWidget::class.java).setAction(ACTION).putExtra(EXTRA, what),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun openApp(context: Context, action: String?, code: Int): PendingIntent =
            PendingIntent.getActivity(
                context, 100 + code,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .apply { if (action != null) putExtra(EXTRA_WIDGET_ACTION, action) },
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, mgr, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle) {
        render(context, mgr, id)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        WidgetStore.forget(context, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION) return
        val service = PlaybackService.instance
        if (service == null) {
            refresh(context) // service went away: show the idle buttons
            return
        }
        service.widgetCommand(intent.getStringExtra(EXTRA) ?: return)
    }
}
