package com.st000ps.powerstone.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Downloaded tracks for listening without a connection — subscribers only, the way streaming
 * services do offline mode: files live in the app's private storage; they play offline for
 * [GRACE_MS] after the subscription was last confirmed; and they're deleted if the site says the
 * account isn't subscribed (or on sign-out). A failed network check never deletes anything.
 */
class OfflineStore(
    private val dir: File,
    private val json: Json,
    private val download: suspend (url: String) -> ByteArray,
    private val now: () -> Long = System::currentTimeMillis,
) {
    companion object { const val GRACE_MS = 30L * 24 * 3600 * 1000 }

    @Serializable data class Entry(val file: String, val bytes: Long, val added: Long)
    @Serializable private data class Index(val tracks: Map<String, Entry> = emptyMap(), val verifiedAt: Long = 0)

    private val indexFile = File(dir, "index.json")
    private var index: Index = runCatching { json.decodeFromString<Index>(indexFile.readText()) }.getOrDefault(Index())

    /** Bumps whenever downloads change, so screens can refresh. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    init { dir.mkdirs() }

    @Synchronized private fun save() { runCatching { indexFile.writeText(json.encodeToString(index)) }; _version.value++ }

    val count: Int @Synchronized get() = index.tracks.size
    val totalBytes: Long @Synchronized get() = index.tracks.values.sumOf { it.bytes }
    @Synchronized fun has(id: String) = id in index.tracks

    /** True while downloads may be played (subscription confirmed within the grace period). */
    val usable: Boolean @Synchronized get() = index.verifiedAt > 0 && now() - index.verifiedAt < GRACE_MS

    /** The local file for a downloaded track, or null (not downloaded, or locked). */
    @Synchronized fun fileFor(id: String): File? {
        val e = index.tracks[id] ?: return null
        if (!usable) return null
        return File(dir, e.file).takeIf { it.exists() }
    }

    /** Call only with a real /api/me answer — never after a network failure. */
    fun accountChecked(me: Me) {
        if (me.isSubscriber) synchronized(this) { index = index.copy(verifiedAt = now()); save() }
        else if (count > 0) removeAll()
    }

    private fun fileName(id: String) = id.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("") + ".mp3"

    /** Downloads the tracks that aren't here yet, one at a time; returns how many were added. */
    suspend fun download(items: List<Pair<String, String>>, progress: (done: Int, total: Int) -> Unit = { _, _ -> }): Int {
        if (!usable) throw ApiException("Downloads are for subscribers. Sign in with an active subscription first.")
        val todo = items.distinctBy { it.first }.filterNot { has(it.first) }
        progress(0, todo.size)
        var done = 0
        for ((id, url) in todo) {
            currentCoroutineContext().ensureActive()
            val bytes = download(url)
            val name = fileName(id)
            withContext(Dispatchers.IO) {
                val tmp = File(dir, "$name.part")
                tmp.writeBytes(bytes)
                tmp.renameTo(File(dir, name))
            }
            synchronized(this) { index = index.copy(tracks = index.tracks + (id to Entry(name, bytes.size.toLong(), now()))); save() }
            done++
            progress(done, todo.size)
        }
        return done
    }

    @Synchronized fun remove(ids: Collection<String>) {
        ids.forEach { id -> index.tracks[id]?.let { File(dir, it.file).delete() } }
        index = index.copy(tracks = index.tracks - ids.toSet())
        save()
    }

    @Synchronized fun removeAll() {
        index.tracks.values.forEach { File(dir, it.file).delete() }
        index = Index()
        save()
    }
}
