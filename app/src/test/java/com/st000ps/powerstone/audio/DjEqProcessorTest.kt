package com.st000ps.powerstone.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import com.st000ps.powerstone.data.EqState
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class DjEqProcessorTest {
    private val rate = 44100

    @After fun reset() { AudioFx.apply(EqState()); AudioFx.vinyl = 1f }

    /** Plays one second of a stereo sine through the EQ and returns output RMS / input RMS over the last quarter. */
    private fun gainAt(freq: Double, eq: EqState): Double {
        AudioFx.apply(eq)
        val p = DjEqProcessor()
        p.configure(AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        @Suppress("DEPRECATION") p.flush()
        val frames = rate
        val input = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        val src = DoubleArray(frames) { 0.5 * sin(2 * PI * freq * it / rate) }
        src.forEach { val s = (it * 32767).toInt().toShort(); input.putShort(s); input.putShort(s) }
        input.flip()
        val out = ArrayList<Short>(frames * 2)
        while (input.hasRemaining()) {
            val chunk = input.duplicate().order(ByteOrder.nativeOrder())
            val n = minOf(4096, input.remaining())
            chunk.limit(chunk.position() + n)
            input.position(input.position() + n)
            p.queueInput(chunk)
            val o = p.output
            while (o.hasRemaining()) out += o.getShort()
        }
        val tail = frames * 3 / 4
        fun rms(xs: List<Double>) = sqrt(xs.sumOf { it * it } / xs.size)
        val outL = (tail until frames).map { out[it * 2] / 32768.0 }
        val inL = (tail until frames).map { src[it] }
        return rms(outL) / rms(inL)
    }

    @Test fun flatIsUnityAcrossTheSpectrum() {
        for (f in listOf(60.0, 1000.0, 8000.0)) {
            val g = gainAt(f, EqState())
            assertTrue("flat gain at $f Hz was $g", g in 0.85..1.15)
        }
    }

    @Test fun killMidRemovesMidrange() {
        assertTrue(gainAt(1000.0, EqState(kill = setOf("mid"))) < 0.1)
        assertTrue(gainAt(60.0, EqState(kill = setOf("mid"))) > 0.85)
    }

    @Test fun isoLoKeepsOnlyBass() {
        assertTrue(gainAt(60.0, EqState(solo = "lo")) > 0.85)
        assertTrue(gainAt(8000.0, EqState(solo = "lo")) < 0.05)
    }

    @Test fun boostIsCapped() {
        // 1.5x of a 0.5 sine = 0.75 peak: no clipping, gain ≈ 1.5
        val g = gainAt(8000.0, EqState(hi = 1.5f))
        assertTrue("hi boost was $g", g in 1.35..1.6)
    }

    @Test fun vinylEnvelopeSilences() {
        AudioFx.vinyl = 0f
        assertTrue(gainAt(1000.0, EqState()) < 0.01)
    }
}
