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

    private fun range(start: Long, end: Long) = VideoByteRange(
        bytes = file.copyOfRange(start.toInt(), end.toInt() + 1),
        totalBytes = file.size.toLong(),
    )
}
