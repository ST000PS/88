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
    /** The card's colour, taken from the cover. */
    val tint: Int = DEFAULT_TINT,
    val hasTrack: Boolean = false,
    val fav: Boolean = false,
    val canFav: Boolean = false,
    val bpm: Int? = null,
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
        private const val GREY = 0xFF7A7A7A.toInt()
        private val TEMPOS = intArrayOf(70, 80, 90, 100, 115, 135)
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
                        SizeF(250f, 120f) to build(context, R.layout.widget_large, o),
                        SizeF(250f, 230f) to build(context, R.layout.widget_tall, o),
                    ),
                )
            } else {
                val h = mgr.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 40)
                build(context, if (h >= 230) R.layout.widget_tall else if (h >= 120) R.layout.widget_large else R.layout.widget_small, o)
            }
            mgr.updateAppWidget(id, views)
        }

        @VisibleForTesting
        fun build(context: Context, layout: Int, o: WidgetOptions, s: WidgetState = state(context)): RemoteViews {
            val live = s.hasTrack && live()
            val playing = live && s.playing
            val moving = playing && o.motion
            val small = layout == R.layout.widget_small
            val tall = layout == R.layout.widget_tall
            val art = s.artName?.let { artUri(context, it) }
            return RemoteViews(context.packageName, layout).apply {
                // Card colour: the cover's, dimmed when the app is closed.
                val tint = if (!o.coverColour || !s.hasTrack) WidgetState.DEFAULT_TINT else if (live) s.tint else blend(s.tint, WidgetState.DEFAULT_TINT, 0.5f)
                setInt(R.id.w_bg, "setColorFilter", tint)

                // Words.
                setTextViewText(R.id.w_title, if (s.hasTrack) s.title else "88.8FM")
                setTextColor(R.id.w_title, if (live) 0xFFEDEDED.toInt() else 0xFFBDBDBD.toInt())
                val idle = when {
                    s.hasTrack && !live && s.todayMinutes > 0 -> "Tap play to resume · today ${s.todayMinutes} min"
                    s.hasTrack && !live -> "Tap play to resume"
                    s.todayMinutes > 0 -> "Today · ${s.todayMinutes} min on 88.8 · tap play"
                    else -> "Tap play to tune in"
                }
                setTextViewText(R.id.w_sub, if (live && s.hasTrack) s.subtitle else idle)
                setViewVisibility(R.id.w_flip, if (playing) View.VISIBLE else View.GONE)
                setViewVisibility(R.id.w_sub, if (playing) View.GONE else View.VISIBLE)
                if (playing) {
                    setTextViewText(R.id.w_f1, s.subtitle)
                    setTextViewText(R.id.w_f2, s.detail.ifEmpty { s.subtitle })
                    setTextViewText(R.id.w_f3, s.upNext.ifEmpty { s.detail.ifEmpty { "thepowerstone.com" } })
                }

                // Pictures.
                if (art != null) setImageViewUri(R.id.w_art, art) else setImageViewResource(R.id.w_art, R.mipmap.ic_launcher)
                setInt(R.id.w_art, "setImageAlpha", if (live) 255 else 190)

                // Motion: each animation has a still twin for paused / closed / motion off.
                val band = tempoBand(s.bpm)
                BARS.forEachIndexed { i, v -> setViewVisibility(v, if (moving && i == band) View.VISIBLE else View.GONE) }
                setViewVisibility(R.id.w_bars_off, if (moving) View.GONE else View.VISIBLE)
                setProgressBar(R.id.w_line, 1000, if (live) s.progress() else 0, false)
                setViewVisibility(R.id.w_line, if (live) View.VISIBLE else View.INVISIBLE)

                if (!small) {
                    pair(R.id.w_lamp_on, R.id.w_lamp_off, moving)
                    setTextViewText(R.id.w_onair, if (playing) "ON AIR" else if (live) "PAUSED" else "OFF AIR")
                    setTextColor(R.id.w_onair, if (playing) RED else GREY)
                    // The needle: sweeping in and drifting / resting on 88.8 / parked off-station.
                    setViewVisibility(R.id.w_needle_a, if (moving && s.tune % 2 == 0) View.VISIBLE else View.GONE)
                    setViewVisibility(R.id.w_needle_b, if (moving && s.tune % 2 == 1) View.VISIBLE else View.GONE)
                    setViewVisibility(R.id.w_needle_on, if (live && !moving) View.VISIBLE else View.GONE)
                    setViewVisibility(R.id.w_needle_parked, if (live) View.GONE else View.VISIBLE)
                    timer(s, live, playing)
                    setOnClickPendingIntent(R.id.w_shuffle, if (live) command(context, "shuffle", 4) else openApp(context, "shuffle", 4))
                }

                if (tall) {
                    setViewVisibility(R.id.w_record, if (o.record) View.VISIBLE else View.GONE)
                    setViewVisibility(R.id.w_cover, if (o.record) View.GONE else View.VISIBLE)
                    // The record has its own progress ring; the plain cover uses the hairline.
                    if (o.record) setViewVisibility(R.id.w_line, View.GONE)
                    pair(R.id.w_sheen_on, R.id.w_sheen_off, moving)
                    setProgressBar(R.id.w_ring, 1000, if (live) s.progress() else 0, false)
                    val label = s.labelName?.let { artUri(context, it) }
                    if (label != null) setImageViewUri(R.id.w_label, label) else setImageViewResource(R.id.w_label, R.drawable.w_label_blank)
                    setViewVisibility(R.id.w_next_line, if (live && s.upNext.isNotEmpty()) View.VISIBLE else View.GONE)
                    setTextViewText(R.id.w_next_line, s.upNext.uppercase())
                    if (art != null) setImageViewUri(R.id.w_cover, art) else setImageViewResource(R.id.w_cover, R.mipmap.ic_launcher)
                    setTextViewText(R.id.w_freq, if (playing) "88.8 MHz · LOCKED" else "88.8 MHz")

                    val showFav = live && s.canFav
                    setViewVisibility(R.id.w_fav, if (showFav) View.VISIBLE else View.GONE)
                    if (showFav) {
                        if (Build.VERSION.SDK_INT >= 31) {
                            setCompoundButtonChecked(R.id.w_fav, s.fav)
                            setOnCheckedChangeResponse(R.id.w_fav, RemoteViews.RemoteResponse.fromPendingIntent(command(context, "fav", 5)))
                        } else {
                            setImageViewResource(R.id.w_fav, if (s.fav) R.drawable.w_heart_on else R.drawable.w_heart_off)
                            setOnClickPendingIntent(R.id.w_fav, command(context, "fav", 5))
                        }
                    }
                }

                // Buttons. App not running: playing needs the app (Android won't let a widget start
                // playback in the background), so they open it and resume the last session or shuffle.
                val open = openApp(context, null, 0)
                setOnClickPendingIntent(android.R.id.background, open)
                setOnClickPendingIntent(R.id.w_art, open)
                if (tall) {
                    setOnClickPendingIntent(R.id.w_record, open)
                    setOnClickPendingIntent(R.id.w_cover, open)
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

        /** A deep, muted version of the cover's average colour: each track changes the card's mood. */
        fun tintFrom(art: Bitmap): Int {
            val small = Bitmap.createScaledBitmap(art, 12, 12, true)
            var r = 0L; var g = 0L; var b = 0L
            for (y in 0 until 12) for (x in 0 until 12) {
                val c = small.getPixel(x, y)
                r += Color.red(c); g += Color.green(c); b += Color.blue(c)
            }
            if (small !== art) small.recycle()
            val hsv = FloatArray(3)
            Color.RGBToHSV((r / 144).toInt(), (g / 144).toInt(), (b / 144).toInt(), hsv)
            hsv[1] = (hsv[1] * 0.9f).coerceAtMost(0.6f)
            hsv[2] = 0.30f
            return Color.HSVToColor(hsv)
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
