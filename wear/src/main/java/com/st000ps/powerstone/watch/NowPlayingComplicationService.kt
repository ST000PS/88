package com.st000ps.powerstone.watch

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/** Watch-face slot: the phone's now playing (BPM in a short slot, title · artist in a long one). */
class NowPlayingComplicationService : SuspendingComplicationDataSourceService() {

    private fun plain(s: String) = PlainComplicationText.Builder(s).build()

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun data(type: ComplicationType, now: PhoneNow?): ComplicationData? {
        val playing = now?.takeIf { it.hasTrack }
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(
                plain(playing?.bpm?.takeIf { it > 0 }?.toString() ?: "88.8"),
                plain(playing?.let { "Now playing ${it.title}" } ?: "88.8FM"),
            ).setTitle(plain(if (playing?.playing == true) "♪ BPM" else "FM")).setTapAction(openApp()).build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                plain(playing?.title ?: "Nothing playing"),
                plain(playing?.let { "${it.title} by ${it.artist}" } ?: "88.8FM"),
            ).setTitle(plain(playing?.artist?.ifEmpty { null } ?: "88.8FM")).setTapAction(openApp()).build()
            else -> null
        }
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        data(type, PhoneNow("GT.3", "Knxwledge", "WT.23", 92, "", true, true, false, false, 0, 0, 0, Eq(), 0, "", null))

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        data(request.complicationType, watchApp.link.refresh() ?: watchApp.link.now.value)
}
