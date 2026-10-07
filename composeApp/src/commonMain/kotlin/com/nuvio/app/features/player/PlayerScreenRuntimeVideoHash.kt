package com.nuvio.app.features.player

import androidx.compose.runtime.snapshotFlow
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How long an automatic addon subtitle pick waits for the hash-matched list once the addon list
 * and playback are ready. Keeps the auto pick from choosing one subtitle and swapping it for the
 * hash match a moment later.
 */
internal const val VideoHashAutoPickHoldMs = 4_000L

/** Upper bound for the whole hold, whatever the hash or addons are doing. */
internal const val VideoHashAutoPickMaxHoldMs = 45_000L

/** The URL the player actually reads, if its OpenSubtitles hash can be computed over HTTP. */
internal val PlayerScreenRuntime.subtitleHashSourceUrl: String?
    get() {
        val url = if (activeTorrentInfoHash != null) p2pResolvedSourceUrl else activeSourceUrl
        return url?.takeIf(SubtitleVideoHashRepository::canHash)
    }

/** Stable identity of the playing file for the persistent hash cache. */
internal val PlayerScreenRuntime.subtitleHashIdentity: String?
    get() = subtitleVideoHashIdentity(
        torrentInfoHash = activeTorrentInfoHash,
        torrentFileIdx = activeTorrentFileIdx,
        torrentFilename = activeTorrentFilename,
        sourceUrl = activeSourceUrl.takeIf { activeTorrentInfoHash == null },
    )

/**
 * The `videoHash`/`videoSize`/`filename` extras for subtitle requests: the stream's behavior
 * hints, plus the hash of the playing file when the addon did not provide one. A remembered hash
 * is used right away (torrent files are immutable; an HTTP source is re-hashed and corrected).
 */
internal fun PlayerScreenRuntime.currentSubtitleVideoParams(): SubtitleVideoParams? =
    resolveSubtitleVideoParams(
        streamHints = activeSubtitleVideoParams,
        computedHash = rememberedSubtitleVideoHash(
            identity = subtitleHashIdentity,
            hintedFileSize = activeSubtitleVideoParams?.videoSize,
        ),
    )

internal fun resolveSubtitleVideoParams(
    streamHints: SubtitleVideoParams?,
    computedHash: OpenSubtitlesVideoHash?,
): SubtitleVideoParams? {
    val hints = streamHints?.normalized() ?: SubtitleVideoParams()
    // The hash covers the file size, so a computed hash is only sent with its own size.
    val params = if (hints.videoHash == null && computedHash != null) {
        hints.copy(videoHash = computedHash.hash, videoSize = computedHash.fileSize)
    } else {
        hints
    }
    return params.normalized().takeUnless { it.isEmpty }
}

/**
 * The remembered hash for [identity]: a verified one (torrent file, or hashed in this session) as
 * is, an older HTTP one only while the stream's size hint does not show it is another file.
 */
internal fun rememberedSubtitleVideoHash(
    identity: String?,
    hintedFileSize: Long?,
    cachedHash: (String?) -> OpenSubtitlesVideoHash? = SubtitleVideoHashRepository::cachedHash,
    verifiedHash: (String?) -> OpenSubtitlesVideoHash? = SubtitleVideoHashRepository::verifiedHash,
): OpenSubtitlesVideoHash? {
    verifiedHash(identity)?.let { return it }
    val cached = cachedHash(identity) ?: return null
    val hinted = hintedFileSize?.takeIf { it > 0L }
    return cached.takeIf { hinted == null || hinted == it.fileSize }
}

/**
 * Hashes the playing file as soon as its URL is known, in parallel with the player opening it,
 * then refetches addon subtitles with the hash if that changes the request.
 *
 * For torrents the two reads cost (almost) nothing extra: for every read, nuvio-engine already
 * schedules the pieces at the read position and the last 2 MiB of the file (where containers
 * keep their index) at top priority, and the player reads the file head at open anyway.
 * A hash that cannot be read yet (e.g. a slow swarm) is tried once more after playback started.
 */
internal suspend fun PlayerScreenRuntime.computeSubtitleVideoHashAndRefresh(url: String) {
    if (activeSubtitleVideoParams?.videoHash != null) return
    val identity = subtitleHashIdentity
    if (SubtitleVideoHashRepository.verifiedHash(identity) != null) {
        // Known from an earlier playback: the first subtitle fetch already carried it.
        refreshAddonSubtitlesForVideoParams()?.join()
        return
    }
    val expectedFileSize = activeSubtitleVideoParams?.videoSize
        ?: SubtitleVideoHashRepository.cachedHash(identity)?.fileSize
    val headers = activeSourceHeaders
    if (computeSubtitleVideoHashMarkingPending(url, headers, identity, expectedFileSize) != null) return
    snapshotFlow { playerControllerSourceUrl == url && !playbackSnapshot.isLoading }.first { it }
    if (subtitleHashSourceUrl != url) return
    computeSubtitleVideoHashMarkingPending(url, headers, identity, expectedFileSize)
}

private suspend fun PlayerScreenRuntime.computeSubtitleVideoHashMarkingPending(
    url: String,
    headers: Map<String, String>,
    identity: String?,
    expectedFileSize: Long?,
): OpenSubtitlesVideoHash? {
    subtitleVideoHashPending = true
    try {
        val hash = SubtitleVideoHashRepository.computeHash(
            url = url,
            headers = headers,
            identity = identity,
            expectedFileSize = expectedFileSize,
        ) ?: return null
        if (subtitleHashSourceUrl == url) {
            refreshAddonSubtitlesForVideoParams()?.join()
        }
        return hash
    } finally {
        subtitleVideoHashPending = false
    }
}

private fun PlayerScreenRuntime.refreshAddonSubtitlesForVideoParams(): Job? {
    if (activeSourceUrl.startsWith("file:") && externalSubtitles.isNotEmpty()) return null
    val fetchKey = addonSubtitleFetchKey ?: return null
    // Before the first fetch for this item, that fetch picks up the hash itself.
    if (autoFetchedAddonSubtitlesForKey != fetchKey) return null
    val type = activeAddonSubtitleType.takeIf { it.isNotBlank() } ?: return null
    val videoId = activeVideoId?.takeIf { it.isNotBlank() } ?: return null
    val params = currentSubtitleVideoParams() ?: return null
    if (SubtitleRepository.lastRequestedVideoParams == params) return null
    return SubtitleRepository.refreshAddonSubtitles(type, videoId, params)
}

/**
 * Keeps [PlayerScreenRuntime.holdAddonSubtitleAutoPickForVideoHash] set while the hash (and the
 * refetch with it) is pending, but at most [VideoHashAutoPickHoldMs] after both the addon
 * subtitle list and playback are ready, so a slow or failing hash never blocks subtitles.
 */
internal suspend fun PlayerScreenRuntime.holdAddonSubtitleAutoPickWhileVideoHashPending(pending: Boolean) {
    if (!pending) {
        holdAddonSubtitleAutoPickForVideoHash = false
        return
    }
    holdAddonSubtitleAutoPickForVideoHash = true
    try {
        withTimeoutOrNull(VideoHashAutoPickMaxHoldMs) {
            combine(
                SubtitleRepository.isLoading,
                SubtitleRepository.addonSubtitles,
                SubtitleRepository.error,
            ) { loading, subtitles, error ->
                !loading && (subtitles.isNotEmpty() || error != null)
            }.first { it }
            snapshotFlow { !playbackSnapshot.isLoading }.first { it }
            delay(VideoHashAutoPickHoldMs)
        }
    } finally {
        holdAddonSubtitleAutoPickForVideoHash = false
    }
}

/**
 * Prepares subtitles for the next episode once its stream has been chosen (the autoplay
 * countdown), so they are ready the moment it starts:
 * - HTTP sources (debrid): hashes the file with two range reads now;
 * - torrents: uses a remembered hash only. nuvio-engine streams one file at a time: preparing
 *   another file pauses the other torrents and, for a season pack, deprioritises the episode
 *   that is still playing, so the next torrent is not touched before the switch;
 * then prefetches the addon subtitle list with the same extras the player will ask for, which
 * SubtitleRepository hands over without a new request after the switch.
 */
internal fun PlayerScreenRuntime.prepareNextEpisodeSubtitles(stream: StreamItem, episode: MetaVideo) {
    val type = activeAddonSubtitleType.takeIf { it.isNotBlank() } ?: return
    val videoId = episode.id.takeIf { it.isNotBlank() } ?: return
    val hints = stream.behaviorHints.toSubtitleVideoParams()
    val torrentInfoHash = stream.p2pInfoHash?.takeIf { isP2pStream(stream) }
    val url = stream.playableDirectUrl?.takeIf { torrentInfoHash == null }
    val identity = subtitleVideoHashIdentity(
        torrentInfoHash = torrentInfoHash,
        torrentFileIdx = stream.p2pFileIdx,
        torrentFilename = stream.behaviorHints.filename,
        sourceUrl = url,
    )
    val headers = sanitizePlaybackHeaders(stream.behaviorHints.proxyHeaders?.request)
    nextEpisodeSubtitlePrepJob?.cancel()
    nextEpisodeSubtitlePrepJob = scope.launch {
        val params = nextEpisodeSubtitleVideoParams(
            hints = hints,
            identity = identity,
            url = url,
            headers = headers,
        )
        SubtitleRepository.prefetchAddonSubtitles(type, videoId, params)
    }
}

/**
 * The subtitle extras the player will send once the next episode plays, computed ahead of time:
 * hashing an HTTP source now stores the hash under the same identity the player looks up after
 * the switch, so its first subtitle request matches the prefetched one.
 */
internal suspend fun nextEpisodeSubtitleVideoParams(
    hints: SubtitleVideoParams,
    identity: String?,
    url: String?,
    headers: Map<String, String>,
    computeHash: suspend (url: String, headers: Map<String, String>, identity: String?, expectedFileSize: Long?) ->
        OpenSubtitlesVideoHash? = { hashUrl, hashHeaders, hashIdentity, size ->
            SubtitleVideoHashRepository.computeHash(hashUrl, hashHeaders, hashIdentity, size)
        },
    canHash: (String?) -> Boolean = SubtitleVideoHashRepository::canHash,
    verifiedHash: (String?) -> OpenSubtitlesVideoHash? = SubtitleVideoHashRepository::verifiedHash,
    cachedHash: (String?) -> OpenSubtitlesVideoHash? = SubtitleVideoHashRepository::cachedHash,
): SubtitleVideoParams? {
    val computed = when {
        hints.videoHash != null -> null
        verifiedHash(identity) != null -> null
        url != null && canHash(url) -> computeHash(url, headers, identity, hints.videoSize)
        else -> null
    }
    return resolveSubtitleVideoParams(
        streamHints = hints,
        computedHash = computed ?: rememberedSubtitleVideoHash(identity, hints.videoSize, cachedHash, verifiedHash),
    )
}
