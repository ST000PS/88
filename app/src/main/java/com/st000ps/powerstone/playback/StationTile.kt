package com.st000ps.powerstone.playback

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.st000ps.powerstone.ui.MainActivity
import com.st000ps.powerstone.widget.PlayerWidget

/**
 * Quick Settings tile: pull down the shade and tap 88.8FM to pause or carry on. With nothing
 * loaded (or the app closed) it opens the app and resumes the last session, or starts the station.
 */
class StationTile : TileService() {
    companion object {
        /** Ask the system to refresh the tile (called when playback starts or stops). */
        fun refresh(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, StationTile::class.java)) }
        }
    }

    override fun onStartListening() {
        val service = PlaybackService.instance
        qsTile?.apply {
            state = if (service?.isPlayingNow == true) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= 29) subtitle = service?.nowTitle?.takeIf { it.isNotEmpty() } ?: "Tap to tune in"
            updateTile()
        }
    }

    // Below Android 14 the Intent form is the only one there is.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        val service = PlaybackService.instance
        if (service != null && service.hasMedia) {
            service.widgetCommand("play_pause")
            onStartListening()
            return
        }
        // Starting playback needs the app in the foreground (Android won't let a tile start it).
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(PlayerWidget.EXTRA_WIDGET_ACTION, if (PlayerWidget.state(this).hasTrack) "resume" else "shuffle")
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 7, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
