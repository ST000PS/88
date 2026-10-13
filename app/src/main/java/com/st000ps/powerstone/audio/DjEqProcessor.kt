package com.st000ps.powerstone.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.st000ps.powerstone.data.EqState
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Live values shared between the UI/player thread and the audio thread. */
object AudioFx {
    @Volatile var lo = 1f
    @Volatile var mid = 1f
    @Volatile var hi = 1f
    /** Extra gain used by the vinyl stop / spin-up envelope. */
    @Volatile var vinyl = 1f
    @Volatile var spectrumOn = true

    fun apply(eq: EqState) { lo = eq.gain("lo"); mid = eq.gain("mid"); hi = eq.gain("hi") }

    /** Latest spectrum, 0..1 per band, low to high. */
    val spectrum = FloatArray(Spectrum.BANDS)
    @Volatile var spectrumAt = 0L
}

/** RBJ biquad, transposed direct form II. */
private class Biquad(lowpass: Boolean, freq: Double, rate: Int) {
    private val b0: Float; private val b1: Float; private val b2: Float; private val a1: Float; private val a2: Float
    private var z1 = 0f; private var z2 = 0f

    init {
        val w = 2 * PI * freq / rate
        val alpha = sin(w) / (2 * 0.7071067811865476)
        val c = cos(w)
        val a0 = 1 + alpha
        if (lowpass) {
            b0 = ((1 - c) / 2 / a0).toFloat(); b1 = ((1 - c) / a0).toFloat(); b2 = b0
        } else {
            b0 = ((1 + c) / 2 / a0).toFloat(); b1 = (-(1 + c) / a0).toFloat(); b2 = b0
        }
        a1 = (-2 * c / a0).toFloat(); a2 = ((1 - alpha) / a0).toFloat()
    }

    fun run(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    fun reset() { z1 = 0f; z2 = 0f }
}

/** 4th-order Linkwitz–Riley section = two Butterworth biquads in series (as `lr4()` on the site). */
private class Lr4(lowpass: Boolean, freq: Double, rate: Int) {
    private val a = Biquad(lowpass, freq, rate); private val b = Biquad(lowpass, freq, rate)
    fun run(x: Float) = b.run(a.run(x))
    fun reset() { a.reset(); b.reset() }
}

private class Channel(rate: Int) {
    val lo = Lr4(true, 250.0, rate)
    val hpA = Lr4(false, 250.0, rate)
    val mid = Lr4(true, 4000.0, rate)
    val hi = Lr4(false, 4000.0, rate)
    fun reset() { lo.reset(); hpA.reset(); mid.reset(); hi.reset() }
}

/**
 * The site's DJ EQ, ported: the signal is split at 250 Hz and 4 kHz with
 * Linkwitz–Riley crossovers into LO / MID / HI, each with its own gain, then
 * summed. Also feeds the visualizer and applies the vinyl-stop envelope.
 */
@OptIn(UnstableApi::class)
class DjEqProcessor : BaseAudioProcessor() {
    private var channels: Array<Channel> = emptyArray()
    private var gLo = 1f; private var gMid = 1f; private var gHi = 1f; private var gVinyl = 1f
    private var spectrum: Spectrum? = null

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        // Anything but 16-bit PCM (never the case for these MP3s) just bypasses the EQ.
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioFormat.NOT_SET
        channels = Array(inputAudioFormat.channelCount) { Channel(inputAudioFormat.sampleRate) }
        spectrum = Spectrum(inputAudioFormat.sampleRate)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        val chs = channels
        val n = chs.size
        val tLo = AudioFx.lo; val tMid = AudioFx.mid; val tHi = AudioFx.hi; val tVinyl = AudioFx.vinyl
        val spec = if (AudioFx.spectrumOn) spectrum else null
        val k = 0.003f // per-frame smoothing, ~7 ms at 44.1 kHz: no zipper noise on slider moves
        while (inputBuffer.remaining() >= 2 * n) {
            gLo += (tLo - gLo) * k; gMid += (tMid - gMid) * k; gHi += (tHi - gHi) * k; gVinyl += (tVinyl - gVinyl) * k
            var mono = 0f
            for (c in 0 until n) {
                val x = inputBuffer.getShort() / 32768f
                val ch = chs[c]
                val high = ch.hpA.run(x)
                var y = gLo * ch.lo.run(x) + gMid * ch.mid.run(high) + gHi * ch.hi.run(high)
                y *= gVinyl
                if (y > 1f) y = 1f else if (y < -1f) y = -1f
                mono += y
                out.putShort((y * 32767f).toInt().toShort())
            }
            spec?.push(mono / n)
        }
        out.flip()
    }

    override fun onFlush() { channels.forEach { it.reset() } }

    override fun onReset() { channels = emptyArray(); spectrum = null }
}
