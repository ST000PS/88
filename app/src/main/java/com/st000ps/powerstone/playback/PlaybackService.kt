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
import com.st000ps.powerstone.audio.Groove
import com.st000ps.powerstone.audio.GrooveAnalyzer
import com.st000ps.powerstone.audio.VinylPlayer
import com.st000ps.powerstone.data.PlayEvent
import com.st000ps.powerstone.data.SavedSession
import com.st000ps.powerstone.data.SleepState
import com.st000ps.powerstone.data.resolve
import com.st000ps.powerstone.data.trackId
import com.st000ps.powerstone.ui.LockScreenActivity
import com.st000ps.powerstone.widget.PlayerWidget
import com.st000ps.powerstone.widget.WidgetState
import com.st000ps.powerstone.widget.WidgetStore
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
import kotlinx.coroutines.withContext

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
    private var artFiles: Pair<String, String>? = null
    private var tune = 0
    private var todayMinutes = 0
    /** Moves the widget's progress ring once a second, while music plays and the screen is on. */
    private val widgetTick = object : Runnable {
        override fun run() {
            if (!exo.isPlaying) return
            maybeListenForGroove()
            val power = getSystemService(android.os.PowerManager::class.java)
            if (power?.isInteractive != false && PlayerWidget.ids(this@PlaybackService).isNotEmpty()) PlayerWidget.refresh(this@PlaybackService)
            handler.postDelayed(this, 1000)
        }
    }
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
        // Through the shared audio cache, with the API's client (and cookie jar) behind it so
        // full-length streams are authorised.
        val dataSource = MediaCache.factory(this)
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
                publishToWatch()
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) updateButtons()
                if (events.contains(Player.EVENT_IS_PLAYING_CHANGED) && player.isPlaying) {
                    handler.removeCallbacks(fadeTick); handler.post(fadeTick)
                    handler.removeCallbacks(widgetTick); handler.postDelayed(widgetTick, 1000)
                }
                if (events.contains(Player.EVENT_IS_PLAYING_CHANGED) || events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) StationTile.refresh(this@PlaybackService)
                if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                    if (!player.isPlaying) refreshToday()
                    handler.removeCallbacks(saveRunnable)
                    if (player.isPlaying) handler.postDelayed(saveRunnable, 10_000) else saveSession()
                }
                if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) saveSession()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Fade the new track in only when it followed on by itself (not a skip or a new queue).
                fadeInStart = if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) SystemClock.elapsedRealtime() else 0L
                updateFade()
                tune++ // the widget's tuner needle sweeps in again
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                // The site's session ended mid-listen (401/403 on a full track): sign back in
                // quietly with "stay signed in" and carry on from the same spot. Once a minute at most.
                val http = generateSequence(error.cause) { it.cause }.filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()
                if (http == null || http.responseCode !in setOf(401, 403)) return
                val now = SystemClock.elapsedRealtime()
                if (now - lastReauth < 60_000) return
                lastReauth = now
                scope.launch {
                    if (app.ensureSignedIn()?.signedIn == true) { exo.prepare(); exo.play() }
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // Sleep timer "end of track": ExoPlayer paused at the end of the item; that was the one.
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && app.sleep.value.endOfTrack) {
                    app.sleep.value = SleepState()
                }
            }
        })
        app.sleep.onEach { applySleep(it); pushWidget() }.launchIn(scope)
        app.favs.onEach { updateButtons(); pushWidget(); publishToWatch() }.launchIn(scope)
        app.settings.eq.onEach { publishToWatch() }.launchIn(scope)

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
        // Started by the widget, a Bluetooth play or media resumption: make sure the session is
        // live before the first full-length stream is requested.
        scope.launch { app.ensureSignedIn() }
        refreshToday()
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

    /** For the Quick Settings tile. */
    val isPlayingNow get() = exo.isPlaying
    val hasMedia get() = exo.currentMediaItem != null
    val nowTitle get() = exo.mediaMetadata.title?.toString() ?: ""

    /** Called by the widget's buttons (main thread). */
    fun widgetCommand(what: String) {
        val p = session?.player ?: return
        when (what) {
            "play_pause" -> if (p.isPlaying) p.pause() else { if (p.playbackState == Player.STATE_IDLE) p.prepare(); p.play() }
            "next" -> p.seekToNextMediaItem()
            "previous" -> p.seekToPrevious()
            "fav" -> toggleFav()
            "shuffle" -> {
                val picks = Queue.smartShuffle(app, 60).mapNotNull { (r, t) -> Queue.mediaItem(app, r, t, QueueKind.SHUFFLE) }
                if (picks.isNotEmpty()) { p.setMediaItems(picks, 0, 0); p.prepare(); p.play() }
            }
        }
    }

    /** Sends now playing to the Pixel Watch app (no-op when nothing visible changed). */
    fun publishToWatch() = com.st000ps.powerstone.wear.WearBridge.publish(this, exo)

    /**
     * Heart-rate radio from the watch: starts a queue of tracks near [bpm], or, when one is already
     * playing and the target moved a lot, swaps the upcoming tracks without cutting the current one.
     */
    fun radio(bpm: Int, force: Boolean = false) {
        val bridge = com.st000ps.powerstone.wear.WearBridge
        val previous = bridge.radioBpm
        bridge.radioBpm = bpm
        val p = session?.player ?: return
        val onRadio = Queue.kindOf(exo.currentMediaItem) == QueueKind.RADIO
        // Small heart-rate wobbles don't reshuffle; a deliberate retune (phone) always does.
        if (!force && onRadio && previous != null && kotlin.math.abs(previous - bpm) < 10) return
        val current = exo.currentMediaItem?.mediaId
        val picks = Queue.radio(app, bpm, 10, setOfNotNull(current))
            .mapNotNull { (r, t) -> Queue.mediaItem(app, r, t, QueueKind.RADIO) }
        if (picks.isEmpty()) return
        if (onRadio) {
            val next = exo.currentMediaItemIndex + 1
            if (next < exo.mediaItemCount) exo.removeMediaItems(next, exo.mediaItemCount)
            exo.addMediaItems(picks)
        } else {
            p.setMediaItems(picks, 0, 0); p.prepare(); p.play()
        }
    }

    private val grooveJobs = HashSet<String>()
    private var lastReauth = 0L

    /**
     * Works out the playing track's groove for the widget's pads, once per track: decodes ~16 s of
     * it and finds where its kicks, snares and hats land. Checked once a second while playing.
     */
    private fun maybeListenForGroove() {
        val item = exo.currentMediaItem ?: return
        val id = item.mediaId
        if (id in grooveJobs || WidgetStore.groove(this, id) != null) return
        val r = app.releases.value.firstOrNull { id.startsWith(it.id + "/") } ?: return
        val bpm = r.tracks.firstOrNull { trackId(r, it) == id }?.bpm ?: return
        val uri = item.localConfiguration?.uri ?: return
        val local = uri.scheme == "file" || uri.scheme == "content"
        val duration = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        // Streams: wait until playback is past the stretch analysed (from a third in), so it's
        // read from the audio cache rather than downloaded again.
        if (!local && exo.currentPosition < duration * 0.45) return
        grooveJobs += id
        scope.launch {
            val groove = withContext(Dispatchers.IO) {
                GrooveAnalyzer.analyze(duration, bpm) { ex ->
                    if (local) ex.setDataSource(this@PlaybackService, uri, null)
                    else ex.setDataSource(CachedMediaSource(MediaCache.factory(this@PlaybackService), uri))
                }
            }
            grooveJobs -= id
            if (groove != null) {
                WidgetStore.setGroove(this@PlaybackService, id, groove)
                pushWidget()
            }
        }
    }

    /** Minutes listened today, for the widget's resting line. */
    private fun refreshToday() {
        scope.launch {
            val start = java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
            }.timeInMillis
            val ms = withContext(Dispatchers.IO) { app.history.all().filter { it.ts >= start }.sumOf { it.ms } }
            todayMinutes = (ms / 60_000).toInt()
            pushWidget()
        }
    }

    private fun pushWidget() {
        val item = exo.currentMediaItem
        val md = exo.mediaMetadata
        val url = md.artworkUri?.toString()
        if (url != artUrl) {
            artUrl = url; artFiles = null
            artJob?.cancel()
            if (url != null) artJob = scope.launch {
                val result = imageLoader.execute(ImageRequest.Builder(this@PlaybackService).data(url).size(360).allowHardware(false).build())
                val bmp = result.drawable?.toBitmap(360, 360) ?: return@launch
                // Saved as files the launcher reads through a content:// link, so widget updates stay tiny.
                val files = withContext(Dispatchers.IO) {
                    WidgetStore.saveArt(this@PlaybackService, url, bmp, PlayerWidget.labelFrom(bmp))
                }
                if (artUrl != url) return@launch
                artFiles = files
                pushWidget()
            }
        }
        val id = item?.mediaId
        val found = id?.let { mid ->
            val r = app.releases.value.firstOrNull { mid.startsWith(it.id + "/") }
            r?.let { rel -> rel.tracks.firstOrNull { trackId(rel, it) == mid }?.let { rel to it } }
        }
        val detail = found?.let { (r, t) ->
            listOfNotNull(t.bpm?.let { "$it BPM" }, t.key, "track ${t.n}/${r.tracks.size}").joinToString(" · ")
        } ?: ""
        val nextIndex = exo.nextMediaItemIndex
        val upNext = if (nextIndex != C.INDEX_UNSET) exo.getMediaItemAt(nextIndex).mediaMetadata.title?.let { "next › $it" } ?: "" else ""
        val playing = exo.playWhenReady && exo.playbackState != Player.STATE_ENDED && item != null
        val position = exo.currentPosition
        val sleep = app.sleep.value
        val next = WidgetState(
            title = md.title?.toString() ?: "",
            subtitle = listOfNotNull(md.artist, md.albumTitle).joinToString(" · "),
            detail = detail,
            upNext = upNext,
            playing = playing,
            artName = artFiles?.first,
            labelName = artFiles?.second,
            bpm = found?.second?.bpm,
            artist = md.artist?.toString() ?: "",
            release = md.albumTitle?.toString() ?: "",
            trackNo = found?.second?.n,
            trackKey = id ?: "",
            groove = id?.let { WidgetStore.groove(this, it) } ?: Groove.DEFAULT,
            tune = tune,
            todayMinutes = todayMinutes,
            hasTrack = item != null,
            fav = id != null && id in app.favs.value,
            canFav = app.me.value.signedIn,
            // The timer only ticks once the audio really moves (not while buffering).
            startedAt = if (exo.isPlaying) SystemClock.elapsedRealtime() - position else 0,
            positionMs = if (exo.isPlaying) 0 else position,
            durationMs = exo.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0,
            sleepAt = sleep.endAt,
            sleepEndOfTrack = sleep.endOfTrack,
        )
        if (next.sameAs(lastWidget)) return // only redraw when something visible changed
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
        PlayerWidget.update(this, PlayerWidget.state(this).copy(playing = false))
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
                QueueKind.RADIO -> if (remaining < 2) {
                    val inQueue = (maxOf(0, player.mediaItemCount - 50) until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
                    val target = com.st000ps.powerstone.wear.WearBridge.radioBpm
                    // Radio switched off: the station carries on as a smart shuffle instead of stopping.
                    player.addMediaItems(
                        if (target != null) Queue.radio(app, target, 8, inQueue).mapNotNull { (r, t) -> Queue.mediaItem(app, r, t, QueueKind.RADIO) }
                        else Queue.smartShuffle(app, 50, inQueue).mapNotNull { (r, t) -> Queue.mediaItem(app, r, t, QueueKind.SHUFFLE) },
                    )
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
