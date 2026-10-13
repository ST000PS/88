package com.st000ps.powerstone.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray

@Serializable
data class Catalog(val releases: List<Release> = emptyList())

@Serializable
data class Release(
    val id: String,
    val title: String = "",
    val artist: String = "",
    val year: String = "",
    val date: String = "",
    val sort: Long = 0,
    val kind: String = "released",
    @SerialName("preview_seconds") val previewSeconds: Int = 28,
    val cover: String? = null,
    val thumb: String? = null,
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val bandcamp: String? = null,
    val tracks: List<Track> = emptyList(),
) {
    /** Release series, the same grouping as the site's 表示 view. */
    val series: String get() = SERIES.firstOrNull { title.startsWith(it) } ?: "OTHER"
    val totalSeconds: Double get() = tracks.sumOf { it.duration }
    val hasVideo: Boolean get() = tracks.any { it.video != null }
    val bpmRange: IntRange? get() = tracks.mapNotNull { it.bpm }.takeIf { it.isNotEmpty() }?.let { it.min()..it.max() }
    val kindLabel: String get() = when (kind) {
        "unreleased" -> "UNRELEASED"
        "loose" -> "RARE TRAX"
        else -> "RELEASED · REMASTERED"
    }

    companion object {
        val SERIES = listOf("WT", "HX", "VGM", "ガムビー", "家")
    }
}

@Serializable
data class Track(
    val n: Int,
    val title: String = "",
    val duration: Double = 0.0,
    val bpm: Int? = null,
    val key: String? = null,
    @SerialName("key_ja") val keyJa: String? = null,
    val tags: List<String> = emptyList(),
    val video: String? = null,
    val preview: String? = null,
    val lo: String? = null,
    val std: String? = null,
    val full: String? = null,
) {
    val isMinor: Boolean get() = key?.endsWith("m") == true
}

@Serializable
data class Me(
    val tier: String = "guest",
    val signedIn: Boolean = false,
    val email: String? = null,
    val owner: Boolean = false,
    val status: String? = null,
    val plan: String? = null,
    val currency: String = "usd",
) {
    val isSubscriber: Boolean get() = tier == "sub"
    val prices: Pair<String, String> get() = if (currency == "jpy") "¥888" to "¥8,888" else "$8.88" to "$88.88"
}

@Serializable
data class Fav(val id: String, val t: Long = 0)

@Serializable
data class FavsResponse(val favs: List<Fav> = emptyList())

@Serializable
data class PlayEvent(val r: String, val n: Int, val ts: Long, val ms: Long)

@Serializable
data class PlaysBody(val events: List<PlayEvent>)

@Serializable
data class TrackRef(val r: String, val n: Int)

@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val tracks: List<TrackRef> = emptyList(),
    val updated: Long = 0,
)

@Serializable
data class PlaylistsBody(val playlists: List<Playlist> = emptyList(), val deleted: List<String> = emptyList())

@Serializable
data class SharedPlaylist(val name: String = "Shared playlist", val tracks: List<TrackRef> = emptyList())

@Serializable
data class IdResponse(val id: String? = null)

@Serializable
data class UrlResponse(val url: String? = null)

@Serializable
data class PasswordState(val hasPassword: Boolean = false)

@Serializable
data class HistoryResponse(val sessions: List<RawSession> = emptyList())

/** A listening session from /api/history; items are `[release, n, ts, ms]` arrays. */
@Serializable
data class RawSession(val s: Long = 0, val e: Long = 0, val items: List<JsonArray> = emptyList())

data class ListeningSession(val start: Long, val plays: List<PlayEvent>)

@Serializable
data class StatTrack(val r: String, val n: Int, val plays: Int = 0, val ms: Long = 0, val title: String? = null, val release: String? = null)

@Serializable
data class StatRelease(val r: String, val title: String = "", val plays: Int = 0)

@Serializable
data class StatTag(val tag: String, val plays: Int = 0)

@Serializable
data class Stats(
    val minutes: Int = 0,
    val plays: Int = 0,
    val uniqueTracks: Int = 0,
    val topTracks: List<StatTrack> = emptyList(),
    val topReleases: List<StatRelease> = emptyList(),
    val topTags: List<StatTag> = emptyList(),
    /** Play count per "release|n" for the period (sent by the server for the year view). */
    val counts: Map<String, Int> = emptyMap(),
)

/** Stream quality options, mirroring the site's AUTO / HIGH / STANDARD / DATA SAVER setting. */
enum class Quality(val label: String) {
    AUTO("Auto · by connection"),
    HIGH("High · 320 kbps"),
    STD("Standard · 160 kbps"),
    LO("Data saver · 64 kbps"),
}

/** Fav / play ids on the site are "<releaseId>/<trackNumber>". */
fun trackId(release: Release, track: Track) = "${release.id}/${track.n}"

/**
 * Picks the stream path the same way the site does: subscribers get the
 * requested quality, everyone else gets the public preview. [quality] must
 * already be resolved (not AUTO).
 */
fun Track.streamPath(subscriber: Boolean, quality: Quality): String? =
    if (!subscriber) preview
    else when (quality) {
        Quality.LO -> lo ?: std ?: full
        Quality.STD -> std ?: full
        Quality.HIGH, Quality.AUTO -> full
    } ?: preview

fun formatDuration(seconds: Double): String {
    val s = seconds.toInt()
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
