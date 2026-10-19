package com.nuvio.app.features.player

import androidx.compose.ui.Modifier
import com.nuvio.app.core.storage.DesktopStorage
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerVideoHashRuntimeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val english = AddonSubtitle(
        id = "subs-en",
        url = "https://subs.example/en.srt",
        language = "en",
        display = "en",
        addonName = "Subs",
    )

    @Before
    fun requireIsolatedStorage() {
        // These tests write the hash cache through DesktopStorage; never let them touch real user data.
        assumeTrue("DesktopStorage is not isolated in a temporary directory", storageIsIsolated())
        DesktopStorage.wipe()
        SubtitleVideoHashRepository.clearLocalState()
    }

    @After
    fun cleanup() {
        scope.cancel()
        SubtitleVideoHashRepository.clearLocalState()
        if (storageIsIsolated()) DesktopStorage.wipe()
    }

    @Test
    fun addonFallbackPickWaitsWhileTheVideoHashIsPending() {
        val runtime = runtime()
        val controller = runtime.playerController as RecordingController
        runtime.holdAddonSubtitleAutoPickForVideoHash = true

        runtime.refreshTracks()
        assertTrue(controller.subtitleUrls.isEmpty())
        assertNull(runtime.selectedAddonSubtitleId)

        runtime.holdAddonSubtitleAutoPickForVideoHash = false
        runtime.refreshTracks()
        assertEquals(listOf(english.url), controller.subtitleUrls)
    }

    @Test
    fun rememberedHashesSurviveARestartByFileIdentity() {
        val torrent = "bt:0123456789abcdef0123456789abcdef01234567:3"
        val http = "url:https://cdn.example/d/show.s01e03.mkv"
        val hash = OpenSubtitlesVideoHash(hash = "8e245d9679d31e12", fileSize = 12_909_756L)
        SubtitleVideoHashRepository.remember(torrent, hash)
        SubtitleVideoHashRepository.remember(http, hash)

        SubtitleVideoHashRepository.clearLocalState()

        assertEquals(hash, SubtitleVideoHashRepository.cachedHash(torrent))
        assertEquals(hash, SubtitleVideoHashRepository.verifiedHash(torrent))
        assertEquals(hash, SubtitleVideoHashRepository.cachedHash(http))
        // An HTTP URL may serve another file later: it is re-hashed before it counts as verified.
        assertNull(SubtitleVideoHashRepository.verifiedHash(http))
    }

    @Test
    fun firstSubtitleRequestCarriesARememberedHash() {
        val runtime = runtime()
        val identity = runtime.subtitleHashIdentity
        assertEquals("url:https://cdn.example/episode-1.mkv", identity)
        SubtitleVideoHashRepository.remember(
            identity,
            OpenSubtitlesVideoHash(hash = "8e245d9679d31e12", fileSize = 12_909_756L),
        )

        assertEquals(
            SubtitleVideoParams(videoHash = "8e245d9679d31e12", videoSize = 12_909_756L),
            runtime.currentSubtitleVideoParams(),
        )
    }

    private fun runtime() = PlayerScreenRuntime(
        PlayerScreenArgs(
            profileId = 1,
            title = "Series",
            sourceUrl = "https://cdn.example/episode-1.mkv?token=abc",
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
            videoId = "tt2027:1:1",
            parentMetaId = "tt2027",
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
        scope = this@PlayerVideoHashRuntimeTest.scope
        playerSettingsUiState = PlayerSettingsUiState(preferredSubtitleLanguage = "en")
        addonSubtitles = listOf(english)
        playbackSnapshot = PlayerPlaybackSnapshot(isLoading = false)
        playerController = RecordingController()
        resetIdentityStateIfNeeded()
    }

    private class RecordingController : PlayerEngineController {
        val subtitleUrls = mutableListOf<String>()

        override fun setSubtitleUri(url: String) {
            subtitleUrls += url
        }

        override fun selectSubtitleTrack(index: Int) = Unit
        override fun getAudioTracks() = emptyList<AudioTrack>()
        override fun getSubtitleTracks() = emptyList<SubtitleTrack>()
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

    companion object {
        private val temporaryRoot: Path =
            Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()

        @BeforeClass
        @JvmStatic
        fun isolateUserHome() {
            // Used by DesktopStorage when no XDG_CONFIG_HOME/APPDATA override is present.
            System.setProperty("user.home", Files.createTempDirectory("nuvio-video-hash-test").toString())
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
