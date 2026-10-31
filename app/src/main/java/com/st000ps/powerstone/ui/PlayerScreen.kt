package com.st000ps.powerstone.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
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
                    PixelLabel(now.album.uppercase(), Dim, 9.sp)
                }
                RoundButton(if (now.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/pause", 40.dp, filled = true, onClick = vm::togglePlay)
                Spacer(Modifier.width(8.dp))
                RoundButton(Icons.Filled.SkipNext, "Next", 36.dp, enabled = now.hasNext, onClick = vm::next)
            }
        }
    }
}

/**
 * The player, laid out like the watch app: three pages you swipe between (NOW, DJ EQ, RADIO) with
 * square page dots, over the cover dimmed and blurred to fill the screen.
 */
@Composable
fun PlayerScreen(state: UiState, vm: AppViewModel, close: () -> Unit, openSound: () -> Unit, openRelease: (String) -> Unit) {
    val pager = rememberPagerState { 3 }
    Box(Modifier.fillMaxSize().background(Ink)) {
        // The cover behind everything, like the watch's Now page.
        AsyncImage(
            state.now.artwork, null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().blur(36.dp).alpha(0.30f),
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Ink.copy(alpha = 0.35f), Ink.copy(alpha = 0.85f), Ink))))
        Column(Modifier.fillMaxSize()) {
            PlayerHeader(state, vm, close, openSound)
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
                when (page) {
                    0 -> NowPage(state, vm, openRelease)
                    1 -> EqPage(state, vm, openSound)
                    else -> RadioPage(state, vm)
                }
            }
            PageDots(3, pager.currentPage, Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp, top = 4.dp))
        }
    }
}

@Composable
private fun NowPage(state: UiState, vm: AppViewModel, openRelease: (String) -> Unit) {
    // Adapts to whatever window it gets, including split-screen and freeform windows:
    //  - tall:  stacked (artwork, then controls)
    //  - short: artwork shrinks so the transport stays on screen
    //  - wide and short (landscape / side-by-side split): artwork left, controls right
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > maxHeight && maxHeight < 600.dp
        val short = !wide && maxHeight < 560.dp
        val compact = wide || short
        if (wide) {
            val side = minOf(maxWidth * 0.45f, maxHeight - 16.dp - if (state.visualizerOn) 96.dp else 0.dp).coerceAtLeast(96.dp)
            Row(Modifier.fillMaxSize().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(side)) { PlayerMedia(state, vm, openRelease, Modifier.width(side)) }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                    PlayerControls(state, vm, openRelease, compact = true)
                }
            }
        } else {
            val artSize = if (short) (maxHeight * 0.32f).coerceAtLeast(96.dp) else null
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(4.dp))
                PlayerMedia(state, vm, openRelease, if (artSize != null) Modifier.height(artSize) else Modifier.fillMaxWidth())
                Spacer(Modifier.height(if (compact) 6.dp else 16.dp))
                PlayerControls(state, vm, openRelease, compact)
            }
        }
    }
}

/** The watch's DJ EQ page: pixel-block bands you drag (the phone's crown), KILL / ISO / RESET. */
@Composable
private fun EqPage(state: UiState, vm: AppViewModel, openSound: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        PixelLabel("DJ EQ - DRAG A BAND", Dim, 11.sp)
        Text("250 Hz / 4 kHz crossovers, like a mixer", color = Dim, fontSize = 11.sp)
        Spacer(Modifier.height(20.dp))
        PixelEq(state.eq, vm::setEq)
        Spacer(Modifier.height(22.dp))
        WatchChip("MORE SOUND >", false, openSound)
    }
}

/**
 * Tempo radio, the phone's take on the watch's heart-rate radio: music whose tempo matches a BPM
 * you set (tap it, nudge it, or take this track's). When the watch's heart-rate radio is steering,
 * this page follows it.
 */
@Composable
private fun RadioPage(state: UiState, vm: AppViewModel) {
    val target by com.st000ps.powerstone.wear.WearBridge.radio.collectAsState()
    val fromWatch by com.st000ps.powerstone.wear.WearBridge.radioFromWatch.collectAsState()
    val running = target != null && state.now.kind == QueueKind.RADIO
    val trackBpm = state.now.mediaId?.let(vm::lookup)?.second?.bpm
    var bpm by remember { mutableIntStateOf(target ?: trackBpm ?: 90) }
    LaunchedEffect(target) { target?.let { bpm = it } }
    val tapper = remember { TapTempo() }
    var taps by remember { mutableIntStateOf(0) }
    fun set(v: Int) { bpm = v.coerceIn(50, 200); if (running) vm.startRadio(bpm) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        PixelLabel(if (fromWatch && running) "HEART RATE RADIO" else "TEMPO RADIO", Dim, 11.sp)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Favorite, null, tint = if (running) Rose else Edge, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(10.dp))
            Text("$bpm", color = White, fontSize = 72.sp, fontFamily = pixelFont())
        }
        PixelLabel("BPM", Dim, 12.sp)
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            RoundButton(Icons.Filled.Remove, "Slower", 52.dp, enabled = !fromWatch, onClick = { set(bpm - 2) })
            // Tap along with your pulse, a song, your steps: three taps set the tempo.
            RoundButton(Icons.Filled.TouchApp, "Tap tempo", 92.dp, filled = true, enabled = !fromWatch, onClick = {
                tapper.tap()?.let { set(it) }
                taps = tapper.count
            }) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.TouchApp, null, tint = Ink, modifier = Modifier.size(26.dp))
                    PixelLabel(if (taps in 1..2) "TAP ${3 - taps}" else "TAP", Ink, 11.sp)
                }
            }
            RoundButton(Icons.Filled.Add, "Faster", 52.dp, enabled = !fromWatch, onClick = { set(bpm + 2) })
        }
        Spacer(Modifier.height(18.dp))
        Text(
            when {
                fromWatch && running -> "Your watch is steering: the music follows your heart rate. Stop it on the watch to take over here."
                running -> "On air: tracks near $bpm BPM (half and double time count), unheard first. It keeps going until you stop it."
                else -> "Plays tracks whose tempo matches. Tap the big button along with anything, your pulse, a song, your steps."
            },
            color = Dim, fontSize = 12.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (trackBpm != null && !fromWatch) WatchChip("THIS TRACK $trackBpm", bpm == trackBpm) { set(trackBpm) }
            if (!fromWatch) WatchChip(if (running) "STOP RADIO" else "START RADIO", running) {
                if (running) vm.stopRadio() else vm.startRadio(bpm)
            }
        }
    }
}

@Composable
private fun PlayerHeader(state: UiState, vm: AppViewModel, close: () -> Unit, openSound: () -> Unit) {
    val now = state.now
    val radio by com.st000ps.powerstone.wear.WearBridge.radio.collectAsState()
    val fromWatch by com.st000ps.powerstone.wear.WearBridge.radioFromWatch.collectAsState()
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = close) { Icon(Icons.Filled.KeyboardArrowDown, "Close") }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            KindPill(
                when (now.kind) {
                    QueueKind.SHUFFLE -> "SHUFFLE"
                    QueueKind.ALBUM -> "ALBUM"
                    QueueKind.RADIO -> (if (fromWatch) "HEART RADIO" else "RADIO") + (radio?.let { " $it BPM" } ?: "")
                    else -> "NOW PLAYING"
                },
            )
        }
        if (now.video != null) IconButton(onClick = { vm.setVideoOn(!state.videoOn) }) {
            Icon(if (state.videoOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff, "Video", tint = if (state.videoOn) Rose else Dim)
        }
        SleepButton(state, vm)
        IconButton(onClick = openSound) { Icon(Icons.Filled.Tune, "Sound") }
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

    // Title and what it is, centred like the watch.
    Text(
        now.title, fontWeight = FontWeight.Bold, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
    )
    Text(
        listOf(now.artist, now.album).filter { it.isNotEmpty() }.joinToString(" · "), color = Dim, maxLines = 1,
        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().clickable { current?.let { openRelease(it.first.id) } },
    )
    current?.second?.let { t ->
        val meta = listOfNotNull(t.bpm?.let { "$it BPM" }, t.key?.let { "KEY $it" + (t.keyJa?.let { j -> " $j" } ?: "") }).joinToString("  /  ")
        if (meta.isNotEmpty()) PixelLabel(meta, Dim, 10.sp, Modifier.fillMaxWidth().padding(top = 4.dp))
    }

    val duration = now.durationMs.coerceAtLeast(1)
    Slider(
        value = if (dragging) dragPos else now.positionMs.toFloat() / duration,
        onValueChange = { dragging = true; dragPos = it },
        onValueChangeFinished = { vm.seekTo((dragPos * duration).toLong()); dragging = false },
        colors = SliderDefaults.colors(thumbColor = White, activeTrackColor = Rose, inactiveTrackColor = Edge),
    )
    Row(Modifier.fillMaxWidth()) {
        PixelLabel(formatDuration(now.positionMs / 1000.0), Dim, 10.sp)
        Spacer(Modifier.weight(1f))
        if (!sub) PixelLabel("PREVIEW", Rose, 10.sp)
        Spacer(Modifier.weight(1f))
        PixelLabel(formatDuration(now.durationMs / 1000.0), Dim, 10.sp)
    }
    Spacer(Modifier.height(if (compact) 6.dp else 14.dp))

    // Transport: outlined rounds around a solid white play, as on the watch.
    val big = if (compact) 64.dp else 84.dp
    val mid = if (compact) 48.dp else 58.dp
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        RoundButton(Icons.Filled.SkipPrevious, "Previous", mid, onClick = vm::previous)
        Spacer(Modifier.width(18.dp))
        RoundButton(if (now.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/pause", big, filled = true, onClick = vm::togglePlay) {
            if (now.isBuffering && !now.isPlaying) CircularProgressIndicator(Modifier.size(28.dp), color = Ink, strokeWidth = 2.dp)
            else Icon(if (now.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/pause", tint = Ink, modifier = Modifier.size(big * 0.5f))
        }
        Spacer(Modifier.width(18.dp))
        RoundButton(Icons.Filled.SkipNext, "Next", mid, enabled = now.hasNext, onClick = vm::next)
    }
    Spacer(Modifier.height(if (compact) 8.dp else 14.dp))
    // Second row, small rounds: favourite, shuffle everything, repeat (off > tape > 1), more.
    val small = 44.dp
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally)) {
        if (state.me.signedIn && id != null) {
            val fav = id in state.favs
            RoundButton(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Favourite", small,
                tint = if (fav) Rose else White, onClick = { vm.toggleFav(id) })
        }
        RoundButton(Icons.Filled.Shuffle, "Shuffle everything", small, tint = if (now.kind == QueueKind.SHUFFLE) Rose else White, onClick = vm::smartShuffle)
        RoundButton(
            if (now.repeatMode == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat, "Repeat", small,
            tint = if (now.repeatMode != Player.REPEAT_MODE_OFF) Rose else White,
            onClick = {
                when (now.repeatMode) {
                    Player.REPEAT_MODE_OFF -> vm.setRepeat(Player.REPEAT_MODE_ALL)
                    Player.REPEAT_MODE_ALL -> vm.setRepeat(Player.REPEAT_MODE_ONE)
                    else -> vm.setRepeat(Player.REPEAT_MODE_ONE) // toggles back off
                }
            },
        )
        if (current != null) Box {
            RoundButton(Icons.Filled.MoreVert, "More", small, onClick = { menu = true })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                TrackMenuItems(current.first, current.second, state, vm) { menu = false }
            }
        }
    }
    Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) { SleepLabel(state) }
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
            Spacer(Modifier.height(12.dp))
            PixelEq(eq, vm::setEq, Modifier.fillMaxWidth())

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
