package com.st000ps.powerstone.watch

import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.st000ps.powerstone.data.Api
import com.st000ps.powerstone.data.Quality
import com.st000ps.powerstone.data.Release
import com.st000ps.powerstone.data.Track
import com.st000ps.powerstone.data.streamPath
import com.st000ps.powerstone.data.trackId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 88.8FM playing on the watch itself: to Bluetooth headphones when connected, otherwise the
 * watch speaker. Unlike most watch music apps it doesn't refuse the speaker.
 */
@OptIn(UnstableApi::class)
class WatchPlaybackService : MediaSessionService() {
    companion object {
        @Volatile var instance: WatchPlaybackService? = null
            private set
        private const val KIND = "kind"
        private const val SHUFFLE = "SHUFFLE"
        private const val RADIO = "RADIO"
    }

    private lateinit var exo: ExoPlayer
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Heart-rate radio target when the radio plays on the watch. */
    @Volatile var radioBpm: Int? = null
        private set

    val player: Player get() = exo

    override fun onCreate() {
        super.onCreate()
        val app = watchApp
        exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(OkHttpDataSource.Factory(app.api.http)))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = extendQueue()
        })
        session = MediaSession.Builder(this, exo).build()
        instance = this
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    private fun item(release: Release, track: Track, kind: String): MediaItem? {
        // Watch bandwidth: standard quality for subscribers, previews otherwise.
        val url = Api.absolute(track.streamPath(watchApp.me.value.isSubscriber, Quality.STD)) ?: return null
        return MediaItem.Builder()
            .setMediaId(trackId(release, track))
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(release.artist)
                    .setAlbumTitle(release.title)
                    .setArtworkUri(Api.absolute(release.thumb ?: release.cover)?.let(Uri::parse))
                    .setExtras(Bundle().apply { putString(KIND, kind) })
                    .build(),
            )
            .build()
    }

    private fun picks(count: Int, kind: String, avoid: Set<String>): List<MediaItem> {
        val all = watchApp.releases.value.flatMap { r -> r.tracks.map { r to it } }
            .filter { (r, t) -> trackId(r, t) !in avoid }
        val chosen = if (kind == RADIO) {
            val target = radioBpm ?: return emptyList()
            val withBpm = all.filter { it.second.bpm != null }
            listOf(3, 6, 10, 16, 1000).firstNotNullOf { w ->
                withBpm.filter { tempoDistance(it.second.bpm!!, target) <= w }.takeIf { it.size >= count || w == 1000 }
            }.shuffled()
        } else {
            all.shuffled()
        }
        return chosen.take(count).mapNotNull { (r, t) -> item(r, t, kind) }
    }

    private fun currentKind() = exo.currentMediaItem?.mediaMetadata?.extras?.getString(KIND)

    private fun extendQueue() {
        val kind = currentKind() ?: return
        val remaining = exo.mediaItemCount - 1 - exo.currentMediaItemIndex
        if (remaining >= 2) return
        val inQueue = (0 until exo.mediaItemCount).map { exo.getMediaItemAt(it).mediaId }.toSet()
        exo.addMediaItems(picks(10, kind, inQueue))
    }

    fun shuffle() = scope.launch {
        watchApp.ensureAccount()
        watchApp.ensureCatalog()
        radioBpm = null
        val items = picks(30, SHUFFLE, emptySet())
        if (items.isEmpty()) return@launch
        exo.setMediaItems(items, 0, 0); exo.prepare(); exo.play()
    }

    /** Starts or re-targets heart-rate radio on the watch (keeps the current track playing). */
    fun radio(bpm: Int) = scope.launch {
        val previous = radioBpm
        radioBpm = bpm
        val onRadio = currentKind() == RADIO
        if (onRadio && previous != null && kotlin.math.abs(previous - bpm) < 10) return@launch
        watchApp.ensureAccount()
        watchApp.ensureCatalog()
        val items = picks(10, RADIO, setOfNotNull(exo.currentMediaItem?.mediaId))
        if (items.isEmpty()) return@launch
        if (onRadio) {
            val next = exo.currentMediaItemIndex + 1
            if (next < exo.mediaItemCount) exo.removeMediaItems(next, exo.mediaItemCount)
            exo.addMediaItems(items)
        } else {
            exo.setMediaItems(items, 0, 0); exo.prepare(); exo.play()
        }
    }

    fun stopRadio() { radioBpm = null }

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        if (!exo.playWhenReady || exo.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        instance = null
        session?.release()
        exo.release()
        scope.cancel()
        super.onDestroy()
    }
}

/** How far [bpm] is from [target], counting half and double time as a match (70 fits 140). */
fun tempoDistance(bpm: Int, target: Int): Int = listOf(bpm, bpm * 2, bpm / 2).minOf { kotlin.math.abs(it - target) }
