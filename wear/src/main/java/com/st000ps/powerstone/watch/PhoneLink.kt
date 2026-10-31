package com.st000ps.powerstone.watch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import android.content.ComponentName
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.st000ps.powerstone.data.Me
import com.st000ps.powerstone.wear.WearProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Same JSON as the phone's EqState: each band 0–1.5×, KILL per band, one ISO band. */
@Serializable
data class Eq(
    val lo: Float = 1f,
    val mid: Float = 1f,
    val hi: Float = 1f,
    val kill: Set<String> = emptySet(),
    val solo: String? = null,
) {
    fun level(band: String) = when (band) { "lo" -> lo; "mid" -> mid; else -> hi }
    fun with(band: String, v: Float) = when (band) { "lo" -> copy(lo = v); "mid" -> copy(mid = v); else -> copy(hi = v) }

    companion object { val BANDS = listOf("lo", "mid", "hi") }
}

@Serializable
private data class SessionHandoff(val cookies: List<String> = emptyList(), val me: Me = Me())

/** The phone's now playing, as published by its WearBridge. */
data class PhoneNow(
    val title: String,
    val artist: String,
    val release: String,
    val bpm: Int,
    val key: String,
    val playing: Boolean,
    val hasTrack: Boolean,
    val fav: Boolean,
    val canFav: Boolean,
    val positionMs: Long,
    val positionAt: Long,
    val durationMs: Long,
    val eq: Eq,
    val radioBpm: Int,
    val kind: String,
    val art: Bitmap?,
) {
    /** Position now, extrapolated from when the phone last reported it. */
    fun positionNow(): Long =
        if (!playing) positionMs else (positionMs + System.currentTimeMillis() - positionAt).coerceAtMost(durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE)
}

/** Watch side of the Wearable Data Layer link to the phone app. */
class PhoneLink(private val context: Context) : DataClient.OnDataChangedListener, MessageClient.OnMessageReceivedListener {
    private val app get() = context.watchApp
    private val data = Wearable.getDataClient(context)
    private val messages = Wearable.getMessageClient(context)
    private val nodes = Wearable.getNodeClient(context)

    private val _now = MutableStateFlow<PhoneNow?>(null)
    val now: StateFlow<PhoneNow?> = _now.asStateFlow()
    private var pendingSession: CompletableDeferred<Boolean>? = null

    fun start() {
        data.addListener(this)
        messages.addListener(this)
        app.scope.launch { refresh() }
    }

    /** Reads the last now-playing item (also used by the tile and complication). */
    suspend fun refresh(): PhoneNow? {
        val items = runCatching { data.getDataItems(Uri.parse("wear://*${WearProtocol.NOW}")).await() }.getOrNull() ?: return null
        val item = items.firstOrNull()
        val parsed = item?.let { parse(DataMapItem.fromDataItem(it).dataMap) }
        items.release()
        if (parsed != null) _now.value = parsed
        return parsed
    }

    override fun onDataChanged(events: DataEventBuffer) {
        val maps = events.filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == WearProtocol.NOW }
            .map { DataMapItem.fromDataItem(it.dataItem).dataMap }
        events.release()
        maps.lastOrNull()?.let { map ->
            app.scope.launch {
                _now.value = parse(map)
                TileService.getUpdater(context).requestUpdate(NowPlayingTileService::class.java)
                ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, NowPlayingComplicationService::class.java))
                    .requestUpdateAll()
            }
        }
    }

    private suspend fun parse(m: DataMap): PhoneNow {
        val art = m.getAsset(WearProtocol.ART)?.let { asset ->
            runCatching {
                data.getFdForAsset(asset).await().inputStream.use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
        }
        val eq = runCatching { app.api.json.decodeFromString(Eq.serializer(), m.getString(WearProtocol.EQ, "{}")) }.getOrDefault(Eq())
        return PhoneNow(
            title = m.getString(WearProtocol.TITLE, ""),
            artist = m.getString(WearProtocol.ARTIST, ""),
            release = m.getString(WearProtocol.RELEASE, ""),
            bpm = m.getInt(WearProtocol.BPM),
            key = m.getString(WearProtocol.KEY, ""),
            playing = m.getBoolean(WearProtocol.PLAYING),
            hasTrack = m.getBoolean(WearProtocol.HAS_TRACK),
            fav = m.getBoolean(WearProtocol.FAV),
            canFav = m.getBoolean(WearProtocol.CAN_FAV),
            positionMs = m.getLong(WearProtocol.POSITION),
            positionAt = m.getLong(WearProtocol.POSITION_AT),
            durationMs = m.getLong(WearProtocol.DURATION),
            eq = eq,
            radioBpm = m.getInt(WearProtocol.RADIO),
            kind = m.getString(WearProtocol.KIND, ""),
            art = art,
        )
    }

    /** Sends a command to the phone app. False when no phone is connected. */
    suspend fun send(cmd: String): Boolean = sendTo(WearProtocol.CMD, cmd)

    private suspend fun sendTo(path: String, payload: String): Boolean {
        val connected = runCatching { nodes.connectedNodes.await() }.getOrDefault(emptyList())
        if (connected.isEmpty()) return false
        connected.forEach { node ->
            runCatching { messages.sendMessage(node.id, path, payload.toByteArray(Charsets.UTF_8)).await() }
        }
        return true
    }

    /** Asks the phone for its 88.8FM sign-in and installs it here. True when it arrived. */
    suspend fun requestSession(): Boolean {
        val wait = CompletableDeferred<Boolean>().also { pendingSession = it }
        if (!sendTo(WearProtocol.SESSION_REQUEST, "")) return false
        return withTimeoutOrNull(10_000) { wait.await() } ?: false
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearProtocol.SESSION) return
        val handoff = runCatching {
            app.api.json.decodeFromString(SessionHandoff.serializer(), String(event.data, Charsets.UTF_8))
        }.getOrNull()
        if (handoff != null && handoff.cookies.isNotEmpty()) {
            app.api.cookies.import(handoff.cookies)
            app.me.value = handoff.me
        }
        pendingSession?.complete(handoff != null)
    }
}
