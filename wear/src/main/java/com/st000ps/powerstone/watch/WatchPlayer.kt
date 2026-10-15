package com.st000ps.powerstone.watch

import android.content.ComponentName
import android.content.Context
import androidx.concurrent.futures.await
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken

/** Connects to (and so starts) the watch's own playback service. */
object WatchPlayer {
    private var controller: MediaController? = null

    suspend fun connect(context: Context): MediaController {
        controller?.takeIf { it.isConnected }?.let { return it }
        val token = SessionToken(context, ComponentName(context, WatchPlaybackService::class.java))
        return MediaController.Builder(context.applicationContext, token).buildAsync().await().also { controller = it }
    }

    /** The running service, starting it first if needed. */
    suspend fun service(context: Context): WatchPlaybackService? {
        connect(context)
        return WatchPlaybackService.instance
    }
}
