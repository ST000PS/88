package com.st000ps.powerstone.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.st000ps.powerstone.data.ListeningSession
import com.st000ps.powerstone.data.Stats
import com.st000ps.powerstone.data.trackId
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFmt = SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.US)
private val timeFmt = SimpleDateFormat("HH:mm", Locale.US)

/** MY 88.8 — favourites, listening history, and monthly / yearly stats (the yearly wrap-up). */
@Composable
fun MeScreen(state: UiState, vm: AppViewModel, wrapYear: Int?, openRelease: (String) -> Unit, openTag: (String) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(if (wrapYear != null) "year" else if (state.me.signedIn) "favs" else "history") }
    var year by rememberSaveable { mutableIntStateOf(wrapYear ?: vm.currentYear) }
    LaunchedEffect(wrapYear) { if (wrapYear != null) { tab = "year"; year = wrapYear } }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                if (tab == "year" && year != vm.currentYear) "YOUR $year ON 88.8FM" else "MY 88.8",
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp),
            )
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill("♥ FAVOURITES", tab == "favs") { tab = "favs" }
                Pill("HISTORY", tab == "history") { tab = "history" }
                Pill("THIS MONTH", tab == "month") { tab = "month" }
                Pill("YEAR", tab == "year") { tab = "year" }
            }
            if (!state.me.signedIn && tab != "favs") Text(
                "Showing listening from this device. Sign in to keep your history and stats across devices and get your yearly wrap-up.",
                style = MaterialTheme.typography.bodySmall, color = Dim, modifier = Modifier.padding(16.dp),
            )
            if (tab == "year") Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                IconButton(onClick = { year-- }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous year") }
                Text("$year", style = MaterialTheme.typography.titleMedium)
                IconButton(onClick = { year++ }, enabled = year < vm.currentYear) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next year") }
            }
        }
        when (tab) {
            "favs" -> favourites(state, vm)
            "history" -> item { HistoryTab(state, vm) }
            "month" -> item(key = "month") { StatsTab("month", vm.currentYear, "THIS MONTH", state, vm, openRelease, openTag) }
            else -> item(key = "year$year") { StatsTab("year", year, "$year", state, vm, openRelease, openTag) }
        }
    }
}

private fun LazyListScope.favourites(state: UiState, vm: AppViewModel) {
    if (!state.me.signedIn) { item { Text("Sign in to see your favourites.", color = Dim, modifier = Modifier.padding(16.dp)) }; return }
    val items = state.favs.mapNotNull(vm::lookup)
    item {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${items.size} favourites", color = Dim, modifier = Modifier.weight(1f))
            if (items.isNotEmpty()) Button(onClick = { vm.playFavs() }) { Icon(Icons.Filled.PlayArrow, null); Text("PLAY") }
        }
        if (items.isEmpty()) Text("Tap the heart on any track to save it here.", color = Dim, modifier = Modifier.padding(horizontal = 16.dp))
    }
    itemsIndexed(items, key = { _, p -> "fav:" + trackId(p.first, p.second) }) { i, (r, t) ->
        TrackRow(r, t, state, vm, showAlbum = true) { vm.playFavs(i) }
    }
}

@Composable
private fun Loading() = Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() }

@Composable
private fun HistoryTab(state: UiState, vm: AppViewModel) {
    var data by remember { mutableStateOf<HistoryData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(state.me.signedIn) {
        try { data = vm.history() } catch (e: Exception) { error = e.message }
    }
    val d = data ?: run { if (error != null) Text(error!!, color = Rose, modifier = Modifier.padding(16.dp)) else Loading(); return }
    if (d.sessions.isEmpty()) {
        Text("Nothing yet. Play something and it shows up here, grouped by listening session.", color = Dim, modifier = Modifier.padding(16.dp))
        return
    }
    Column {
        Text("Grouped by listening session. Every play also counts toward THIS YEAR, which starts fresh every January 1.",
            style = MaterialTheme.typography.bodySmall, color = Dim, modifier = Modifier.padding(16.dp))
        d.sessions.take(30).forEachIndexed { i, s -> SessionBlock(s, d.yearCounts, expandedByDefault = i == 0, state, vm) }
    }
}

@Composable
private fun SessionBlock(s: ListeningSession, yearCounts: Map<String, Int>, expandedByDefault: Boolean, state: UiState, vm: AppViewModel) {
    var open by remember { mutableStateOf(expandedByDefault) }
    val grouped = s.plays.groupBy { it.r to it.n }.entries.toList()
    val mins = s.plays.sumOf { it.ms } / 60000
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(dateFmt.format(Date(s.start)), fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("$mins MIN · ${s.plays.size} PLAYS · ${grouped.size} TRK", style = MaterialTheme.typography.bodySmall, color = Dim)
        }
        if (open) {
            val items = grouped.mapNotNull { (k, _) -> vm.lookup(k.first, k.second) }
            grouped.forEach { (k, plays) ->
                val (r, t) = vm.lookup(k.first, k.second) ?: return@forEach
                Row(
                    Modifier.fillMaxWidth().clickable { vm.play(items, items.indexOfFirst { it.second === t }) }.padding(start = 28.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (state.now.mediaId == trackId(r, t)) Rose else MaterialTheme.colorScheme.onSurface)
                        Text(r.title, style = MaterialTheme.typography.bodySmall, color = Dim, maxLines = 1)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("×${plays.size}", color = Rose, fontWeight = FontWeight.Bold)
                        Text("THIS YEAR ${yearCounts["${k.first}|${k.second}"] ?: plays.size} · ${timeFmt.format(Date(plays.first().ts))}",
                            style = MaterialTheme.typography.labelSmall, color = Dim)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatsTab(period: String, year: Int, label: String, state: UiState, vm: AppViewModel, openRelease: (String) -> Unit, openTag: (String) -> Unit) {
    var stats by remember { mutableStateOf<Stats?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(period, year, state.me.signedIn) {
        stats = null; error = null
        try { stats = vm.stats(period, year) } catch (e: Exception) { error = e.message ?: "Stats didn't load. Try again." }
    }
    val st = stats ?: run { if (error != null) Text(error!!, color = Rose, modifier = Modifier.padding(16.dp)) else Loading(); return }
    if (st.plays == 0) { Text("No listening in this period yet.", color = Dim, modifier = Modifier.padding(16.dp)); return }
    Column {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Kpi("${st.minutes}", "MINUTES", Modifier.weight(1f))
            Kpi("${st.plays}", "PLAYS", Modifier.weight(1f))
            Kpi("${st.uniqueTracks}", "TRACKS", Modifier.weight(1f))
        }
        SectionLabel("TOP TRACKS · $label", "よく聴いた曲")
        val top = st.topTracks.mapNotNull { vm.lookup(it.r, it.n) }
        st.topTracks.forEachIndexed { i, t ->
            RankRow(i + 1, t.title ?: vm.lookup(t.r, t.n)?.second?.title ?: "?", t.release ?: vm.release(t.r)?.title ?: "", "${t.plays}×") {
                val idx = top.indexOfFirst { it.first.id == t.r && it.second.n == t.n }
                if (idx >= 0) vm.play(top, idx)
            }
        }
        SectionLabel("TOP RELEASES", "よく聴いた作品")
        st.topReleases.forEachIndexed { i, r -> RankRow(i + 1, r.title, "", "${r.plays}×") { openRelease(r.r) } }
        if (st.topTags.isNotEmpty()) {
            SectionLabel("TOP TAGS", "よく聴いたタグ")
            st.topTags.forEachIndexed { i, t -> RankRow(i + 1, "#${t.tag}", "", "${t.plays}") { openTag(t.tag) } }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Kpi(value: String, label: String, modifier: Modifier) = Surface(color = Panel, modifier = modifier) {
    Column(Modifier.padding(12.dp)) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Rose)
        Text(label, style = MaterialTheme.typography.labelSmall, color = Dim)
    }
}

@Composable
private fun RankRow(rank: Int, title: String, sub: String, count: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("%02d".format(rank), color = Dim, modifier = Modifier.width(32.dp))
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = Dim, maxLines = 1)
        }
        Text(count, color = Dim)
    }
}
