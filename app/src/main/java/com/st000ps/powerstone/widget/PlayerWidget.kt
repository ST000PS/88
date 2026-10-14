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
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.SizeF
import android.view.View
import android.widget.RemoteViews
import com.st000ps.powerstone.R
import com.st000ps.powerstone.playback.PlaybackService
import com.st000ps.powerstone.ui.MainActivity

/** What the widget shows. Lives in memory; when the app isn't running the widget shows the idle state. */
data class WidgetState(
    val title: String = "",
    val subtitle: String = "",
    /** Rotates with [subtitle] while playing: BPM / key / track number, and what's up next. */
    val detail: String = "",
    val upNext: String = "",
    val playing: Boolean = false,
    val art: Bitmap? = null,
    /** The cover cut into a circle: the record label on the big card. */
    val label: Bitmap? = null,
    /** The card's colour, taken from the cover. */
    val tint: Int = DEFAULT_TINT,
    val hasTrack: Boolean = false,
    val fav: Boolean = false,
    val canFav: Boolean = false,
    /** Track position: while playing, the elapsedRealtime at which the track was at 0:00; while paused, [positionMs]. */
    val startedAt: Long = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    /** Sleep timer: wall-clock end, or "at the end of this track". */
    val sleepAt: Long? = null,
    val sleepEndOfTrack: Boolean = false,
) {
    /** Same picture, ignoring how the clock drifted between two looks at the player. */
    fun sameAs(o: WidgetState?): Boolean =
        o != null && copy(startedAt = 0, positionMs = 0) == o.copy(startedAt = 0, positionMs = 0) &&
            kotlin.math.abs(startedAt - o.startedAt) < 400 && kotlin.math.abs(positionMs - o.positionMs) < 1000

    companion object {
        val DEFAULT_TINT = Color.rgb(0x1C, 0x1C, 0x1E)
    }
}

/**
 * Home-screen player, in the site's look (monospace, 88.8 red on near-black, FM radio):
 *  - 4×1 row: art, title, a bouncing level meter beside the info line, previous / play / next.
 *  - Card: an ON AIR lamp, a ticking track timer, and an FM tuner strip whose needle drifts on 88.8.
 *  - Big card: the cover becomes the label of a record that spins, plus favourite and shuffle.
 * The card takes its colour from the cover. Everything that moves is an animated vector run by the
 * launcher (in an indeterminate ProgressBar) and the timers are Chronometers, so nothing is redrawn
 * by the app while it plays; when paused or closed, each animation is swapped for a still image.
 * While music is loaded the buttons control the playback service directly; when the app isn't
 * running they open it and resume (or shuffle).
 */
class PlayerWidget : AppWidgetProvider() {
    companion object {
        private const val ACTION = "com.st000ps.powerstone.widget.ACTION"
        private const val EXTRA = "what"
        private const val RED = 0xFFC04A5E.toInt()
        private const val GREY = 0xFF7A7A7A.toInt()
        /** Passed to MainActivity when the widget opens the app: "resume" or "shuffle". */
        const val EXTRA_WIDGET_ACTION = "widget_action"

        @Volatile var state = WidgetState()
            private set

        fun update(context: Context, newState: WidgetState) {
            state = newState
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, PlayerWidget::class.java))
            ids.forEach { render(context, mgr, it) }
        }

        private fun render(context: Context, mgr: AppWidgetManager, id: Int) {
            val views = if (Build.VERSION.SDK_INT >= 31) {
                // Let the launcher pick the layout for whatever size the widget is.
                RemoteViews(
                    mapOf(
                        SizeF(180f, 40f) to build(context, R.layout.widget_small),
                        SizeF(250f, 120f) to build(context, R.layout.widget_large),
                        SizeF(250f, 230f) to build(context, R.layout.widget_tall),
                    ),
                )
            } else {
                val h = mgr.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 40)
                build(context, if (h >= 230) R.layout.widget_tall else if (h >= 120) R.layout.widget_large else R.layout.widget_small)
            }
            mgr.updateAppWidget(id, views)
        }

        private fun build(context: Context, layout: Int): RemoteViews {
            val s = state
            val live = s.hasTrack && PlaybackService.isRunning
            val playing = live && s.playing
            val small = layout == R.layout.widget_small
            val tall = layout == R.layout.widget_tall
            return RemoteViews(context.packageName, layout).apply {
                setInt(R.id.w_bg, "setColorFilter", if (live) s.tint else WidgetState.DEFAULT_TINT)
                setTextViewText(R.id.w_title, if (live) s.title else "88.8FM")
                val idle = if (s.hasTrack) "Tap play to resume" else "Shuffle the catalogue"
                setTextViewText(R.id.w_sub, if (live) s.subtitle else idle)
                if (live && s.art != null) setImageViewBitmap(R.id.w_art, s.art) else setImageViewResource(R.id.w_art, R.mipmap.ic_launcher)
                setImageViewResource(R.id.w_play, if (playing) R.drawable.ic_w_pause else R.drawable.ic_w_play)

                // Motion only while music plays: each animation has a still twin for paused / closed.
                animate(R.id.w_bars_on, R.id.w_bars_off, playing)
                setViewVisibility(R.id.w_flip, if (playing) View.VISIBLE else View.GONE)
                setViewVisibility(R.id.w_sub, if (playing) View.GONE else View.VISIBLE)
                if (playing) {
                    setTextViewText(R.id.w_f1, s.subtitle)
                    setTextViewText(R.id.w_f2, s.detail.ifEmpty { s.subtitle })
                    setTextViewText(R.id.w_f3, s.upNext.ifEmpty { s.detail.ifEmpty { "thepowerstone.com" } })
                }

                if (!small) {
                    animate(R.id.w_lamp_on, R.id.w_lamp_off, playing)
                    animate(R.id.w_needle_on, R.id.w_needle_off, playing)
                    setTextViewText(R.id.w_onair, if (playing) "ON AIR" else if (live) "PAUSED" else "OFF AIR")
                    setTextColor(R.id.w_onair, if (playing) RED else GREY)
                    timer(s, live, playing)
                }
                if (tall) {
                    animate(R.id.w_sheen_on, R.id.w_sheen_off, playing)
                    if (live && s.label != null) setImageViewBitmap(R.id.w_label, s.label) else setImageViewResource(R.id.w_label, R.mipmap.ic_launcher)
                    val showFav = live && s.canFav
                    setViewVisibility(R.id.w_fav, if (showFav) View.VISIBLE else View.GONE)
                    if (showFav) {
                        setImageViewResource(R.id.w_fav, if (s.fav) R.drawable.ic_heart else R.drawable.ic_heart_outline)
                        setInt(R.id.w_fav, "setColorFilter", if (s.fav) RED else Color.WHITE)
                        setOnClickPendingIntent(R.id.w_fav, command(context, "fav", 5))
                    }
                }

                setOnClickPendingIntent(R.id.w_root, openApp(context, null, 0))
                setOnClickPendingIntent(R.id.w_art, openApp(context, null, 0))
                if (live) {
                    setOnClickPendingIntent(R.id.w_play, command(context, "play_pause", 1))
                    setOnClickPendingIntent(R.id.w_next, command(context, "next", 2))
                    setOnClickPendingIntent(R.id.w_prev, command(context, "previous", 3))
                } else {
                    // App not running: playing needs the app (Android won't let a widget start playback
                    // in the background), so open it and resume the last session or start shuffling.
                    val start = openApp(context, if (s.hasTrack) "resume" else "shuffle", 1)
                    setOnClickPendingIntent(R.id.w_play, start)
                    setOnClickPendingIntent(R.id.w_next, start)
                    setOnClickPendingIntent(R.id.w_prev, start)
                }
                if (!small) setOnClickPendingIntent(R.id.w_shuffle,
                    if (live) command(context, "shuffle", 4) else openApp(context, "shuffle", 4))
            }
        }

        private fun RemoteViews.animate(on: Int, off: Int, moving: Boolean) {
            setViewVisibility(on, if (moving) View.VISIBLE else View.GONE)
            setViewVisibility(off, if (moving) View.GONE else View.VISIBLE)
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

        /** The cover cut into a circle, for the record label. */
        fun labelFrom(art: Bitmap, size: Int = 144): Bitmap {
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val scaled = Bitmap.createScaledBitmap(art, size, size, true)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(scaled, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            Canvas(out).drawCircle(size / 2f, size / 2f, size / 2f, paint)
            // The spindle hole.
            Canvas(out).drawCircle(size / 2f, size / 2f, size * 0.045f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0B0B0B.toInt() })
            return out
        }

        private fun command(context: Context, what: String, code: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context, code,
                Intent(context, PlayerWidget::class.java).setAction(ACTION).putExtra(EXTRA, what),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        private fun openApp(context: Context, action: String?, code: Int): PendingIntent =
            PendingIntent.getActivity(
                context, 100 + code,
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .apply { if (action != null) putExtra(EXTRA_WIDGET_ACTION, action) },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { render(context, mgr, it) }
    }

    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle) {
        render(context, mgr, id)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION) return
        val service = PlaybackService.instance
        if (service == null) {
            update(context, state) // service went away: show the idle buttons
            return
        }
        service.widgetCommand(intent.getStringExtra(EXTRA) ?: return)
    }
}
