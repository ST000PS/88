package com.st000ps.powerstone.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** What was playing, so the app (and Bluetooth / system media resumption) can pick up where it left off. */
@Serializable
data class SavedSession(val ids: List<String>, val index: Int, val positionMs: Long, val kind: String)

/** Sleep timer: pause at [endAt] (epoch ms), or at the end of the current track. */
data class SleepState(val endAt: Long? = null, val endOfTrack: Boolean = false) {
    val active get() = endAt != null || endOfTrack
}

class SessionStore(context: Context, private val json: Json) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    fun save(s: SavedSession) = prefs.edit().putString("session", json.encodeToString(s)).apply()

    fun load(): SavedSession? = runCatching { json.decodeFromString<SavedSession>(prefs.getString("session", null)!!) }.getOrNull()

    /** Last known account, so a restored queue streams at the right tier before the network answers. */
    fun saveMe(me: Me) = prefs.edit().putString("me", json.encodeToString(me)).apply()

    fun loadMe(): Me? = runCatching { json.decodeFromString<Me>(prefs.getString("me", null)!!) }.getOrNull()
}

/** Rebuilds a saved queue from the catalogue, skipping tracks that no longer exist. */
fun SavedSession.resolve(releases: List<Release>): Pair<List<Pair<Release, Track>>, Int>? {
    val byId = releases.associateBy { it.id }
    val items = mutableListOf<Pair<Release, Track>>()
    var start = -1
    ids.forEachIndexed { i, id ->
        val rel = byId[id.substringBeforeLast('/')] ?: return@forEachIndexed
        val n = id.substringAfterLast('/').toIntOrNull() ?: return@forEachIndexed
        val t = rel.tracks.firstOrNull { it.n == n } ?: return@forEachIndexed
        if (i == index) start = items.size
        items += rel to t
    }
    return if (items.isEmpty()) null else items to start.coerceAtLeast(0)
}
