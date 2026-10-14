package com.st000ps.powerstone.ui

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.st000ps.powerstone.app
import com.st000ps.powerstone.audio.AudioFx
import com.st000ps.powerstone.data.Api
import com.st000ps.powerstone.data.EqState
import com.st000ps.powerstone.data.ListeningSession
import com.st000ps.powerstone.data.Me
import com.st000ps.powerstone.data.PasswordState
import com.st000ps.powerstone.data.Playlist
import com.st000ps.powerstone.data.Quality
import com.st000ps.powerstone.data.Release
import com.st000ps.powerstone.data.SharedPlaylist
import com.st000ps.powerstone.data.SleepState
import com.st000ps.powerstone.data.Stats
import com.st000ps.powerstone.data.StatsMath
import com.st000ps.powerstone.data.Track
import com.st000ps.powerstone.data.TrackRef
import com.st000ps.powerstone.data.VinylStop
import com.st000ps.powerstone.data.trackId
import com.st000ps.powerstone.playback.PlaybackService
import com.st000ps.powerstone.playback.Queue
import com.st000ps.powerstone.playback.QueueKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

data class NowPlaying(
    val mediaId: String? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artwork: String? = null,
    val video: String? = null,
    val kind: QueueKind? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val volume: Float = 1f,
)

/** One line of the "Up next" list. */
data class QueueEntry(val index: Int, val title: String, val album: String, val current: Boolean)

data class UiState(
    val releases: List<Release> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val me: Me = Me(),
    val favs: List<String> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val quality: Quality = Quality.AUTO,
    val eq: EqState = EqState(),
    val vinyl: VinylStop = VinylStop.MED,
    val videoOn: Boolean = true,
    val visualizerOn: Boolean = true,
    val password: PasswordState = PasswordState(),
    val now: NowPlaying = NowPlaying(),
    val queue: List<QueueEntry> = emptyList(),
    val sleep: SleepState = SleepState(),
    val download: String? = null,
    /** Bumps when downloads change (so "downloaded" marks refresh). */
    val offlineVersion: Int = 0,
    val offline: Boolean = false,
    val crossfade: Int = 0,
)

/** One-off navigation requests (deep links, "open the playlist I just made"). */
sealed interface NavRequest {
    data class Release(val id: String, val track: Int? = null) : NavRequest
    data class Playlist(val id: String) : NavRequest
    data class Shared(val id: String) : NavRequest
    data class Tag(val tag: String) : NavRequest
    data class Me(val wrapYear: Int? = null) : NavRequest
}

/** History tab content: sessions plus this year's play count per "release|n". */
data class HistoryData(val sessions: List<ListeningSession>, val yearCounts: Map<String, Int>)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.app
    private val api: Api = app.api
    private val settings = app.settings
    private val _state = MutableStateFlow(
        UiState(
            releases = app.releases.value,
            loading = app.releases.value.isEmpty(),
            quality = settings.quality,
            videoOn = settings.videoOn,
            visualizerOn = settings.visualizerOn,
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()
    private val _nav = Channel<NavRequest>(Channel.BUFFERED)
    val nav = _nav.receiveAsFlow()

    /** Shares the user asked for, handed to the activity to open the system share sheet. */
    private val _shares = Channel<Pair<String, String>>(Channel.BUFFERED)
    val shares = _shares.receiveAsFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    init {
        combine(app.releases, app.me, app.playlists.list, settings.eq, settings.vinyl) { rel, me, pls, eq, vinyl ->
            _state.update { it.copy(releases = rel, me = me, playlists = pls, eq = eq, vinyl = vinyl) }
        }.launchIn(viewModelScope)
        app.sleep.onEach { s -> _state.update { it.copy(sleep = s) } }.launchIn(viewModelScope)
        app.favs.onEach { f -> _state.update { it.copy(favs = f) } }.launchIn(viewModelScope)
        app.downloadStatus.onEach { d -> _state.update { it.copy(download = d) } }.launchIn(viewModelScope)
        app.offline.version.onEach { v -> _state.update { it.copy(offlineVersion = v) } }.launchIn(viewModelScope)
        app.isOffline.onEach { o -> _state.update { it.copy(offline = o) } }.launchIn(viewModelScope)
        settings.crossfade.onEach { c -> _state.update { it.copy(crossfade = c) } }.launchIn(viewModelScope)
        refresh()
        connectPlayer()
    }

    // ---------- sleep timer ----------

    /** Minutes from now, or [endOfTrack]; null minutes and false = off. */
    fun setSleep(minutes: Int?, endOfTrack: Boolean = false) {
        app.sleep.value = SleepState(endAt = minutes?.let { System.currentTimeMillis() + it * 60_000L }, endOfTrack = endOfTrack)
        toast(when {
            endOfTrack -> "Sleep timer — stopping after this track"
            minutes != null -> "Sleep timer — $minutes min"
            else -> "Sleep timer off"
        })
    }

    // ---------- widget ----------

    private var pendingWidgetAction: String? = null

    /** The widget opened the app to "resume" the last session or "shuffle"; runs once the player is connected. */
    fun widgetAction(action: String) {
        val c = controller
        if (c == null) { pendingWidgetAction = action; return }
        when (action) {
            "resume" -> if (c.mediaItemCount > 0) { if (c.playbackState == Player.STATE_IDLE) c.prepare(); c.play() } else smartShuffle()
            "shuffle" -> smartShuffle()
        }
    }

    fun jumpTo(index: Int) { controller?.run { seekTo(index, 0); play() } }

    fun toast(msg: String) { _messages.trySend(msg) }

    fun refresh() = viewModelScope.launch {
        _state.update { it.copy(loading = it.releases.isEmpty(), error = null) }
        try {
            app.releases.value = api.catalog().releases
        } catch (e: Exception) {
            _state.update { it.copy(error = if (it.releases.isEmpty()) "Couldn't load the catalogue. ${e.message ?: ""}" else null) }
        }
        refreshAccount()
        _state.update { it.copy(loading = false) }
    }

    /** Re-reads the account, e.g. after returning from Stripe checkout. */
    fun refreshMe() = viewModelScope.launch { refreshAccount() }

    private suspend fun refreshAccount() {
        val fresh = api.tryMe()
        app.isOffline.value = fresh == null
        if (fresh != null) {
            app.me.value = fresh
            // Only a real answer from the site may lock or delete downloads — never a network failure.
            app.offline.accountChecked(fresh)
        }
        val me = app.me.value
        if (fresh == null) { app.playlists.load(false); return } // offline: keep what we know
        app.favs.value = if (me.signedIn) runCatching { api.favs().map { it.id } }.getOrDefault(app.favs.value) else emptyList()
        val pw = if (me.signedIn) api.passwordState() else PasswordState()
        _state.update { it.copy(password = pw) }
        app.playlists.load(me.signedIn)
    }

    // ---------- downloads ----------

    fun download(items: List<Pair<Release, Track>>, what: String) = app.startDownload(items, what, ::toast)
    fun cancelDownload() = app.cancelDownload()
    fun removeDownloads(items: List<Pair<Release, Track>>) { app.offline.remove(items.map { trackId(it.first, it.second) }); toast("Download removed") }
    fun removeAllDownloads() { app.offline.removeAll(); toast("All downloads removed") }
    fun isDownloaded(r: Release, t: Track) = app.offline.has(trackId(r, t))
    fun setCrossfade(seconds: Int) = settings.setCrossfade(seconds)

    fun release(id: String): Release? = _state.value.releases.firstOrNull { it.id == id }

    fun lookup(r: String, n: Int): Pair<Release, Track>? {
        val rel = release(r) ?: return null
        val t = rel.tracks.firstOrNull { it.n == n } ?: return null
        return rel to t
    }

    fun lookup(id: String): Pair<Release, Track>? =
        id.substringAfterLast('/').toIntOrNull()?.let { lookup(id.substringBeforeLast('/'), it) }

    fun resolve(refs: List<TrackRef>) = refs.mapNotNull { lookup(it.r, it.n) }

    /** Every track carrying [tag] (on the track, or on its release as a tag or genre). */
    fun tagged(tag: String): List<Pair<Release, Track>> = _state.value.releases.flatMap { r ->
        val relHas = tag in r.tags || tag in r.genres
        r.tracks.filter { relHas || tag in it.tags }.map { r to it }
    }

    // ---------- account ----------

    suspend fun requestCode(email: String) = api.requestCode(email)

    suspend fun verifyCode(email: String, code: String) { api.verifyCode(email, code); refreshAccount() }

    suspend fun passwordLogin(email: String, password: String) { api.passwordLogin(email, password); refreshAccount() }

    suspend fun setPassword(password: String) {
        api.setPassword(password)
        _state.update { it.copy(password = PasswordState(hasPassword = true)) }
    }

    suspend fun checkoutUrl(plan: String) = api.checkoutUrl(plan)
    suspend fun portalUrl() = api.portalUrl()

    fun logout() = viewModelScope.launch {
        api.logout()
        controller?.run { stop(); clearMediaItems() }
        app.me.value = Me()
        app.playlists.signedOut()
        app.favs.value = emptyList()
        app.offline.removeAll() // downloads belong to the subscription
        _state.update { it.copy(password = PasswordState()) }
    }

    // ---------- settings ----------

    fun setQuality(q: Quality) { settings.quality = q; _state.update { it.copy(quality = q) } }
    fun resolvedQuality() = app.resolvedQuality()
    fun setEq(eq: EqState) = settings.setEq(eq)
    fun setVinyl(v: VinylStop) = settings.setVinyl(v)
    fun setVideoOn(on: Boolean) { settings.videoOn = on; _state.update { it.copy(videoOn = on) } }
    fun setVisualizerOn(on: Boolean) {
        settings.visualizerOn = on; AudioFx.spectrumOn = on
        _state.update { it.copy(visualizerOn = on) }
    }
    fun setVolume(v: Float) { controller?.volume = v; syncNow() }

    // ---------- favourites ----------

    fun toggleFav(id: String) = viewModelScope.launch {
        if (!_state.value.me.signedIn) { toast("Sign in to save favourites"); return@launch }
        val on = id !in app.favs.value
        val title = lookup(id)?.second?.title ?: id
        toast(if (on) "$title — added to favourites" else "$title — removed")
        if (!app.setFav(id, on)) toast("Couldn't update favourites")
    }

    // ---------- playlists ----------

    fun newPlaylist(with: Pair<Release, Track>? = null): Playlist {
        val pl = app.playlists.create(tracks = listOfNotNull(with?.let { TrackRef(it.first.id, it.second.n) }))
        if (with != null) toast("Added to ${pl.name}")
        return pl
    }

    fun addToPlaylist(plId: String, rel: Release, t: Track) {
        val pl = app.playlists.get(plId) ?: return
        toast(if (app.playlists.add(plId, TrackRef(rel.id, t.n))) "Added to ${pl.name}" else "Already in ${pl.name}")
    }

    fun removeFromPlaylist(plId: String, index: Int) = app.playlists.removeAt(plId, index)
    fun movePlaylistTrack(plId: String, from: Int, to: Int) = app.playlists.move(plId, from, to)
    fun renamePlaylist(plId: String, name: String) = app.playlists.rename(plId, name)
    fun deletePlaylist(plId: String) { app.playlists.delete(plId); toast("Playlist deleted") }

    fun saveShared(shared: SharedPlaylist) {
        val pl = app.playlists.create(shared.name, shared.tracks.filter { lookup(it.r, it.n) != null })
        toast("Saved to your playlists")
        _nav.trySend(NavRequest.Playlist(pl.id))
    }

    suspend fun loadShared(id: String): SharedPlaylist = api.sharedPlaylist(id)

    // ---------- sharing (links open in the app or on the site) ----------

    private fun share(path: String, title: String) { _shares.trySend((Api.BASE + path) to title) }

    fun shareRelease(r: Release) = share("/?r=${Uri.encode(r.id)}", "${r.title} · 88.8FM")
    fun shareTrack(r: Release, t: Track) = share("/?r=${Uri.encode(r.id)}&t=${t.n}", "${t.title} — ${r.title} · 88.8FM")
    fun shareTag(tag: String) = share("/?tag=${Uri.encode(tag)}", "#$tag · 88.8FM")
    fun shareSharedPlaylist(id: String, name: String) = share("/?s=${Uri.encode(id)}", "$name · 88.8FM")

    fun sharePlaylist(pl: Playlist) = viewModelScope.launch {
        try {
            val id = api.sharePlaylist(pl.name, pl.tracks)
            share("/?s=${Uri.encode(id)}", "${pl.name} · 88.8FM")
        } catch (e: Exception) {
            toast(e.message ?: "Couldn't share right now")
        }
    }

    /** Handles thepowerstone.com links: ?r= ?t= ?s= ?tag= ?wrap= ?me */
    fun openLink(uri: Uri) {
        if (uri.host?.endsWith(Api.HOST) != true) return
        val r = uri.getQueryParameter("r")
        val t = uri.getQueryParameter("t")?.toIntOrNull()
        val s = uri.getQueryParameter("s")
        val tag = uri.getQueryParameter("tag")
        val wrap = uri.getQueryParameter("wrap")?.toIntOrNull()
        when {
            r != null -> _nav.trySend(NavRequest.Release(r, t))
            tag != null -> _nav.trySend(NavRequest.Tag(tag))
            s != null -> _nav.trySend(NavRequest.Shared(s))
        }
        if (wrap != null) _nav.trySend(NavRequest.Me(wrap))
        else if (uri.getQueryParameterNames().contains("me")) _nav.trySend(NavRequest.Me())
    }

    // ---------- MY 88.8: history & stats ----------

    val currentYear: Int get() = Calendar.getInstance().get(Calendar.YEAR)

    suspend fun history(): HistoryData {
        val year = currentYear
        if (_state.value.me.signedIn) {
            runCatching {
                val h = api.history()
                val counts = runCatching { api.stats("year", year).counts }.getOrDefault(emptyMap())
                return HistoryData(StatsMath.fromServer(h.sessions), counts)
            }
        }
        val local = withContext(Dispatchers.IO) { app.history.all() }
        val from = StatsMath.startOfYear(year)
        return HistoryData(
            StatsMath.sessions(local),
            local.filter { it.ts >= from }.groupingBy { "${it.r}|${it.n}" }.eachCount(),
        )
    }

    /** [period] "month" or "year". Signed out, stats come from this device's history. */
    suspend fun stats(period: String, year: Int): Stats {
        if (_state.value.me.signedIn) return api.stats(period, year)
        val local = withContext(Dispatchers.IO) { app.history.all() }
        val from = if (period == "month") StatsMath.startOfMonth() else StatsMath.startOfYear(year)
        val until = if (period == "month") Long.MAX_VALUE else StatsMath.startOfYear(year + 1)
        return StatsMath.compute(local, from, until, _state.value.releases)
    }

    // ---------- playback ----------

    private fun connectPlayer() {
        val ctx = getApplication<Application>()
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val future = MediaController.Builder(ctx, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            controller = c
            c.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    syncNow()
                    if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) syncQueue()
                }
            })
            syncNow()
            syncQueue()
            pendingWidgetAction?.let { pendingWidgetAction = null; widgetAction(it) }
        }, ContextCompat.getMainExecutor(ctx))

        viewModelScope.launch {
            while (isActive) {
                if (controller?.isPlaying == true) syncNow()
                delay(250)
            }
        }
    }

    private fun syncNow() {
        val c = controller ?: return
        val md = c.mediaMetadata
        val item = c.currentMediaItem
        _state.update {
            it.copy(
                now = NowPlaying(
                    mediaId = item?.mediaId,
                    title = md.title?.toString() ?: "",
                    artist = md.artist?.toString() ?: "",
                    album = md.albumTitle?.toString() ?: "",
                    artwork = md.artworkUri?.toString(),
                    video = item?.mediaMetadata?.extras?.getString(Queue.VIDEO),
                    kind = Queue.kindOf(item),
                    isPlaying = c.isPlaying,
                    isBuffering = c.playbackState == Player.STATE_BUFFERING,
                    positionMs = c.currentPosition.coerceAtLeast(0),
                    durationMs = c.duration.takeIf { d -> d > 0 } ?: 0,
                    hasNext = c.hasNextMediaItem(),
                    hasPrevious = c.hasPreviousMediaItem(),
                    repeatMode = c.repeatMode,
                    volume = c.volume,
                ),
            )
        }
    }

    /** The current track and what comes after it (a window, for long shuffles). */
    private fun syncQueue() {
        val c = controller ?: return
        val cur = c.currentMediaItemIndex
        val from = (cur - 1).coerceAtLeast(0)
        val to = minOf(c.mediaItemCount, cur + 40)
        val list = (from until to).map { i ->
            val md = c.getMediaItemAt(i).mediaMetadata
            QueueEntry(i, md.title?.toString() ?: "", md.albumTitle?.toString() ?: "", i == cur)
        }
        _state.update { it.copy(queue = list) }
    }

    /** Current playback position, read fresh (for keeping the music video locked to the audio). */
    fun positionMs(): Long = controller?.currentPosition ?: 0

    /** Replace the queue with [items] and start at [start]. */
    fun play(items: List<Pair<Release, Track>>, start: Int, kind: QueueKind = QueueKind.LIST) {
        val c = controller ?: run { toast("Player is starting — try again"); return }
        val media = items.mapNotNull { (r, t) -> Queue.mediaItem(app, r, t, kind) }
        if (media.isEmpty()) return
        val startId = items.getOrNull(start)?.let { (r, t) -> trackId(r, t) }
        val idx = media.indexOfFirst { it.mediaId == startId }.coerceAtLeast(0)
        c.shuffleModeEnabled = false
        c.setMediaItems(media, idx, 0)
        c.prepare()
        c.play()
    }

    fun playRelease(release: Release, start: Int = 0) = play(release.tracks.map { release to it }, start, QueueKind.ALBUM)

    fun playFavs(start: Int = 0) = play(_state.value.favs.mapNotNull(::lookup), start)

    fun shuffled(items: List<Pair<Release, Track>>) {
        if (items.isEmpty()) { toast("Nothing to play"); return }
        play(items.shuffled(), 0)
    }

    /** The site's SHUFFLE: unheard songs from across the whole catalogue, forever. */
    fun smartShuffle() {
        val picks = Queue.smartShuffle(app, 60)
        if (picks.isEmpty()) return
        play(picks, 0, QueueKind.SHUFFLE)
        toast("Shuffle — ${picks[0].second.title} / ${picks[0].first.title}")
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.mediaItemCount == 0) { smartShuffle(); return } // nothing queued: play means "play me something"
        if (c.playWhenReady && c.isPlaying) c.pause() else { if (c.playbackState == Player.STATE_IDLE) c.prepare(); c.play() }
    }
    fun next() { controller?.seekToNextMediaItem() }
    fun previous() { controller?.seekToPrevious() }
    fun seekTo(ms: Long) { controller?.seekTo(ms); syncNow() }

    /** REPEAT 1 / REPEAT TAPE (whole album or playlist) — subscribers only, as on the site. */
    fun setRepeat(mode: Int) {
        if (!_state.value.me.isSubscriber) { toast("Repeat is for subscribers"); return }
        val c = controller ?: return
        c.repeatMode = if (c.repeatMode == mode) Player.REPEAT_MODE_OFF else mode
        toast(when (c.repeatMode) {
            Player.REPEAT_MODE_ONE -> "Repeat 1 — looping this track"
            Player.REPEAT_MODE_ALL -> "Repeat tape — looping the whole queue"
            else -> "Repeat off"
        })
    }

    override fun onCleared() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onCleared()
    }
}
