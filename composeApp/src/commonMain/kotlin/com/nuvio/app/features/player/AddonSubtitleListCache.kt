package com.nuvio.app.features.player

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The cache key of a subtitle list: the exact addon request URLs, so it changes with the enabled
 * addons, the video id and the `videoHash`/`videoSize`/`filename` extras.
 */
internal fun addonSubtitleListCacheKey(requests: List<SubtitleAddonRequest>): String =
    requests.joinToString(separator = "\n") { it.url }

/**
 * Session cache of addon subtitle lists plus in-flight prefetches. Only non-empty lists are kept,
 * so a failed or empty answer is asked again next time.
 */
internal class AddonSubtitleListCache(
    private val scope: CoroutineScope,
    private val maxEntries: Int = 24,
    private val ttl: Duration = 30.minutes,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private class Entry(val subtitles: List<AddonSubtitle>, val storedAt: TimeMark)

    private val lock = SynchronizedObject()
    private val entries = LinkedHashMap<String, Entry>()
    private val prefetches = HashMap<String, Deferred<List<AddonSubtitle>>>()

    fun cached(key: String): List<AddonSubtitle>? = synchronized(lock) {
        val entry = entries[key] ?: return@synchronized null
        if (entry.storedAt.elapsedNow() > ttl) {
            entries.remove(key)
            null
        } else {
            entry.subtitles
        }
    }

    fun store(key: String, subtitles: List<AddonSubtitle>) {
        if (subtitles.isEmpty()) return
        synchronized(lock) {
            entries.remove(key)
            entries[key] = Entry(subtitles, timeSource.markNow())
            while (entries.size > maxEntries) {
                entries.remove(entries.keys.first())
            }
        }
    }

    /** A prefetch of [key] that is still running, so a fetch can wait for it instead of repeating it. */
    fun inFlight(key: String): Deferred<List<AddonSubtitle>>? = synchronized(lock) {
        prefetches[key]?.takeIf { it.isActive }
    }

    /** Starts loading [key] in the background unless it is cached or already loading. */
    fun prefetch(key: String, load: suspend () -> List<AddonSubtitle>): Deferred<List<AddonSubtitle>> {
        cached(key)?.let { return CompletableDeferred(it) }
        return synchronized(lock) {
            prefetches[key]?.takeIf { it.isActive }?.let { return@synchronized it }
            val deferred = scope.async {
                val subtitles = try {
                    load()
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    emptyList()
                }
                store(key, subtitles)
                synchronized(lock) { prefetches.remove(key) }
                subtitles
            }
            prefetches[key] = deferred
            deferred
        }
    }

    fun clear() {
        val running = synchronized(lock) {
            entries.clear()
            prefetches.values.toList().also { prefetches.clear() }
        }
        running.forEach { it.cancel() }
    }
}

/** Awaits a prefetch; a failed or cancelled prefetch yields an empty list. */
internal suspend fun Deferred<List<AddonSubtitle>>.awaitOrEmpty(): List<AddonSubtitle> =
    try {
        await()
    } catch (error: CancellationException) {
        // The prefetch itself was cancelled; only propagate when the caller is being cancelled.
        currentCoroutineContext().ensureActive()
        emptyList()
    }
