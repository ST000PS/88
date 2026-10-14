package com.st000ps.powerstone.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.st000ps.powerstone.ui.Dim
import com.st000ps.powerstone.ui.Ink
import com.st000ps.powerstone.ui.Pill
import com.st000ps.powerstone.ui.PowerstoneTheme
import com.st000ps.powerstone.ui.Rose

/**
 * The widget's settings: shown when it's added (skippable on Android 12+) and from the launcher's
 * long-press menu. Changes apply live, so the widget behind this screen updates as you tap.
 */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        // Backing out still keeps the widget (with whatever was picked).
        val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        setResult(Activity.RESULT_OK, result)

        setContent {
            PowerstoneTheme {
                Surface(Modifier.fillMaxSize(), color = Ink) {
                    var o by remember { mutableStateOf(WidgetStore.options(this, id)) }
                    fun set(n: WidgetOptions) {
                        o = n
                        WidgetStore.setOptions(this, id, n)
                        PlayerWidget.refresh(this)
                    }
                    Column(Modifier.safeDrawingPadding().padding(24.dp)) {
                        Text("88.8FM", style = MaterialTheme.typography.labelLarge, color = Rose)
                        Text("Widget", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(24.dp))
                        Choice("BIG CARD", "What sits beside the title when the widget is tall.") {
                            Pill("RECORD", o.record) { set(o.copy(record = true)) }
                            Pill("COVER", !o.record) { set(o.copy(record = false)) }
                        }
                        Choice("MOTION", "Lamp, level meter, tuner needle and spinning record while music plays.") {
                            Pill("ON", o.motion) { set(o.copy(motion = true)) }
                            Pill("STILL", !o.motion) { set(o.copy(motion = false)) }
                        }
                        Choice("COLOUR", "Tint the card with the cover's colour, or keep it black.") {
                            Pill("FROM COVER", o.coverColour) { set(o.copy(coverColour = true)) }
                            Pill("CLASSIC", !o.coverColour) { set(o.copy(coverColour = false)) }
                        }
                        Spacer(Modifier.weight(1f))
                        Button(
                            onClick = { setResult(Activity.RESULT_OK, result); finish() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Rose),
                        ) { Text("DONE") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Choice(title: String, hint: String, pills: @Composable () -> Unit) {
    Column(Modifier.padding(bottom = 22.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = Dim)
        Text(hint, style = MaterialTheme.typography.bodySmall, color = Dim)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { pills() }
    }
}
