package com.st000ps.powerstone.wear

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.media3.common.C
import androidx.media3.common.Player
import coil.imageLoader
import coil.request.ImageRequest
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.st000ps.powerstone.app
import com.st000ps.powerstone.data.trackId
import com.st000ps.powerstone.playback.Queue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Keeps the Pixel Watch's copy of "now playing" up to date ([WearProtocol.NOW] data item).
 * Only sends when something visible changed; the watch extrapolates the position itself.
 */
object WearBridge {
    /** Heart-rate radio target from the watch, or null when the radio is off. */
    @Volatile var radioBpm: Int? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastKey: String? = null
    private var artUrl: String? = null
    private var art: Asset? = null
    private var artJob: Job? = null

    fun publish(context: Context, player: Player) {
        val app = context.app
        val item = player.currentMediaItem
        val md = player.mediaMetadata
        val id = item?.mediaId
        val track = id?.let { mid ->
            app.releases.value.firstOrNull { mid.startsWith(it.id + "/") }
                ?.let { r -> r.tracks.firstOrNull { trackId(r, it) == mid } }
        }
        val playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED && item != null
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val eq = app.api.json.encodeToString(com.st000ps.powerstone.data.EqState.serializer(), app.settings.eq.value)
        val fav = id != null && id in app.favs.value

        val url = md.artworkUri?.toString()
        if (url != artUrl) {
            artUrl = url; art = null
            artJob?.cancel()
            if (url != null) artJob = scope.launch {
                val bmp = context.imageLoader.execute(
                    ImageRequest.Builder(context).data(url).size(200).allowHardware(false).build(),
                ).drawable?.toBitmap(200, 200) ?: return@launch
                val bytes = withContext(Dispatchers.Default) {
                    ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
                }
                if (artUrl != url) return@launch
                art = Asset.createFromBytes(bytes)
                lastKey = null
                publish(context, player)
            }
        }

        val key = listOf(id, playing, fav, eq, radioBpm, duration, art != null, player.currentPosition / 5000).joinToString("|")
        if (key == lastKey) return
        lastKey = key

        val req = PutDataMapRequest.create(WearProtocol.NOW).apply {
            dataMap.putString(WearProtocol.TITLE, md.title?.toString() ?: "")
            dataMap.putString(WearProtocol.ARTIST, md.artist?.toString() ?: "")
            dataMap.putString(WearProtocol.RELEASE, md.albumTitle?.toString() ?: "")
            dataMap.putInt(WearProtocol.BPM, track?.bpm ?: 0)
            dataMap.putString(WearProtocol.KEY, track?.key ?: "")
            dataMap.putBoolean(WearProtocol.PLAYING, playing)
            dataMap.putBoolean(WearProtocol.HAS_TRACK, item != null)
            dataMap.putBoolean(WearProtocol.FAV, fav)
            dataMap.putBoolean(WearProtocol.CAN_FAV, app.me.value.signedIn)
            dataMap.putLong(WearProtocol.POSITION, player.currentPosition.coerceAtLeast(0))
            dataMap.putLong(WearProtocol.POSITION_AT, System.currentTimeMillis())
            dataMap.putLong(WearProtocol.DURATION, duration)
            dataMap.putString(WearProtocol.EQ, eq)
            dataMap.putInt(WearProtocol.RADIO, radioBpm ?: 0)
            dataMap.putString(WearProtocol.KIND, Queue.kindOf(item)?.name ?: "")
            art?.let { dataMap.putAsset(WearProtocol.ART, it) }
        }.asPutDataRequest().setUrgent()

        scope.launch {
            runCatching { Wearable.getDataClient(context).putDataItem(req).await() }
        }
    }
}
