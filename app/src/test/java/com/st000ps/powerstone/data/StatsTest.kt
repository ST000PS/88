package com.st000ps.powerstone.data

import com.st000ps.powerstone.playback.Queue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StatsTest {
    private val a = Release("a", "Album A", tracks = listOf(Track(1, "One"), Track(2, "Two")), tags = listOf("jazz"))
    private val b = Release("b", "Album B", tracks = listOf(Track(1, "Uno", tags = listOf("vgm"))))
    private val rare = Release("r", "Rare", kind = "loose", tracks = listOf(Track(1, "Loose")))
    private val min = 60_000L

    @Test fun sessionsSplitOnThirtyMinuteGaps() {
        val ev = listOf(PlayEvent("a", 1, 0, 1000), PlayEvent("a", 2, 10 * min, 1000), PlayEvent("b", 1, 50 * min, 1000))
        val s = StatsMath.sessions(ev)
        assertEquals(2, s.size)
        assertEquals(50 * min, s[0].start) // newest first
        assertEquals(2, s[1].plays.size)
    }

    @Test fun computeRanksTracksReleasesAndTags() {
        val ev = listOf(
            PlayEvent("a", 1, 100, 2 * min), PlayEvent("a", 1, 200, 2 * min),
            PlayEvent("b", 1, 300, min), PlayEvent("a", 2, 50, min), // before range
        )
        val st = StatsMath.compute(ev, from = 100, until = Long.MAX_VALUE, releases = listOf(a, b))
        assertEquals(3, st.plays)
        assertEquals(5, st.minutes)
        assertEquals(2, st.uniqueTracks)
        assertEquals("One", st.topTracks[0].title)
        assertEquals(2, st.topTracks[0].plays)
        assertEquals("a", st.topReleases[0].r)
        assertEquals(listOf("jazz", "vgm"), st.topTags.map { it.tag })
        assertEquals(2, st.counts["a|1"])
    }

    @Test fun albumsRollIntoTheNextReleaseThenRareTrax() {
        val all = listOf(a, rare, b)
        assertEquals(listOf("a", "b", "r"), Queue.sequence(all).map { it.id })
        assertEquals("b", Queue.nextAlbum(all, "a")?.id)
        assertEquals("r", Queue.nextAlbum(all, "b")?.id)
        assertEquals("a", Queue.nextAlbum(all, "r")?.id) // wraps around
        assertNull(Queue.nextAlbum(all, "missing"))
    }

    @Test fun seriesComesFromTheTitle() {
        assertEquals("WT", Release("x", "WT.4").series)
        assertEquals("OTHER", Release("y", "GT.3").series)
    }

    @Test fun eqGainRulesMatchTheSite() {
        val eq = EqState(lo = 1.2f, mid = 0.5f, kill = setOf("hi"))
        assertEquals(1.2f, eq.gain("lo"))
        assertEquals(0f, eq.gain("hi"))
        val iso = eq.copy(solo = "mid")
        assertEquals(0.6f, iso.gain("mid")) // ISO keeps at least 0.6
        assertEquals(0f, iso.gain("lo"))
    }
}
