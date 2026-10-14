package com.st000ps.powerstone.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import com.st000ps.powerstone.R
import com.st000ps.powerstone.playback.PlaybackService
import com.st000ps.powerstone.ui.MainActivity

/** What the widget shows. Lives in memory; when the app isn't running the widget shows the idle state. */
data class WidgetState(
    val title: String = "",
    val subtitle: String = "",
    val playing: Boolean = false,
    val art: Bitmap? = null,
    val hasTrack: Boolean = false,
)

/**
 * Home-screen player: a 4×1 row (art, title, prev / play / next) that grows into a card with
 * bigger art and shuffle-everything. While music is loaded the buttons control the playback
 * service directly; when the app isn't running they open it and resume (or shuffle).
 */
class PlayerWidget : AppWidgetProvider() {
    companion object {
        private const val ACTION = "com.st000ps.powerstone.widget.ACTION"
        private const val EXTRA = "what"
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
                RemoteViews(mapOf(SizeF(180f, 40f) to build(context, R.layout.widget_small), SizeF(250f, 110f) to build(context, R.layout.widget_large)))
            } else {
                val h = mgr.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 40)
                build(context, if (h >= 110) R.layout.widget_large else R.layout.widget_small)
            }
            mgr.updateAppWidget(id, views)
        }

        private fun build(context: Context, layout: Int): RemoteViews {
            val s = state
            val live = s.hasTrack && PlaybackService.isRunning
            return RemoteViews(context.packageName, layout).apply {
                setTextViewText(R.id.w_title, if (live) s.title else "88.8FM")
                setTextViewText(R.id.w_sub, if (live) s.subtitle else if (s.hasTrack) "Tap play to resume" else "Shuffle the catalogue")
                if (live && s.art != null) setImageViewBitmap(R.id.w_art, s.art) else setImageViewResource(R.id.w_art, R.mipmap.ic_launcher)
                setImageViewResource(R.id.w_play, if (live && s.playing) R.drawable.ic_w_pause else R.drawable.ic_w_play)
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
                if (layout == R.layout.widget_large) setOnClickPendingIntent(R.id.w_shuffle,
                    if (live) command(context, "shuffle", 4) else openApp(context, "shuffle", 4))
            }
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
