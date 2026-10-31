package com.st000ps.powerstone.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.st000ps.powerstone.PowerstoneApp
import com.st000ps.powerstone.data.Api
import com.st000ps.powerstone.data.Release
import com.st000ps.powerstone.data.Track
import com.st000ps.powerstone.data.streamPath
import com.st000ps.powerstone.data.trackId

/** What a queue was started from; decides what happens when it runs out. */
enum class QueueKind { ALBUM, SHUFFLE, LIST, RADIO }

object Queue {
    const val KIND = "kind"
    const val VIDEO = "video"

    fun kindOf(item: MediaItem?): QueueKind? =
        item?.mediaMetadata?.extras?.getString(KIND)?.let { runCatching { QueueKind.valueOf(it) }.getOrNull() }

    fun mediaItem(app: PowerstoneApp, release: Release, track: Track, kind: QueueKind): MediaItem? {
        // A downloaded copy plays from the device; otherwise stream.
        val url = app.offline.fileFor(trackId(release, track))?.let { Uri.fromFile(it).toString() }
            ?: Api.absolute(track.streamPath(app.me.value.isSubscriber, app.resolvedQuality())) ?: return null
        val extras = Bundle().apply {
            putString(KIND, kind.name)
            track.video?.let { putString(VIDEO, Api.absolute(it)) }
        }
        return MediaItem.Builder()
            .setMediaId(trackId(release, track))
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(release.artist)
                    .setAlbumTitle(release.title)
                    .setTrackNumber(track.n)
                    .setArtworkUri(Api.absolute(release.cover)?.let(Uri::parse))
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .setExtras(extras)
                    .build(),
            )
            .build()
    }

    /** Release order for "keep playing the next album": released first, then unreleased / rare. */
    fun sequence(releases: List<Release>): List<Release> =
        releases.filter { it.kind == "released" && it.tracks.isNotEmpty() } +
            releases.filter { (it.kind == "unreleased" || it.kind == "loose") && it.tracks.isNotEmpty() }

    fun nextAlbum(releases: List<Release>, currentId: String): Release? {
        val seq = sequence(releases)
        val i = seq.indexOfFirst { it.id == currentId }
        if (i < 0 || seq.size < 2) return null
        return seq[(i + 1) % seq.size]
    }

    /**
     * Smart shuffle: random release, then random track, steered to songs not
     * heard yet; once everything has been heard it starts over.
     */
    fun smartShuffle(app: PowerstoneApp, count: Int, avoid: Set<String> = emptySet()): List<Pair<Release, Track>> {
        val rels = app.releases.value.filter { it.tracks.isNotEmpty() }
        if (rels.isEmpty()) return emptyList()
        var heard = app.settings.shuffleHeard
        val picked = mutableSetOf<String>()
        val out = mutableListOf<Pair<Release, Track>>()
        repeat(count) {
            fun pool() = rels.flatMap { r -> r.tracks.map { r to it } }
                .filter { (r, t) -> trackId(r, t).let { id -> id !in heard && id !in picked && id !in avoid } }
            var p = pool()
            if (p.isEmpty()) { heard = emptySet(); app.settings.shuffleHeard = emptySet(); p = pool() }
            if (p.isEmpty() && avoid.isNotEmpty()) {
                // Everything is "avoided" (a very long shuffle): ignore that rather than stop.
                p = rels.flatMap { r -> r.tracks.map { r to it } }.filter { (r, t) -> trackId(r, t) !in picked }
            }
            if (p.isEmpty()) return out
            // pick a release first (each equally likely), then a track in it — same feel as the site
            val byRel = p.groupBy { it.first.id }
            val pick = byRel.values.random().random()
            picked += trackId(pick.first, pick.second)
            out += pick
        }
        return out
    }

    /** How far [bpm] is from [target], counting half and double time as a match (70 fits 140). */
    fun tempoDistance(bpm: Int, target: Int): Int =
        listOf(bpm, bpm * 2, bpm / 2).minOf { kotlin.math.abs(it - target) }

    /**
     * Heart-rate radio (from the watch): tracks whose tempo is close to [target], unheard first,
     * widening the window when too few match.
     */
    fun radio(app: PowerstoneApp, target: Int, count: Int, avoid: Set<String> = emptySet()): List<Pair<Release, Track>> {
        val all = app.releases.value.flatMap { r -> r.tracks.filter { it.bpm != null }.map { r to it } }
            .filter { (r, t) -> trackId(r, t) !in avoid }
        val heard = app.settings.shuffleHeard
        for (window in listOf(3, 6, 10, 16, 1000)) {
            val near = all.filter { (_, t) -> tempoDistance(t.bpm!!, target) <= window }
            if (near.size >= count || window == 1000) {
                val (fresh, old) = near.shuffled().partition { (r, t) -> trackId(r, t) !in heard }
                return (fresh + old).take(count)
            }
        }
        return emptyList()
    }

    fun markHeard(app: PowerstoneApp, id: String) {
        app.settings.shuffleHeard = app.settings.shuffleHeard + id
    }
}
