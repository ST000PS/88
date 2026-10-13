package com.st000ps.powerstone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.st000ps.powerstone.data.Release
import com.st000ps.powerstone.data.formatDuration

private val GROUPS = listOf(
    Triple(listOf("released"), "RELEASED" to "発売済み", "The whole catalogue, remastered."),
    Triple(listOf("unreleased", "loose"), "UNRELEASED / RARE TRAX" to "未発表", "Alt takes, roughs, test presses and rare trax from the streams."),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(
    state: UiState,
    vm: AppViewModel,
    openRelease: (String) -> Unit,
    openTag: (String) -> Unit,
    openBpm: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var series by rememberSaveable { mutableStateOf("ALL") }
    var videoOnly by rememberSaveable { mutableStateOf(false) }
    var sort by rememberSaveable { mutableStateOf("new") }
    var grid by rememberSaveable { mutableStateOf(true) }
    var genre by rememberSaveable { mutableStateOf<String?>(null) }

    val q = query.trim().lowercase()
    val filtered = state.releases.filter { r ->
        (series == "ALL" || r.series == series) && (!videoOnly || r.hasVideo) && (genre == null || genre in r.genres) &&
            (q.isEmpty() || (r.title + " " + r.tracks.joinToString(" ") { it.title }).lowercase().contains(q))
    }.let { list ->
        when (sort) {
            "old" -> list.reversed()
            "az" -> list.sortedBy { it.title.lowercase() }
            else -> list
        }
    }
    val songs = if (q.length < 2) emptyList() else state.releases.flatMap { r -> r.tracks.filter { it.title.lowercase().contains(q) }.map { r to it } }
    val genres = state.releases.flatMap { it.genres }.distinct().sorted()
    val tags = (state.releases.flatMap { r -> r.tags.flatMap { t -> List(r.tracks.size) { t } } } + state.releases.flatMap { r -> r.tracks.flatMap { it.tags } })
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(60).map { it.key }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        full {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).background(Rose))
                    Spacer(Modifier.width(10.dp))
                    Text("88.8FM", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = openBpm) { Icon(Icons.Filled.GraphicEq, "Browse by BPM") }
                    TextButton(onClick = vm::smartShuffle, enabled = state.releases.isNotEmpty()) {
                        Icon(Icons.Filled.Shuffle, null); Spacer(Modifier.width(6.dp)); Text("SHUFFLE")
                    }
                }
                Text(
                    when {
                        state.me.isSubscriber -> "SUBSCRIBER · full tracks"
                        state.me.signedIn -> "NO ACTIVE PLAN · ${state.releases.firstOrNull()?.previewSeconds ?: 28}s previews"
                        else -> "GUEST · ${state.releases.firstOrNull()?.previewSeconds ?: 28}s previews"
                    },
                    style = MaterialTheme.typography.bodySmall, color = Dim,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, "Clear") } },
                    placeholder = { Text("Search releases or songs") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (listOf("ALL") + Release.SERIES + "OTHER").forEach { s -> Pill(s, series == s) { series = s } }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("▣ VIDEO", videoOnly) { videoOnly = !videoOnly }
                    Pill("NEWEST", sort == "new") { sort = "new" }
                    Pill("OLDEST", sort == "old") { sort = "old" }
                    Pill("A–Z", sort == "az") { sort = "az" }
                    Pill("▦ GRID", grid) { grid = true }
                    Pill("☰ LIST", !grid) { grid = false }
                }
                if (genres.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("ALL GENRES", genre == null) { genre = null }
                    genres.forEach { g -> Pill(g, genre == g) { genre = if (genre == g) null else g } }
                }
                state.error?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = Rose)
                    TextButton(onClick = { vm.refresh() }) { Text("RETRY") }
                }
                if (state.loading) Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() }
                Text("${filtered.size} / ${state.releases.size}", style = MaterialTheme.typography.labelSmall, color = Dim, modifier = Modifier.padding(top = 4.dp))
            }
        }

        if (songs.isNotEmpty()) {
            full { SectionLabel("SONGS · ${songs.size}", "曲") }
            songs.take(30).forEachIndexed { i, (r, t) ->
                full { TrackRow(r, t, state, vm, showAlbum = true) { vm.play(songs, i) } }
            }
        }

        GROUPS.forEach { (kinds, label, note) ->
            val items = filtered.filter { it.kind in kinds }
            if (items.isEmpty() && q.isNotEmpty()) return@forEach
            full {
                Column {
                    SectionLabel(label.first, label.second)
                    Text(if (items.isEmpty()) "Koming soon." else note, style = MaterialTheme.typography.bodySmall, color = Dim, modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
            if (grid) {
                items(items, key = { it.id }) { r -> Box(Modifier.padding(horizontal = 6.dp)) { ReleaseCard(r) { openRelease(r.id) } } }
            } else {
                items(items, key = { it.id }, span = { GridItemSpan(maxLineSpan) }) { r -> ReleaseListRow(r) { openRelease(r.id) } }
            }
        }

        if (tags.isNotEmpty()) {
            full { SectionLabel("TAGS", "タグ") }
            full { TagChips(tags, openTag) }
        }
    }
}

private fun LazyGridScope.full(content: @Composable () -> Unit) = item(span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
private fun ReleaseCard(r: Release, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Cover(r, Modifier.fillMaxWidth().aspectRatio(1f))
        Spacer(Modifier.height(6.dp))
        Text(r.title + if (r.hasVideo) " ▣" else "", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
        Text("${r.date.replace('-', '.').ifEmpty { r.year }} · ${r.tracks.size} TRK", style = MaterialTheme.typography.bodySmall, color = Dim)
    }
}

@Composable
private fun ReleaseListRow(r: Release, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(r, Modifier.size(48.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(r.title + if (r.hasVideo) " ▣" else "", maxLines = 1, overflow = TextOverflow.Ellipsis)
            val bpm = r.bpmRange?.let { if (it.first == it.last) "${it.first} BPM" else "${it.first}–${it.last} BPM" }
            Text(
                listOfNotNull(r.series.takeIf { it != "OTHER" }, r.date.replace('-', '.').ifEmpty { r.year }, "${r.tracks.size} TRK", formatDuration(r.totalSeconds), bpm).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
