package com.st000ps.powerstone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.st000ps.powerstone.data.Api
import com.st000ps.powerstone.data.Release
import com.st000ps.powerstone.data.Track
import com.st000ps.powerstone.data.formatDuration
import com.st000ps.powerstone.data.trackId

@Composable
fun Cover(release: Release, modifier: Modifier, big: Boolean = false) = AsyncImage(
    model = Api.absolute(if (big) release.cover ?: release.thumb else release.thumb ?: release.cover),
    contentDescription = release.title,
    contentScale = ContentScale.Crop,
    modifier = modifier.background(Panel),
)

@Composable
fun BackBar(title: String? = null, back: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        else Spacer(Modifier.weight(1f))
        actions()
    }
}

@Composable
fun SectionLabel(en: String, ja: String? = null, modifier: Modifier = Modifier) {
    Row(modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Bottom) {
        Text(en, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Rose)
        if (ja != null) { Spacer(Modifier.width(8.dp)); Text(ja, style = MaterialTheme.typography.labelSmall, color = Dim) }
    }
}

@Composable
fun Pill(label: String, selected: Boolean, onClick: () -> Unit) = FilterChip(
    selected = selected, onClick = onClick, label = { Text(label) },
    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Rose, selectedLabelColor = Color.White),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagChips(tags: List<String>, onTag: (String) -> Unit) {
    if (tags.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
        tags.distinct().forEach { t -> Pill("#$t", false) { onTag(t) } }
    }
}

/**
 * A track line: number or cover, title, BPM / key / video, duration, heart,
 * and a ⋯ menu to add it to a playlist or share it — like the site's track menu.
 */
@Composable
fun TrackRow(
    r: Release,
    t: Track,
    state: UiState,
    vm: AppViewModel,
    showAlbum: Boolean,
    number: Int = t.n,
    extraMenu: @Composable ((close: () -> Unit) -> Unit)? = null,
    onClick: () -> Unit,
) {
    val id = trackId(r, t)
    val current = state.now.mediaId == id
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showAlbum) Cover(r, Modifier.size(44.dp))
        else Text("%02d".format(number), color = if (current) Rose else Dim, modifier = Modifier.width(32.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, color = if (current) Rose else Color.Unspecified, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                val meta = listOfNotNull(
                    if (showAlbum) r.title else null,
                    t.bpm?.let { "$it BPM" },
                    t.key?.takeIf { it.isNotEmpty() }?.let { "KEY $it" },
                ).joinToString(" · ")
                if (t.video != null) { Icon(Icons.Filled.Videocam, "Has video", Modifier.size(14.dp), tint = Dim); Spacer(Modifier.width(4.dp)) }
                if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(formatDuration(t.duration), style = MaterialTheme.typography.bodySmall, color = Dim)
        if (state.me.signedIn) {
            val fav = id in state.favs
            IconButton(onClick = { vm.toggleFav(id) }) {
                Icon(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Favourite", tint = if (fav) Rose else Dim)
            }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More", tint = Dim) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                TrackMenuItems(r, t, state, vm) { menu = false }
                extraMenu?.invoke { menu = false }
            }
        }
    }
}

@Composable
fun TrackMenuItems(r: Release, t: Track, state: UiState, vm: AppViewModel, close: () -> Unit) {
    Text("ADD TO PLAYLIST", style = MaterialTheme.typography.labelSmall, color = Dim, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
    state.playlists.forEach { pl ->
        val inIt = pl.tracks.any { it.r == r.id && it.n == t.n }
        DropdownMenuItem(
            text = { Text((if (inIt) "✓ " else "") + "${pl.name}  (${pl.tracks.size})", color = if (inIt) Dim else Color.Unspecified) },
            leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
            enabled = !inIt,
            onClick = { close(); vm.addToPlaylist(pl.id, r, t) },
        )
    }
    DropdownMenuItem(text = { Text("New playlist") }, leadingIcon = { Icon(Icons.Filled.Add, null) }, onClick = { close(); vm.newPlaylist(r to t) })
    HorizontalDivider()
    DropdownMenuItem(text = { Text("Share track") }, leadingIcon = { Icon(Icons.Filled.Share, null) }, onClick = { close(); vm.shareTrack(r, t) })
}

fun kindLabel(kind: String) = when (kind) { "unreleased" -> "UNRELEASED"; "loose" -> "RARE TRAX"; else -> "RELEASED" }
