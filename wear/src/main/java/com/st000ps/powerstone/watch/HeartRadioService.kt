package com.st000ps.powerstone.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DeltaDataType
import com.st000ps.powerstone.wear.WearProtocol
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Heart-rate radio: measures heart rate in the background and steers the music's tempo to it,
 * on the phone or on the watch (whichever the app is set to). Runs until switched off.
 */
class HeartRadioService : Service() {
    companion object {
        val heartRate = MutableStateFlow<Int?>(null)
        val running = MutableStateFlow(false)
        private const val CHANNEL = "radio"

        fun start(context: Context) = context.startForegroundService(Intent(context, HeartRadioService::class.java))
        fun stop(context: Context) = context.stopService(Intent(context, HeartRadioService::class.java))
    }

    private val measure by lazy { HealthServices.getClient(this).measureClient }
    private var lastSent: Int? = null
    private var lastSentAt = 0L
    private var target: Target = Target.PHONE

    private val callback = object : MeasureCallback {
        override fun onAvailabilityChanged(dataType: DeltaDataType<*, *>, availability: Availability) = Unit

        override fun onDataReceived(data: DataPointContainer) {
            val bpm = data.getData(DataType.HEART_RATE_BPM).lastOrNull()?.value?.toInt() ?: return
            if (bpm < 40) return
            heartRate.value = bpm
            steer(bpm)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running.value) return START_STICKY
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        running.value = true
        target = watchApp.target.value
        lastSent = null
        measure.registerMeasureCallback(DataType.HEART_RATE_BPM, callback)
        return START_STICKY
    }

    /** Re-target when the heart rate has moved enough, at most every 15 s. */
    private fun steer(bpm: Int) {
        val now = System.currentTimeMillis()
        val last = lastSent
        if (last != null && (kotlin.math.abs(bpm - last) < 6 || now - lastSentAt < 15_000)) return
        lastSent = bpm; lastSentAt = now
        val app = watchApp
        app.scope.launch {
            when (target) {
                Target.PHONE -> app.link.send(WearProtocol.RADIO_PREFIX + bpm)
                Target.WATCH -> WatchPlayer.service(this@HeartRadioService)?.radio(bpm)
            }
        }
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Heart-rate radio", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Heart-rate radio")
            .setContentText("Matching the music to your heart rate")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        runCatching { measure.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, callback) }
        running.value = false
        heartRate.value = null
        val app = watchApp
        when (target) {
            Target.PHONE -> app.scope.launch { app.link.send(WearProtocol.RADIO_PREFIX + "off") }
            Target.WATCH -> WatchPlaybackService.instance?.stopRadio()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
