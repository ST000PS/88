package com.st000ps.powerstone.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.st000ps.powerstone.app
import com.st000ps.powerstone.audio.AudioFx
import com.st000ps.powerstone.audio.Spectrum
import com.st000ps.powerstone.data.EqState
import com.st000ps.powerstone.data.VinylStop
import com.st000ps.powerstone.data.formatDuration
import com.st000ps.powerstone.playback.QueueKind
import kotlinx.coroutines.delay
import kotlin.math.abs

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
                IconButton(onClick = vm::togglePlay) { Icon(if (now.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/pause") }
                IconButton(onClick = vm::next, enabled = now.hasNext) { Icon(Icons.Filled.SkipNext, "Next") }
            }
        }
    }
}

@Composable
fun PlayerScreen(state: UiState, vm: AppViewModel, close: () -> Unit, openSound: () -> Unit, openRelease: (String) -> Unit) {
    // Adapts to whatever window it gets, including split-screen and freeform windows:
    //  - tall:  stacked (artwork, then controls)
    //  - short: artwork shrinks so the transport stays on screen
    //  - wide and short (landscape / side-by-side split): artwork left, controls right
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight && maxHeight < 600.dp
        val short = !wide && maxHeight < 560.dp
        val compact = wide || short
        if (wide) {
            // Leave room under the artwork for the visualizer strip when it's on.
            val side = minOf(maxWidth * 0.45f, maxHeight - 16.dp - if (state.visualizerOn) 96.dp else 0.dp).coerceAtLeast(96.dp)
            Row(Modifier.fillMaxSize().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(side)) {
                    PlayerMedia(state, vm, openRelease, Modifier.width(side))
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    PlayerHeader(state, vm, close, openSound)
                    PlayerControls(state, vm, openRelease, compact = true)
                }
            }
        } else {
            val artSize = if (short) (maxHeight * 0.3f).coerceAtLeast(96.dp) else null
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PlayerHeader(state, vm, close, openSound)
                PlayerMedia(state, vm, openRelease, if (artSize != null) Modifier.height(artSize) else Modifier.fillMaxWidth())
                Spacer(Modifier.height(if (compact) 4.dp else 12.dp))
                PlayerControls(state, vm, openRelease, compact)
            }
        }
    }
}

@Composable
private fun PlayerHeader(state: UiState, vm: AppViewModel, close: () -> Unit, openSound: () -> Unit) {
    val now = state.now
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = close) { Icon(Icons.Filled.KeyboardArrowDown, "Close") }
        Text(
            when (now.kind) { QueueKind.SHUFFLE -> "SHUFFLE"; QueueKind.ALBUM -> "ALBUM"; else -> "NOW PLAYING" },
            style = MaterialTheme.typography.labelMedium, color = Dim, modifier = Modifier.weight(1f),
        )
        if (now.video != null) IconButton(onClick = { vm.setVideoOn(!state.videoOn) }) {
            Icon(if (state.videoOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff, "Video", tint = if (state.videoOn) Rose else Dim)
        }
        SleepButton(state, vm)
        IconButton(onClick = openSound) { Icon(Icons.Filled.Equalizer, "Sound") }
    }
}

/** Sleep timer: pause (with the vinyl stop) after a while, or at the end of this track. */
@Composable
private fun SleepButton(state: UiState, vm: AppViewModel) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.Bedtime, "Sleep timer", tint = if (state.sleep.active) Rose else Dim) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text("SLEEP TIMER", style = MaterialTheme.typography.labelSmall, color = Dim, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
            listOf(15, 30, 45, 60, 90).forEach { m ->
                DropdownMenuItem(text = { Text(if (m < 60) "$m minutes" else if (m == 60) "1 hour" else "1½ hours") }, onClick = { open = false; vm.setSleep(m) })
            }
            DropdownMenuItem(text = { Text("End of this track") }, onClick = { open = false; vm.setSleep(null, endOfTrack = true) })
            if (state.sleep.active) {
                HorizontalDivider()
                DropdownMenuItem(text = { Text("Off") }, onClick = { open = false; vm.setSleep(null) })
            }
        }
    }
}

/** "SLEEP 23:10" / "SLEEP · END OF TRACK", ticking once a second. */
@Composable
private fun SleepLabel(state: UiState) {
    val s = state.sleep
    if (!s.active) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(s) { while (true) { now = System.currentTimeMillis(); delay(1000) } }
    Text(
        if (s.endOfTrack) "SLEEP · END OF TRACK" else "SLEEP " + formatDuration(((s.endAt ?: now) - now).coerceAtLeast(0) / 1000.0),
        style = MaterialTheme.typography.labelSmall, color = Rose,
    )
}

/** The rest of the queue; tap to jump. */
@Composable
private fun UpNext(state: UiState, vm: AppViewModel) {
    if (state.queue.size < 2) return
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text("UP NEXT", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Rose, modifier = Modifier.padding(vertical = 8.dp))
        state.queue.forEach { q ->
            Row(Modifier.fillMaxWidth().clickable { vm.jumpTo(q.index) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(q.title, color = if (q.current) Rose else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(q.album, style = MaterialTheme.typography.bodySmall, color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (q.current) Text("NOW", style = MaterialTheme.typography.labelSmall, color = Rose)
            }
        }
    }
}

/**
 * Artwork (or the music video) plus the visualizer. [size] fixes either the
 * width or the height; the other side follows the aspect ratio.
 */
@Composable
private fun PlayerMedia(state: UiState, vm: AppViewModel, openRelease: (String) -> Unit, size: Modifier) {
    val now = state.now
    val current = now.mediaId?.let(vm::lookup)
    val sub = state.me.isSubscriber
    if (now.video != null && state.videoOn && sub) {
        VideoPane(now.video, now.isPlaying, vm, size.aspectRatio(16 / 9f, matchHeightConstraintsFirst = true))
    } else {
        Box(size.aspectRatio(1f, matchHeightConstraintsFirst = true)) {
            AsyncImage(
                now.artwork, now.album, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(MaterialTheme.shapes.small).background(Panel)
                    .clickable { current?.let { openRelease(it.first.id) } },
            )
            if (now.video != null && state.videoOn && !sub) Text(
                "MUSIC VIDEO — SUBSCRIBERS", style = MaterialTheme.typography.labelSmall, color = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).background(Rose).padding(6.dp),
            )
        }
    }
    if (state.visualizerOn) VisualizerStrip(now.isPlaying)
}

@Composable
private fun PlayerControls(state: UiState, vm: AppViewModel, openRelease: (String) -> Unit, compact: Boolean) {
    val now = state.now
    var dragging by remember { mutableStateOf(false) }
    var dragPos by remember { mutableFloatStateOf(0f) }
    var menu by remember { mutableStateOf(false) }
    val id = now.mediaId
    val current = id?.let(vm::lookup)
    val sub = state.me.isSubscriber

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                now.title, fontWeight = FontWeight.Bold, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis,
                style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
            )
            Text("${now.artist} · ${now.album}", color = Dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable { current?.let { openRelease(it.first.id) } })
            current?.second?.let { t ->
                val meta = listOfNotNull(t.bpm?.let { "$it BPM" }, t.key?.let { "KEY $it" + (t.keyJa?.let { j -> " · $j" } ?: "") }).joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, color = Dim)
            }
        }
        if (state.me.signedIn && id != null) {
            val fav = id in state.favs
            IconButton(onClick = { vm.toggleFav(id) }) {
                Icon(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Favourite", tint = if (fav) Rose else Dim)
            }
        }
        if (current != null) Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                TrackMenuItems(current.first, current.second, state, vm) { menu = false }
            }
        }
    }
    val duration = now.durationMs.coerceAtLeast(1)
    Slider(
        value = if (dragging) dragPos else now.positionMs.toFloat() / duration,
        onValueChange = { dragging = true; dragPos = it },
        onValueChangeFinished = { vm.seekTo((dragPos * duration).toLong()); dragging = false },
    )
    Row(Modifier.fillMaxWidth()) {
        Text(formatDuration(now.positionMs / 1000.0), style = MaterialTheme.typography.bodySmall, color = Dim)
        Spacer(Modifier.weight(1f))
        if (!sub) Text("PREVIEW", style = MaterialTheme.typography.labelSmall, color = Rose)
        Spacer(Modifier.weight(1f))
        Text(formatDuration(now.durationMs / 1000.0), style = MaterialTheme.typography.bodySmall, color = Dim)
    }
    Spacer(Modifier.height(if (compact) 4.dp else 12.dp))
    val big = if (compact) 56.dp else 72.dp
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = vm::smartShuffle) {
            Icon(Icons.Filled.Shuffle, "Shuffle the catalogue", tint = if (now.kind == QueueKind.SHUFFLE) Rose else Dim)
        }
        IconButton(onClick = vm::previous) { Icon(Icons.Filled.SkipPrevious, "Previous", Modifier.size(36.dp)) }
        FilledIconButton(
            onClick = vm::togglePlay, modifier = Modifier.size(big),
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = Rose),
        ) {
            if (now.isBuffering && !now.isPlaying) CircularProgressIndicator(Modifier.size(28.dp), color = Color.White)
            else Icon(if (now.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/pause", Modifier.size(big * 0.55f))
        }
        IconButton(onClick = vm::next, enabled = now.hasNext) { Icon(Icons.Filled.SkipNext, "Next", Modifier.size(36.dp)) }
        // Side by side when space is tight, stacked otherwise.
        val repeatOne: @Composable () -> Unit = {
            IconButton(onClick = { vm.setRepeat(Player.REPEAT_MODE_ONE) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.RepeatOne, "Repeat 1", tint = if (now.repeatMode == Player.REPEAT_MODE_ONE) Rose else Dim)
            }
        }
        val repeatTape: @Composable () -> Unit = {
            IconButton(onClick = { vm.setRepeat(Player.REPEAT_MODE_ALL) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Repeat, "Repeat tape", tint = if (now.repeatMode == Player.REPEAT_MODE_ALL) Rose else Dim)
            }
        }
        if (compact) Row { repeatOne(); repeatTape() } else Column(horizontalAlignment = Alignment.CenterHorizontally) { repeatOne(); repeatTape() }
    }
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { SleepLabel(state) }
    UpNext(state, vm)
    Spacer(Modifier.height(if (compact) 8.dp else 24.dp))
}

/** Music video, muted and locked to the audio clock (re-synced when it drifts > 250 ms), as on the site. */
@OptIn(UnstableApi::class)
@Composable
private fun VideoPane(url: String, playing: Boolean, vm: AppViewModel, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ctx).setDataSourceFactory(OkHttpDataSource.Factory(ctx.app.api.http)))
            .build().apply {
                trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
                volume = 0f
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                seekTo(vm.positionMs())
            }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LaunchedEffect(player, playing) {
        player.playWhenReady = playing
        while (true) {
            val target = vm.positionMs()
            if (abs(player.currentPosition - target) > 250) player.seekTo(target)
            delay(1000)
        }
    }
    AndroidView(
        factory = { c -> PlayerView(c).apply { useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; setShutterBackgroundColor(android.graphics.Color.BLACK); this.player = player } },
        update = { it.player = player },
        modifier = modifier.background(Color.Black),
    )
}

private val MODES = listOf("BARS", "BLOCKS", "MIRROR", "LINE")

/** Live spectrum of what's playing (after the EQ), drawn from [AudioFx.spectrum]. Tap to change style. */
@Composable
private fun VisualizerStrip(playing: Boolean) {
    var mode by rememberSaveable { mutableStateOf(0) }
    var frame by remember { mutableLongStateOf(0L) }
    val shown = remember { FloatArray(Spectrum.BANDS) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { frame = it } }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Canvas(Modifier.fillMaxWidth().height(72.dp).clickable { mode = (mode + 1) % MODES.size }) {
            @Suppress("UNUSED_EXPRESSION") frame
            val fresh = playing && System.nanoTime() - AudioFx.spectrumAt < 200_000_000L
            for (i in shown.indices) shown[i] = if (fresh) AudioFx.spectrum[i] else shown[i] * 0.9f
            val n = shown.size
            val w = size.width / n
            val h = size.height
            when (MODES[mode]) {
                "BARS" -> shown.forEachIndexed { i, v ->
                    drawRect(Rose, Offset(i * w + 1, h * (1 - v)), Size(w - 2, h * v))
                }
                "BLOCKS" -> {
                    val cell = w
                    val rows = (h / cell).toInt().coerceAtLeast(1)
                    shown.forEachIndexed { i, v ->
                        val lit = (v * rows).toInt()
                        for (r in 0 until lit) drawRect(if (r == lit - 1) Color.White else Rose, Offset(i * w + 1, h - (r + 1) * cell + 1), Size(cell - 2, cell - 2))
                    }
                }
                "MIRROR" -> shown.forEachIndexed { i, v ->
                    val bh = h * v / 2
                    drawRect(Rose, Offset(i * w + 1, h / 2 - bh), Size(w - 2, bh * 2))
                }
                else -> {
                    val path = Path()
                    shown.forEachIndexed { i, v -> val x = i * w + w / 2; val y = h * (1 - v); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
                    drawPath(path, Rose, style = Stroke(width = 3f))
                }
            }
        }
        Text(MODES[mode], style = MaterialTheme.typography.labelSmall, color = Dim)
    }
}

/** SOUND: volume, the 3-band DJ EQ with KILL / ISO, vinyl stop, video and visualizer switches. */
@Composable
fun SoundScreen(state: UiState, vm: AppViewModel, back: () -> Unit) {
    val eq = state.eq
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BackBar("SOUND · 音", back)
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("VOLUME", style = MaterialTheme.typography.labelLarge, color = Dim)
            Slider(value = state.now.volume, onValueChange = vm::setVolume)

            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("EQ · 3-BAND", style = MaterialTheme.typography.labelLarge, color = Dim, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.setEq(EqState()) }, enabled = !eq.isFlat) { Text("FLAT") }
            }
            Text("Splits at 250 Hz and 4 kHz like a DJ mixer. KILL mutes a band; ISO plays only that band.",
                style = MaterialTheme.typography.bodySmall, color = Dim)
            EqState.BANDS.forEach { band ->
                val label = mapOf("lo" to "LO · 低音", "mid" to "MID · 中音", "hi" to "HI · 高音")[band]!!
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Text(label, modifier = Modifier.width(96.dp))
                    Text("${(eq.level(band) * 100).toInt()}%", color = Dim, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.weight(1f))
                    Pill("KILL", band in eq.kill) { vm.setEq(eq.copy(kill = if (band in eq.kill) eq.kill - band else eq.kill + band)) }
                    Spacer(Modifier.width(6.dp))
                    Pill("ISO", eq.solo == band) { vm.setEq(eq.copy(solo = if (eq.solo == band) null else band)) }
                }
                Slider(value = eq.level(band), valueRange = 0f..1.5f, onValueChange = { vm.setEq(eq.with(band, it)) })
            }

            Spacer(Modifier.height(16.dp))
            Text("VINYL STOP · ビニールストップ", style = MaterialTheme.typography.labelLarge, color = Dim)
            Text("Pausing winds the record down like a turntable losing power; playing spins it back up.",
                style = MaterialTheme.typography.bodySmall, color = Dim)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                VinylStop.entries.forEach { v -> Pill(v.label.uppercase(), state.vinyl == v) { vm.setVinyl(v) } }
            }

            Spacer(Modifier.height(16.dp))
            Text("CROSSFADE", style = MaterialTheme.typography.labelLarge, color = Dim)
            Text("Fades the end of one track out and the next one in, in shuffles and playlists. Albums stay gapless — many of these tapes run track into track.",
                style = MaterialTheme.typography.bodySmall, color = Dim)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 2, 4, 6, 8).forEach { s -> Pill(if (s == 0) "OFF" else "$s S", state.crossfade == s) { vm.setCrossfade(s) } }
            }

            Spacer(Modifier.height(16.dp))
            SwitchRow("MUSIC VIDEOS", "Show the video for tracks that have one (subscribers).", state.videoOn, vm::setVideoOn)
            SwitchRow("VISUALIZER", "Live spectrum under the artwork. Tap it to change style.", state.visualizerOn, vm::setVisualizerOn)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SwitchRow(title: String, note: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { set(!on) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = Dim)
            Text(note, style = MaterialTheme.typography.bodySmall, color = Dim)
        }
        Switch(checked = on, onCheckedChange = set)
    }
}
