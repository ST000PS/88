package com.st000ps.powerstone.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.Calendar

/**
 * On-device listening log (last 3000 plays), used for history and stats when
 * signed out, exactly like the site's local history.
 */
class ListeningHistory(filesDir: File, private val json: Json) {
    private val file = File(filesDir, "history.json")

    @Synchronized
    fun all(): List<PlayEvent> = runCatching { json.decodeFromString<List<PlayEvent>>(file.readText()) }.getOrDefault(emptyList())

    @Synchronized
    fun add(events: List<PlayEvent>) {
        if (events.isEmpty()) return
        runCatching { file.writeText(json.encodeToString((all() + events).takeLast(3000))) }
    }
}

object StatsMath {
    private const val SESSION_GAP = 30 * 60 * 1000L

    /** Groups plays into sessions split by 30-minute gaps, newest first. */
    fun sessions(events: List<PlayEvent>): List<ListeningSession> {
        val out = mutableListOf<MutableList<PlayEvent>>()
        var lastTs = Long.MIN_VALUE
        for (e in events.sortedBy { it.ts }) {
            if (out.isEmpty() || e.ts - lastTs > SESSION_GAP) out += mutableListOf<PlayEvent>()
            out.last() += e
            lastTs = maxOf(lastTs, e.ts)
        }
        return out.map { ListeningSession(it.first().ts, it) }.reversed()
    }

    fun fromServer(raw: List<RawSession>): List<ListeningSession> = raw.map { s ->
        ListeningSession(s.s, s.items.mapNotNull { a ->
            runCatching {
                PlayEvent(
                    r = a[0].jsonPrimitive.contentOrNull!!,
                    n = a[1].jsonPrimitive.contentOrNull!!.toDouble().toInt(),
                    ts = a.getOrNull(2)?.jsonPrimitive?.longOrNull ?: s.s,
                    ms = a.getOrNull(3)?.jsonPrimitive?.contentOrNull?.toDouble()?.toLong() ?: 0,
                )
            }.getOrNull()
        })
    }

    fun startOfYear(year: Int): Long = Calendar.getInstance().apply { clear(); set(year, Calendar.JANUARY, 1) }.timeInMillis

    fun startOfMonth(): Long = Calendar.getInstance().apply {
        set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** Same numbers the site computes for signed-out listeners. */
    fun compute(events: List<PlayEvent>, from: Long, until: Long, releases: List<Release>): Stats {
        val byId = releases.associateBy { it.id }
        val inRange = events.filter { it.ts in from until until }
        val perTrack = inRange.groupBy { it.r to it.n }.map { (k, es) -> StatTrack(k.first, k.second, es.size, es.sumOf { it.ms }) }
            .sortedWith(compareByDescending<StatTrack> { it.plays }.thenByDescending { it.ms })
            .map { st ->
                val rel = byId[st.r]
                st.copy(title = rel?.tracks?.firstOrNull { it.n == st.n }?.title, release = rel?.title)
            }
        val relPlays = mutableMapOf<String, Int>()
        val tagPlays = mutableMapOf<String, Int>()
        for (t in perTrack) {
            val rel = byId[t.r] ?: continue
            val track = rel.tracks.firstOrNull { it.n == t.n } ?: continue
            relPlays[t.r] = (relPlays[t.r] ?: 0) + t.plays
            (track.tags + rel.tags + rel.genres).forEach { tagPlays[it] = (tagPlays[it] ?: 0) + t.plays }
        }
        return Stats(
            minutes = (inRange.sumOf { it.ms } / 60000).toInt(),
            plays = inRange.size,
            uniqueTracks = perTrack.size,
            topTracks = perTrack.take(10),
            topReleases = relPlays.entries.sortedByDescending { it.value }.take(5).map { StatRelease(it.key, byId[it.key]?.title ?: it.key, it.value) },
            topTags = tagPlays.entries.sortedByDescending { it.value }.take(8).map { StatTag(it.key, it.value) },
            counts = inRange.groupingBy { "${it.r}|${it.n}" }.eachCount(),
        )
    }
}
