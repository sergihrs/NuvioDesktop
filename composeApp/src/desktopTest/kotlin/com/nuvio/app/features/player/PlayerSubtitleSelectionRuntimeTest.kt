package com.nuvio.app.features.player

import androidx.compose.ui.Modifier
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerSubtitleSelectionRuntimeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val englishEpisodeOne = subtitle(episode = 1, language = "en", addonName = "Subs")
    private val frenchEpisodeOne = subtitle(episode = 1, language = "fr", addonName = "Subs")
    private val englishEpisodeTwo = subtitle(episode = 2, language = "en", addonName = "Subs")
    private val frenchEpisodeTwo = subtitle(episode = 2, language = "fr", addonName = "Subs")
    private val otherFrenchEpisodeTwo = subtitle(episode = 2, language = "fr", addonName = "Other")

    @Before
    fun requireIsolatedStorage() {
        // These tests write player preferences through DesktopStorage; never let them touch real user data.
        assumeTrue("DesktopStorage is not isolated in a temporary directory", storageIsIsolated())
        DesktopStorage.wipe()
    }

    @After
    fun cleanup() {
        scope.cancel()
        if (storageIsIsolated()) DesktopStorage.wipe()
    }

    @Test
    fun desktopControlsAddonPickIsNotReplacedByAutoSelection() {
        val runtime = runtime(bingeGroup = null)
        val controller = runtime.playerController as RecordingController
        runtime.refreshTracks()
        assertEquals(listOf(englishEpisodeOne.url), controller.subtitleUrls)

        val frenchIndex = runtime.visibleAddonSubtitles.indexOfFirst { it.url == frenchEpisodeOne.url }
        runtime.handlePlayerControlsEvent("selectAddonSubtitle", frenchIndex.toDouble())
        runtime.refreshTracks()

        assertTrue(runtime.isUserExplicitSubtitleSelection)
        assertEquals(frenchEpisodeOne.url, runtime.selectedAddonSubtitle?.url)
        assertEquals(listOf(englishEpisodeOne.url, frenchEpisodeOne.url), controller.subtitleUrls)
    }

    @Test
    fun sameBingeGroupCarriesAddonAndLanguageIntoTheNextEpisode() {
        val runtime = runtime(bingeGroup = GROUP_A)
        runtime.persistAddonSubtitlePreference(frenchEpisodeOne)

        val controller = advanceToEpisodeTwo(runtime, bingeGroup = GROUP_A)
        runtime.addonSubtitles = listOf(otherFrenchEpisodeTwo, englishEpisodeTwo, frenchEpisodeTwo)
        runtime.refreshTracks()

        assertEquals(listOf(frenchEpisodeTwo.url), controller.subtitleUrls)
        assertTrue(runtime.isUserExplicitSubtitleSelection)
    }

    @Test
    fun carriedSubtitleWaitsForTheSavedAddon() {
        val runtime = runtime(bingeGroup = GROUP_A)
        runtime.persistAddonSubtitlePreference(frenchEpisodeOne)

        val controller = advanceToEpisodeTwo(runtime, bingeGroup = GROUP_A)
        runtime.addonSubtitles = listOf(otherFrenchEpisodeTwo, englishEpisodeTwo)
        runtime.isLoadingAddonSubtitles = true
        runtime.refreshTracks()

        assertTrue(controller.subtitleUrls.isEmpty())

        runtime.addonSubtitles = listOf(otherFrenchEpisodeTwo, englishEpisodeTwo, frenchEpisodeTwo)
        runtime.refreshTracks()

        assertEquals(listOf(frenchEpisodeTwo.url), controller.subtitleUrls)
    }

    @Test
    fun missingSavedAddonFallsBackToThePreferredLanguage() {
        val runtime = runtime(bingeGroup = GROUP_A)
        runtime.persistAddonSubtitlePreference(frenchEpisodeOne)

        val controller = advanceToEpisodeTwo(runtime, bingeGroup = GROUP_A)
        runtime.addonSubtitles = listOf(otherFrenchEpisodeTwo, englishEpisodeTwo)
        runtime.refreshTracks()

        assertEquals(listOf(englishEpisodeTwo.url), controller.subtitleUrls)
    }

    @Test
    fun anotherBingeGroupKeepsThePreferredLanguageSelection() {
        val runtime = runtime(bingeGroup = GROUP_A)
        runtime.persistAddonSubtitlePreference(frenchEpisodeOne)

        val controller = advanceToEpisodeTwo(runtime, bingeGroup = GROUP_B)
        runtime.addonSubtitles = listOf(englishEpisodeTwo, frenchEpisodeTwo)
        runtime.refreshTracks()

        assertEquals(listOf(englishEpisodeTwo.url), controller.subtitleUrls)
    }

    @Test
    fun disabledSettingKeepsThePreferredLanguageAndZeroDelay() {
        val runtime = runtime(bingeGroup = GROUP_A)
        runtime.persistAddonSubtitlePreference(frenchEpisodeOne)
        runtime.setSubtitleDelay(1_500)

        val controller = advanceToEpisodeTwo(runtime, bingeGroup = GROUP_A)
        runtime.playerSettingsUiState = runtime.playerSettingsUiState.copy(keepSubtitlesAcrossEpisodes = false)
        runtime.addonSubtitles = listOf(englishEpisodeTwo, frenchEpisodeTwo)
        runtime.refreshTracks()

        assertEquals(listOf(englishEpisodeTwo.url), controller.subtitleUrls)
        assertEquals(0, runtime.loadStartingSubtitleDelayMs())
    }

    @Test
    fun subtitleDelayCarriesOnlyWithinTheSameBingeGroup() {
        val runtime = runtime(bingeGroup = GROUP_A)
        runtime.setSubtitleDelay(1_500)

        advanceToEpisodeTwo(runtime, bingeGroup = GROUP_A)
        assertEquals(1_500, runtime.loadStartingSubtitleDelayMs())

        runtime.currentStreamBingeGroup = GROUP_B
        assertEquals(0, runtime.loadStartingSubtitleDelayMs())
    }

    @Test
    fun episodeDelayWinsOverTheCarriedDelay() {
        val runtime = runtime(bingeGroup = GROUP_A)
        advanceToEpisodeTwo(runtime, bingeGroup = GROUP_A)
        runtime.setSubtitleDelay(-400)

        val episodeOne = runtime(bingeGroup = GROUP_A)
        episodeOne.setSubtitleDelay(1_500)

        val reopenedEpisodeTwo = runtime(bingeGroup = GROUP_A)
        advanceToEpisodeTwo(reopenedEpisodeTwo, bingeGroup = GROUP_A)
        assertEquals(-400, reopenedEpisodeTwo.loadStartingSubtitleDelayMs())
    }

    private fun advanceToEpisodeTwo(
        runtime: PlayerScreenRuntime,
        bingeGroup: String?,
        embeddedSubtitleTracks: List<SubtitleTrack> = emptyList(),
    ): RecordingController {
        runtime.switchToEpisodeStream(
            StreamItem(
                url = "https://example.com/episode-2.mp4",
                addonName = "Test",
                addonId = "test",
                behaviorHints = StreamBehaviorHints(bingeGroup = bingeGroup),
            ),
            MetaVideo(id = "tt2026:1:2", title = "Episode 2", season = 1, episode = 2),
        )
        runtime.resetIdentityStateIfNeeded()
        runtime.addonSubtitles = listOf(englishEpisodeTwo)
        runtime.playbackSnapshot = PlayerPlaybackSnapshot(isLoading = false)
        return RecordingController(embeddedSubtitleTracks).also {
            runtime.playerController = it
            runtime.playerControllerSourceUrl = runtime.activeSourceUrl
        }
    }

    @Test
    fun rememberedEmbeddedTrackIsPickedOnTheNextEpisodeWithoutAPreferredLanguage() {
        val runtime = runtime(bingeGroup = GROUP_A, preferredSubtitleLanguage = SubtitleLanguageOption.NONE)
        runtime.playerController = RecordingController(embeddedTracks())
        runtime.refreshTracks()
        runtime.handlePlayerControlsEvent("selectBuiltInSubtitleTrack", 1.0)

        val controller = advanceToEpisodeTwo(runtime, bingeGroup = GROUP_A, embeddedSubtitleTracks = embeddedTracks())
        runtime.refreshTracks()

        assertEquals(listOf(1), controller.subtitleSelections)
        assertEquals(1, runtime.selectedSubtitleIndex)
        assertTrue(runtime.isUserExplicitSubtitleSelection)
    }

    @Test
    fun rememberedOffBeatsThePreferredLanguage() {
        val runtime = runtime(bingeGroup = GROUP_A)
        runtime.handlePlayerControlsEvent("selectBuiltInSubtitleTrack", -1.0)

        val controller = advanceToEpisodeTwo(runtime, bingeGroup = GROUP_A, embeddedSubtitleTracks = embeddedTracks())
        runtime.refreshTracks()

        assertTrue(controller.subtitleUrls.isEmpty())
        assertEquals(listOf(-1), controller.subtitleSelections)
        assertEquals(-1, runtime.selectedSubtitleIndex)
    }

    @Test
    fun choiceAndDelaySurviveARestartAndEachReleaseKeepsItsOwn() {
        val episodeOne = runtime(bingeGroup = GROUP_A)
        val frenchIndex = episodeOne.visibleAddonSubtitles.indexOfFirst { it.url == frenchEpisodeOne.url }
        episodeOne.handlePlayerControlsEvent("selectAddonSubtitle", frenchIndex.toDouble())
        episodeOne.handlePlayerControlsEvent("subtitleDelayDelta", 1_000.0)

        // Another release of the same episode has no record: auto-selection.
        val otherRelease = runtime(bingeGroup = GROUP_B)
        val otherReleaseController = otherRelease.playerController as RecordingController
        otherRelease.refreshTracks()
        assertEquals(listOf(englishEpisodeOne.url), otherReleaseController.subtitleUrls)

        // Back on the first release, the same episode reopens the exact subtitle.
        val reopened = runtime(bingeGroup = GROUP_A)
        val reopenedController = reopened.playerController as RecordingController
        reopened.refreshTracks()
        assertEquals(listOf(frenchEpisodeOne.url), reopenedController.subtitleUrls)

        // A fresh player on the next episode (Continue Watching after a restart).
        val episodeTwo = runtime(bingeGroup = GROUP_A, episode = 2)
        episodeTwo.addonSubtitles = listOf(englishEpisodeTwo, otherFrenchEpisodeTwo, frenchEpisodeTwo)
        val episodeTwoController = episodeTwo.playerController as RecordingController
        episodeTwo.refreshTracks()
        assertEquals(listOf(frenchEpisodeTwo.url), episodeTwoController.subtitleUrls)
        assertEquals(1_000, episodeTwo.loadStartingSubtitleDelayMs())
    }

    @Test
    fun automaticPicksNeverOverwriteTheRememberedChoice() {
        val episodeOne = runtime(bingeGroup = GROUP_A)
        episodeOne.persistAddonSubtitlePreference(frenchEpisodeOne)

        val episodeTwo = runtime(bingeGroup = GROUP_A, episode = 2)
        episodeTwo.addonSubtitles = listOf(englishEpisodeTwo)
        episodeTwo.refreshTracks()
        assertEquals(englishEpisodeTwo.url, episodeTwo.selectedAddonSubtitle?.url)

        val remembered = PlayerTrackPreferenceStorage.loadReleaseSubtitleChoice("tt2026", GROUP_A)
        assertEquals(PersistedSubtitleSelectionType.ADDON, remembered?.subtitleType)
        assertEquals("fr", remembered?.language)
    }

    @Test
    fun delayOnAnAutomaticPickRemembersThatSubtitle() {
        val runtime = runtime(bingeGroup = GROUP_A)
        runtime.refreshTracks()
        runtime.setSubtitleDelay(750)

        val remembered = PlayerTrackPreferenceStorage.loadReleaseSubtitleChoice("tt2026", GROUP_A)
        assertEquals(PersistedSubtitleSelectionType.ADDON, remembered?.subtitleType)
        assertEquals("en", remembered?.language)
        assertEquals(750, remembered?.delayMs)
    }

    @Test
    fun embeddedFullSubtitleBeatsAHashMatchedAddonSubtitle() {
        val runtime = runtime(bingeGroup = null)
        runtime.addonSubtitles = listOf(englishEpisodeOne.copy(isVideoHashMatch = true))
        val controller = RecordingController(
            listOf(
                SubtitleTrack(index = 0, id = "3", label = "English Signs (ass)", language = "eng"),
                SubtitleTrack(index = 1, id = "4", label = "en (subrip)", language = "eng"),
            ),
        )
        runtime.playerController = controller

        runtime.refreshTracks()

        assertEquals(listOf(1), controller.subtitleSelections)
        assertTrue(controller.subtitleUrls.isEmpty())
    }

    @Test
    fun lateHashMatchReplacesOnlyAnAutomaticPick() {
        val hashMatch = englishEpisodeTwo.copy(url = "https://subs.example/episode-2-en-hash.srt", isVideoHashMatch = true)

        val automatic = runtime(bingeGroup = GROUP_A, episode = 2)
        val automaticController = automatic.playerController as RecordingController
        automatic.refreshTracks()
        automatic.addonSubtitles = listOf(englishEpisodeTwo, hashMatch)
        automatic.tryReplaceAutoSelectedSubtitleWithVideoHashMatch()
        assertEquals(listOf(englishEpisodeTwo.url, hashMatch.url), automaticController.subtitleUrls)

        runtime(bingeGroup = GROUP_A).persistAddonSubtitlePreference(englishEpisodeOne)
        val remembered = runtime(bingeGroup = GROUP_A, episode = 2)
        val rememberedController = remembered.playerController as RecordingController
        remembered.refreshTracks()
        remembered.addonSubtitles = listOf(englishEpisodeTwo, hashMatch)
        remembered.tryReplaceAutoSelectedSubtitleWithVideoHashMatch()
        assertEquals(listOf(englishEpisodeTwo.url), rememberedController.subtitleUrls)
    }

    @Test
    fun hashMatchRestoredForANonHashPickStartsWithoutTheRememberedDelay() {
        val episodeOne = runtime(bingeGroup = GROUP_A)
        episodeOne.handlePlayerControlsEvent(
            "selectAddonSubtitle",
            episodeOne.visibleAddonSubtitles.indexOfFirst { it.url == englishEpisodeOne.url }.toDouble(),
        )
        episodeOne.handlePlayerControlsEvent("subtitleDelayDelta", 1_000.0)

        val hashMatch = englishEpisodeTwo.copy(url = "https://subs.example/episode-2-en-hash.srt", isVideoHashMatch = true)
        val episodeTwo = runtime(bingeGroup = GROUP_A, episode = 2)
        episodeTwo.addonSubtitles = listOf(englishEpisodeTwo, hashMatch)
        episodeTwo.subtitleDelayMs = episodeTwo.loadStartingSubtitleDelayMs()
        assertEquals(1_000, episodeTwo.subtitleDelayMs)
        episodeTwo.refreshTracks()

        assertEquals(hashMatch.url, episodeTwo.selectedAddonSubtitle?.url)
        assertEquals(0, episodeTwo.subtitleDelayMs)
        val remembered = PlayerTrackPreferenceStorage.loadReleaseSubtitleChoice("tt2026", GROUP_A)
        assertEquals(false, remembered?.isVideoHashMatch)
        assertEquals(1_000, remembered?.delayMs)

        // Adjusting the delay now records the hash-matched subtitle with that delay.
        episodeTwo.handlePlayerControlsEvent("subtitleDelayDelta", 250.0)
        val updated = PlayerTrackPreferenceStorage.loadReleaseSubtitleChoice("tt2026", GROUP_A)
        assertEquals(true, updated?.isVideoHashMatch)
        assertEquals(250, updated?.delayMs)
    }

    @Test
    fun hashMatchRestoredForAHashPickKeepsTheRememberedDelay() {
        val hashEpisodeOne = englishEpisodeOne.copy(isVideoHashMatch = true)
        val episodeOne = runtime(bingeGroup = GROUP_A)
        episodeOne.persistAddonSubtitlePreference(hashEpisodeOne)
        episodeOne.setSubtitleDelay(300)

        val hashMatch = englishEpisodeTwo.copy(isVideoHashMatch = true)
        val episodeTwo = runtime(bingeGroup = GROUP_A, episode = 2)
        episodeTwo.addonSubtitles = listOf(hashMatch)
        episodeTwo.subtitleDelayMs = episodeTwo.loadStartingSubtitleDelayMs()
        episodeTwo.refreshTracks()

        assertEquals(hashMatch.url, episodeTwo.selectedAddonSubtitle?.url)
        assertEquals(300, episodeTwo.subtitleDelayMs)
    }

    @Test
    fun episodeDelayWinsOverTheHashMatchReset() {
        runtime(bingeGroup = GROUP_A).apply {
            persistAddonSubtitlePreference(englishEpisodeOne)
            setSubtitleDelay(1_000)
        }
        // A delay saved on episode 2 itself (for example in an earlier session).
        PlayerTrackPreferenceStorage.saveSubtitleDelayMs("tt2026:1:2", -200)

        val episodeTwo = runtime(bingeGroup = GROUP_A, episode = 2)
        episodeTwo.addonSubtitles = listOf(englishEpisodeTwo.copy(isVideoHashMatch = true))
        episodeTwo.subtitleDelayMs = episodeTwo.loadStartingSubtitleDelayMs()
        episodeTwo.refreshTracks()

        assertEquals(-200, episodeTwo.subtitleDelayMs)
    }

    private fun embeddedTracks() = listOf(
        SubtitleTrack(index = 0, id = "3", label = "Signs", language = "eng"),
        SubtitleTrack(index = 1, id = "4", label = "en (subrip)", language = "eng"),
        SubtitleTrack(index = 2, id = "5", label = "ja (subrip)", language = "jpn"),
    )

    private fun runtime(
        bingeGroup: String?,
        episode: Int = 1,
        preferredSubtitleLanguage: String = "en",
    ) = PlayerScreenRuntime(
        PlayerScreenArgs(
            profileId = 1,
            title = "Series",
            sourceUrl = "https://example.com/episode-$episode.mp4",
            sourceAudioUrl = null,
            sourceHeaders = emptyMap(),
            sourceResponseHeaders = emptyMap(),
            streamType = null,
            providerName = "Test",
            streamTitle = "Episode $episode",
            streamSubtitle = null,
            initialBingeGroup = bingeGroup,
            pauseDescription = null,
            onBack = {},
            onOpenInExternalPlayer = null,
            onOpenExternalUrl = null,
            modifier = Modifier,
            logo = null,
            poster = null,
            background = null,
            seasonNumber = 1,
            episodeNumber = episode,
            episodeTitle = "Episode $episode",
            episodeThumbnail = null,
            contentType = "series",
            videoId = "tt2026:1:$episode",
            parentMetaId = "tt2026",
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
        scope = this@PlayerSubtitleSelectionRuntimeTest.scope
        playerSettingsUiState = PlayerSettingsUiState(preferredSubtitleLanguage = preferredSubtitleLanguage)
        addonSubtitles = if (episode == 1) listOf(englishEpisodeOne, frenchEpisodeOne) else listOf(englishEpisodeTwo)
        playbackSnapshot = PlayerPlaybackSnapshot(isLoading = false)
        playerController = RecordingController()
        resetIdentityStateIfNeeded()
    }

    private fun subtitle(episode: Int, language: String, addonName: String) = AddonSubtitle(
        id = "$addonName-$language-$episode",
        url = "https://${addonName.lowercase()}.example/episode-$episode-$language.srt",
        language = language,
        display = language,
        addonName = addonName,
    )

    private class RecordingController(
        private var embeddedSubtitleTracks: List<SubtitleTrack> = emptyList(),
    ) : PlayerEngineController {
        val subtitleUrls = mutableListOf<String>()
        val subtitleSelections = mutableListOf<Int>()

        override fun setSubtitleUri(url: String) {
            subtitleUrls += url
        }

        override fun selectSubtitleTrack(index: Int) {
            subtitleSelections += index
            embeddedSubtitleTracks = embeddedSubtitleTracks.map { it.copy(isSelected = it.index == index) }
        }

        override fun getAudioTracks() = emptyList<AudioTrack>()
        override fun getSubtitleTracks() = embeddedSubtitleTracks
        override fun play() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun seekBy(offsetMs: Long) = Unit
        override fun retry() = Unit
        override fun setPlaybackSpeed(speed: Float) = Unit
        override fun applyAudioLanguagePreferences(languages: List<String>) = Unit
        override fun selectAudioTrack(index: Int) = Unit
        override fun clearExternalSubtitle() = Unit
        override fun clearExternalSubtitleAndSelect(trackIndex: Int) = selectSubtitleTrack(trackIndex)
    }

    companion object {
        private const val GROUP_A = "addon|1080p|group-a"
        private const val GROUP_B = "addon|2160p|group-b"

        private val temporaryRoot: Path =
            Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()

        @BeforeClass
        @JvmStatic
        fun isolateUserHome() {
            // Used by DesktopStorage when no XDG_CONFIG_HOME/APPDATA override is present.
            System.setProperty("user.home", Files.createTempDirectory("nuvio-subtitle-runtime-test").toString())
        }

        private fun storageIsIsolated(): Boolean {
            val osName = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT)
            val configBase = when {
                osName.contains("win") -> System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                    ?: System.getProperty("user.home")
                osName.contains("mac") -> System.getProperty("user.home")
                else -> System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
                    ?: System.getProperty("user.home")
            }
            if (!Paths.get(configBase).toAbsolutePath().normalize().startsWith(temporaryRoot)) return false
            return DesktopStorage.rootDir.toAbsolutePath().normalize().startsWith(temporaryRoot)
        }
    }
}
