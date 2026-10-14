package com.st000ps.powerstone.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.file.Files

class OfflineStoreTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val sub = Me(tier = "sub", signedIn = true)
    private var now = 1_000_000_000L
    private val fetched = mutableListOf<String>()
    private fun store(dir: java.io.File) = OfflineStore(dir, json, { url -> fetched += url; byteArrayOf(1, 2, 3) }, { now })

    @Test fun nonSubscribersCannotDownload() {
        val s = store(Files.createTempDirectory("off").toFile())
        assertThrows(ApiException::class.java) { runBlocking { s.download(listOf("a/1" to "/a/full/a/1.mp3")) } }
    }

    @Test fun downloadsOncePersistsAndLocksThenWipes() = runBlocking {
        val dir = Files.createTempDirectory("off").toFile()
        val s = store(dir)
        s.accountChecked(sub)
        val items = listOf("gt-3/1" to "/x/1.mp3", "gt-3/2" to "/x/2.mp3")
        assertEquals(2, s.download(items))
        assertEquals(0, s.download(items))
        assertEquals(2, fetched.size)
        val reloaded = store(dir)
        assertEquals(2, reloaded.count)
        assertEquals(6L, reloaded.totalBytes)
        assertNotNull(reloaded.fileFor("gt-3/1"))

        now += 31L * 24 * 3600 * 1000
        assertNull(reloaded.fileFor("gt-3/1"))   // locked after a month without confirmation…
        assertEquals(2, reloaded.count)          // …but kept (might just be offline)
        reloaded.accountChecked(sub)
        assertNotNull(reloaded.fileFor("gt-3/1"))
        reloaded.accountChecked(Me(tier = "guest", signedIn = true))
        assertEquals(0, reloaded.count)          // not subscribed any more: gone
        assertFalse(reloaded.usable)
    }
}
