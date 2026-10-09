package com.nuvio.app.features.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubtitleVideoHashCacheTest {

    @Test
    fun `torrent identity uses infohash and file index or filename`() {
        assertEquals(
            "bt:abcdef0123:4",
            subtitleVideoHashIdentity(" ABCDEF0123 ", 4, "Show.S01E05.mkv", "http://127.0.0.1:1/x"),
        )
        assertEquals(
            "bt:abcdef0123:name:show.s01e05.mkv",
            subtitleVideoHashIdentity("abcdef0123", null, " Show.S01E05.mkv ", null),
        )
        assertEquals("bt:abcdef0123", subtitleVideoHashIdentity("abcdef0123", null, null, null))
        assertTrue(isContentAddressedVideoHashIdentity("bt:abcdef0123:4"))
    }

    @Test
    fun `http identity drops expiring credentials but keeps the file id`() {
        assertEquals(
            "url:https://cdn.example/d/ABC/Show.S01E05.mkv?id=42",
            subtitleVideoHashIdentity(
                torrentInfoHash = null,
                torrentFileIdx = null,
                torrentFilename = null,
                sourceUrl = "https://cdn.example/d/ABC/Show.S01E05.mkv?token=s3cr3t&id=42&Expires=99" +
                    "&X-Amz-Signature=f00#t=10",
            ),
        )
        assertEquals("https://a.example/f.mkv", stableSubtitleHashUrl("https://a.example/f.mkv?e=1&st=2"))
        assertNull(subtitleVideoHashIdentity(null, null, null, "file:///home/x.mkv"))
        assertNull(subtitleVideoHashIdentity(null, null, null, null))
        assertFalse(isContentAddressedVideoHashIdentity("url:https://a.example/f.mkv"))
    }

    @Test
    fun `cache keeps the most recently used entries`() {
        val cache = SubtitleVideoHashCache(maxEntries = 2)
        cache.put("a", hash(1))
        cache.put("b", hash(2))
        assertEquals(hash(1), cache.get("a"))
        cache.put("c", hash(3))

        assertNull(cache.get("b"))
        assertEquals(hash(1), cache.get("a"))
        assertEquals(hash(3), cache.get("c"))
        assertFalse(cache.put("c", hash(3)))
        assertTrue(cache.put("c", hash(4)))
    }

    @Test
    fun `cache payload round trips and ignores malformed entries`() {
        val cache = SubtitleVideoHashCache()
        cache.put("bt:aa:1", hash(1))
        cache.put("url:https://a.example/f.mkv", hash(2))

        val restored = SubtitleVideoHashCache.decode(cache.encode())

        assertEquals(hash(1), restored.get("bt:aa:1"))
        assertEquals(hash(2), restored.get("url:https://a.example/f.mkv"))
        assertEquals(0, SubtitleVideoHashCache.decode("not json").size)
        assertEquals(0, SubtitleVideoHashCache.decode(null).size)
        assertEquals(
            0,
            SubtitleVideoHashCache.decode("""[{"k":"x","h":"XYZ","s":5},{"k":"y","h":"00000000000000aa","s":0}]""").size,
        )
    }

    @Test
    fun `remembered http hash is dropped when the size hint disagrees`() {
        val cached = OpenSubtitlesVideoHash("00000000000000aa", 2_000_000L)
        val lookup: (String?) -> OpenSubtitlesVideoHash? = { cached }
        val notVerified: (String?) -> OpenSubtitlesVideoHash? = { null }

        assertEquals(cached, rememberedSubtitleVideoHash("url:https://a.example/f", null, lookup, notVerified))
        assertEquals(cached, rememberedSubtitleVideoHash("url:https://a.example/f", 2_000_000L, lookup, notVerified))
        assertNull(rememberedSubtitleVideoHash("url:https://a.example/f", 1_000L, lookup, notVerified))
        // A verified hash (torrent file, or hashed in this session) wins over a wrong hint.
        assertEquals(cached, rememberedSubtitleVideoHash("bt:aa:1", 1_000L, lookup, lookup))
    }

    @Test
    fun `shared computation runs once for concurrent callers`() = runTest {
        val computations = SharedSuspendComputations<String, Int>(this)
        var runs = 0
        val gate = CompletableDeferred<Int>()
        val first = async { computations.await("u") { runs += 1; gate.await() } }
        val second = async { computations.await("u") { runs += 1; gate.await() } }
        advanceUntilIdle()
        gate.complete(7)

        assertEquals(7, first.await())
        assertEquals(7, second.await())
        assertEquals(1, runs)
        assertTrue(computations.activeKeys.isEmpty())
    }

    @Test
    fun `shared computation is cancelled when its last caller leaves`() = runTest {
        val computations = SharedSuspendComputations<String, Int>(this)
        var cancelled = false
        val computation: suspend () -> Int = {
            try {
                awaitCancellation()
            } finally {
                cancelled = true
            }
        }
        val first = async { computations.await("u", computation) }
        val second = async { computations.await("u", computation) }
        advanceUntilIdle()

        first.cancel()
        advanceUntilIdle()
        assertFalse(cancelled, "another caller still waits")
        assertEquals(setOf("u"), computations.activeKeys)

        second.cancel()
        advanceUntilIdle()
        assertTrue(cancelled)
        assertTrue(computations.activeKeys.isEmpty())
    }

    private fun hash(seed: Int) = OpenSubtitlesVideoHash(
        hash = seed.toString(16).padStart(16, '0'),
        fileSize = 1_000_000L + seed,
    )
}
