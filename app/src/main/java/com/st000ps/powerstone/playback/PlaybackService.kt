package com.st000ps.powerstone.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.graphics.drawable.toBitmap
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.st000ps.powerstone.R
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin
import com.st000ps.powerstone.app
import com.st000ps.powerstone.audio.DjEqProcessor
import com.st000ps.powerstone.audio.VinylPlayer
import com.st000ps.powerstone.data.PlayEvent
import com.st000ps.powerstone.data.SavedSession
import com.st000ps.powerstone.data.SleepState
import com.st000ps.powerstone.data.resolve
import com.st000ps.powerstone.ui.LockScreenActivity
import com.st000ps.powerstone.widget.PlayerWidget
import com.st000ps.powerstone.widget.WidgetState
import coil.imageLoader
import coil.request.ImageRequest
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Runs the player in the background so audio keeps going with the screen off,
 * and exposes it to the notification, lock screen, headphones and Bluetooth.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    companion object {
        /** The running service, for the home-screen widget's buttons (same process). */
        @Volatile var instance: PlaybackService? = null
            private set
        val isRunning get() = instance != null
    }

    private var session: MediaSession? = null
    private lateinit var exo: ExoPlayer
    private lateinit var tracker: PlayTracker
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private val sleepRunnable = Runnable { onSleepTimer() }
    private val saveRunnable = object : Runnable {
        override fun run() { saveSession(); if (exo.isPlaying) handler.postDelayed(this, 10_000) }
    }
    private var artUrl: String? = null
    private var art: android.graphics.Bitmap? = null
    private var artJob: Job? = null
    private var lastWidget: WidgetState? = null
    private lateinit var vinyl: VinylPlayer
    private var fadeInStart = 0L
    private val fadeTick = object : Runnable {
        override fun run() { updateFade(); if (exo.isPlaying) handler.postDelayed(this, 50) }
    }
    private val favCommand = SessionCommand("fav", android.os.Bundle.EMPTY)
    private val shuffleCommand = SessionCommand("shuffle", android.os.Bundle.EMPTY)

    override fun onCreate() {
        super.onCreate()
        // Same OkHttp client (and cookie jar) as the API, so full-length streams are authorised.
        val dataSource = OkHttpDataSource.Factory(app.api.http)
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(DjEqProcessor()))
                    .setEnableFloatOutput(false)
                    .build()
        }
        exo = ExoPlayer.Builder(this, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(dataSource))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

        tracker = PlayTracker(exo)
        exo.addListener(tracker)
        exo.addListener(QueueExtender(exo))
        exo.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                pushWidget()
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) updateButtons()
                if (events.contains(Player.EVENT_IS_PLAYING_CHANGED) && player.isPlaying) { handler.removeCallbacks(fadeTick); handler.post(fadeTick) }
                if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                    handler.removeCallbacks(saveRunnable)
                    if (player.isPlaying) handler.postDelayed(saveRunnable, 10_000) else saveSession()
                }
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) saveSession()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Fade the new track in only when it followed on by itself (not a skip or a new queue).
                fadeInStart = if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) SystemClock.elapsedRealtime() else 0L
                updateFade()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // Sleep timer "end of track": ExoPlayer paused at the end of the item; that was the one.
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && app.sleep.value.endOfTrack) {
                    app.sleep.value = SleepState()
                }
            }
        })
        app.sleep.onEach(::applySleep).launchIn(scope)
        app.favs.onEach { updateButtons() }.launchIn(scope)

        // Tapping the media notification: over the lock screen it opens the lock-screen player,
        // otherwise it goes straight to the app.
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, LockScreenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        vinyl = VinylPlayer(exo, app.settings)
        session = MediaSession.Builder(this, vinyl)
            .setSessionActivity(openApp)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
                    MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(
                            MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(favCommand).add(shuffleCommand).build(),
                        )
                        .build()

                // The heart and shuffle buttons in the notification / lock screen / quick settings.
                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: android.os.Bundle,
                ): ListenableFuture<SessionResult> {
                    when (customCommand.customAction) {
                        favCommand.customAction -> toggleFav()
                        shuffleCommand.customAction -> widgetCommand("shuffle")
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }

                // Bluetooth play / Android's media resumption after the app was closed: the last queue.
                override fun onPlaybackResumption(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                    val restored = restoredItems()
                        ?: return Futures.immediateFailedFuture(UnsupportedOperationException("Nothing to resume"))
                    return Futures.immediateFuture(restored)
                }
            })
            .build()
        instance = this
        restoreSession()
        pushWidget()
        updateButtons()
    }

    // ---------- notification / lock-screen buttons ----------

    private fun toggleFav() {
        val id = exo.currentMediaItem?.mediaId ?: return
        if (!app.me.value.signedIn) return
        scope.launch { app.setFav(id, id !in app.favs.value) }
    }

    /** Heart (filled when the current track is a favourite) and shuffle-everything. */
    private fun updateButtons() {
        val s = session ?: return
        val id = exo.currentMediaItem?.mediaId
        val buttons = buildList {
            if (app.me.value.signedIn && id != null) {
                val fav = id in app.favs.value
                add(CommandButton.Builder().setDisplayName(if (fav) "Remove favourite" else "Favourite")
                    .setIconResId(if (fav) R.drawable.ic_heart else R.drawable.ic_heart_outline)
                    .setSessionCommand(favCommand).build())
            }
            add(CommandButton.Builder().setDisplayName("Shuffle everything").setIconResId(R.drawable.ic_w_shuffle)
                .setSessionCommand(shuffleCommand).build())
        }
        s.setCustomLayout(buttons)
    }

    // ---------- crossfade (fade out / fade in) ----------

    /**
     * Android's player can't overlap two tracks, so "crossfade" here fades the end of a track out and
     * the next one in, through the player volume (applied at the speaker, so it lines up with what you
     * hear). Shuffles and playlists only; albums stay gapless.
     */
    private fun updateFade() {
        val ms = app.settings.crossfade.value * 1000L
        val allowed = ms > 0 && Queue.kindOf(exo.currentMediaItem) != QueueKind.ALBUM &&
            exo.repeatMode == Player.REPEAT_MODE_OFF && !exo.pauseAtEndOfMediaItems
        if (!allowed) { vinyl.fadeGain = 1f; return }
        var g = 1.0
        val dur = exo.duration
        if (dur > 0 && exo.hasNextMediaItem()) {
            val rem = (dur - exo.currentPosition).coerceAtLeast(0)
            if (rem < ms) g = sin(rem.toDouble() / ms * PI / 2)
        }
        if (fadeInStart > 0) {
            val since = SystemClock.elapsedRealtime() - fadeInStart
            if (since < ms) g = min(g, sin(since.toDouble() / ms * PI / 2)) else fadeInStart = 0L
        }
        vinyl.fadeGain = g.toFloat().coerceIn(0f, 1f)
    }

    // ---------- resume where you left off ----------

    private fun saveSession() {
        if (exo.mediaItemCount == 0) return
        val ids = (0 until exo.mediaItemCount).map { exo.getMediaItemAt(it).mediaId }
        val kind = Queue.kindOf(exo.currentMediaItem)?.name ?: QueueKind.LIST.name
        app.session.save(SavedSession(ids, exo.currentMediaItemIndex, exo.currentPosition.coerceAtLeast(0), kind))
    }

    private fun restoredItems(): MediaSession.MediaItemsWithStartPosition? {
        val saved = app.session.load() ?: return null
        val (items, start) = saved.resolve(app.releases.value) ?: return null
        val kind = runCatching { QueueKind.valueOf(saved.kind) }.getOrDefault(QueueKind.LIST)
        val media = items.mapNotNull { (r, t) -> Queue.mediaItem(app, r, t, kind) }
        if (media.isEmpty()) return null
        val pos = if (saved.index in items.indices && start < media.size) saved.positionMs else 0L
        return MediaSession.MediaItemsWithStartPosition(media, start.coerceIn(0, media.lastIndex), pos)
    }

    /** Cue the last session (paused), so the app, widget and mini player open where you left off. */
    private fun restoreSession() {
        if (exo.mediaItemCount > 0) return
        val r = restoredItems() ?: return
        exo.setMediaItems(r.mediaItems, r.startIndex, r.startPositionMs)
        exo.prepare()
    }

    // ---------- sleep timer ----------

    private fun applySleep(s: SleepState) {
        handler.removeCallbacks(sleepRunnable)
        exo.pauseAtEndOfMediaItems = s.endOfTrack
        s.endAt?.let { handler.postDelayed(sleepRunnable, (it - System.currentTimeMillis()).coerceAtLeast(0)) }
    }

    private fun onSleepTimer() {
        app.sleep.value = SleepState()
        session?.player?.pause() // through the vinyl player: winds down like a turntable
    }

    // ---------- home-screen widget ----------

    /** Called by the widget's buttons (main thread). */
    fun widgetCommand(what: String) {
        val p = session?.player ?: return
        when (what) {
            "play_pause" -> if (p.isPlaying) p.pause() else { if (p.playbackState == Player.STATE_IDLE) p.prepare(); p.play() }
            "next" -> p.seekToNextMediaItem()
            "previous" -> p.seekToPrevious()
            "shuffle" -> {
                val picks = Queue.smartShuffle(app, 60).mapNotNull { (r, t) -> Queue.mediaItem(app, r, t, QueueKind.SHUFFLE) }
                if (picks.isNotEmpty()) { p.setMediaItems(picks, 0, 0); p.prepare(); p.play() }
            }
        }
    }

    private fun pushWidget() {
        val item = exo.currentMediaItem
        val md = exo.mediaMetadata
        val url = md.artworkUri?.toString()
        if (url != artUrl) {
            artUrl = url; art = null
            artJob?.cancel()
            if (url != null) artJob = scope.launch {
                val result = imageLoader.execute(ImageRequest.Builder(this@PlaybackService).data(url).size(200).allowHardware(false).build())
                // Small on purpose: widget updates travel over Binder, which caps their size.
                art = result.drawable?.toBitmap(200, 200)
                pushWidget()
            }
        }
        val next = WidgetState(
            title = md.title?.toString() ?: "",
            subtitle = listOfNotNull(md.artist, md.albumTitle).joinToString(" · "),
            playing = exo.playWhenReady && exo.playbackState != Player.STATE_ENDED && item != null,
            art = art,
            hasTrack = item != null,
        )
        if (next == lastWidget) return // only redraw when something visible changed
        lastWidget = next
        PlayerWidget.update(this, next)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        session?.run {
            saveSession()
            tracker.finish()
            exo.release()
            release()
        }
        session = null
        scope.cancel()
        PlayerWidget.update(this, PlayerWidget.state.copy(playing = false))
        super.onDestroy()
    }

    /**
     * Keeps music going when a queue runs out, like the site: an album rolls
     * into the next album, and shuffle keeps drawing unheard songs forever.
     */
    private inner class QueueExtender(private val player: ExoPlayer) : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            mediaItem?.mediaId?.let { Queue.markHeard(app, it) }
            if (player.repeatMode != Player.REPEAT_MODE_OFF) return
            val remaining = player.mediaItemCount - 1 - player.currentMediaItemIndex
            when (Queue.kindOf(mediaItem)) {
                QueueKind.SHUFFLE -> if (remaining < 3) {
                    // Avoid only what's recently queued; avoiding the whole queue would eventually exclude everything.
                    val inQueue = (maxOf(0, player.mediaItemCount - 50) until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
                    val more = Queue.smartShuffle(app, 50, inQueue).mapNotNull { (r, t) -> Queue.mediaItem(app, r, t, QueueKind.SHUFFLE) }
                    player.addMediaItems(more)
                }
                QueueKind.ALBUM -> if (remaining == 0) {
                    val relId = mediaItem?.mediaId?.substringBeforeLast('/') ?: return
                    val next = Queue.nextAlbum(app.releases.value, relId) ?: return
                    player.addMediaItems(next.tracks.mapNotNull { Queue.mediaItem(app, next, it, QueueKind.ALBUM) })
                }
                else -> Unit
            }
        }
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
                if (n != null) {
                    val e = PlayEvent(r = rel, n = n, ts = startedAt, ms = listenedMs)
                    buffer += e
                    app.scope.launch(Dispatchers.IO) { app.history.add(listOf(e)) }
                }
            }
            currentId = null; listenedMs = 0; resumedAt = 0L
            if (flush) send() else maybeFlush()
        }

        private fun maybeFlush() { if (buffer.size >= 5) send() }

        private fun send() {
            if (buffer.isEmpty()) return
            val batch = buffer.toList(); buffer.clear()
            val api = app.api
            app.scope.launch(Dispatchers.IO) {
                val me = app.me.value.takeIf { it.signedIn } ?: api.me().also { app.me.value = it }
                if (me.signedIn && !api.reportPlays(batch)) launch(Dispatchers.Main) { buffer.addAll(0, batch) }
            }
        }
    }
}
