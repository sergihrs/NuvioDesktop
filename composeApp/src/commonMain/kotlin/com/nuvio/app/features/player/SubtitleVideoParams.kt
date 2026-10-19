package com.nuvio.app.features.player

import com.nuvio.app.features.addons.encodeAddonPathSegment
import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.isIos
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull

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
 * Computes the OpenSubtitles hash with two range reads; the file size comes from the first
 * response's Content-Range. [fetchRange] returns the requested bytes (inclusive range) or null
 * when the source cannot serve byte ranges.
 */
internal suspend fun computeOpenSubtitlesVideoHash(
    fetchRange: suspend (start: Long, endInclusive: Long) -> VideoByteRange?,
): OpenSubtitlesVideoHash? {
    val chunk = OpenSubtitlesHashChunkSize.toLong()
    val head = fetchRange(0L, chunk - 1) ?: return null
    val fileSize = head.totalBytes ?: return null
    if (fileSize < chunk * 2) return null
    if (head.bytes.size != OpenSubtitlesHashChunkSize) return null
    val tail = fetchRange(fileSize - chunk, fileSize - 1) ?: return null
    if (tail.bytes.size != OpenSubtitlesHashChunkSize || tail.totalBytes != fileSize) return null
    return OpenSubtitlesVideoHash(
        hash = computeOpenSubtitlesHash(fileSize, head.bytes, tail.bytes),
        fileSize = fileSize,
    )
}

/**
 * Computes and caches the OpenSubtitles hash of playing HTTP sources (debrid links, the local
 * torrent stream server). Each source is hashed once in the background; callers only wait
 * as long as they choose to.
 */
internal object SubtitleVideoHashRepository {
    private const val HASH_TIMEOUT_MS = 30_000L
    private const val MAX_CACHED_SOURCES = 16

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = SynchronizedObject()
    private val inFlight = LinkedHashMap<String, Deferred<OpenSubtitlesVideoHash?>>()
    private val completed = LinkedHashMap<String, OpenSubtitlesVideoHash>()

    /**
     * Range reads need a client that stops after the requested bytes; the iOS HTTP client
     * buffers whole responses, so a server that ignores Range would download the whole file.
     */
    val isSupported: Boolean
        get() = !isIos

    fun canHash(url: String?): Boolean =
        isSupported && url != null &&
            (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true))

    fun hashAsync(
        url: String,
        headers: Map<String, String>,
    ): Deferred<OpenSubtitlesVideoHash?>? {
        if (!canHash(url)) return null
        return synchronized(lock) {
            inFlight[url]?.let { return@synchronized it }
            val deferred = scope.async {
                val result = withTimeoutOrNull(HASH_TIMEOUT_MS) {
                    computeOpenSubtitlesVideoHash { start, endInclusive ->
                        fetchHttpRange(url, headers, start, endInclusive)
                    }
                }
                synchronized(lock) {
                    if (result != null) {
                        completed[url] = result
                        completed.trimToSize()
                    } else {
                        // Allow a later playback of the same URL to try again.
                        inFlight.remove(url)
                    }
                }
                result
            }
            inFlight[url] = deferred
            inFlight.trimToSize()
            deferred
        }
    }

    fun cachedHash(url: String?): OpenSubtitlesVideoHash? {
        if (url == null) return null
        return synchronized(lock) { completed[url] }
    }

    private fun <V> LinkedHashMap<String, V>.trimToSize() {
        while (size > MAX_CACHED_SOURCES) {
            remove(keys.first())
        }
    }

    private suspend fun fetchHttpRange(
        url: String,
        headers: Map<String, String>,
        start: Long,
        endInclusive: Long,
    ): VideoByteRange? {
        val expectedLength = (endInclusive - start + 1).toInt()
        val response = try {
            httpRequestRaw(
                method = "GET",
                url = url,
                headers = headers.filterKeys { !it.equals("Range", ignoreCase = true) } +
                    ("Range" to "bytes=$start-$endInclusive"),
                body = "",
                maxResponseBodyBytes = expectedLength,
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            return null
        }
        // A 200 means the server ignored Range; the tail cannot be read cheaply then.
        if (response.status != 206) return null
        val contentRange = response.headers.entries
            .firstOrNull { (name, _) -> name.equals("Content-Range", ignoreCase = true) }
            ?.value
        if (contentRange?.trim()?.startsWith("bytes $start-") != true) return null
        return VideoByteRange(
            bytes = response.bodyBytes,
            totalBytes = parseContentRangeTotalBytes(contentRange),
        )
    }
}
