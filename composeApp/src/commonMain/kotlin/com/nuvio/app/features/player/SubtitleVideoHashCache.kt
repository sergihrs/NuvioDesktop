package com.nuvio.app.features.player

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A stable identity for the file whose OpenSubtitles hash is cached, so a hash survives a new
 * playback URL (the local torrent stream route changes on every start, debrid links carry tokens).
 *
 * - Torrents: `bt:<infohash>:<file index>` (or the filename hint when no index is known). The
 *   bytes behind it never change, so a cached hash can be trusted without reading the file.
 * - HTTP sources: `url:<url without volatile query parameters and fragment>`. The same URL can
 *   serve another file later, so a cached hash is only a first guess that is verified again.
 */
internal fun subtitleVideoHashIdentity(
    torrentInfoHash: String?,
    torrentFileIdx: Int?,
    torrentFilename: String?,
    sourceUrl: String?,
): String? {
    val infoHash = torrentInfoHash?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
    if (infoHash != null) {
        val filename = torrentFilename?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        return when {
            torrentFileIdx != null && torrentFileIdx >= 0 -> "$TorrentIdentityPrefix$infoHash:$torrentFileIdx"
            filename != null -> "$TorrentIdentityPrefix$infoHash:name:$filename"
            else -> "$TorrentIdentityPrefix$infoHash"
        }
    }
    val url = sourceUrl?.trim()?.takeIf {
        it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
    } ?: return null
    return UrlIdentityPrefix + stableSubtitleHashUrl(url)
}

/** Whether a cached hash for [identity] describes immutable content (a torrent file). */
internal fun isContentAddressedVideoHashIdentity(identity: String?): Boolean =
    identity?.startsWith(TorrentIdentityPrefix) == true

private const val TorrentIdentityPrefix = "bt:"
private const val UrlIdentityPrefix = "url:"

/** Drops the fragment and query parameters that only carry expiring credentials. */
internal fun stableSubtitleHashUrl(url: String): String {
    val withoutFragment = url.substringBefore('#')
    val queryStart = withoutFragment.indexOf('?')
    if (queryStart < 0) return withoutFragment
    val base = withoutFragment.substring(0, queryStart)
    val kept = withoutFragment.substring(queryStart + 1)
        .split('&')
        .filter { parameter ->
            val name = parameter.substringBefore('=').lowercase()
            name.isNotBlank() && !isVolatileUrlQueryParameter(name)
        }
    return if (kept.isEmpty()) base else base + "?" + kept.joinToString("&")
}

private fun isVolatileUrlQueryParameter(name: String): Boolean =
    name in VolatileUrlQueryParameters || VolatileUrlQueryParameterPrefixes.any(name::startsWith)

private val VolatileUrlQueryParameters = setOf(
    "token",
    "access_token",
    "auth",
    "authorization",
    "apikey",
    "api_key",
    "expires",
    "expire",
    "expiry",
    "exp",
    "e",
    "st",
    "ts",
    "timestamp",
    "sig",
    "signature",
    "hmac",
    "policy",
    "key-pair-id",
    "hdnts",
    "hdnea",
    "ttl",
    "verify",
)

private val VolatileUrlQueryParameterPrefixes = listOf("x-amz-", "x-goog-")

/**
 * Least-recently-used map of computed hashes by file identity, serialisable for the profile's
 * local storage. Not thread-safe; [SubtitleVideoHashRepository] guards it.
 */
internal class SubtitleVideoHashCache(
    private val maxEntries: Int = DefaultMaxEntries,
) {
    private val entries = LinkedHashMap<String, OpenSubtitlesVideoHash>()

    val size: Int get() = entries.size

    fun get(identity: String): OpenSubtitlesVideoHash? {
        val value = entries.remove(identity) ?: return null
        entries[identity] = value
        return value
    }

    /** Stores [value]; returns whether anything changed (so callers only persist when needed). */
    fun put(identity: String, value: OpenSubtitlesVideoHash): Boolean {
        val previous = entries.remove(identity)
        entries[identity] = value
        while (entries.size > maxEntries) {
            entries.remove(entries.keys.first())
        }
        return previous != value
    }

    fun clear() {
        entries.clear()
    }

    fun encode(): String = json.encodeToString(
        entries.map { (identity, value) ->
            PersistedVideoHash(identity = identity, hash = value.hash, fileSize = value.fileSize)
        },
    )

    @Serializable
    private data class PersistedVideoHash(
        @SerialName("k") val identity: String,
        @SerialName("h") val hash: String,
        @SerialName("s") val fileSize: Long,
    )

    companion object {
        const val DefaultMaxEntries = 300
        private val json = Json { ignoreUnknownKeys = true }
        private val hashPattern = Regex("^[0-9a-f]{16}$")

        fun decode(payload: String?, maxEntries: Int = DefaultMaxEntries): SubtitleVideoHashCache {
            val cache = SubtitleVideoHashCache(maxEntries)
            if (payload.isNullOrBlank()) return cache
            val persisted = runCatching {
                json.decodeFromString<List<PersistedVideoHash>>(payload)
            }.getOrNull().orEmpty()
            persisted.forEach { entry ->
                if (entry.identity.isNotBlank() && entry.fileSize > 0L && hashPattern.matches(entry.hash)) {
                    cache.put(entry.identity, OpenSubtitlesVideoHash(hash = entry.hash, fileSize = entry.fileSize))
                }
            }
            return cache
        }
    }
}

/**
 * Runs one computation per key however many callers ask for it, and cancels it once every caller
 * has stopped waiting (e.g. the player switched to another source before the hash was known).
 */
internal class SharedSuspendComputations<K : Any, V>(
    private val scope: CoroutineScope,
) {
    private class Entry<V>(val deferred: Deferred<V>) {
        var waiters = 0
    }

    private val lock = SynchronizedObject()
    private val entries = HashMap<K, Entry<V>>()

    val activeKeys: Set<K>
        get() = synchronized(lock) { entries.keys.toSet() }

    suspend fun await(key: K, compute: suspend () -> V): V {
        val entry = synchronized(lock) {
            val current = entries[key]?.takeUnless { it.deferred.isCancelled }
                ?: Entry(scope.async(start = CoroutineStart.LAZY) { compute() }).also { entries[key] = it }
            current.waiters += 1
            current
        }
        entry.deferred.start()
        try {
            return entry.deferred.await()
        } finally {
            val abandoned = synchronized(lock) {
                entry.waiters -= 1
                if (entry.waiters == 0) {
                    if (entries[key] === entry) entries.remove(key)
                    true
                } else {
                    false
                }
            }
            if (abandoned && !entry.deferred.isCompleted) entry.deferred.cancel()
        }
    }
}
