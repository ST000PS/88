package com.st000ps.powerstone.wear

import android.content.ComponentName
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.google.common.util.concurrent.MoreExecutors
import com.st000ps.powerstone.app
import com.st000ps.powerstone.data.EqState
import com.st000ps.powerstone.data.Me
import com.st000ps.powerstone.playback.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.Serializable

/** Sign-in handed to the watch: the site's cookies plus who the account is. */
@Serializable
data class SessionHandoff(val cookies: List<String> = emptyList(), val me: Me = Me())

/** Receives the Pixel Watch app's commands and sign-in requests. */
class PhoneWearService : WearableListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WearProtocol.CMD -> {
                val cmd = String(event.data, Charsets.UTF_8)
                if (cmd == WearProtocol.VOLUME_UP || cmd == WearProtocol.VOLUME_DOWN) {
                    // The watch's crown: the phone's media volume, without starting playback.
                    getSystemService(android.media.AudioManager::class.java).adjustStreamVolume(
                        android.media.AudioManager.STREAM_MUSIC,
                        if (cmd == WearProtocol.VOLUME_UP) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER,
                        0,
                    )
                    return
                }
                scope.launch { withPlayback { service -> run(service, cmd) } }
            }
            WearProtocol.SESSION_REQUEST -> scope.launch {
                val me = app.ensureSignedIn() ?: app.me.value
                val payload = app.api.json.encodeToString(SessionHandoff.serializer(), SessionHandoff(app.api.cookies.export(), me))
                runCatching {
                    Wearable.getMessageClient(this@PhoneWearService)
                        .sendMessage(event.sourceNodeId, WearProtocol.SESSION, payload.toByteArray()).await()
                }
            }
        }
    }

    /** Runs [block] against the playback service, starting it (via a media controller) if needed. */
    private fun withPlayback(block: (PlaybackService) -> Unit) {
        PlaybackService.instance?.let { block(it); return }
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            val controller = runCatching { future.get() }.getOrNull() ?: return@addListener
            PlaybackService.instance?.let(block)
            // Let the command take effect before letting go of the service.
            android.os.Handler(mainLooper).postDelayed({ controller.release() }, 5_000)
        }, MoreExecutors.directExecutor())
    }

    private fun run(service: PlaybackService, cmd: String) {
        when {
            cmd.startsWith(WearProtocol.EQ_PREFIX) -> runCatching {
                app.settings.setEq(app.api.json.decodeFromString(EqState.serializer(), cmd.removePrefix(WearProtocol.EQ_PREFIX)))
            }
            cmd.startsWith(WearProtocol.RADIO_PREFIX) -> {
                val bpm = cmd.removePrefix(WearProtocol.RADIO_PREFIX).toIntOrNull()
                if (bpm == null) { WearBridge.radioBpm = null; WearBridge.radioFromWatch.value = false }
                else { WearBridge.radioFromWatch.value = true; service.radio(bpm) }
            }
            else -> service.widgetCommand(cmd) // play_pause, next, previous, fav, shuffle
        }
        service.publishToWatch()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
