package com.st000ps.powerstone.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin


/** Collects samples and publishes a log-spaced spectrum (the visualizer's data) into [AudioFx.spectrum]. */
class Spectrum(private val rate: Int) {
    companion object {
        const val BANDS = 48
        private const val N = 2048
        private const val MIN_DB = -92f
        private const val MAX_DB = -18f
    }

    private val buf = FloatArray(N)
    private var fill = 0
    private val re = FloatArray(N)
    private val im = FloatArray(N)
    private val window = FloatArray(N) { (0.5 - 0.5 * cos(2 * PI * it / (N - 1))).toFloat() }
    private val cosT = FloatArray(N / 2) { cos(2 * PI * it / N).toFloat() }
    private val sinT = FloatArray(N / 2) { sin(2 * PI * it / N).toFloat() }
    // Band edges from 30 Hz to 16 kHz, log spaced.
    private val edges = IntArray(BANDS + 1) { i ->
        val f = 30.0 * (16000.0 / 30.0).pow(i.toDouble() / BANDS)
        (f * N / rate).toInt().coerceIn(1, N / 2 - 1)
    }

    fun push(x: Float) {
        buf[fill++] = x
        if (fill == N) { analyse(); fill = N / 2; System.arraycopy(buf, N / 2, buf, 0, N / 2) }
    }

    private fun analyse() {
        for (i in 0 until N) { re[i] = buf[i] * window[i]; im[i] = 0f }
        fft()
        val out = AudioFx.spectrum
        for (b in 0 until BANDS) {
            val lo = edges[b]; val hi = maxOf(edges[b + 1], lo + 1)
            var peak = 0f
            for (i in lo until hi) { val m = re[i] * re[i] + im[i] * im[i]; if (m > peak) peak = m }
            val db = 10f * log10(peak / (N * N / 16f) + 1e-12f)
            val v = ((db - MIN_DB) / (MAX_DB - MIN_DB)).coerceIn(0f, 1f)
            // fast attack, slower fall, like the site's analyser bars
            out[b] = if (v > out[b]) v else out[b] * 0.82f + v * 0.18f
        }
        AudioFx.spectrumAt = System.nanoTime()
    }

    private fun fft() {
        var j = 0
        for (i in 1 until N) {
            var bit = N shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= N) {
            val step = N / len
            val half = len / 2
            var i = 0
            while (i < N) {
                for (k in 0 until half) {
                    val wr = cosT[k * step]; val wi = -sinT[k * step]
                    val a = i + k; val b = a + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                }
                i += len
            }
            len = len shl 1
        }
    }
}

