package com.st000ps.powerstone.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(message: String) : IOException(message)

/** Thin client for the thepowerstone.com JSON API, sharing one cookie-backed session. */
class Api(context: Context) {
    companion object {
        const val HOST = "thepowerstone.com"
        const val BASE = "https://$HOST"
        private val JSON_TYPE = "application/json".toMediaType()

        fun absolute(path: String?): String? = when {
            path.isNullOrEmpty() -> null
            path.startsWith("http") -> path
            else -> BASE + path
        }
    }

    val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false; encodeDefaults = true }
    val cookies = PersistentCookieJar(context)
    val http: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookies)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", "Powerstone-Android/1.1").build())
        }
        .build()

    private val catalogCache = File(context.filesDir, "catalog.json")

    private suspend fun send(method: String, path: String, body: String? = null): Pair<Int, String> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(BASE + path).header("Cache-Control", "no-cache")
            .method(method, body?.toRequestBody(JSON_TYPE))
            .build()
        http.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
    }

    private suspend fun get(path: String) = send("GET", path)
    private suspend fun post(path: String, body: String) = send("POST", path, body)

    private suspend inline fun <reified T> getJson(path: String, what: String): T {
        val (code, body) = get(path)
        if (code == 401) throw ApiException("Sign in to see $what.")
        if (code != 200) throw ApiException("Couldn't load $what ($code).")
        return json.decodeFromString(body)
    }

    /** Last catalog saved to disk, for instant start-up and offline browsing. */
    fun cachedCatalog(): Catalog? = runCatching {
        json.decodeFromString<Catalog>(catalogCache.readText())
    }.getOrNull()

    suspend fun catalog(): Catalog {
        val (code, body) = get("/api/catalog")
        if (code != 200) throw ApiException("Catalog request failed ($code)")
        val catalog = json.decodeFromString<Catalog>(body)
        withContext(Dispatchers.IO) { runCatching { catalogCache.writeText(body) } }
        return catalog
    }

    /** /api/me, or null when the site couldn't be reached — so "offline" is never mistaken for "signed out". */
    suspend fun tryMe(): Me? = runCatching {
        val (code, body) = get("/api/me")
        when (code) {
            200 -> json.decodeFromString<Me>(body)
            401, 403 -> Me()
            else -> null
        }
    }.getOrNull()

    /** Downloads a track with the session cookie (full tracks need it). */
    suspend fun download(path: String): ByteArray = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(absolute(path)!!).build()
        http.newCall(req).execute().use { r ->
            if (r.code == 401) throw ApiException("This track needs a subscription.")
            if (!r.isSuccessful) throw ApiException("Download failed (${r.code}).")
            r.body!!.bytes()
        }
    }

    suspend fun me(): Me {
        val (code, body) = get("/api/me")
        if (code != 200) return Me()
        return json.decodeFromString(body)
    }

    // ---------- sign-in ----------

    /** Step 1 of email sign-in: the site emails a 6-digit code. */
    suspend fun requestCode(email: String) {
        val (code, _) = post("/api/login", json.encodeToString(mapOf("email" to email)))
        if (code !in 200..299) throw ApiException("Couldn't send a code ($code)")
    }

    /** Step 2 of email sign-in. On success the session cookie is stored by the cookie jar. */
    suspend fun verifyCode(email: String, sixDigits: String) {
        val (code, body) = post("/api/login/code", json.encodeToString(mapOf("email" to email, "code" to sixDigits)))
        val obj = runCatching { json.parseToJsonElement(body) as JsonObject }.getOrNull()
        val ok = obj?.get("ok")?.jsonPrimitive?.booleanOrNull == true
        if (code !in 200..299 || !ok) {
            val err = obj?.get("error")?.jsonPrimitive?.contentOrNull
            throw ApiException(
                if (err == "expired") "That code expired or was already used. Send a new one."
                else "That code didn't work."
            )
        }
    }

    suspend fun passwordLogin(email: String, password: String) {
        val (code, body) = post("/api/login/password", json.encodeToString(mapOf("email" to email, "password" to password)))
        val obj = runCatching { json.parseToJsonElement(body) as JsonObject }.getOrNull()
        val okFlag = obj?.get("ok")?.jsonPrimitive?.booleanOrNull
        if (code == 404) throw ApiException("Password sign-in isn't available. Use an email code.")
        if (code !in 200..299 || okFlag == false) {
            throw ApiException(obj?.get("message")?.jsonPrimitive?.contentOrNull ?: "Email or password didn't match.")
        }
    }

    suspend fun logout() {
        runCatching { get("/logout") }
        cookies.clear()
    }

    suspend fun passwordState(): PasswordState =
        runCatching { json.decodeFromString<PasswordState>(get("/api/password").second) }.getOrDefault(PasswordState())

    /** Set or change the account password (8+ characters, checked by the caller too). */
    suspend fun setPassword(password: String) {
        val (code, body) = post("/api/password", json.encodeToString(mapOf("password" to password)))
        if (code !in 200..299) {
            val msg = runCatching { (json.parseToJsonElement(body) as JsonObject)["message"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            throw ApiException(msg ?: "Couldn't save the password ($code).")
        }
    }

    // ---------- subscription (Stripe pages opened in the browser) ----------

    suspend fun checkoutUrl(plan: String): String {
        val (code, body) = post("/api/checkout", json.encodeToString(mapOf("plan" to plan)))
        val url = runCatching { json.decodeFromString<UrlResponse>(body).url }.getOrNull()
        if (code !in 200..299 || url == null) throw ApiException("Checkout didn't open. Try again in a moment.")
        return url
    }

    suspend fun portalUrl(): String {
        val (code, body) = post("/api/portal", "{}")
        val url = runCatching { json.decodeFromString<UrlResponse>(body).url }.getOrNull()
        if (code !in 200..299 || url == null) throw ApiException("Your account page didn't open. Try again.")
        return url
    }

    // ---------- favourites ----------

    suspend fun favs(): List<Fav> {
        val (code, body) = get("/api/favs")
        if (code == 401) return emptyList()
        if (code != 200) throw ApiException("Favorites request failed ($code)")
        return json.decodeFromString<FavsResponse>(body).favs
    }

    suspend fun setFav(id: String, on: Boolean): List<Fav>? {
        val payload = "{\"id\":${json.encodeToString(id)},\"on\":$on}"
        val (code, body) = post("/api/favs", payload)
        if (code !in 200..299) throw ApiException("Couldn't update favorite ($code)")
        return runCatching { json.decodeFromString<FavsResponse>(body).favs }.getOrNull()
    }

    // ---------- playlists & sharing ----------

    suspend fun playlists(): PlaylistsBody = getJson("/api/playlists", "playlists")

    suspend fun putPlaylists(body: PlaylistsBody): Boolean =
        runCatching { send("PUT", "/api/playlists", json.encodeToString(body)).first in 200..299 }.getOrDefault(false)

    /** Publishes a playlist snapshot; returns the share id used in `/?s=<id>` links. */
    suspend fun sharePlaylist(name: String, tracks: List<TrackRef>): String {
        val body = json.encodeToString(SharedPlaylist(name, tracks))
        val (code, resp) = post("/api/share", body)
        val id = runCatching { json.decodeFromString<IdResponse>(resp).id }.getOrNull()
        if (code !in 200..299 || id == null) throw ApiException("Couldn't share right now.")
        return id
    }

    suspend fun sharedPlaylist(id: String): SharedPlaylist {
        return getJson("/api/share/" + android.net.Uri.encode(id), "that shared playlist")
    }

    // ---------- listening history ----------

    /** Listening history, so plays from the app show up in the site's stats. */
    suspend fun reportPlays(events: List<PlayEvent>): Boolean {
        if (events.isEmpty()) return true
        return runCatching { post("/api/plays", json.encodeToString(PlaysBody(events))).first in 200..299 }
            .getOrDefault(false)
    }

    suspend fun history(): HistoryResponse = getJson("/api/history", "your history")

    /** [period] is "month" or "year". */
    suspend fun stats(period: String, year: Int): Stats = getJson("/api/stats?period=$period&year=$year", "your stats")
}
