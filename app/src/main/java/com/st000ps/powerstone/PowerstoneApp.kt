package com.st000ps.powerstone

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.st000ps.powerstone.audio.AudioFx
import com.st000ps.powerstone.data.Api
import com.st000ps.powerstone.data.ListeningHistory
import com.st000ps.powerstone.data.Me
import com.st000ps.powerstone.data.OfflineStore
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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import com.st000ps.powerstone.data.streamPath

class PowerstoneApp : Application(), coil.ImageLoaderFactory {
    lateinit var api: Api private set
    lateinit var settings: Settings private set
    lateinit var vault: com.st000ps.powerstone.data.CredentialVault private set
    lateinit var playlists: PlaylistStore private set
    lateinit var history: ListeningHistory private set
    lateinit var session: SessionStore private set
    lateinit var offline: OfflineStore private set

    /** App-wide scope for work that should outlive a screen (syncing, reporting plays). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Catalogue in site order (newest first), shared by the UI and the playback service. */
    val releases = MutableStateFlow<List<Release>>(emptyList())
    val me = MutableStateFlow(Me())

    /** Favourite track ids, shared by the screens and the lock-screen / notification heart. */
    val favs = MutableStateFlow<List<String>>(emptyList())

    /** "↓ GT.3 · 3/8" while a download runs. */
    val downloadStatus = MutableStateFlow<String?>(null)
    private var downloadJob: kotlinx.coroutines.Job? = null

    /** True when the last account check couldn't reach the site. */
    val isOffline = MutableStateFlow(false)

    /** Sleep timer, set from the UI and carried out by the playback service. */
    val sleep = MutableStateFlow(SleepState())

    override fun onCreate() {
        super.onCreate()
        api = Api(this)
        settings = Settings(this)
        vault = com.st000ps.powerstone.data.CredentialVault(this)
        playlists = PlaylistStore(api, settings, filesDir, scope)
        history = ListeningHistory(filesDir, api.json)
        session = SessionStore(this, api.json)
        offline = OfflineStore(java.io.File(filesDir, "offline"), api.json, api::download)
        session.loadMe()?.let { me.value = it }
        me.onEach(session::saveMe).launchIn(scope)
        api.cachedCatalog()?.let { releases.value = it.releases }
        settings.eq.onEach(AudioFx::apply).launchIn(scope)
        AudioFx.spectrumOn = settings.visualizerOn
    }

    /**
     * Covers load through the API's client (one connection pool, one TLS session to the site) and
     * keep a 256 MB disk cache, so scrolling the catalogue again is instant and costs no data.
     */
    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this)
        .okHttpClient(api.http)
        .diskCache { coil.disk.DiskCache.Builder().directory(cacheDir.resolve("covers")).maxSizeBytes(256L shl 20).build() }
        .crossfade(true)
        .build()

    private val signInLock = kotlinx.coroutines.sync.Mutex()

    /**
     * The account as the site sees it, signing back in first if the session has ended and this
     * phone has "stay signed in" credentials. Null when the site can't be reached (offline).
     * A rejected password forgets the stored one; network trouble never does.
     */
    suspend fun ensureSignedIn(): com.st000ps.powerstone.data.Me? = signInLock.withLock {
        val now = api.tryMe() ?: return@withLock null
        if (now.signedIn) return@withLock now
        val (email, password) = vault.load() ?: return@withLock now
        try {
            api.passwordLogin(email, password)
        } catch (e: com.st000ps.powerstone.data.ApiException) {
            vault.clear()
            return@withLock now
        } catch (e: Exception) {
            return@withLock now
        }
        api.tryMe()?.also { me.value = it } ?: now
    }

    /** Adds or removes a favourite (optimistic; rolls back if the site says no). Returns success. */
    suspend fun setFav(id: String, on: Boolean): Boolean {
        val before = favs.value
        favs.value = if (on) listOf(id) + before.filter { it != id } else before - id
        return try {
            api.setFav(id, on)?.let { list -> favs.value = list.map { it.id } }
            true
        } catch (_: Exception) {
            favs.value = before
            false
        }
    }

    /** Downloads tracks for offline listening (subscribers), one at a time. */
    fun startDownload(items: List<Pair<Release, com.st000ps.powerstone.data.Track>>, what: String, done: (String) -> Unit) {
        if (!me.value.isSubscriber) { done("Downloads are for subscribers"); return }
        if (downloadJob?.isActive == true) { done("A download is already running"); return }
        val q = settings.quality.let { if (it == Quality.AUTO) Quality.HIGH else it }
        val pairs = items.mapNotNull { (r, t) -> t.streamPath(true, q)?.let { com.st000ps.powerstone.data.trackId(r, t) to it } }
        downloadJob = scope.launch {
            try {
                val n = offline.download(pairs) { d, total -> downloadStatus.value = if (total == 0) null else "↓ $what · $d/$total" }
                done(if (n == 0) "$what is already downloaded" else "Downloaded $what ($n tracks)")
            } catch (_: kotlinx.coroutines.CancellationException) {
                done("Download stopped")
            } catch (e: Exception) {
                done("Download failed: ${e.message}")
            } finally {
                downloadStatus.value = null
            }
        }
    }

    fun cancelDownload() { downloadJob?.cancel() }

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
