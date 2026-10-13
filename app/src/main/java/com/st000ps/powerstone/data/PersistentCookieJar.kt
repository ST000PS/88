package com.st000ps.powerstone.data

import android.content.Context
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Keeps the site's session cookie across app restarts. Session cookies (no
 * expiry) are persisted too, so signing in once is enough.
 */
class PersistentCookieJar(context: Context) : CookieJar {
    private val prefs = context.getSharedPreferences("cookies", Context.MODE_PRIVATE)
    private val cookies = mutableMapOf<String, Cookie>()

    init {
        val base = HttpUrl.Builder().scheme("https").host(Api.HOST).build()
        prefs.all.values.filterIsInstance<String>().forEach { raw ->
            Cookie.parse(base, raw)?.let { cookies[key(it)] = it }
        }
    }

    private fun key(c: Cookie) = "${c.domain}|${c.path}|${c.name}"

    private fun serialize(c: Cookie): String {
        // Cookie.toString() drops the expiry for session cookies; give them a long one on disk.
        val persisted = if (c.persistent) c else Cookie.Builder()
            .name(c.name).value(c.value).path(c.path)
            .apply { if (c.hostOnly) hostOnlyDomain(c.domain) else domain(c.domain) }
            .apply { if (c.secure) secure() }
            .apply { if (c.httpOnly) httpOnly() }
            .expiresAt(System.currentTimeMillis() + 365L * 24 * 3600 * 1000)
            .build()
        return persisted.toString()
    }

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val edit = prefs.edit()
        for (c in cookies) {
            val k = key(c)
            if (c.expiresAt < System.currentTimeMillis()) {
                this.cookies.remove(k); edit.remove(k)
            } else {
                this.cookies[k] = c; edit.putString(k, serialize(c))
            }
        }
        edit.apply()
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        cookies.values.filter { it.matches(url) }

    @Synchronized
    fun clear() {
        cookies.clear()
        prefs.edit().clear().apply()
    }
}
