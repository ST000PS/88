package com.st000ps.powerstone.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.st000ps.powerstone.data.Release
import com.st000ps.powerstone.data.SharedPlaylist
import com.st000ps.powerstone.data.Track
import com.st000ps.powerstone.data.formatDuration
import com.st000ps.powerstone.data.trackId

@Composable
private fun ActionRow(content: @Composable () -> Unit) =
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }

@Composable
private fun Header(title: String, subtitle: String, kind: String) {
    Text(kind, style = MaterialTheme.typography.labelSmall, color = Rose)
    Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text(subtitle, color = Dim)
    Spacer(Modifier.height(12.dp))
}

private fun summary(items: List<Pair<Release, Track>>) = "${items.size} trax · ${formatDuration(items.sumOf { it.second.duration })}"

// ---------------------------------------------------------------- release

@Composable
fun ReleaseScreen(r: Release, focusTrack: Int?, state: UiState, vm: AppViewModel, openTag: (String) -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    val list = rememberLazyListState()
    LaunchedEffect(r.id, focusTrack) {
        val i = r.tracks.indexOfFirst { it.n == focusTrack }
        if (i >= 0) list.animateScrollToItem(i + 1)
    }
    LazyColumn(Modifier.fillMaxSize(), state = list) {
        item {
            BackBar(back = back) { IconButton(onClick = { vm.shareRelease(r) }) { Icon(Icons.Filled.Share, "Share") } }
            Cover(r, Modifier.padding(horizontal = 16.dp).fillMaxWidth().aspectRatio(1f), big = true)
            Column(Modifier.padding(16.dp)) {
                Header(r.title, "${r.artist} · ${r.date.replace('-', '.').ifEmpty { r.year }} · ${r.tracks.size} trax · ${formatDuration(r.totalSeconds)}", r.kindLabel)
                ActionRow {
                    Button(onClick = { vm.playRelease(r) }) { Icon(Icons.Filled.PlayArrow, null); Text("PLAY") }
                    OutlinedButton(onClick = { vm.shuffled(r.tracks.map { r to it }) }) { Icon(Icons.Filled.Shuffle, null); Spacer(Modifier.width(4.dp)); Text("SHUFFLE") }
                    r.bandcamp?.let { url -> TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) { Text("BANDCAMP") } }
                    if (state.me.isSubscriber) {
                        @Suppress("UNUSED_EXPRESSION") state.offlineVersion
                        val items = r.tracks.map { r to it }
                        val all = items.all { (rr, t) -> vm.isDownloaded(rr, t) }
                        TextButton(onClick = { if (all) vm.removeDownloads(items) else vm.download(items, r.title) }) {
                            Text(if (all) "↓ DOWNLOADED · REMOVE" else "↓ DOWNLOAD")
                        }
                    }
                }
                if (!state.me.isSubscriber) {
                    Spacer(Modifier.height(8.dp))
                    Text("Playing ${r.previewSeconds}-second previews. Subscribe for full tracks.", style = MaterialTheme.typography.bodySmall, color = Dim)
                }
            }
            TagChips(r.genres + r.tags, openTag)
            HorizontalDivider(color = Edge, modifier = Modifier.padding(top = 8.dp))
        }
        itemsIndexed(r.tracks, key = { _, t -> t.n }) { i, t ->
            Box(if (t.n == focusTrack) Modifier.background(Edge) else Modifier) {
                TrackRow(r, t, state, vm, showAlbum = false) { vm.playRelease(r, i) }
            }
            if (t.tags.isNotEmpty()) Box(Modifier.padding(start = 44.dp, bottom = 6.dp)) { TagChips(t.tags, openTag) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ---------------------------------------------------------------- playlists

@Composable
fun PlaylistsScreen(state: UiState, vm: AppViewModel, open: (String) -> Unit, openFavs: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Playlists", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Button(onClick = { open(vm.newPlaylist().id) }) { Icon(Icons.Filled.Add, null); Text("NEW") }
            }
            if (!state.me.signedIn) Text(
                "Saved on this device. Sign in to keep playlists on all your devices.",
                style = MaterialTheme.typography.bodySmall, color = Dim, modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (state.me.signedIn) PlaylistRow("♥ Favourites", "${state.favs.size} TRK", openFavs)
        }
        if (state.playlists.isEmpty()) item {
            Text("No playlists yet. Tap NEW, or use ⋮ on any track → New playlist.", color = Dim, modifier = Modifier.padding(16.dp))
        }
        state.playlists.forEach { pl -> item(key = pl.id) { PlaylistRow(pl.name, "${pl.tracks.size} TRK") { open(pl.id) } } }
    }
}

@Composable
private fun PlaylistRow(name: String, meta: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).background(Panel), Alignment.Center) { Icon(Icons.AutoMirrored.Filled.QueueMusic, null, tint = Rose) }
        Spacer(Modifier.width(12.dp))
        Text(name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(meta, color = Dim, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun PlaylistScreen(id: String, state: UiState, vm: AppViewModel, back: () -> Unit) {
    val pl = state.playlists.firstOrNull { it.id == id }
    if (pl == null) { BackBar("Playlist", back); Text("This playlist was deleted.", color = Dim, modifier = Modifier.padding(16.dp)); return }
    val items = vm.resolve(pl.tracks)
    var renaming by remember { mutableStateOf(pl.tracks.isEmpty() && pl.name.startsWith("MY PLAYLIST")) }
    var confirmDelete by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize()) {
        item {
            BackBar(back = back) {
                IconButton(onClick = { renaming = true }) { Icon(Icons.Filled.Edit, "Rename") }
                IconButton(onClick = { vm.sharePlaylist(pl) }, enabled = pl.tracks.isNotEmpty()) { Icon(Icons.Filled.Share, "Share") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") }
            }
            Column(Modifier.padding(horizontal = 16.dp)) {
                Header(pl.name, summary(items), "PLAYLIST")
                ActionRow {
                    Button(onClick = { vm.play(items, 0) }, enabled = items.isNotEmpty()) { Icon(Icons.Filled.PlayArrow, null); Text("PLAY") }
                    OutlinedButton(onClick = { vm.shuffled(items) }, enabled = items.isNotEmpty()) { Icon(Icons.Filled.Shuffle, null); Spacer(Modifier.width(4.dp)); Text("SHUFFLE PLAY") }
                    if (state.me.isSubscriber && items.isNotEmpty()) TextButton(onClick = { vm.download(items, pl.name) }) { Text("↓ DOWNLOAD") }
                }
                if (items.isEmpty()) Text("Empty. Add songs with ⋮ on any track.", color = Dim, modifier = Modifier.padding(vertical = 16.dp))
            }
            HorizontalDivider(color = Edge, modifier = Modifier.padding(top = 12.dp))
        }
        itemsIndexed(items, key = { i, p -> "$i:" + trackId(p.first, p.second) }) { i, (r, t) ->
            TrackRow(r, t, state, vm, showAlbum = true, extraMenu = { close ->
                HorizontalDivider()
                if (i > 0) DropdownMenuItem(text = { Text("Move up") }, leadingIcon = { Icon(Icons.Filled.ArrowUpward, null) }, onClick = { close(); vm.movePlaylistTrack(pl.id, i, i - 1) })
                if (i < items.lastIndex) DropdownMenuItem(text = { Text("Move down") }, leadingIcon = { Icon(Icons.Filled.ArrowDownward, null) }, onClick = { close(); vm.movePlaylistTrack(pl.id, i, i + 1) })
                DropdownMenuItem(text = { Text("Remove from playlist") }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { close(); vm.removeFromPlaylist(pl.id, i) })
            }) { vm.play(items, i) }
        }
    }

    if (renaming) {
        var name by remember { mutableStateOf(pl.name) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename playlist") },
            text = { OutlinedTextField(name, { name = it.take(80) }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.renamePlaylist(pl.id, name); renaming = false }) { Text("SAVE") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("CANCEL") } },
        )
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${pl.name}?") },
        text = { Text("This removes it from all your devices.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deletePlaylist(pl.id); back() }) { Text("DELETE", color = Rose) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("CANCEL") } },
    )
}

// ---------------------------------------------------------------- shared playlist (?s=)

@Composable
fun SharedPlaylistScreen(id: String, state: UiState, vm: AppViewModel, back: () -> Unit) {
    var shared by remember { mutableStateOf<SharedPlaylist?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(id) {
        try { shared = vm.loadShared(id) } catch (e: Exception) { error = e.message ?: "Couldn't open that playlist." }
    }
    val sp = shared
    if (sp == null) {
        Column {
            BackBar("Shared playlist", back)
            if (error != null) Text(error!!, color = Rose, modifier = Modifier.padding(16.dp))
            else Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() }
        }
        return
    }
    val items = vm.resolve(sp.tracks)
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            BackBar(back = back) { IconButton(onClick = { vm.shareSharedPlaylist(id, sp.name) }) { Icon(Icons.Filled.Share, "Share") } }
            Column(Modifier.padding(horizontal = 16.dp)) {
                Header(sp.name, summary(items), "SHARED PLAYLIST")
                ActionRow {
                    Button(onClick = { vm.play(items, 0) }, enabled = items.isNotEmpty()) { Icon(Icons.Filled.PlayArrow, null); Text("PLAY") }
                    OutlinedButton(onClick = { vm.saveShared(sp) }) { Icon(Icons.Filled.Add, null); Spacer(Modifier.width(4.dp)); Text("SAVE TO MY PLAYLISTS") }
                }
            }
            HorizontalDivider(color = Edge, modifier = Modifier.padding(top = 12.dp))
        }
        itemsIndexed(items, key = { i, p -> "$i:" + trackId(p.first, p.second) }) { i, (r, t) ->
            TrackRow(r, t, state, vm, showAlbum = true) { vm.play(items, i) }
        }
    }
}

// ---------------------------------------------------------------- tag (?tag=)

@Composable
fun TagScreen(tag: String, state: UiState, vm: AppViewModel, back: () -> Unit) {
    val items = vm.tagged(tag)
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            BackBar(back = back) { IconButton(onClick = { vm.shareTag(tag) }) { Icon(Icons.Filled.Share, "Share") } }
            Column(Modifier.padding(horizontal = 16.dp)) {
                Header("#$tag", summary(items), "TAG")
                ActionRow {
                    Button(onClick = { vm.play(items, 0) }, enabled = items.isNotEmpty()) { Icon(Icons.Filled.PlayArrow, null); Text("PLAY") }
                    OutlinedButton(onClick = { vm.shuffled(items) }, enabled = items.isNotEmpty()) { Icon(Icons.Filled.Shuffle, null); Spacer(Modifier.width(4.dp)); Text("SHUFFLE") }
                }
            }
            HorizontalDivider(color = Edge, modifier = Modifier.padding(top = 12.dp))
        }
        itemsIndexed(items, key = { _, p -> trackId(p.first, p.second) }) { i, (r, t) ->
            TrackRow(r, t, state, vm, showAlbum = true) { vm.play(items, i) }
        }
    }
}
