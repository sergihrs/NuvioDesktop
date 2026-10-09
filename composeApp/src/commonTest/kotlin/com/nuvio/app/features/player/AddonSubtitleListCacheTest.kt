package com.nuvio.app.features.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

class AddonSubtitleListCacheTest {

    @Test
    fun `prefetched subtitle list is handed over and cached`() = runTest {
        val cache = AddonSubtitleListCache(backgroundScope)
        val gate = CompletableDeferred<List<AddonSubtitle>>()
        var loads = 0
        val prefetch = cache.prefetch("k") { loads += 1; gate.await() }
        assertEquals(prefetch, cache.prefetch("k") { loads += 1; emptyList() })
        advanceUntilIdle()
        assertEquals(prefetch, cache.inFlight("k"))

        gate.complete(listOf(subtitle("en")))
        assertEquals(listOf(subtitle("en")), prefetch.awaitOrEmpty())
        advanceUntilIdle()

        assertEquals(1, loads)
        assertNull(cache.inFlight("k"))
        assertEquals(listOf(subtitle("en")), cache.cached("k"))
    }

    @Test
    fun `empty or failed prefetches are not cached`() = runTest {
        val cache = AddonSubtitleListCache(backgroundScope)
        assertEquals(emptyList(), cache.prefetch("empty") { emptyList() }.awaitOrEmpty())
        assertEquals(emptyList(), cache.prefetch("failed") { error("addon down") }.awaitOrEmpty())
        advanceUntilIdle()

        assertNull(cache.cached("empty"))
        assertNull(cache.cached("failed"))
    }

    @Test
    fun `cached subtitle lists expire`() = runTest {
        val time = TestTimeSource()
        val cache = AddonSubtitleListCache(backgroundScope, ttl = 30.minutes, timeSource = time)
        cache.store("k", listOf(subtitle("en")))
        time += 29.minutes
        assertEquals(listOf(subtitle("en")), cache.cached("k"))
        time += 2.minutes
        assertNull(cache.cached("k"))
    }

    @Test
    fun `cancelled prefetch yields an empty list to the waiting fetch`() = runTest {
        val cache = AddonSubtitleListCache(backgroundScope)
        val prefetch = cache.prefetch("k") { CompletableDeferred<List<AddonSubtitle>>().await() }
        advanceUntilIdle()
        cache.clear()

        assertEquals(emptyList(), prefetch.awaitOrEmpty())
    }

    private fun subtitle(language: String) = AddonSubtitle(
        id = "id-$language",
        url = "https://subs.example/$language.srt",
        language = language,
        display = language,
    )
}
