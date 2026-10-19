package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PlayerTrackSelectionTest {

    @Test
    fun forcedSelectionUsesPrimaryPreferredLanguageInsteadOfTrackOrder() {
        val tracks = listOf(
            subtitleTrack(index = 0, language = "ja", isForced = true),
            subtitleTrack(index = 1, language = "en", isForced = false),
            subtitleTrack(index = 2, language = "en", isForced = true),
        )

        val selectedIndex = findPreferredSubtitleTrackIndex(
            tracks = tracks,
            targets = listOf("en"),
            mode = SubtitleAutoSelectionMode.FORCED_ONLY,
            selectedAudioTrack = audioTrack(language = "en"),
        )

        assertEquals(2, selectedIndex)
    }

    @Test
    fun matchingAudioUsesForcedOnlyPrimarySubtitleTarget() {
        val plan = assertNotNull(
            resolveSubtitleAutoSelectionPlan(
                selectedAudioTrack = audioTrack(language = "en"),
                preferredAudioTargets = listOf("en"),
                preferredSubtitleTargets = listOf("en", "fr"),
                useForcedSubtitles = true,
            ),
        )

        assertEquals(listOf("en"), plan.targets)
        assertEquals(SubtitleAutoSelectionMode.FORCED_ONLY, plan.mode)
    }

    @Test
    fun forcedSelectionRejectsTracksOutsidePreferredLanguages() {
        val tracks = listOf(
            subtitleTrack(index = 0, language = "ja", isForced = true),
            subtitleTrack(index = 1, language = "en", isForced = false),
        )

        val selectedIndex = findPreferredSubtitleTrackIndex(
            tracks = tracks,
            targets = listOf("en"),
            mode = SubtitleAutoSelectionMode.FORCED_ONLY,
            selectedAudioTrack = audioTrack(language = "en"),
        )

        assertEquals(-1, selectedIndex)
    }

    @Test
    fun differentAudioUsesNormalPreferredSubtitles() {
        val tracks = listOf(
            subtitleTrack(index = 0, language = "en", isForced = true),
            subtitleTrack(index = 1, language = "en", isForced = false),
        )
        val plan = assertNotNull(
            resolveSubtitleAutoSelectionPlan(
                selectedAudioTrack = audioTrack(language = "ja"),
                preferredAudioTargets = listOf("ja"),
                preferredSubtitleTargets = listOf("en", "fr"),
                useForcedSubtitles = true,
            ),
        )

        val selectedIndex = findPreferredSubtitleTrackIndex(
            tracks = tracks,
            targets = plan.targets,
            mode = plan.mode,
            selectedAudioTrack = audioTrack(language = "ja"),
        )

        assertEquals(SubtitleAutoSelectionMode.NORMAL_ONLY, plan.mode)
        assertEquals(1, selectedIndex)
    }

    @Test
    fun audioMatchingOnlySecondarySubtitleTargetUsesNormalSubtitles() {
        val plan = assertNotNull(
            resolveSubtitleAutoSelectionPlan(
                selectedAudioTrack = audioTrack(language = "fr"),
                preferredAudioTargets = listOf("fr"),
                preferredSubtitleTargets = listOf("en", "fr"),
                useForcedSubtitles = true,
            ),
        )

        assertEquals(listOf("en", "fr"), plan.targets)
        assertEquals(SubtitleAutoSelectionMode.NORMAL_ONLY, plan.mode)
    }

    @Test
    fun forcedToggleOffExcludesForcedTracks() {
        val tracks = listOf(
            subtitleTrack(index = 0, language = "en", isForced = true),
            subtitleTrack(index = 1, language = "en", isForced = false),
        )
        val plan = assertNotNull(
            resolveSubtitleAutoSelectionPlan(
                selectedAudioTrack = audioTrack(language = "en"),
                preferredAudioTargets = listOf("en"),
                preferredSubtitleTargets = listOf("en"),
                useForcedSubtitles = false,
            ),
        )

        val selectedIndex = findPreferredSubtitleTrackIndex(
            tracks = tracks,
            targets = plan.targets,
            mode = plan.mode,
        )

        assertEquals(SubtitleAutoSelectionMode.NORMAL_ONLY, plan.mode)
        assertEquals(1, selectedIndex)
    }

    @Test
    fun normalSelectionSkipsSignsAndCommentaryTracks() {
        val tracks = listOf(
            subtitleTrack(index = 0, language = "en", label = "English Signs/Songs (ass)", isForced = false),
            subtitleTrack(index = 1, language = "en", label = "Commentary", isForced = false),
            subtitleTrack(index = 2, language = "en", label = "en (subrip)", isForced = false),
        )

        assertEquals(
            2,
            findPreferredSubtitleTrackIndex(tracks, listOf("en"), SubtitleAutoSelectionMode.NORMAL_ONLY),
        )
        assertEquals(
            -1,
            findPreferredSubtitleTrackIndex(tracks.take(2), listOf("en"), SubtitleAutoSelectionMode.NORMAL_ONLY),
        )
    }

    @Test
    fun forcedToggleOffRejectsForcedOnlyTrackList() {
        val selectedIndex = findPreferredSubtitleTrackIndex(
            tracks = listOf(subtitleTrack(index = 0, language = "en", isForced = true)),
            targets = listOf("en"),
            mode = SubtitleAutoSelectionMode.NORMAL_ONLY,
        )

        assertEquals(-1, selectedIndex)
    }

    @Test
    fun forcedModeWithoutSubtitleTargetUsesMatchingSelectedAudioLanguage() {
        val plan = assertNotNull(
            resolveSubtitleAutoSelectionPlan(
                selectedAudioTrack = audioTrack(language = "ja"),
                preferredAudioTargets = listOf("ja"),
                preferredSubtitleTargets = emptyList(),
                useForcedSubtitles = true,
            ),
        )

        assertEquals(listOf("ja"), plan.targets)
        assertEquals(SubtitleAutoSelectionMode.FORCED_ONLY, plan.mode)
    }

    @Test
    fun forcedModeWaitsUntilSelectedAudioIsKnown() {
        val plan = resolveSubtitleAutoSelectionPlan(
            selectedAudioTrack = null,
            preferredAudioTargets = listOf("en"),
            preferredSubtitleTargets = listOf("en"),
            useForcedSubtitles = true,
        )

        assertNull(plan)
    }

    @Test
    fun resolvesSelectedAudioLanguageFromTrackLabel() {
        val target = resolveAudioTrackLanguageTarget(
            AudioTrack(
                index = 0,
                id = "audio-0",
                label = "English Original",
                language = null,
                isSelected = true,
            ),
        )

        assertEquals("en", target)
    }

    @Test
    fun forcedSelectionMatchesSubtitleLanguageFromTrackLabel() {
        val selectedIndex = findPreferredSubtitleTrackIndex(
            tracks = listOf(
                subtitleTrack(
                    index = 0,
                    language = null,
                    label = "English Forced",
                    isForced = true,
                ),
            ),
            targets = listOf("en"),
            mode = SubtitleAutoSelectionMode.FORCED_ONLY,
            selectedAudioTrack = audioTrack(language = "en"),
        )

        assertEquals(0, selectedIndex)
    }

    @Test
    fun forcedSelectionRequiresMatchingSelectedAudioLanguage() {
        val tracks = listOf(
            subtitleTrack(index = 0, language = "en", isForced = true),
        )

        assertEquals(
            -1,
            findPreferredSubtitleTrackIndex(
                tracks = tracks,
                targets = listOf("en"),
                mode = SubtitleAutoSelectionMode.FORCED_ONLY,
                selectedAudioTrack = null,
            ),
        )
        assertEquals(
            -1,
            findPreferredSubtitleTrackIndex(
                tracks = tracks,
                targets = listOf("en"),
                mode = SubtitleAutoSelectionMode.FORCED_ONLY,
                selectedAudioTrack = audioTrack(language = "ja"),
            ),
        )
        assertEquals(
            0,
            findPreferredSubtitleTrackIndex(
                tracks = tracks,
                targets = listOf("en"),
                mode = SubtitleAutoSelectionMode.FORCED_ONLY,
                selectedAudioTrack = audioTrack(language = "en"),
            ),
        )
    }

    @Test
    fun genericPortugueseAudioActivatesForcedForBrazilianTarget() {
        val plan = assertNotNull(
            resolveSubtitleAutoSelectionPlan(
                selectedAudioTrack = audioTrack(language = "pt"),
                preferredAudioTargets = listOf("pt"),
                preferredSubtitleTargets = listOf("pt-br"),
                useForcedSubtitles = true,
            ),
        )

        assertEquals(listOf("pt-br"), plan.targets)
        assertEquals(SubtitleAutoSelectionMode.FORCED_ONLY, plan.mode)
    }

    @Test
    fun europeanPortugueseTargetDoesNotSelectBrazilianTrack() {
        val selectedIndex = findPreferredSubtitleTrackIndex(
            tracks = listOf(
                subtitleTrack(
                    index = 0,
                    language = "pt",
                    label = "Portuguese (Brazil)",
                    isForced = false,
                ),
            ),
            targets = listOf("pt"),
            mode = SubtitleAutoSelectionMode.NORMAL_ONLY,
        )

        assertEquals(-1, selectedIndex)
    }

    @Test
    fun forcedRestoreSkipsNonForcedLanguageFallback() {
        val selectedIndex = findPersistedSubtitleTrackIndex(
            tracks = listOf(
                subtitleTrack(index = 0, language = "en", isForced = false),
                subtitleTrack(index = 1, language = "en", isForced = true),
            ),
            preference = PersistedPlayerTrackPreference(
                subtitleType = PersistedSubtitleSelectionType.INTERNAL,
                subtitleLanguage = "en",
                subtitleIsForced = true,
            ),
        )

        assertEquals(1, selectedIndex)
    }

    @Test
    fun forcedAddonMatchIgnoresRegularTranslationAddons() {
        val regular = addonSubtitle(id = "english", language = "en")
        val forced = addonSubtitle(
            id = "english-forced",
            language = "en",
            url = "https://example.com/en.forced.srt",
        )

        assertEquals(false, addonSubtitleIsForced(regular))
        assertEquals(true, addonSubtitleIsForced(forced))
        assertEquals(true, addonSubtitleMatchesLanguage(forced, "en"))
        assertEquals(true, addonSubtitleMatchesSelectedAudioLanguage(forced, audioTrack(language = "en")))
        assertEquals(false, addonSubtitleMatchesSelectedAudioLanguage(forced, audioTrack(language = "ja")))
    }

    @Test
    fun preferredOnlyFilteringRemovesNonPreferredAddons() {
        val subtitles = listOf(
            addonSubtitle(id = "english", language = "en"),
            addonSubtitle(id = "japanese", language = "ja"),
        )
        val settings = PlayerSettingsUiState(
            preferredSubtitleLanguage = "en",
            subtitleStyle = SubtitleStyleState.DEFAULT.copy(showOnlyPreferredLanguages = true),
        )

        val visibleSubtitles = filterAddonSubtitlesForSettings(
            subtitles = subtitles,
            settings = settings,
        )

        assertEquals(listOf("english"), visibleSubtitles.map { it.id })
    }

    @Test
    fun forcedToggleKeepsPreferredLanguagesForAddonFiltering() {
        val subtitles = listOf(
            addonSubtitle(id = "japanese", language = "ja"),
            addonSubtitle(id = "french", language = "fr"),
            addonSubtitle(id = "english", language = "en"),
        )
        val settings = PlayerSettingsUiState(
            preferredSubtitleLanguage = "en",
            secondaryPreferredSubtitleLanguage = "fr",
            subtitleStyle = SubtitleStyleState.DEFAULT.copy(
                useForcedSubtitles = true,
                showOnlyPreferredLanguages = true,
            ),
        )

        val visibleSubtitles = filterAddonSubtitlesForSettings(
            subtitles = subtitles,
            settings = settings,
        )

        assertEquals(listOf("french", "english"), visibleSubtitles.map { it.id })
    }

    @Test
    fun addonSubtitleUrlRestoresForTheSameItem() {
        val preference = addonSubtitlePreference(itemId = "series|1|1")

        assertEquals(
            "https://example.com/episode-1.srt",
            persistedAddonSubtitleUrlForItem(preference, "series|1|1"),
        )
    }

    @Test
    fun addonSubtitleUrlDoesNotCarryIntoAnotherEpisode() {
        val preference = addonSubtitlePreference(itemId = "series|1|1")

        assertNull(persistedAddonSubtitleUrlForItem(preference, "series|1|2"))
    }

    @Test
    fun legacyAddonSubtitleUrlWithoutVideoScopeIsNotRestored() {
        val preference = addonSubtitlePreference(itemId = null)

        assertNull(persistedAddonSubtitleUrlForItem(preference, "series|1|2"))
    }

    private fun audioTrack(language: String?) = AudioTrack(
        index = 0,
        id = "audio-0",
        label = language ?: "Audio",
        language = language,
        isSelected = true,
    )

    private fun subtitleTrack(
        index: Int,
        language: String?,
        label: String = "Track $index",
        isForced: Boolean,
    ) = SubtitleTrack(
        index = index,
        id = "track-$index",
        label = label,
        language = language,
        isForced = isForced,
    )

    @Test
    fun videoHashMatchesMoveToFrontKeepingRelativeOrder() {
        val subtitles = listOf(
            addonSubtitle(id = "a", language = "en"),
            addonSubtitle(id = "b", language = "ro", isVideoHashMatch = true),
            addonSubtitle(id = "c", language = "en"),
            addonSubtitle(id = "d", language = "en", isVideoHashMatch = true),
        )

        assertEquals(listOf("b", "d", "a", "c"), subtitles.preferringVideoHashMatches().map { it.id })
        val firstEnglish = subtitles.preferringVideoHashMatches().first {
            SubtitleLanguageMatching.matchesLanguageCode(it.language, "en")
        }
        assertEquals("d", firstEnglish.id)
    }

    @Test
    fun autoSelectedSubtitleIsReplacedByHashMatchInSameLanguageOnly() {
        val selected = addonSubtitle(id = "plain", language = "en")
        val subtitles = listOf(
            addonSubtitle(id = "hash-ro", language = "ro", isVideoHashMatch = true),
            selected,
            addonSubtitle(id = "hash-en", language = "en", isVideoHashMatch = true),
        )

        assertEquals("hash-en", findVideoHashMatchedReplacement(selected, subtitles)?.id)
        assertNull(findVideoHashMatchedReplacement(subtitles.last(), subtitles))
        assertNull(findVideoHashMatchedReplacement(null, subtitles))
        assertNull(
            findVideoHashMatchedReplacement(
                addonSubtitle(id = "plain-de", language = "de"),
                subtitles,
            ),
        )
    }

    private fun addonSubtitle(
        id: String,
        language: String,
        url: String = "https://example.com/$id.srt",
        isVideoHashMatch: Boolean = false,
    ) = AddonSubtitle(
        id = id,
        url = url,
        language = language,
        display = id,
        addonName = "Addon",
        isVideoHashMatch = isVideoHashMatch,
    )

    private fun addonSubtitlePreference(itemId: String?) = PersistedPlayerTrackPreference(
        subtitleType = PersistedSubtitleSelectionType.ADDON,
        addonSubtitleId = "episode-1",
        addonSubtitleUrl = "https://example.com/episode-1.srt",
        addonSubtitleItemId = itemId,
    )
}
