package com.nuvio.app.features.player

import androidx.compose.ui.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerSubtitleTrackSelectionSyncTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun cleanup() {
        scope.cancel()
    }

    @Test
    fun playerDefaultTrackTurnedOffWithoutPreferredLanguageIsNotShownAsSelected() {
        // The player auto-selects the default-flagged embedded track when the file opens.
        val controller = RecordingController(
            embeddedSubtitleTracks = listOf(
                SubtitleTrack(index = 0, id = "3", label = "en (subrip)", language = "eng", isSelected = true),
                SubtitleTrack(index = 1, id = "4", label = "ja (subrip)", language = "jpn"),
            ),
        )
        val runtime = runtime(controller)

        runtime.refreshTracks()

        assertEquals(listOf(-1), controller.subtitleSelections)
        assertTrue(runtime.subtitleTracks.none { it.isSelected })
        assertEquals(
            null,
            selectedSubtitleOptionId(runtime.subtitleTracks, runtime.selectedSubtitleIndex, runtime.selectedAddonSubtitle),
        )
        assertEquals(
            SubtitleOffLanguageKey,
            selectedSubtitleLanguageKey(runtime.subtitleTracks, runtime.selectedSubtitleIndex, runtime.selectedAddonSubtitle),
        )
    }

    private fun runtime(controller: RecordingController) = PlayerScreenRuntime(
        PlayerScreenArgs(
            profileId = 1,
            title = "Series",
            sourceUrl = "https://example.com/episode-1.mkv",
            sourceAudioUrl = null,
            sourceHeaders = emptyMap(),
            sourceResponseHeaders = emptyMap(),
            streamType = null,
            providerName = "Test",
            streamTitle = "Episode 1",
            streamSubtitle = null,
            initialBingeGroup = null,
            pauseDescription = null,
            onBack = {},
            onOpenInExternalPlayer = null,
            onOpenExternalUrl = null,
            modifier = Modifier,
            logo = null,
            poster = null,
            background = null,
            seasonNumber = 1,
            episodeNumber = 1,
            episodeTitle = "Episode 1",
            episodeThumbnail = null,
            contentType = "series",
            videoId = "tt2026:1:1",
            // Blank so no stored track preference is read or written.
            parentMetaId = "",
            parentMetaType = "series",
            providerAddonId = "test",
            torrentInfoHash = null,
            torrentFileIdx = null,
            torrentFilename = null,
            torrentTrackers = emptyList(),
            initialPositionMs = 0L,
            initialProgressFraction = null,
        ),
    ).apply {
        scope = this@PlayerSubtitleTrackSelectionSyncTest.scope
        playerSettingsUiState = PlayerSettingsUiState(preferredSubtitleLanguage = SubtitleLanguageOption.NONE)
        playbackSnapshot = PlayerPlaybackSnapshot(isLoading = false)
        playerController = controller
    }

    private class RecordingController(
        private var embeddedSubtitleTracks: List<SubtitleTrack>,
    ) : PlayerEngineController {
        val subtitleSelections = mutableListOf<Int>()

        override fun selectSubtitleTrack(index: Int) {
            subtitleSelections += index
            embeddedSubtitleTracks = embeddedSubtitleTracks.map { it.copy(isSelected = it.index == index) }
        }

        override fun getAudioTracks() = emptyList<AudioTrack>()
        override fun getSubtitleTracks() = embeddedSubtitleTracks
        override fun setSubtitleUri(url: String) = Unit
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun seekBy(offsetMs: Long) = Unit
        override fun retry() = Unit
        override fun setPlaybackSpeed(speed: Float) = Unit
        override fun applyAudioLanguagePreferences(languages: List<String>) = Unit
        override fun selectAudioTrack(index: Int) = Unit
        override fun clearExternalSubtitle() = Unit
        override fun clearExternalSubtitleAndSelect(trackIndex: Int) = Unit
    }
}
