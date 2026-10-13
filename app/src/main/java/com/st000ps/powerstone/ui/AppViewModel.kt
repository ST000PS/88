package com.st000ps.powerstone.ui

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.st000ps.powerstone.app
import com.st000ps.powerstone.data.Api
import com.st000ps.powerstone.data.Me
import com.st000ps.powerstone.data.Quality
import com.st000ps.powerstone.data.Release
import com.st000ps.powerstone.data.Track
import com.st000ps.powerstone.data.streamPath
import com.st000ps.powerstone.data.trackId
import com.st000ps.powerstone.playback.PlaybackService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class NowPlaying(
    val mediaId: String? = null,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val artwork: String? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
)

data class UiState(
    val releases: List<Release> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val me: Me = Me(),
    val favs: List<String> = emptyList(),
    val quality: Quality = Quality.HIGH,
    val now: NowPlaying = NowPlaying(),
)

class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val api: Api = application.app.api
    private val _state = MutableStateFlow(UiState(quality = application.app.quality))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    init {
        api.cachedCatalog()?.let { c -> _state.update { it.copy(releases = c.releases.sortedByDescending { r -> r.sort }, loading = false) } }
        refresh()
        connectPlayer()
    }

    fun refresh() = viewModelScope.launch {
        _state.update { it.copy(loading = it.releases.isEmpty(), error = null) }
        try {
            val catalog = api.catalog()
            _state.update { it.copy(releases = catalog.releases.sortedByDescending { r -> r.sort }) }
        } catch (e: Exception) {
            _state.update { it.copy(error = if (it.releases.isEmpty()) "Couldn't load the catalogue. ${e.message ?: ""}" else null) }
        }
        refreshAccount()
        _state.update { it.copy(loading = false) }
    }

    private suspend fun refreshAccount() {
        val me = runCatching { api.me() }.getOrDefault(_state.value.me)
        val favs = if (me.signedIn) runCatching { api.favs().map { it.id } }.getOrDefault(_state.value.favs) else emptyList()
        _state.update { it.copy(me = me, favs = favs) }
    }

    fun release(id: String): Release? = _state.value.releases.firstOrNull { it.id == id }

    fun lookup(id: String): Pair<Release, Track>? {
        val rel = release(id.substringBeforeLast('/')) ?: return null
        val n = id.substringAfterLast('/').toIntOrNull() ?: return null
        val t = rel.tracks.firstOrNull { it.n == n } ?: return null
        return rel to t
    }

    // ---------- account ----------

    suspend fun requestCode(email: String) = api.requestCode(email)

    suspend fun verifyCode(email: String, code: String) {
        api.verifyCode(email, code)
        refreshAccount()
    }

    suspend fun passwordLogin(email: String, password: String) {
        api.passwordLogin(email, password)
        refreshAccount()
    }

    fun logout() = viewModelScope.launch {
        api.logout()
        controller?.run { stop(); clearMediaItems() }
        _state.update { it.copy(me = Me(), favs = emptyList()) }
    }

    fun setQuality(q: Quality) {
        getApplication<Application>().app.quality = q
        _state.update { it.copy(quality = q) }
    }

    // ---------- favourites ----------

    fun toggleFav(id: String) = viewModelScope.launch {
        if (!_state.value.me.signedIn) return@launch
        val on = id !in _state.value.favs
        val before = _state.value.favs
        _state.update { it.copy(favs = if (on) listOf(id) + it.favs else it.favs - id) }
        try {
            api.setFav(id, on)?.let { list -> _state.update { it.copy(favs = list.map { f -> f.id }) } }
        } catch (_: Exception) {
            _state.update { it.copy(favs = before) }
        }
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
                override fun onEvents(player: Player, events: Player.Events) = syncNow()
            })
            syncNow()
        }, ContextCompat.getMainExecutor(ctx))

        viewModelScope.launch {
            while (isActive) {
                if (controller?.isPlaying == true) syncNow()
                delay(500)
            }
        }
    }

    private fun syncNow() {
        val c = controller ?: return
        val md = c.mediaMetadata
        _state.update {
            it.copy(
                now = NowPlaying(
                    mediaId = c.currentMediaItem?.mediaId,
                    title = md.title?.toString() ?: "",
                    artist = md.artist?.toString() ?: "",
                    album = md.albumTitle?.toString() ?: "",
                    artwork = md.artworkUri?.toString(),
                    isPlaying = c.isPlaying,
                    isBuffering = c.playbackState == Player.STATE_BUFFERING,
                    positionMs = c.currentPosition.coerceAtLeast(0),
                    durationMs = c.duration.takeIf { d -> d > 0 } ?: 0,
                    hasNext = c.hasNextMediaItem(),
                    hasPrevious = c.hasPreviousMediaItem(),
                    shuffle = c.shuffleModeEnabled,
                    repeatMode = c.repeatMode,
                ),
            )
        }
    }

    private fun mediaItem(release: Release, track: Track): MediaItem? {
        val s = _state.value
        val url = Api.absolute(track.streamPath(s.me.isSubscriber, s.quality)) ?: return null
        return MediaItem.Builder()
            .setMediaId(trackId(release, track))
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(release.artist)
                    .setAlbumTitle(release.title)
                    .setTrackNumber(track.n)
                    .setArtworkUri(Api.absolute(release.cover)?.let(Uri::parse))
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build(),
            )
            .build()
    }

    /** Replace the queue with [items] and start at [start]. */
    fun play(items: List<Pair<Release, Track>>, start: Int) {
        val c = controller ?: return
        val media = items.mapNotNull { (r, t) -> mediaItem(r, t) }
        if (media.isEmpty()) return
        val startId = items.getOrNull(start)?.let { (r, t) -> trackId(r, t) }
        val idx = media.indexOfFirst { it.mediaId == startId }.coerceAtLeast(0)
        c.setMediaItems(media, idx, 0)
        c.prepare()
        c.play()
    }

    fun playRelease(release: Release, start: Int = 0) = play(release.tracks.map { release to it }, start)

    fun playFavs(start: Int = 0) = play(_state.value.favs.mapNotNull(::lookup), start)

    fun shuffleAll() {
        val all = _state.value.releases.flatMap { r -> r.tracks.map { r to it } }.shuffled().take(200)
        play(all, 0)
    }

    fun togglePlay() { controller?.run { if (isPlaying) pause() else { if (playbackState == Player.STATE_IDLE) prepare(); play() } } }
    fun next() { controller?.seekToNextMediaItem() }
    fun previous() { controller?.seekToPrevious() }
    fun seekTo(ms: Long) { controller?.seekTo(ms); syncNow() }
    fun toggleShuffle() { controller?.run { shuffleModeEnabled = !shuffleModeEnabled } }
    fun cycleRepeat() {
        controller?.run {
            repeatMode = when (repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    override fun onCleared() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        super.onCleared()
    }
}
