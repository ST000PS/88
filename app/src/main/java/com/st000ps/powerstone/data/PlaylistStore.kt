package com.st000ps.powerstone.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.io.File
import kotlin.random.Random

/**
 * Playlists, synced the same way the site does it: kept on the device, and when
 * signed in mirrored to /api/playlists with a list of deleted ids so deletions
 * stick across devices. Playlists made while signed out are merged in on sign-in.
 */
class PlaylistStore(private val api: Api, private val settings: Settings, filesDir: File, private val scope: CoroutineScope) {
    private val file = File(filesDir, "playlists.json")
    private val _list = MutableStateFlow(readLocal())
    val list: StateFlow<List<Playlist>> = _list.asStateFlow()
    private var signedIn = false
    private var pushJob: Job? = null

    private fun readLocal(): List<Playlist> =
        runCatching { api.json.decodeFromString<List<Playlist>>(file.readText()) }.getOrDefault(emptyList())

    private fun saveLocal() { runCatching { file.writeText(api.json.encodeToString(_list.value)) } }

    private fun set(list: List<Playlist>) { _list.value = list; push() }

    private fun push() {
        saveLocal()
        if (!signedIn) return
        pushJob?.cancel()
        pushJob = scope.launch(Dispatchers.IO) {
            delay(400)
            api.putPlaylists(PlaylistsBody(_list.value, settings.playlistsDeleted))
        }
    }

    private fun dedupe(list: List<Playlist>) = list.map { pl ->
        val unique = pl.tracks.distinct()
        if (unique.size == pl.tracks.size) pl else pl.copy(tracks = unique, updated = System.currentTimeMillis())
    }

    suspend fun load(signedIn: Boolean) {
        pushJob?.join()
        this.signedIn = signedIn
        val local = readLocal()
        if (!signedIn) {
            settings.playlistsGuest = true
            _list.value = dedupe(local); saveLocal(); return
        }
        try {
            val remote = api.playlists()
            val gone = (remote.deleted + settings.playlistsDeleted).toSet()
            val server = remote.playlists.filter { it.id !in gone }
            val ids = server.map { it.id }.toSet()
            val wasGuest = settings.playlistsGuest
            settings.playlistsGuest = false
            val merged = dedupe(server + local.filter { wasGuest && it.id !in ids && it.id !in gone })
            _list.value = merged
            if (merged != remote.playlists) push() else saveLocal()
        } catch (_: Exception) {
            _list.value = dedupe(local)
        }
    }

    fun signedOut() { signedIn = false }

    fun get(id: String) = _list.value.firstOrNull { it.id == id }

    fun create(name: String? = null, tracks: List<TrackRef> = emptyList()): Playlist {
        val id = System.currentTimeMillis().toString(36) + Random.nextInt(36 * 36 * 36 * 36).toString(36)
        val pl = Playlist(id, name ?: "MY PLAYLIST ${_list.value.size + 1}", tracks.distinct(), System.currentTimeMillis())
        set(listOf(pl) + _list.value)
        return pl
    }

    private fun update(id: String, f: (Playlist) -> Playlist) =
        set(_list.value.map { if (it.id == id) f(it).copy(updated = System.currentTimeMillis()) else it })

    /** Returns false when the track is already in the playlist. */
    fun add(id: String, ref: TrackRef): Boolean {
        val pl = get(id) ?: return false
        if (ref in pl.tracks) return false
        update(id) { it.copy(tracks = it.tracks + ref) }
        return true
    }

    fun removeAt(id: String, index: Int) = update(id) { it.copy(tracks = it.tracks.filterIndexed { i, _ -> i != index }) }

    fun move(id: String, from: Int, to: Int) = update(id) {
        val t = it.tracks.toMutableList()
        if (from in t.indices && to in t.indices) t.add(to, t.removeAt(from))
        it.copy(tracks = t)
    }

    fun rename(id: String, name: String) {
        val clean = name.trim().take(80)
        if (clean.isNotEmpty()) update(id) { it.copy(name = clean) }
    }

    fun delete(id: String) {
        settings.playlistsDeleted = listOf(id) + settings.playlistsDeleted.filter { it != id }
        set(_list.value.filter { it.id != id })
    }
}
