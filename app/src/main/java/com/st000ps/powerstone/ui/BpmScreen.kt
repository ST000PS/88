package com.st000ps.powerstone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.st000ps.powerstone.data.trackId

/** The site's テンポ view: every track sorted by tempo, with a BPM range and major/minor filter. */
@Composable
fun BpmScreen(state: UiState, vm: AppViewModel, back: () -> Unit) {
    val all = state.releases.flatMap { r -> r.tracks.filter { it.bpm != null }.map { r to it } }
    if (all.isEmpty()) { BackBar("BPM", back); return }
    val min = all.minOf { it.second.bpm!! }
    val max = all.maxOf { it.second.bpm!! }
    var lo by rememberSaveable { mutableStateOf(min.toFloat()) }
    var hi by rememberSaveable { mutableStateOf(max.toFloat()) }
    var key by rememberSaveable { mutableStateOf("all") }

    val shown = all.filter { (_, t) ->
        t.bpm!! in lo.toInt()..hi.toInt() && (key == "all" || (key == "min") == t.isMinor)
    }.sortedWith(compareBy({ it.second.bpm }, { it.second.title }))
    val groups = shown.groupBy { it.second.bpm!! }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            BackBar("BROWSE BY BPM · テンポ", back)
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text("${lo.toInt()} – ${hi.toInt()} BPM", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                RangeSlider(
                    value = lo..hi, valueRange = min.toFloat()..max.toFloat(),
                    onValueChange = { lo = it.start; hi = it.endInclusive },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill("ALL KEYS", key == "all") { key = "all" }
                    Pill("MAJOR", key == "maj") { key = "maj" }
                    Pill("MINOR", key == "min") { key = "min" }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    Text("${shown.size} / ${all.size} TRACKS", color = Dim, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Button(onClick = { vm.play(shown, 0) }, enabled = shown.isNotEmpty()) { Icon(Icons.Filled.PlayArrow, null); Text("PLAY ALL") }
                }
            }
        }
        groups.forEach { (bpm, tracks) ->
            item(key = "h$bpm") { SectionLabel("$bpm BPM", "${tracks.size} TRK") }
            tracks.forEach { (r, t) ->
                item(key = trackId(r, t)) {
                    TrackRow(r, t, state, vm, showAlbum = true) { vm.play(shown, shown.indexOfFirst { it.second === t }) }
                }
            }
        }
    }
}
