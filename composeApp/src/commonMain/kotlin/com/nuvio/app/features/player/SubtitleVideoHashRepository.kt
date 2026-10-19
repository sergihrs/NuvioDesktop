package com.nuvio.app.features.player

import com.nuvio.app.features.addons.httpRequestRaw
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.isIos
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Computes and remembers the OpenSubtitles hash of HTTP sources (debrid links, the local torrent
 * stream route). Hashes are stored by file identity ([subtitleVideoHashIdentity]) in the
 * profile's local storage, so the same file has its hash at once the next time it is played.
 *
 * A computation is shared by everyone asking for the same URL and cancelled when nobody waits
 * for it any more, so leaving a source never leaves range reads running against it.
 */
internal object SubtitleVideoHashRepository {
    private const val HASH_TIMEOUT_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val computations = SharedSuspendComputations<String, OpenSubtitlesVideoHash?>(scope)
    private val lock = SynchronizedObject()
    private var cache: SubtitleVideoHashCache? = null
    private var cacheProfileId: Int? = null
    private val verifiedThisSession = HashSet<String>()

    /**
     * Range reads need a client that stops after the requested bytes; the iOS HTTP client
     * buffers whole responses, so a server that ignores Range would download the whole file.
     */
    val isSupported: Boolean
        get() = !isIos

    fun canHash(url: String?): Boolean =
        isSupported && url != null &&
            (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true))

    /** The remembered hash of the file with this identity, if it was hashed before. */
    fun cachedHash(identity: String?): OpenSubtitlesVideoHash? {
        if (identity.isNullOrBlank()) return null
        return synchronized(lock) { loadedCache().get(identity) }
    }

    /**
     * A remembered hash that needs no new range reads: torrent files never change, and other
     * sources count once they were hashed in this session.
     */
    fun verifiedHash(identity: String?): OpenSubtitlesVideoHash? {
        if (identity.isNullOrBlank()) return null
        return synchronized(lock) {
            if (isContentAddressedVideoHashIdentity(identity) || identity in verifiedThisSession) {
                loadedCache().get(identity)
            } else {
                null
            }
        }
    }

    fun remember(identity: String?, hash: OpenSubtitlesVideoHash) {
        if (identity.isNullOrBlank()) return
        synchronized(lock) {
            verifiedThisSession += identity
            val current = loadedCache()
            if (current.put(identity, hash)) {
                runCatching { SubtitleVideoHashStorage.savePayload(current.encode()) }
            }
        }
    }

    /** Whether a hash for [url] is being computed right now. */
    fun isComputing(url: String?): Boolean =
        url != null && url in computations.activeKeys

    /**
     * Hashes [url] with two 64 KiB range reads (concurrently when [expectedFileSize] is known)
     * and remembers the result under [identity]. Joins a computation already running for the
     * same URL. Returns null when the source cannot serve ranges or the reads time out.
     */
    suspend fun computeHash(
        url: String,
        headers: Map<String, String>,
        identity: String?,
        expectedFileSize: Long? = null,
    ): OpenSubtitlesVideoHash? {
        if (!canHash(url)) return null
        return computations.await(url) {
            val result = withTimeoutOrNull(HASH_TIMEOUT_MS) {
                computeOpenSubtitlesVideoHash(expectedFileSize) { start, endInclusive ->
                    fetchHttpRange(url, headers, start, endInclusive)
                }
            }
            if (result != null) remember(identity, result)
            result
        }
    }

    fun clearLocalState() {
        synchronized(lock) {
            cache = null
            cacheProfileId = null
            verifiedThisSession.clear()
        }
    }

    private fun loadedCache(): SubtitleVideoHashCache {
        val profileId = ProfileRepository.activeProfileId
        cache?.takeIf { cacheProfileId == profileId }?.let { return it }
        val payload = runCatching { SubtitleVideoHashStorage.loadPayload() }.getOrNull()
        return SubtitleVideoHashCache.decode(payload).also {
            cache = it
            cacheProfileId = profileId
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
