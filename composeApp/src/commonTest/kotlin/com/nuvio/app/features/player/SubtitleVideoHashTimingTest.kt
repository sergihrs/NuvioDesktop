package com.nuvio.app.features.player

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SubtitleVideoHashTimingTest {
    private val file = ByteArray(200_000) { index -> ((index * 31 + 7) % 256).toByte() }
    private val expected = OpenSubtitlesVideoHash(hash = "5f9fe02060a3cd40", fileSize = 200_000L)

    @Test
    fun `known size reads head and tail concurrently`() = runTest {
        val headGate = CompletableDeferred<Unit>()
        val requested = mutableListOf<LongRange>()
        val result = async {
            computeOpenSubtitlesVideoHash(expectedFileSize = file.size.toLong()) { start, end ->
                requested += start..end
                if (start == 0L) headGate.await()
                range(start, end)
            }
        }
        advanceUntilIdle()
        // The tail was asked for while the head read is still blocked.
        assertEquals(setOf(0L..65_535L, 134_464L..199_999L), requested.toSet())

        headGate.complete(Unit)
        assertEquals(expected, result.await())
        assertEquals(2, requested.size)
    }

    @Test
    fun `wrong size hint falls back to the real tail`() = runTest {
        val requested = mutableListOf<LongRange>()
        val result = computeOpenSubtitlesVideoHash(expectedFileSize = 300_000L) { start, end ->
            requested += start..end
            if (end >= file.size) null else range(start, end)
        }

        assertEquals(expected, result)
        assertTrue(134_464L..199_999L in requested)
    }

    @Test
    fun `next episode params match what the player asks for after the switch`() = runTest {
        val identity = "url:https://cdn.example/next.mkv"
        val stored = HashMap<String, OpenSubtitlesVideoHash>()
        val hints = SubtitleVideoParams(videoSize = 200_000L, filename = "Show.S01E02.mkv")
        var hashCalls = 0

        val prepared = nextEpisodeSubtitleVideoParams(
            hints = hints,
            identity = identity,
            url = "https://cdn.example/next.mkv",
            headers = emptyMap(),
            computeHash = { _, _, id, size ->
                hashCalls += 1
                assertEquals(200_000L, size)
                expected.also { stored[id!!] = it }
            },
            canHash = { true },
            verifiedHash = { stored[it] },
            cachedHash = { stored[it] },
        )
        // After the switch the player resolves its params from the stored hash.
        val afterSwitch = resolveSubtitleVideoParams(
            streamHints = hints,
            computedHash = rememberedSubtitleVideoHash(identity, hints.videoSize, { stored[it] }, { stored[it] }),
        )

        assertEquals(1, hashCalls)
        assertEquals(
            SubtitleVideoParams(videoHash = expected.hash, videoSize = 200_000L, filename = "Show.S01E02.mkv"),
            prepared,
        )
        assertEquals(prepared, afterSwitch)
    }

    @Test
    fun `next torrent episode is never read and uses only a remembered hash`() = runTest {
        val hints = SubtitleVideoParams(filename = "Show.S01E02.mkv")
        val withoutHash = nextEpisodeSubtitleVideoParams(
            hints = hints,
            identity = "bt:aa:2",
            url = null,
            headers = emptyMap(),
            computeHash = { _, _, _, _ -> error("torrents must not be read before the switch") },
            canHash = { true },
            verifiedHash = { null },
            cachedHash = { null },
        )
        val remembered = nextEpisodeSubtitleVideoParams(
            hints = hints,
            identity = "bt:aa:2",
            url = null,
            headers = emptyMap(),
            computeHash = { _, _, _, _ -> error("torrents must not be read before the switch") },
            canHash = { true },
            verifiedHash = { expected },
            cachedHash = { expected },
        )

        assertEquals(hints, withoutHash)
        assertEquals(expected.hash, remembered?.videoHash)
    }

    private fun range(start: Long, end: Long) = VideoByteRange(
        bytes = file.copyOfRange(start.toInt(), end.toInt() + 1),
        totalBytes = file.size.toLong(),
    )
}
