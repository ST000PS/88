package com.st000ps.powerstone

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.st000ps.powerstone.audio.AudioFx
import com.st000ps.powerstone.data.Api
import com.st000ps.powerstone.data.ListeningHistory
import com.st000ps.powerstone.data.Me
import com.st000ps.powerstone.data.PlaylistStore
import com.st000ps.powerstone.data.Quality
import com.st000ps.powerstone.data.Release
import com.st000ps.powerstone.data.SessionStore
import com.st000ps.powerstone.data.Settings
import com.st000ps.powerstone.data.SleepState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class PowerstoneApp : Application() {
    lateinit var api: Api private set
    lateinit var settings: Settings private set
    lateinit var playlists: PlaylistStore private set
    lateinit var history: ListeningHistory private set
    lateinit var session: SessionStore private set

    /** App-wide scope for work that should outlive a screen (syncing, reporting plays). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Catalogue in site order (newest first), shared by the UI and the playback service. */
    val releases = MutableStateFlow<List<Release>>(emptyList())
    val me = MutableStateFlow(Me())

    /** Sleep timer, set from the UI and carried out by the playback service. */
    val sleep = MutableStateFlow(SleepState())

    override fun onCreate() {
        super.onCreate()
        api = Api(this)
        settings = Settings(this)
        playlists = PlaylistStore(api, settings, filesDir, scope)
        history = ListeningHistory(filesDir, api.json)
        session = SessionStore(this, api.json)
        session.loadMe()?.let { me.value = it }
        me.onEach(session::saveMe).launchIn(scope)
        api.cachedCatalog()?.let { releases.value = it.releases }
        settings.eq.onEach(AudioFx::apply).launchIn(scope)
        AudioFx.spectrumOn = settings.visualizerOn
    }

    /** AUTO picks like the site: data saver → 64k, mobile data → 160k, otherwise 320k. */
    fun resolvedQuality(): Quality {
        val q = settings.quality
        if (q != Quality.AUTO) return q
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED) return Quality.LO
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        return if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) Quality.STD else Quality.HIGH
    }
}

val Context.app: PowerstoneApp get() = applicationContext as PowerstoneApp
