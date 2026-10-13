package com.st000ps.powerstone.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The site's 3-band DJ EQ: each band 0–1.5×, plus KILL per band and one ISO (solo) band. */
@Serializable
data class EqState(
    val lo: Float = 1f,
    val mid: Float = 1f,
    val hi: Float = 1f,
    val kill: Set<String> = emptySet(),
    val solo: String? = null,
) {
    fun level(band: String) = when (band) { "lo" -> lo; "mid" -> mid; else -> hi }

    /** Effective linear gain for [band], same rules as the site. */
    fun gain(band: String): Float = when {
        solo != null -> if (band == solo) maxOf(level(band), 0.6f) else 0f
        band in kill -> 0f
        else -> level(band)
    }

    fun with(band: String, value: Float) = when (band) { "lo" -> copy(lo = value); "mid" -> copy(mid = value); else -> copy(hi = value) }

    val isFlat get() = lo == 1f && mid == 1f && hi == 1f && kill.isEmpty() && solo == null

    companion object { val BANDS = listOf("lo", "mid", "hi") }
}

/** Pause like a turntable losing power. Lengths in seconds, as on the site. */
enum class VinylStop(val label: String, val seconds: Float) {
    OFF("Off", 0f), SHORT("Short", 0.45f), MED("Medium", 0.9f), LONG("Long", 1.6f)
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    var quality: Quality
        get() = runCatching { Quality.valueOf(prefs.getString("quality", null)!!) }.getOrDefault(Quality.AUTO)
        set(value) = prefs.edit().putString("quality", value.name).apply()

    private val _eq = MutableStateFlow(runCatching { json.decodeFromString<EqState>(prefs.getString("eq", null)!!) }.getOrDefault(EqState()))
    val eq: StateFlow<EqState> = _eq.asStateFlow()
    fun setEq(value: EqState) { _eq.value = value; prefs.edit().putString("eq", json.encodeToString(value)).apply() }

    private val _vinyl = MutableStateFlow(runCatching { VinylStop.valueOf(prefs.getString("vinyl", null)!!) }.getOrDefault(VinylStop.MED))
    val vinyl: StateFlow<VinylStop> = _vinyl.asStateFlow()
    fun setVinyl(value: VinylStop) { _vinyl.value = value; prefs.edit().putString("vinyl", value.name).apply() }

    var videoOn: Boolean
        get() = prefs.getBoolean("video", true)
        set(value) = prefs.edit().putBoolean("video", value).apply()

    var visualizerOn: Boolean
        get() = prefs.getBoolean("visualizer", true)
        set(value) = prefs.edit().putBoolean("visualizer", value).apply()

    /** Tracks smart shuffle has already played, so it works through the whole catalogue before repeating. */
    var shuffleHeard: Set<String>
        get() = prefs.getStringSet("heard", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("heard", value).apply()

    var playlistsDeleted: List<String>
        get() = prefs.getString("pl_deleted", null)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()
        set(value) = prefs.edit().putString("pl_deleted", value.take(200).joinToString("\n")).apply()

    /** True while playlists were made signed-out and still need merging into the account. */
    var playlistsGuest: Boolean
        get() = prefs.getBoolean("pl_guest", false)
        set(value) = prefs.edit().putBoolean("pl_guest", value).apply()
}
