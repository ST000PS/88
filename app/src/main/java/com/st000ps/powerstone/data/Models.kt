package com.st000ps.powerstone.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    @SerialName("preview_seconds") val previewSeconds: Int = 28,
    val cover: String? = null,
    val thumb: String? = null,
    val bandcamp: String? = null,
    val tracks: List<Track> = emptyList(),
)

@Serializable
data class Track(
    val n: Int,
    val title: String = "",
    val duration: Double = 0.0,
    val bpm: Int? = null,
    val key: String? = null,
    val preview: String? = null,
    val lo: String? = null,
    val std: String? = null,
    val full: String? = null,
)

@Serializable
data class Me(
    val tier: String = "guest",
    val signedIn: Boolean = false,
    val email: String? = null,
    val status: String? = null,
    val plan: String? = null,
) {
    val isSubscriber: Boolean get() = tier == "sub"
}

@Serializable
data class Fav(val id: String, val t: Long = 0)

@Serializable
data class FavsResponse(val favs: List<Fav> = emptyList())

@Serializable
data class PlayEvent(val r: String, val n: Int, val ts: Long, val ms: Long)

@Serializable
data class PlaysBody(val events: List<PlayEvent>)

/** Stream quality options, mirroring the site's HIGH / STANDARD / DATA SAVER setting. */
enum class Quality(val label: String) {
    HIGH("High · 320 kbps"),
    STD("Standard · 160 kbps"),
    LO("Data saver · 64 kbps"),
}

/** Fav / play ids on the site are "<releaseId>/<trackNumber>". */
fun trackId(release: Release, track: Track) = "${release.id}/${track.n}"

/**
 * Picks the stream path the same way the site does: subscribers get the
 * requested quality, everyone else gets the public preview.
 */
fun Track.streamPath(subscriber: Boolean, quality: Quality): String? =
    if (!subscriber) preview
    else when (quality) {
        Quality.LO -> lo ?: std ?: full
        Quality.STD -> std ?: full
        Quality.HIGH -> full
    } ?: preview

fun formatDuration(seconds: Double): String {
    val s = seconds.toInt()
    return "%d:%02d".format(s / 60, s % 60)
}
