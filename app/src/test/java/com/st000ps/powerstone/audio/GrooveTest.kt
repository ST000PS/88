package com.st000ps.powerstone.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class GrooveTest {
    private val frameSec = 256.0 / 44100

    /** Envelopes for [bars] bars of a groove at [bpm], starting [offsetSteps] 16ths into a bar (as a mid-track excerpt would). */
    private fun synth(bpm: Double, kicks: Set<Int>, snares: Set<Int>, hats: Set<Int>, bars: Int = 10, offsetSteps: Int = 5): Triple<FloatArray, FloatArray, FloatArray> {
        val step = 60.0 / bpm / 4
        val n = ((bars * 16 * step) / frameSec).toInt()
        val low = FloatArray(n) { 1e-4f }; val mid = FloatArray(n) { 1e-4f }; val high = FloatArray(n) { 1e-4f }
        val rnd = java.util.Random(7)
        for (i in 0 until n) {
            val t = i * frameSec
            val s = ((t / step).toInt() + offsetSteps) % 16
            val into = (t / step) - kotlin.math.floor(t / step)
            val decay = kotlin.math.exp(-into * 6).toFloat()
            if (s in kicks) low[i] += decay
            if (s in snares) mid[i] += decay * 0.8f
            if (s in hats) high[i] += decay * 0.5f
            low[i] *= 1 + rnd.nextFloat() * 0.1f; mid[i] *= 1 + rnd.nextFloat() * 0.1f; high[i] *= 1 + rnd.nextFloat() * 0.1f
        }
        return Triple(low, mid, high)
    }

    private fun groove(bpm: Double, k: Set<Int>, s: Set<Int>, h: Set<Int>): Groove {
        val (l, m, hi) = synth(bpm, k, s, h)
        return GrooveMath.pick(GrooveMath.profile(l, m, hi, frameSec, bpm))
    }

    @Test fun findsBoomBapFromAnExcerptStartingMidBar() {
        val g = groove(92.0, setOf(0, 7, 10), setOf(4, 12), (0 until 16 step 2).toSet())
        assertEquals(1, g.kick); assertEquals(0, g.snare); assertEquals(0, g.hat)
    }

    @Test fun findsFourOnTheFloorWithSixteenthHats() {
        val g = groove(120.0, setOf(0, 4, 8, 12), setOf(4, 12), (0 until 16).toSet())
        assertEquals(3, g.kick); assertEquals(1, g.hat)
    }

    @Test fun findsHalfTimeSnare() {
        val g = groove(80.0, setOf(0, 8), setOf(8), setOf(2, 6, 10, 14))
        assertEquals(0, g.kick); assertEquals(1, g.snare); assertEquals(2, g.hat)
    }

    @Test fun encodesAndDecodes() {
        val g = Groove(5, 2, 3, 1)
        assertEquals(g, Groove.decode(g.encode()))
        assertEquals(null, Groove.decode("junk"))
    }
}
