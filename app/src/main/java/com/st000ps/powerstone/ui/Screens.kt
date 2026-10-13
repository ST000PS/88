package com.st000ps.powerstone.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.st000ps.powerstone.data.Api
import com.st000ps.powerstone.data.Quality
import com.st000ps.powerstone.data.Release
import com.st000ps.powerstone.data.Track
import com.st000ps.powerstone.data.formatDuration
import com.st000ps.powerstone.data.trackId
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- catalogue

@Composable
fun LibraryScreen(state: UiState, vm: AppViewModel, open: (String) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val q = query.trim().lowercase()
    val shown = if (q.isEmpty()) state.releases else state.releases.filter { r ->
        r.title.lowercase().contains(q) || r.year.contains(q) || r.tracks.any { it.title.lowercase().contains(q) }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).background(Rose))
                    Spacer(Modifier.width(10.dp))
                    Text("88.8FM", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = vm::shuffleAll, enabled = state.releases.isNotEmpty()) {
                        Icon(Icons.Filled.Shuffle, null); Spacer(Modifier.width(6.dp)); Text("SHUFFLE")
                    }
                }
                Text(
                    if (state.me.isSubscriber) "Subscriber · full tracks" else "Guest · ${state.releases.firstOrNull()?.previewSeconds ?: 28}s previews",
                    style = MaterialTheme.typography.bodySmall, color = Dim,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    placeholder = { Text("Search releases or tracks") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                state.error?.let {
                    Spacer(Modifier.height(12.dp))
                    Text(it, color = Rose)
                    TextButton(onClick = { vm.refresh() }) { Text("RETRY") }
                }
                if (state.loading) Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() }
            }
        }
        items(shown, key = { it.id }) { r -> ReleaseCard(r) { open(r.id) } }
    }
}

@Composable
private fun ReleaseCard(r: Release, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        AsyncImage(
            model = Api.absolute(r.thumb ?: r.cover), contentDescription = r.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(Panel),
        )
        Spacer(Modifier.height(6.dp))
        Text(r.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
        Text("${r.year} · ${r.tracks.size} trax", style = MaterialTheme.typography.bodySmall, color = Dim)
    }
}

// ---------------------------------------------------------------- release

@Composable
fun ReleaseScreen(r: Release, state: UiState, vm: AppViewModel, back: () -> Unit) {
    val ctx = LocalContext.current
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            AsyncImage(
                model = Api.absolute(r.cover), contentDescription = r.title, contentScale = ContentScale.Crop,
                modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth().aspectRatio(1f).background(Panel),
            )
            Column(Modifier.padding(16.dp)) {
                Text(r.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("${r.artist} · ${r.date.ifEmpty { r.year }} · ${r.tracks.size} trax", color = Dim)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.playRelease(r) }) { Icon(Icons.Filled.PlayArrow, null); Text("PLAY") }
                    OutlinedButton(onClick = { vm.play(r.tracks.map { r to it }.shuffled(), 0) }) {
                        Icon(Icons.Filled.Shuffle, null); Spacer(Modifier.width(4.dp)); Text("SHUFFLE")
                    }
                    r.bandcamp?.let { url ->
                        TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }) { Text("BANDCAMP") }
                    }
                }
                if (!state.me.isSubscriber) {
                    Spacer(Modifier.height(8.dp))
                    Text("Playing ${r.previewSeconds}-second previews. Sign in with a subscription for full tracks.",
                        style = MaterialTheme.typography.bodySmall, color = Dim)
                }
            }
            HorizontalDivider(color = Edge)
        }
        itemsIndexed(r.tracks, key = { _, t -> t.n }) { i, t ->
            TrackRow(r, t, state, vm, showAlbum = false) { vm.playRelease(r, i) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun TrackRow(r: Release, t: Track, state: UiState, vm: AppViewModel, showAlbum: Boolean, onClick: () -> Unit) {
    val id = trackId(r, t)
    val current = state.now.mediaId == id
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showAlbum) {
            AsyncImage(Api.absolute(r.thumb ?: r.cover), null, Modifier.size(44.dp).background(Panel), contentScale = ContentScale.Crop)
        } else {
            Text("%02d".format(t.n), color = if (current) Rose else Dim, modifier = Modifier.width(32.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, color = if (current) Rose else Color.Unspecified, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(
                if (showAlbum) r.title else null,
                t.bpm?.let { "$it bpm" },
                t.key?.takeIf { it.isNotEmpty() },
            ).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = Dim, maxLines = 1)
        }
        Text(formatDuration(t.duration), style = MaterialTheme.typography.bodySmall, color = Dim)
        if (state.me.signedIn) {
            val fav = id in state.favs
            IconButton(onClick = { vm.toggleFav(id) }) {
                Icon(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Favourite", tint = if (fav) Rose else Dim)
            }
        }
    }
}

// ---------------------------------------------------------------- favourites

@Composable
fun FavsScreen(state: UiState, vm: AppViewModel, signIn: () -> Unit) {
    if (!state.me.signedIn) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Sign in to see your favourites.", color = Dim)
            Spacer(Modifier.height(12.dp))
            Button(onClick = signIn) { Text("SIGN IN") }
        }
        return
    }
    val items = state.favs.mapNotNull(vm::lookup)
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Favourites", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (items.isNotEmpty()) Button(onClick = { vm.playFavs() }) { Icon(Icons.Filled.PlayArrow, null); Text("PLAY") }
            }
            if (items.isEmpty()) Text("Tap the heart on any track to save it here.", color = Dim, modifier = Modifier.padding(16.dp))
        }
        itemsIndexed(items, key = { _, p -> trackId(p.first, p.second) }) { i, (r, t) ->
            TrackRow(r, t, state, vm, showAlbum = true) { vm.playFavs(i) }
        }
    }
}

// ---------------------------------------------------------------- account

@Composable
fun AccountScreen(state: UiState, vm: AppViewModel) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text("Account", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        if (state.me.signedIn) {
            Text(state.me.email ?: "Signed in")
            Text(
                if (state.me.isSubscriber) "Subscriber${state.me.plan?.let { " · $it" } ?: ""}" else "No active subscription · previews only",
                color = if (state.me.isSubscriber) Rose else Dim,
            )
            Spacer(Modifier.height(24.dp))
            Text("STREAM QUALITY", style = MaterialTheme.typography.labelLarge, color = Dim)
            Quality.entries.forEach { q ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.setQuality(q) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = state.quality == q, onClick = { vm.setQuality(q) })
                    Text(q.label)
                }
            }
            if (!state.me.isSubscriber) Text("Previews always play at standard quality.", style = MaterialTheme.typography.bodySmall, color = Dim)
            Text("Applies to the next track you start.", style = MaterialTheme.typography.bodySmall, color = Dim)
            Spacer(Modifier.height(24.dp))
            OutlinedButton(onClick = { vm.logout() }) { Text("SIGN OUT") }
        } else {
            SignInForm(vm)
        }
    }
}

@Composable
private fun SignInForm(vm: AppViewModel) {
    val scope = rememberCoroutineScope()
    var email by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var usePassword by rememberSaveable { mutableStateOf(false) }
    var codeSent by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    fun run(block: suspend () -> Unit) {
        busy = true; message = null
        scope.launch {
            try { block() } catch (e: Exception) { message = e.message ?: "Something went wrong." }
            busy = false
        }
    }

    Text("Sign in with your thepowerstone.com account.", color = Dim)
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = email, onValueChange = { email = it }, label = { Text("Email") }, singleLine = true,
        enabled = !codeSent, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(12.dp))
    if (usePassword) {
        OutlinedTextField(
            value = password, onValueChange = { password = it }, label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Button(enabled = !busy && email.isNotBlank() && password.isNotEmpty(), onClick = {
            run { vm.passwordLogin(email.trim(), password) }
        }) { Text("SIGN IN") }
    } else if (!codeSent) {
        Button(enabled = !busy && email.contains('@'), onClick = {
            run { vm.requestCode(email.trim()); codeSent = true }
        }) { Text("EMAIL ME A CODE") }
    } else {
        Text("Enter the 6-digit code sent to ${email.trim()}.", style = MaterialTheme.typography.bodySmall, color = Dim)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) }, label = { Text("Code") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && code.length == 6, onClick = { run { vm.verifyCode(email.trim(), code) } }) { Text("SIGN IN") }
            TextButton(onClick = { codeSent = false; code = "" }) { Text("CHANGE EMAIL") }
        }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 12.dp))
    message?.let { Text(it, color = Rose, modifier = Modifier.padding(top = 12.dp)) }
    Spacer(Modifier.height(20.dp))
    TextButton(onClick = { usePassword = !usePassword; message = null }) {
        Text(if (usePassword) "Use an email code instead" else "Use a password instead")
    }
}

// ---------------------------------------------------------------- player

@Composable
fun MiniPlayer(state: UiState, vm: AppViewModel, expand: () -> Unit) {
    val now = state.now
    Surface(color = Panel) {
        Column {
            LinearProgressIndicator(
                progress = { if (now.durationMs > 0) now.positionMs.toFloat() / now.durationMs else 0f },
                modifier = Modifier.fillMaxWidth().height(2.dp), color = Rose, trackColor = Edge,
            )
            Row(Modifier.fillMaxWidth().clickable(onClick = expand).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(now.artwork, null, Modifier.size(44.dp).background(Ink), contentScale = ContentScale.Crop)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(now.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(now.album, style = MaterialTheme.typography.bodySmall, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = vm::togglePlay) {
                    Icon(if (now.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/pause")
                }
                IconButton(onClick = vm::next, enabled = now.hasNext) { Icon(Icons.Filled.SkipNext, "Next") }
            }
        }
    }
}

@Composable
fun PlayerScreen(state: UiState, vm: AppViewModel, close: () -> Unit) {
    val now = state.now
    var dragging by remember { mutableStateOf(false) }
    var dragPos by remember { mutableFloatStateOf(0f) }
    val id = now.mediaId

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth()) {
            IconButton(onClick = close) { Icon(Icons.Filled.KeyboardArrowDown, "Close") }
        }
        Spacer(Modifier.height(8.dp))
        AsyncImage(
            now.artwork, now.album, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(MaterialTheme.shapes.small).background(Panel),
        )
        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(now.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2)
                Text("${now.artist} · ${now.album}", color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (state.me.signedIn && id != null) {
                val fav = id in state.favs
                IconButton(onClick = { vm.toggleFav(id) }) {
                    Icon(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Favourite", tint = if (fav) Rose else Dim)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        val duration = now.durationMs.coerceAtLeast(1)
        Slider(
            value = if (dragging) dragPos else now.positionMs.toFloat() / duration,
            onValueChange = { dragging = true; dragPos = it },
            onValueChangeFinished = { vm.seekTo((dragPos * duration).toLong()); dragging = false },
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatDuration(now.positionMs / 1000.0), style = MaterialTheme.typography.bodySmall, color = Dim)
            Spacer(Modifier.weight(1f))
            Text(formatDuration(now.durationMs / 1000.0), style = MaterialTheme.typography.bodySmall, color = Dim)
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::toggleShuffle) {
                Icon(Icons.Filled.Shuffle, "Shuffle", tint = if (now.shuffle) Rose else Dim)
            }
            IconButton(onClick = vm::previous) { Icon(Icons.Filled.SkipPrevious, "Previous", Modifier.size(36.dp)) }
            FilledIconButton(
                onClick = vm::togglePlay, modifier = Modifier.size(72.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Rose),
            ) {
                if (now.isBuffering && !now.isPlaying) CircularProgressIndicator(Modifier.size(28.dp), color = Color.White)
                else Icon(if (now.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/pause", Modifier.size(40.dp))
            }
            IconButton(onClick = vm::next, enabled = now.hasNext) { Icon(Icons.Filled.SkipNext, "Next", Modifier.size(36.dp)) }
            IconButton(onClick = vm::cycleRepeat) {
                Icon(
                    if (now.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat, "Repeat",
                    tint = if (now.repeatMode == Player.REPEAT_MODE_OFF) Dim else Rose,
                )
            }
        }
        if (!state.me.isSubscriber) {
            Spacer(Modifier.height(16.dp))
            Text("Preview", style = MaterialTheme.typography.labelSmall, color = Dim)
        }
    }
}
