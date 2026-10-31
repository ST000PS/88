package com.st000ps.powerstone.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.st000ps.powerstone.data.EqState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(org.robolectric.RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h915dp-xxhdpi", application = android.app.Application::class)
class WatchStyleTest {
    @Test fun tapTempoAveragesTheLastTaps() {
        val t = TapTempo()
        assertNull(t.tap(0)); assertNull(t.tap(500))
        assertEquals(120, t.tap(1000))
        assertEquals(120, t.tap(1500))
        // A long pause starts over.
        assertNull(t.tap(6000))
    }

    /** Draws the watch-style pieces the phone now uses, for a look before it's on a phone. */
    @Test fun drawTheComponents() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            PowerstoneTheme {
                Column(Modifier.fillMaxSize().background(Ink).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    KindPill("RADIO 92 BPM")
                    Text("Bstlove", color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    PixelLabel("92 BPM  /  KEY Am", Dim, 10.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        RoundButton(Icons.Filled.SkipPrevious, "", 58.dp, onClick = {})
                        RoundButton(Icons.Filled.Pause, "", 84.dp, filled = true, onClick = {})
                        RoundButton(Icons.Filled.SkipNext, "", 58.dp, onClick = {})
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        RoundButton(Icons.Filled.Favorite, "", 44.dp, tint = Rose, onClick = {})
                        RoundButton(Icons.Filled.Shuffle, "", 44.dp, onClick = {})
                        RoundButton(Icons.Filled.Repeat, "", 44.dp, tint = Rose, onClick = {})
                        RoundButton(Icons.Filled.MoreVert, "", 44.dp, onClick = {})
                    }
                    PixelEq(EqState(lo = 1.3f, mid = 0.8f, hi = 1f, kill = setOf("hi")), {})
                    PageDots(3, 1)
                }
            }
        }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val root = activity.window.decorView
        val d = activity.resources.displayMetrics
        val w = d.widthPixels; val h = (d.heightPixels * 0.9).toInt()
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File("build/widget-shots").mkdirs()
        File("build/widget-shots/phone_watch_style.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
