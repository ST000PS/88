package com.st000ps.powerstone.playback

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.st000ps.powerstone.app
import java.io.File

/**
 * One on-disk audio cache (1 GB, least-recently-played out first) shared by playback and the
 * widget's groove analysis: replays cost no data and start instantly, and analysing a track reads
 * the bytes playback already fetched. Full-length tracks in it are cleared, like downloads, when
 * the account isn't subscribed any more (see [clear]).
 */
@OptIn(UnstableApi::class)
object MediaCache {
    @Volatile private var cache: SimpleCache? = null

    fun get(context: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(
            File(context.applicationContext.cacheDir, "media"),
            LeastRecentlyUsedCacheEvictor(1L shl 30),
            StandaloneDatabaseProvider(context.applicationContext),
        ).also { cache = it }
    }

    /** Streams through the cache, with the API's client (and its sign-in cookie) behind it. */
    fun factory(context: Context): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(get(context))
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(context.app.api.http))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    fun clear(context: Context) {
        val c = get(context)
        runCatching { c.keys.toList().forEach { c.removeResource(it) } }
    }
}

/** Lets MediaExtractor read a stream through [factory] (the cache first, then the network). */
@OptIn(UnstableApi::class)
class CachedMediaSource(private val factory: DataSource.Factory, private val uri: Uri) : MediaDataSource() {
    private var source: DataSource? = null
    private var at = -1L
    private var length = UNKNOWN

    private fun openAt(position: Long) {
        source?.close()
        val s = factory.createDataSource()
        val n = s.open(DataSpec.Builder().setUri(uri).setPosition(position).build())
        if (length == UNKNOWN) length = if (n == C.LENGTH_UNSET.toLong()) -1 else position + n
        source = s
        at = position
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (size == 0) return 0
        if (length >= 0 && position >= length) return -1
        if (source == null || position != at) openAt(position)
        val n = source!!.read(buffer, offset, size)
        if (n == C.RESULT_END_OF_INPUT) return -1
        at += n
        return n
    }

    override fun getSize(): Long {
        if (length == UNKNOWN) openAt(0)
        return length
    }

    override fun close() {
        source?.close()
        source = null
    }

    private companion object { const val UNKNOWN = -2L }
}
