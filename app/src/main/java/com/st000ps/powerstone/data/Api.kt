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

    val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
    val cookies = PersistentCookieJar(context)
    val http: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookies)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().header("User-Agent", "Powerstone-Android/1.0").build())
        }
        .build()

    private val catalogCache = File(context.filesDir, "catalog.json")

    private suspend fun get(path: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(BASE + path).header("Cache-Control", "no-cache").build()
        http.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
    }

    private suspend fun post(path: String, body: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(BASE + path).post(body.toRequestBody(JSON_TYPE)).build()
        http.newCall(req).execute().use { it.code to (it.body?.string() ?: "") }
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

    suspend fun me(): Me {
        val (code, body) = get("/api/me")
        if (code != 200) return Me()
        return json.decodeFromString(body)
    }

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

    /** Listening history, so plays from the app show up in the site's stats. */
    suspend fun reportPlays(events: List<PlayEvent>): Boolean {
        if (events.isEmpty()) return true
        return runCatching { post("/api/plays", json.encodeToString(PlaysBody(events))).first in 200..299 }
            .getOrDefault(false)
    }
}
