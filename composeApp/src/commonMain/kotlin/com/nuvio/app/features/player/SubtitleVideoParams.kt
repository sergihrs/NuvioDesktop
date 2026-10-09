package com.nuvio.app.features.player

import com.nuvio.app.features.addons.encodeAddonPathSegment
import com.nuvio.app.features.streams.StreamBehaviorHints
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Describes the playing video file for subtitle addons, mirroring Stremio's `VideoParams`.
 * Sent as the `videoHash`, `videoSize` and `filename` extras of `/subtitles/` requests so
 * addons can return subtitles that were made for this exact release.
 */
data class SubtitleVideoParams(
    /** OpenSubtitles "moviehash" of the file as 16 lowercase hex digits. */
    val videoHash: String? = null,
    val videoSize: Long? = null,
    val filename: String? = null,
) {
    val isEmpty: Boolean
        get() = videoHash == null && videoSize == null && filename == null

    fun normalized(): SubtitleVideoParams = SubtitleVideoParams(
        videoHash = videoHash?.trim()?.lowercase()?.takeIf { it.isNotBlank() },
        videoSize = videoSize?.takeIf { it > 0L },
        filename = filename?.trim()?.takeIf { it.isNotBlank() },
    )

    /** Fills missing values from [other]; values already known (e.g. from the addon) win. */
    fun mergedWith(other: SubtitleVideoParams?): SubtitleVideoParams {
        if (other == null) return this
        return SubtitleVideoParams(
            videoHash = videoHash ?: other.videoHash,
            videoSize = videoSize ?: other.videoSize,
            filename = filename ?: other.filename,
        ).normalized()
    }
}

internal fun StreamBehaviorHints.toSubtitleVideoParams(): SubtitleVideoParams =
    SubtitleVideoParams(
        videoHash = videoHash,
        videoSize = videoSize,
        filename = filename,
    ).normalized()

/**
 * Builds the extra path segment of a subtitles request the way Stremio does
 * (`videoHash=…&videoSize=…&filename=…`, each value URL-encoded), or null when nothing is known.
 */
internal fun buildSubtitleExtraPathSegment(params: SubtitleVideoParams?): String? {
    val normalized = params?.normalized() ?: return null
    return buildList {
        normalized.videoHash?.let { add("videoHash=${it.encodeAddonPathSegment()}") }
        normalized.videoSize?.let { add("videoSize=$it") }
        normalized.filename?.let { add("filename=${it.encodeAddonPathSegment()}") }
    }.joinToString(separator = "&").ifBlank { null }
}

internal const val OpenSubtitlesHashChunkSize = 64 * 1024

/**
 * OpenSubtitles hash: file size plus the sum of the first and last 64 KiB read as
 * little-endian unsigned 64-bit words, wrapping on overflow.
 * See https://trac.opensubtitles.org/projects/opensubtitles/wiki/HashSourceCodes
 */
internal fun computeOpenSubtitlesHash(fileSize: Long, head: ByteArray, tail: ByteArray): String {
    var hash = fileSize
    hash += head.sumLittleEndianLongs()
    hash += tail.sumLittleEndianLongs()
    return hash.toULong().toString(16).padStart(16, '0')
}

private fun ByteArray.sumLittleEndianLongs(): Long {
    var sum = 0L
    var offset = 0
    while (offset + 8 <= size) {
        var value = 0L
        for (index in 0 until 8) {
            value = value or ((this[offset + index].toLong() and 0xFF) shl (index * 8))
        }
        sum += value
        offset += 8
    }
    return sum
}

/** Parses the total length from a `Content-Range: bytes 0-65535/123456` header. */
internal fun parseContentRangeTotalBytes(contentRange: String?): Long? =
    contentRange
        ?.substringAfterLast('/', missingDelimiterValue = "")
        ?.trim()
        ?.toLongOrNull()
        ?.takeIf { it > 0L }

internal class VideoByteRange(
    val bytes: ByteArray,
    /** Total file size reported by the server's Content-Range header. */
    val totalBytes: Long?,
)

internal data class OpenSubtitlesVideoHash(
    val hash: String,
    val fileSize: Long,
)

/**
 * Computes the OpenSubtitles hash with two range reads. [fetchRange] returns the requested bytes
 * (inclusive range) or null when the source cannot serve byte ranges.
 *
 * With an [expectedFileSize] (e.g. the stream's `videoSize` hint) the head and tail are read
 * concurrently; the size is then confirmed by both responses' Content-Range, and a wrong hint
 * falls back to reading the tail again at the real end. Without it the tail is read once the
 * head response has reported the file size.
 */
internal suspend fun computeOpenSubtitlesVideoHash(
    expectedFileSize: Long? = null,
    fetchRange: suspend (start: Long, endInclusive: Long) -> VideoByteRange?,
): OpenSubtitlesVideoHash? = coroutineScope {
    val chunk = OpenSubtitlesHashChunkSize.toLong()
    val guessedSize = expectedFileSize?.takeIf { it >= chunk * 2 }
    val guessedTail = guessedSize?.let { size -> async { fetchRange(size - chunk, size - 1) } }
    val head = fetchRange(0L, chunk - 1)
    val fileSize = head?.totalBytes
    if (head == null || fileSize == null || fileSize < chunk * 2 ||
        head.bytes.size != OpenSubtitlesHashChunkSize
    ) {
        guessedTail?.cancel()
        return@coroutineScope null
    }
    val tail = if (guessedTail != null && guessedSize == fileSize) {
        guessedTail.await()
    } else {
        guessedTail?.cancel()
        fetchRange(fileSize - chunk, fileSize - 1)
    }
    if (tail == null || tail.bytes.size != OpenSubtitlesHashChunkSize || tail.totalBytes != fileSize) {
        return@coroutineScope null
    }
    OpenSubtitlesVideoHash(
        hash = computeOpenSubtitlesHash(fileSize, head.bytes, tail.bytes),
        fileSize = fileSize,
    )
}
