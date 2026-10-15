package com.st000ps.powerstone.ui

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.getSystemService
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Now playing over the lock screen: tap the media notification on the lock screen and the full
 * player (art, visualizer, transport, sleep timer, up next) opens without unlocking. Anything that
 * leaves the player asks to unlock first. When the phone isn't locked it simply opens the app.
 */
class LockScreenActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val keyguard = getSystemService<KeyguardManager>()
        if (keyguard?.isKeyguardLocked != true) {
            openApp()
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        else @Suppress("DEPRECATION") window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        enableEdgeToEdge()
        setContent {
            PowerstoneTheme {
                val state by vm.state.collectAsStateWithLifecycle()
                Column(Modifier.fillMaxSize().systemBarsPadding()) {
                    TextButton(onClick = ::unlockAndOpen, modifier = Modifier.padding(horizontal = 8.dp)) { Text("UNLOCK TO OPEN 88.8FM") }
                    PlayerScreen(state, vm, close = ::finish, openSound = ::unlockAndOpen, openRelease = { unlockAndOpen() })
                }
            }
        }
    }

    private fun unlockAndOpen() {
        val keyguard = getSystemService<KeyguardManager>() ?: return openApp()
        keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = openApp()
        })
    }

    private fun openApp() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
