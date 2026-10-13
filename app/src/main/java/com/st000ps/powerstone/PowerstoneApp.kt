package com.st000ps.powerstone

import android.app.Application
import android.content.Context
import com.st000ps.powerstone.data.Api
import com.st000ps.powerstone.data.Quality

class PowerstoneApp : Application() {
    lateinit var api: Api
        private set

    override fun onCreate() {
        super.onCreate()
        api = Api(this)
    }

    private val prefs get() = getSharedPreferences("settings", Context.MODE_PRIVATE)

    var quality: Quality
        get() = runCatching { Quality.valueOf(prefs.getString("quality", null)!!) }.getOrDefault(Quality.HIGH)
        set(value) = prefs.edit().putString("quality", value.name).apply()
}

val Context.app: PowerstoneApp get() = applicationContext as PowerstoneApp
