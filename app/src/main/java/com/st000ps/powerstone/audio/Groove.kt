package com.st000ps.powerstone.audio

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A track's groove, as the widget's pads play it: which kick, snare, hi-hat and sample-chop
 * patterns (from [GrooveBank]) sit closest to where the track's own hits land in the bar.
 */
data class Groove(val kick: Int, val snare: Int, val hat: Int, val chop: Int) {
    fun encode() = "$kick,$snare,$hat,$chop"

    companion object {
        /** Before a track has been listened to: a plain boom-bap. */
        val DEFAULT = Groove(1, 0, 0, 1)

        fun decode(s: String?): Groove? = s?.split(',')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 4 }?.let {
            Groove(
                it[0].coerceIn(0, GrooveBank.KICKS.size - 1), it[1].coerceIn(0, GrooveBank.SNARES.size - 1),
                it[2].coerceIn(0, GrooveBank.HATS.size - 1), it[3].coerceIn(0, GrooveBank.CHOPS - 1),
            )
        }
    }
}

/** The patterns the widget can play, on 16th-note steps of one 4/4 bar. Mirrors widget_art7.py. */
object GrooveBank {
    val KICKS = listOf(setOf(0, 8), setOf(0, 7, 10), setOf(0, 3, 8, 11), setOf(0, 4, 8, 12), setOf(0), setOf(0, 6, 10, 14))
    val SNARES = listOf(setOf(4, 12), setOf(8), setOf(4, 12, 15))
    val HATS = listOf((0 until 16 step 2).toSet(), (0 until 16).toSet(), setOf(2, 6, 10, 14), setOf(0, 4, 8, 12))
    /** Sample-chop patterns, from sparse to busy (chosen by how much happens in the mids). */
    const val CHOPS = 4
}

/** Where a track's hits land in the bar: onset strength per 16th step, per band, each 0..1. */
class StepProfile(val low: FloatArray, val mid: FloatArray, val high: FloatArray)

object GrooveMath {
    private const val BINS = 192 // per bar: 12 per 16th step

    /**
     * Folds band energy envelopes (one value per frame, [frameSec] apart) onto a bar at [bpm]: onset
     * strength (rises in log energy) is summed per position in the bar, the bar's start is put where
     * a kick on 1 and snares on 2 and 4 fit best, and each 16th step gets its share.
     */
    fun profile(low: FloatArray, mid: FloatArray, high: FloatArray, frameSec: Double, bpm: Double): StepProfile {
        val bar = 240.0 / bpm
        fun fold(env: FloatArray): DoubleArray {
            val acc = DoubleArray(BINS)
            var prev = ln(env.firstOrNull()?.toDouble()?.plus(1e-9) ?: 1e-9)
            for (i in env.indices) {
                val e = ln(env[i] + 1e-9)
                val onset = max(0.0, e - prev)
                prev = e
                val pos = ((i * frameSec) % bar) / bar
                acc[(pos * BINS).toInt().coerceIn(0, BINS - 1)] += onset
            }
            return acc
        }
        val l = fold(low); val m = fold(mid); val h = fold(high)
        fun win(a: DoubleArray, c: Int) = (-3..3).sumOf { a[((c + it) % BINS + BINS) % BINS] }
        val phase = (0 until BINS).maxBy { p -> win(l, p) + 0.7 * (win(m, p + BINS / 4) + win(m, p + 3 * BINS / 4)) }
        fun steps(a: DoubleArray): FloatArray {
            val s = FloatArray(16) { win(a, phase + it * BINS / 16).toFloat() }
            val top = s.maxOrNull()?.takeIf { it > 0f } ?: 1f
            return FloatArray(16) { s[it] / top }
        }
        return StepProfile(steps(l), steps(m), steps(h))
    }

    /** The bank patterns nearest the profile. */
    fun pick(p: StepProfile): Groove {
        fun best(options: List<Set<Int>>, band: FloatArray): Int {
            val norm = sqrt(band.sumOf { (it * it).toDouble() }).coerceAtLeast(1e-6)
            // Cosine between the pattern (as 0/1 steps) and the band, minus a little for hits the track doesn't have.
            return options.indices.maxBy { i ->
                val set = options[i]
                val hit = set.sumOf { band[it].toDouble() } / (sqrt(set.size.toDouble()) * norm)
                val miss = set.count { band[it] < 0.25f } * 0.08
                hit - miss
            }
        }
        val kick = best(GrooveBank.KICKS, p.low)
        val snare = best(GrooveBank.SNARES, p.mid)
        val hat = best(GrooveBank.HATS, p.high)
        // Chops: how many mid-band hits there are off the snare steps.
        val busy = (0 until 16).count { it !in GrooveBank.SNARES[snare] && p.mid[it] > 0.45f }
        val chop = when { busy <= 1 -> 0; busy <= 3 -> 3; busy <= 5 -> 1; else -> 2 }
        return Groove(kick, snare, hat, chop)
    }

    /** One-pole filter coefficient for a cutoff at [hz]. */
    fun alpha(hz: Double, rate: Int) = 1.0 - kotlin.math.exp(-2.0 * Math.PI * hz / rate)

    /**
     * Splits mono PCM into low (< 150 Hz: kicks), mid (300 Hz-3 kHz: snares, chops) and high
     * (> 6 kHz: hats) energy envelopes, one value per [frame] samples.
     */
    fun envelopes(pcm: ShortArray, count: Int, rate: Int, frame: Int, state: FilterState, out: Envelopes) {
        val a150 = alpha(150.0, rate); val a300 = alpha(300.0, rate); val a3k = alpha(3000.0, rate); val a6k = alpha(6000.0, rate)
        for (i in 0 until count) {
            val x = pcm[i] / 32768.0
            state.lp150 += a150 * (x - state.lp150)
            state.lp300 += a300 * (x - state.lp300)
            state.lp3k += a3k * (x - state.lp3k)
            state.lp6k += a6k * (x - state.lp6k)
            val lo = state.lp150; val mi = state.lp3k - state.lp300; val hi = x - state.lp6k
            state.eLow += lo * lo; state.eMid += mi * mi; state.eHigh += hi * hi
            if (++state.n == frame) {
                out.low.add((state.eLow / frame).toFloat()); out.mid.add((state.eMid / frame).toFloat()); out.high.add((state.eHigh / frame).toFloat())
                state.eLow = 0.0; state.eMid = 0.0; state.eHigh = 0.0; state.n = 0
            }
        }
    }

    class FilterState { var lp150 = 0.0; var lp300 = 0.0; var lp3k = 0.0; var lp6k = 0.0; var eLow = 0.0; var eMid = 0.0; var eHigh = 0.0; var n = 0 }
    class Envelopes { val low = ArrayList<Float>(); val mid = ArrayList<Float>(); val high = ArrayList<Float>() }
}
