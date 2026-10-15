package com.st000ps.powerstone.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionTest {
    private fun rel(id: String, n: Int) = Release(id, id, tracks = (1..n).map { Track(it, "$id$it") })

    @Test fun resolvesTheSavedQueueAndSkipsMissingTracks() {
        val s = SavedSession(listOf("gone/1", "a/1", "b/2", "a/9"), index = 2, positionMs = 1000, kind = "SHUFFLE")
        val (items, start) = s.resolve(listOf(rel("a", 2), rel("b", 2)))!!
        assertEquals(listOf("a/1", "b/2"), items.map { trackId(it.first, it.second) })
        assertEquals(1, start)
        assertNull(SavedSession(listOf("gone/1"), 0, 0, "LIST").resolve(listOf(rel("a", 1))))
    }

    @Test fun sleepStateIsActiveForEitherMode() {
        assertEquals(false, SleepState().active)
        assertEquals(true, SleepState(endAt = 1L).active)
        assertEquals(true, SleepState(endOfTrack = true).active)
    }
}
