package com.st000ps.powerstone.watch

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.st000ps.powerstone.wear.WearProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

// The phone app's palette (ui/Theme.kt).
private val Ink = Color(0xFF0A0A0A)
private val Edge = Color(0xFF2A2A2A)
private val Rose = Color(0xFFC04A5E)
private val Dim = Color(0xFF9A9A9A)
private val White = Color(0xFFF2F2F2)

/** Crown travel (pixels) per volume step. */
private const val CROWN_STEP = 60f

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { WatchUi() } }
    }
}

/** What the Now page shows, from the phone's player or the watch's own. */
private data class Now(
    val title: String,
    val artist: String,
    val bpm: Int,
    val playing: Boolean,
    val fav: Boolean,
    val canFav: Boolean,
    val progress: Float,
    val art: Bitmap?,
    val hasTrack: Boolean,
)

@Composable
private fun pixel(): FontFamily {
    val ctx = LocalContext.current
    return remember { FontFamily(Font("fonts/Silkscreen-Regular.ttf", ctx.assets)) }
}

@Composable
private fun WatchUi() {
    val ctx = LocalContext.current
    val app = ctx.watchApp
    val target by app.target.collectAsState()
    val phone by app.link.now.collectAsState()
    val scope = rememberCoroutineScope()

    // Watch player state (polled lightly; the controller connects only when the watch is the target).
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(target) {
        if (target == Target.WATCH) controller = runCatching { WatchPlayer.connect(ctx) }.getOrNull()
        while (true) { tick = System.currentTimeMillis(); delay(1000) }
    }
    val watchArt = rememberArt(controller?.mediaMetadata?.artworkUri?.toString().takeIf { target == Target.WATCH })

    val now: Now? = when (target) {
        Target.PHONE -> phone?.let {
            Now(it.title, it.artist, it.bpm, it.playing, it.fav, it.canFav,
                if (it.durationMs > 0) it.positionNow().toFloat() / it.durationMs else 0f, it.art, it.hasTrack)
        }
        Target.WATCH -> controller?.let { c ->
            tick // recompose each second for progress
            val dur = c.duration.takeIf { it > 0 } ?: 0L
            val bpm = c.currentMediaItem?.mediaId?.let { id ->
                app.releases.value.firstOrNull { id.startsWith(it.id + "/") }
                    ?.tracks?.firstOrNull { "${id.substringBeforeLast('/')}/${it.n}" == id }?.bpm
            } ?: 0
            Now(c.mediaMetadata.title?.toString() ?: "", c.mediaMetadata.artist?.toString() ?: "", bpm,
                c.isPlaying, false, false, if (dur > 0) c.currentPosition.toFloat() / dur else 0f, watchArt, c.currentMediaItem != null)
        }
    }

    fun command(cmd: String) = scope.launch {
        when (target) {
            Target.PHONE -> app.link.send(cmd)
            Target.WATCH -> {
                val c = controller ?: WatchPlayer.connect(ctx).also { controller = it }
                when (cmd) {
                    WearProtocol.PLAY_PAUSE -> if (c.mediaItemCount == 0) WatchPlayer.service(ctx)?.shuffle()
                        else if (c.isPlaying) c.pause() else c.play()
                    WearProtocol.NEXT -> c.seekToNextMediaItem()
                    WearProtocol.PREVIOUS -> c.seekToPrevious()
                    WearProtocol.SHUFFLE -> WatchPlayer.service(ctx)?.shuffle()
                }
            }
        }
    }

    // The crown is volume (watch or phone, whichever is the target) everywhere except the EQ page.
    val audio = remember { ctx.getSystemService(android.media.AudioManager::class.java) }
    fun volume(up: Boolean) {
        when (target) {
            Target.WATCH -> audio.adjustStreamVolume(
                android.media.AudioManager.STREAM_MUSIC,
                if (up) android.media.AudioManager.ADJUST_RAISE else android.media.AudioManager.ADJUST_LOWER,
                android.media.AudioManager.FLAG_SHOW_UI,
            )
            Target.PHONE -> scope.launch { app.link.send(if (up) WearProtocol.VOLUME_UP else WearProtocol.VOLUME_DOWN) }
        }
    }
    var crown by remember { mutableStateOf(0f) }
    val focus = remember { List(3) { FocusRequester() } }

    val pager = rememberPagerState { 3 }
    LaunchedEffect(pager.currentPage) { if (pager.currentPage != 1) runCatching { focus[pager.currentPage].requestFocus() } }
    Box(Modifier.fillMaxSize().background(Ink)) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            val crownVolume = if (page == 1) Modifier else Modifier
                .onRotaryScrollEvent { e ->
                    crown += e.verticalScrollPixels
                    while (crown >= CROWN_STEP) { crown -= CROWN_STEP; volume(up = true) }
                    while (crown <= -CROWN_STEP) { crown += CROWN_STEP; volume(up = false) }
                    true
                }
                .focusRequester(focus[page]).focusable()
            Box(Modifier.fillMaxSize().then(crownVolume), contentAlignment = Alignment.Center) {
                when (page) {
                    0 -> NowPage(now, target, { app.target.value = it }, ::command)
                    1 -> EqPage(phone?.eq, target, pager.currentPage == 1) { scope.launch { app.link.send(WearProtocol.EQ_PREFIX + app.api.json.encodeToString(Eq.serializer(), it)) } }
                    else -> RadioPage(target, now?.bpm ?: 0)
                }
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(3) { i -> Box(Modifier.size(if (i == pager.currentPage) 5.dp else 4.dp).background(if (i == pager.currentPage) White else Edge)) }
        }
    }
}

/** Loads a cover for watch playback (phone covers arrive with the now-playing data). */
@Composable
private fun rememberArt(url: String?): Bitmap? {
    val ctx = LocalContext.current
    var bmp by remember(url) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(url) {
        if (url == null) return@LaunchedEffect
        bmp = withContext(Dispatchers.IO) {
            runCatching {
                ctx.watchApp.api.http.newCall(Request.Builder().url(url).build()).execute()
                    .use { r -> r.body?.byteStream()?.let(BitmapFactory::decodeStream) }
            }.getOrNull()
        }
    }
    return bmp
}

@Composable
private fun Label(text: String, color: Color = Dim, size: TextUnit = 9.sp) {
    Text(text, color = color, fontSize = size, fontFamily = pixel(), textAlign = TextAlign.Center)
}

@Composable
private fun RoundButton(icon: ImageVector, desc: String, size: Dp, filled: Boolean = false, tint: Color = White, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape)
            .background(if (filled) White else Color.Transparent)
            .border(1.dp, if (filled) White else Edge, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, desc, tint = if (filled) Ink else tint, modifier = Modifier.size(size * 0.55f))
    }
}

@Composable
private fun NowPage(now: Now?, target: Target, setTarget: (Target) -> Unit, command: (String) -> Unit) {
    // Cover, dimmed, behind everything; progress as a thin rose ring.
    now?.art?.let {
        Image(it.asImageBitmap(), null, Modifier.fillMaxSize().clip(CircleShape).alpha(0.28f), contentScale = ContentScale.Crop)
    }
    val progress = now?.progress ?: 0f
    Canvas(Modifier.fillMaxSize().padding(3.dp)) {
        val s = 3.dp.toPx()
        drawArc(Edge, -90f, 360f, false, Offset(s / 2, s / 2), Size(size.width - s, size.height - s), style = Stroke(s))
        if (progress > 0f) drawArc(Rose, -90f, 360f * progress.coerceIn(0f, 1f), false, Offset(s / 2, s / 2),
            Size(size.width - s, size.height - s), style = Stroke(s, cap = StrokeCap.Round))
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 26.dp)) {
        // Where the buttons act: tap to switch between the phone and the watch.
        Box(
            Modifier.clip(RoundedCornerShape(10.dp)).border(1.dp, Edge, RoundedCornerShape(10.dp))
                .clickable { setTarget(if (target == Target.PHONE) Target.WATCH else Target.PHONE) }
                .padding(horizontal = 8.dp, vertical = 3.dp),
        ) { Label(if (target == Target.PHONE) "ON PHONE <>" else "ON WATCH <>", White) }
        Spacer(Modifier.height(6.dp))
        if (now == null || !now.hasTrack) {
            Text(if (target == Target.PHONE) "Nothing playing on the phone" else "Tap play to shuffle 88.8FM here",
                color = Dim, fontSize = 12.sp, textAlign = TextAlign.Center)
        } else {
            Text(now.title, color = White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Text(listOfNotNull(now.artist.ifEmpty { null }, now.bpm.takeIf { it > 0 }?.let { "$it BPM" }).joinToString(" · "),
                color = Dim, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoundButton(Glyphs.Previous, "Previous", 36.dp) { command(WearProtocol.PREVIOUS) }
            RoundButton(if (now?.playing == true) Glyphs.Pause else Glyphs.Play, "Play or pause", 50.dp, filled = true) {
                command(WearProtocol.PLAY_PAUSE)
            }
            RoundButton(Glyphs.Next, "Next", 36.dp) { command(WearProtocol.NEXT) }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (target == Target.PHONE && now?.canFav == true) {
                RoundButton(if (now.fav) Glyphs.Heart else Glyphs.HeartOutline, "Favourite", 30.dp,
                    tint = if (now.fav) Rose else White) { command(WearProtocol.TOGGLE_FAV) }
            }
            RoundButton(Glyphs.Shuffle, "Shuffle everything", 30.dp) { command(WearProtocol.SHUFFLE) }
        }
    }
}

/** The phone's 3-band DJ EQ: tap a band, turn the crown to sweep it 0–150%. */
@Composable
private fun EqPage(phoneEq: Eq?, target: Target, visible: Boolean, send: (Eq) -> Unit) {
    if (target == Target.WATCH) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(28.dp)) {
            Label("DJ EQ", White, 12.sp)
            Spacer(Modifier.height(6.dp))
            Text("The EQ runs on the phone's player. Switch to ON PHONE.", color = Dim, fontSize = 11.sp, textAlign = TextAlign.Center)
        }
        return
    }
    var eq by remember { mutableStateOf(phoneEq ?: Eq()) }
    var touched by remember { mutableLongStateOf(0L) }
    // Follow the phone, except right after a local change (so the knob doesn't jump back).
    LaunchedEffect(phoneEq) { if (phoneEq != null && System.currentTimeMillis() - touched > 1500) eq = phoneEq }
    LaunchedEffect(eq) {
        if (touched == 0L) return@LaunchedEffect
        delay(120)
        send(eq)
    }
    var band by remember { mutableStateOf("mid") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(visible) { if (visible) runCatching { focus.requestFocus() } }

    fun change(next: Eq) { touched = System.currentTimeMillis(); eq = next }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .onRotaryScrollEvent { e ->
                val v = (eq.level(band) + e.verticalScrollPixels / 900f).coerceIn(0f, 1.5f)
                change(eq.with(band, (v * 100).toInt() / 100f)); true
            }
            .focusRequester(focus).focusable(),
    ) {
        Label("DJ EQ - TURN CROWN", Dim)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            Eq.BANDS.forEach { b ->
                val killed = b in eq.kill
                val solo = eq.solo == b
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { band = b }) {
                    Text("${(eq.level(b) * 100).toInt()}", color = if (b == band) White else Dim, fontSize = 11.sp, fontFamily = pixel())
                    Spacer(Modifier.height(3.dp))
                    // 15 pixel blocks = 0–150 %
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        val lit = (eq.level(b) * 10).toInt()
                        for (i in 14 downTo 0) {
                            val on = i < lit && !killed
                            Box(Modifier.size(width = 22.dp, height = 3.dp).background(
                                when { !on -> Edge; i >= 10 -> Rose; else -> if (b == band) White else Dim }))
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Label(b.uppercase(), if (b == band) White else Dim)
                    Label(when { killed -> "KILL"; solo -> "ISO"; else -> " " }, Rose, 8.sp)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("KILL", band in eq.kill) { change(eq.copy(kill = if (band in eq.kill) eq.kill - band else eq.kill + band)) }
            Chip("ISO", eq.solo == band) { change(eq.copy(solo = if (eq.solo == band) null else band)) }
            Chip("RESET", false) { change(Eq()) }
        }
    }
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) Rose else Color.Transparent)
            .border(1.dp, if (on) Rose else Edge, RoundedCornerShape(8.dp)).clickable(onClick = onClick)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) { Label(text, White, 8.sp) }
}

/** Heart-rate radio: music whose tempo follows your heart rate. */
@Composable
private fun RadioPage(target: Target, trackBpm: Int) {
    val ctx = LocalContext.current
    val running by HeartRadioService.running.collectAsState()
    val hr by HeartRadioService.heartRate.collectAsState()
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted[Manifest.permission.BODY_SENSORS] == true) HeartRadioService.start(ctx)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
        Label("HEART RATE RADIO", Dim)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Glyphs.Heart, null, tint = if (running) Rose else Edge, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(hr?.toString() ?: "--", color = White, fontSize = 36.sp, fontFamily = pixel())
        }
        Label("BPM", Dim)
        Spacer(Modifier.height(6.dp))
        Text(
            if (running) "Steering ${if (target == Target.PHONE) "the phone" else "the watch"}" +
                (if (trackBpm > 0) " · track $trackBpm bpm" else "")
            else "Plays tracks whose tempo matches your heart, on the ${if (target == Target.PHONE) "phone" else "watch"}.",
            color = Dim, fontSize = 10.sp, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Chip(if (running) "STOP RADIO" else "START RADIO", running) {
            if (running) HeartRadioService.stop(ctx)
            else ask.launch(arrayOf(Manifest.permission.BODY_SENSORS, Manifest.permission.POST_NOTIFICATIONS))
        }
    }
}
