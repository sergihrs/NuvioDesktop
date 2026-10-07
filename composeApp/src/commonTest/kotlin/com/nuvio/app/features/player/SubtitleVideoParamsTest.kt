package com.nuvio.app.features.player

import com.nuvio.app.features.addons.buildAddonResourceUrl
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SubtitleVideoParamsTest {

    @Test
    fun `extra path segment follows stremio order and encoding`() {
        val segment = buildSubtitleExtraPathSegment(
            SubtitleVideoParams(
                videoHash = " 8E245D9679D31E12 ",
                videoSize = 12_909_756L,
                filename = "Breaking Bad (2008) S01E01 [x].mkv",
            ),
        )

        assertEquals(
            "videoHash=8e245d9679d31e12&videoSize=12909756&filename=" +
                "Breaking%20Bad%20%282008%29%20S01E01%20%5Bx%5D.mkv",
            segment,
        )
    }

    @Test
    fun `extra path segment skips unknown values`() {
        assertEquals(
            "filename=Show.S01E01.mkv",
            buildSubtitleExtraPathSegment(SubtitleVideoParams(videoSize = 0L, filename = "Show.S01E01.mkv")),
        )
        assertNull(buildSubtitleExtraPathSegment(SubtitleVideoParams(videoHash = " ", filename = "")))
        assertNull(buildSubtitleExtraPathSegment(null))
    }

    @Test
    fun `subtitle url carries extras before json suffix and keeps manifest query`() {
        val url = buildAddonResourceUrl(
            manifestUrl = "https://subs.example.test/config/manifest.json?key=1",
            resource = "subtitles",
            type = "series",
            id = "tt0903747:1:1",
            extraPathSegment = buildSubtitleExtraPathSegment(
                SubtitleVideoParams(videoSize = 6_732_000_000L, filename = "Breaking Bad S01E01.mkv"),
            ),
        )

        assertEquals(
            "https://subs.example.test/config/subtitles/series/tt0903747%3A1%3A1/" +
                "videoSize=6732000000&filename=Breaking%20Bad%20S01E01.mkv.json?key=1",
            url,
        )
    }

    @Test
    fun `opensubtitles hash matches reference implementation`() {
        val file = syntheticFile(200_000)

        assertEquals(
            "5f9fe02060a3cd40",
            computeOpenSubtitlesHash(
                fileSize = file.size.toLong(),
                head = file.copyOfRange(0, OpenSubtitlesHashChunkSize),
                tail = file.copyOfRange(file.size - OpenSubtitlesHashChunkSize, file.size),
            ),
        )
    }

    @Test
    fun `opensubtitles hash wraps around on overflow`() {
        val ones = ByteArray(OpenSubtitlesHashChunkSize) { 0xFF.toByte() }

        // Each chunk sums 8192 words of -1, so the hash is 131072 - 16384.
        assertEquals("000000000001c000", computeOpenSubtitlesHash(131_072L, ones, ones))
    }

    @Test
    fun `video hash is computed from two range reads using content range size`() = runTest {
        val file = syntheticFile(200_000)
        val requestedRanges = mutableListOf<LongRange>()

        val result = computeOpenSubtitlesVideoHash { start, endInclusive ->
            requestedRanges += start..endInclusive
            VideoByteRange(
                bytes = file.copyOfRange(start.toInt(), endInclusive.toInt() + 1),
                totalBytes = file.size.toLong(),
            )
        }

        assertEquals(OpenSubtitlesVideoHash(hash = "5f9fe02060a3cd40", fileSize = 200_000L), result)
        assertEquals(listOf(0L..65_535L, 134_464L..199_999L), requestedRanges)
    }

    @Test
    fun `video hash is skipped without range support or for tiny files`() = runTest {
        assertNull(computeOpenSubtitlesVideoHash { _, _ -> null })

        val tiny = syntheticFile(100_000)
        assertNull(
            computeOpenSubtitlesVideoHash { start, endInclusive ->
                VideoByteRange(
                    bytes = tiny.copyOfRange(start.toInt(), minOf(endInclusive.toInt() + 1, tiny.size)),
                    totalBytes = tiny.size.toLong(),
                )
            },
        )

        assertNull(
            computeOpenSubtitlesVideoHash { _, _ ->
                VideoByteRange(bytes = ByteArray(OpenSubtitlesHashChunkSize), totalBytes = null)
            },
        )
    }

    @Test
    fun `content range total is parsed`() {
        assertEquals(1_180_090_590L, parseContentRangeTotalBytes("bytes 0-65535/1180090590"))
        assertNull(parseContentRangeTotalBytes("bytes 0-65535/*"))
        assertNull(parseContentRangeTotalBytes(null))
    }

    @Test
    fun `computed hash is sent with its own size unless the stream provides a hash`() {
        val computed = OpenSubtitlesVideoHash(hash = "00000000000000aa", fileSize = 2_000_000L)

        assertEquals(
            SubtitleVideoParams(videoHash = "00000000000000aa", videoSize = 2_000_000L, filename = "a.mkv"),
            resolveSubtitleVideoParams(
                streamHints = SubtitleVideoParams(videoSize = 1_999_999L, filename = "a.mkv"),
                computedHash = computed,
            ),
        )
        assertEquals(
            SubtitleVideoParams(videoHash = "00000000000000bb", videoSize = 5L),
            resolveSubtitleVideoParams(
                streamHints = SubtitleVideoParams(videoHash = "00000000000000BB", videoSize = 5L),
                computedHash = computed,
            ),
        )
        assertNull(resolveSubtitleVideoParams(streamHints = null, computedHash = null))
    }

    private fun syntheticFile(size: Int): ByteArray =
        ByteArray(size) { index -> ((index * 31 + 7) % 256).toByte() }
}
