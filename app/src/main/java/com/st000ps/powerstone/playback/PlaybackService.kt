package com.st000ps.powerstone.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.st000ps.powerstone.app
import com.st000ps.powerstone.data.PlayEvent
import com.st000ps.powerstone.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Runs the player in the background so audio keeps going with the screen off,
 * and exposes it to the notification, lock screen, headphones and Bluetooth.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var tracker: PlayTracker

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // Same OkHttp client (and cookie jar) as the API, so full-length streams are authorised.
        val dataSource = OkHttpDataSource.Factory(app.api.http)
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSource))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        tracker = PlayTracker(player)
        player.addListener(tracker)

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player).setSessionActivity(openApp).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            tracker.finish()
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /** Mirrors the site's tracker: a play counts after 10s of actual listening. */
    private inner class PlayTracker(private val player: Player) : Player.Listener {
        private var currentId: String? = null
        private var startedAt = 0L
        private var listenedMs = 0L
        private var resumedAt = 0L
        private val buffer = mutableListOf<PlayEvent>()

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            finish(flush = false)
            begin(mediaItem)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (currentId == null) begin(player.currentMediaItem)
            val now = SystemClock.elapsedRealtime()
            if (isPlaying) resumedAt = now
            else if (resumedAt != 0L) { listenedMs += now - resumedAt; resumedAt = 0L; maybeFlush() }
        }

        private fun begin(item: MediaItem?) {
            currentId = item?.mediaId?.takeIf { it.contains('/') }
            startedAt = System.currentTimeMillis()
            listenedMs = 0
            resumedAt = if (player.isPlaying) SystemClock.elapsedRealtime() else 0L
        }

        fun finish(flush: Boolean = true) {
            if (resumedAt != 0L) listenedMs += SystemClock.elapsedRealtime() - resumedAt
            val id = currentId
            if (id != null && listenedMs >= 10_000) {
                val (rel, n) = id.substringBeforeLast('/') to id.substringAfterLast('/').toIntOrNull()
                if (n != null) buffer += PlayEvent(r = rel, n = n, ts = startedAt, ms = listenedMs)
            }
            currentId = null; listenedMs = 0; resumedAt = 0L
            if (flush) send() else maybeFlush()
        }

        private fun maybeFlush() { if (buffer.size >= 5) send() }

        private fun send() {
            if (buffer.isEmpty()) return
            val batch = buffer.toList(); buffer.clear()
            val api = app.api
            // Fire and forget on the process-wide IO pool so it survives service teardown.
            CoroutineScope(Dispatchers.IO).launch {
                if (api.me().signedIn) api.reportPlays(batch)
            }
        }
    }
}
