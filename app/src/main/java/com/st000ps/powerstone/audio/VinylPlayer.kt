package com.st000ps.powerstone.audio

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.st000ps.powerstone.data.Settings
import kotlin.math.pow

/**
 * Wraps the player so every pause — app, notification, lock screen, headset —
 * winds down like a turntable losing power (speed and pitch fall, the tail
 * fades), and resuming spins back up. Same curves as the site's VINYL STOP.
 */
@OptIn(UnstableApi::class)
class VinylPlayer(private val exo: ExoPlayer, private val settings: Settings) : ForwardingPlayer(exo) {
    private val handler = Handler(Looper.getMainLooper())
    private var anim: Runnable? = null
    private var braking = false
    private var userVolume = exo.volume

    /** Crossfade envelope (0..1) set by the service; multiplied with the listener's own volume. */
    var fadeGain = 1f
        set(value) { if (field != value) { field = value; exo.volume = userVolume * value } }

    override fun setVolume(volume: Float) { userVolume = volume; exo.volume = volume * fadeGain }
    override fun getVolume(): Float = userVolume

    /** Audio is processed ahead of what you hear; keep running a moment so the whole tail is heard. */
    private val tailMs = 450L

    private companion object { const val STEP_MS = 30L }

    override fun pause() {
        if (braking) { finishBrake(); return } // pressed again mid-stop: stop now
        val len = settings.vinyl.value.seconds
        if (len <= 0f || !exo.isPlaying) { cancel(); super.pause(); return }
        brake(len)
    }

    override fun setPlayWhenReady(playWhenReady: Boolean) { if (playWhenReady) play() else pause() }

    override fun play() {
        val resuming = braking || !exo.playWhenReady
        cancel()
        super.play()
        if (resuming && settings.vinyl.value.seconds > 0f && exo.currentPosition > 300) spinUp()
    }

    private fun brake(len: Float) {
        cancel()
        braking = true
        val start = SystemClock.uptimeMillis()
        val total = (len * 1000).toLong()
        anim = object : Runnable {
            override fun run() {
                val p = ((SystemClock.uptimeMillis() - start).toFloat() / total).coerceIn(0f, 1f)
                val r = maxOf(0.1f, (1 - p).pow(1.15f))
                exo.playbackParameters = PlaybackParameters(r, r)
                AudioFx.vinyl = r.pow(1.2f) * (if (p > 0.7f) maxOf(0f, (1 - p) / 0.3f) else 1f)
                if (SystemClock.uptimeMillis() - start >= total + tailMs) finishBrake()
                else handler.postDelayed(this, STEP_MS)
            }
        }.also { handler.post(it) }
    }

    private fun finishBrake() {
        cancel()
        super.pause()
        exo.playbackParameters = PlaybackParameters.DEFAULT
        // Drop the silent tail still queued in the audio output so resuming is instant.
        exo.seekTo(exo.currentPosition)
        AudioFx.vinyl = 1f
    }

    private fun spinUp() {
        val start = SystemClock.uptimeMillis()
        val total = 220L
        AudioFx.vinyl = 0.5f
        anim = object : Runnable {
            override fun run() {
                val p = ((SystemClock.uptimeMillis() - start).toFloat() / total).coerceIn(0f, 1f)
                val r = 0.35f + 0.65f * p.pow(0.8f)
                exo.playbackParameters = PlaybackParameters(r, r)
                AudioFx.vinyl = 0.5f + 0.5f * p
                if (p >= 1f) { anim = null; exo.playbackParameters = PlaybackParameters.DEFAULT; AudioFx.vinyl = 1f }
                else handler.postDelayed(this, STEP_MS)
            }
        }.also { handler.post(it) }
    }

    private fun cancel() {
        anim?.let(handler::removeCallbacks)
        anim = null
        if (braking || exo.playbackParameters != PlaybackParameters.DEFAULT) exo.playbackParameters = PlaybackParameters.DEFAULT
        braking = false
        AudioFx.vinyl = 1f
    }
}
