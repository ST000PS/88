package com.st000ps.powerstone.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelsTest {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }

    // Trimmed copy of a real /api/catalog response.
    private val sample = """
        {"releases": [{"id": "gt-3", "title": "GT.3", "artist": "Knx.", "year": "2026", "date": "2026-10-03",
          "sort": 20261003, "kind": "released", "preview_seconds": 28, "cover": "/a/cover/gt-3.jpg?v=1",
          "thumb": "/a/thumb/gt-3.jpg?v=1", "genres": [], "tags": [], "bandcamp": null,
          "tracks": [{"n": 1, "title": "WDWGW", "duration": 58.9, "tag": "", "tags": [], "video": null,
            "std": "/a/std/gt-3/01-WDWGW.mp3", "lo": "/a/lo/gt-3/01-WDWGW.mp3", "bpm": 62, "key": "F",
            "key_ja": "ヘ長調", "preview": "/a/preview/gt-3/01-WDWGW.mp3", "full": "/a/full/gt-3/01-WDWGW.mp3"},
            {"n": 2, "title": "DNTWNTU", "duration": 126, "preview": "/a/preview/gt-3/02-DNTWNTU.mp3",
            "full": "/a/full/gt-3/02-DNTWNTU.mp3"}]}]}
    """.trimIndent()

    @Test fun parsesCatalog() {
        val r = json.decodeFromString<Catalog>(sample).releases.single()
        assertEquals("gt-3", r.id)
        assertEquals(28, r.previewSeconds)
        assertEquals(2, r.tracks.size)
        assertEquals(126.0, r.tracks[1].duration, 0.0)
        assertEquals("gt-3/1", trackId(r, r.tracks[0]))
    }

    @Test fun guestsGetPreviewsSubscribersGetChosenQuality() {
        val t = json.decodeFromString<Catalog>(sample).releases.single().tracks
        assertEquals("/a/preview/gt-3/01-WDWGW.mp3", t[0].streamPath(subscriber = false, quality = Quality.HIGH))
        assertEquals("/a/full/gt-3/01-WDWGW.mp3", t[0].streamPath(subscriber = true, quality = Quality.HIGH))
        assertEquals("/a/lo/gt-3/01-WDWGW.mp3", t[0].streamPath(subscriber = true, quality = Quality.LO))
        // Falls back to full when a lower bitrate isn't published.
        assertEquals("/a/full/gt-3/02-DNTWNTU.mp3", t[1].streamPath(subscriber = true, quality = Quality.LO))
    }

    @Test fun formatsDuration() {
        assertEquals("1:41", formatDuration(101.7))
    }
}
