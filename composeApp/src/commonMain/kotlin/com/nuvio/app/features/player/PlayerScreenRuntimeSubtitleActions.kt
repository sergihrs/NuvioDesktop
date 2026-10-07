package com.nuvio.app.features.player

import androidx.compose.runtime.snapshotFlow
import com.nuvio.app.core.i18n.localizedNoSubtitleLinesFound
import com.nuvio.app.core.i18n.localizedSubtitleLinesLoadError
import com.nuvio.app.features.addons.httpGetTextWithHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal fun PlayerScreenRuntime.fetchAddonSubtitlesForActiveItem() {
    if (activeSourceUrl.startsWith("file:") && externalSubtitles.isNotEmpty()) {
        SubtitleRepository.clear()
        return
    }
    val type = activeAddonSubtitleType.takeIf { it.isNotBlank() } ?: return
    val videoId = activeVideoId?.takeIf { it.isNotBlank() } ?: return
    SubtitleRepository.fetchAddonSubtitles(type, videoId, currentSubtitleVideoParams())
}

/** The URL the player actually reads, if its OpenSubtitles hash can be computed over HTTP. */
internal val PlayerScreenRuntime.subtitleHashSourceUrl: String?
    get() {
        val url = if (activeTorrentInfoHash != null) p2pResolvedSourceUrl else activeSourceUrl
        return url?.takeIf(SubtitleVideoHashRepository::canHash)
    }

/**
 * The `videoHash`/`videoSize`/`filename` extras for subtitle requests: the stream's behavior
 * hints, plus the hash computed from the playing file when the addon did not provide one.
 */
internal fun PlayerScreenRuntime.currentSubtitleVideoParams(): SubtitleVideoParams? =
    resolveSubtitleVideoParams(
        streamHints = activeSubtitleVideoParams,
        computedHash = SubtitleVideoHashRepository.cachedHash(subtitleHashSourceUrl),
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
 * Hashes the playing file once playback has started (so the tail read does not compete with
 * startup buffering), then refetches addon subtitles with the hash if it changes the request.
 */
internal suspend fun PlayerScreenRuntime.computeSubtitleVideoHashAndRefresh(url: String) {
    if (activeSubtitleVideoParams?.videoHash != null) return
    snapshotFlow { playerControllerSourceUrl == url && !playbackSnapshot.isLoading }.first { it }
    val deferred = SubtitleVideoHashRepository.hashAsync(url, activeSourceHeaders) ?: return
    val hashed = try {
        deferred.await() != null
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        false
    }
    if (!hashed || subtitleHashSourceUrl != url) return
    refreshAddonSubtitlesForVideoParams()
}

private fun PlayerScreenRuntime.refreshAddonSubtitlesForVideoParams() {
    if (activeSourceUrl.startsWith("file:") && externalSubtitles.isNotEmpty()) return
    val fetchKey = addonSubtitleFetchKey ?: return
    // Before the first fetch for this item, that fetch picks up the hash itself.
    if (autoFetchedAddonSubtitlesForKey != fetchKey) return
    val type = activeAddonSubtitleType.takeIf { it.isNotBlank() } ?: return
    val videoId = activeVideoId?.takeIf { it.isNotBlank() } ?: return
    val params = currentSubtitleVideoParams() ?: return
    if (SubtitleRepository.lastRequestedVideoParams == params) return
    SubtitleRepository.refreshAddonSubtitles(type, videoId, params)
}

internal fun PlayerScreenRuntime.setSubtitleDelay(delayMs: Int) {
    val clamped = delayMs.coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
    subtitleDelayMs = clamped
    PlayerTrackPreferenceStorage.saveSubtitleDelayMs(playbackSession.videoId, clamped)
    playerController?.setSubtitleDelayMs(clamped)
}

internal fun PlayerScreenRuntime.loadSubtitleAutoSyncCues(force: Boolean = false) {
    val subtitle = selectedAddonSubtitle ?: return
    if (!force && subtitleAutoSyncState.cues.isNotEmpty()) return
    subtitleAutoSyncState = subtitleAutoSyncState.copy(isLoading = true, errorMessage = null)
    scope.launch {
        val result = runCatching {
            val body = httpGetTextWithHeaders(
                url = subtitle.url,
                headers = sanitizePlaybackHeaders(activeSourceHeaders),
            )
            PlayerSubtitleCueParser.parse(body, subtitle.url)
        }
        result.fold(
            onSuccess = { cues ->
                subtitleAutoSyncState = subtitleAutoSyncState.copy(
                    cues = cues,
                    isLoading = false,
                    errorMessage = if (cues.isEmpty()) localizedNoSubtitleLinesFound() else null,
                )
            },
            onFailure = { error ->
                subtitleAutoSyncState = subtitleAutoSyncState.copy(
                    isLoading = false,
                    errorMessage = error.message ?: localizedSubtitleLinesLoadError(),
                )
            },
        )
    }
}

internal fun PlayerScreenRuntime.captureSubtitleAutoSyncTime() {
    subtitleAutoSyncState = subtitleAutoSyncState.copy(
        capturedPositionMs = playbackSnapshot.positionMs.coerceAtLeast(0L),
        errorMessage = null,
    )
    loadSubtitleAutoSyncCues()
}

internal fun PlayerScreenRuntime.applySubtitleAutoSyncCue(cue: SubtitleSyncCue) {
    val capturedPositionMs = subtitleAutoSyncState.capturedPositionMs ?: return
    val newDelayMs = (capturedPositionMs - cue.startTimeMs - SUBTITLE_AUTO_SYNC_REACTION_COMPENSATION_MS)
        .toInt()
        .coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
    setSubtitleDelay(newDelayMs)
}
