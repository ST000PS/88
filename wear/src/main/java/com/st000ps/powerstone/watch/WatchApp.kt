package com.st000ps.powerstone.watch

import android.app.Application
import android.content.Context
import com.st000ps.powerstone.data.Api
import com.st000ps.powerstone.data.Me
import com.st000ps.powerstone.data.Release
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Where the watch's buttons act: the phone's player, or playback on the watch itself. */
enum class Target { PHONE, WATCH }

class WatchApp : Application() {
    lateinit var api: Api private set
    lateinit var link: PhoneLink private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val me = MutableStateFlow(Me())
    val releases = MutableStateFlow<List<Release>>(emptyList())
    val target = MutableStateFlow(Target.PHONE)

    override fun onCreate() {
        super.onCreate()
        api = Api(this)
        link = PhoneLink(this)
        link.start()
        val prefs = getSharedPreferences("watch", Context.MODE_PRIVATE)
        target.value = runCatching { Target.valueOf(prefs.getString("target", null)!!) }.getOrDefault(Target.PHONE)
        api.cachedCatalog()?.let { releases.value = it.releases }
        scope.launch { target.collect { prefs.edit().putString("target", it.name).apply() } }
    }

    /** Catalogue for playing on the watch (cached copy first, refreshed from the site). */
    suspend fun ensureCatalog(): List<Release> {
        if (releases.value.isEmpty()) runCatching { releases.value = api.catalog().releases }
        return releases.value
    }

    /** Account for streaming on the watch: borrowed from the phone's sign-in when possible. */
    suspend fun ensureAccount(): Me {
        api.tryMe()?.takeIf { it.signedIn }?.let { me.value = it; return it }
        link.requestSession()
        api.tryMe()?.let { me.value = it }
        return me.value
    }
}

val Context.watchApp: WatchApp get() = applicationContext as WatchApp
