package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** "Original language" audio: the original track, else the file's default, plus translating subtitles. */
class OriginalVersionSubtitlesTest {

    @Test
    fun unknownOriginalLanguageKeepsTheFileDefaultInsteadOfTheDeviceLanguage() {
        val targets = resolvePreferredAudioLanguageTargets(
            preferredAudioLanguage = AudioLanguageOption.ORIGINAL,
            secondaryPreferredAudioLanguage = null,
            deviceLanguages = listOf("en"),
            contentOriginalLanguage = null,
        )

        assertEquals(emptyList(), targets)
    }

    @Test
    fun unknownOriginalLanguageStillUsesTheSecondaryLanguage() {
        val targets = resolvePreferredAudioLanguageTargets(
            preferredAudioLanguage = AudioLanguageOption.ORIGINAL,
            secondaryPreferredAudioLanguage = "de",
            deviceLanguages = listOf("en"),
            contentOriginalLanguage = null,
        )

        assertEquals(listOf("de"), targets)
    }

    @Test
    fun knownOriginalLanguageComesFirst() {
        val targets = resolvePreferredAudioLanguageTargets(
            preferredAudioLanguage = AudioLanguageOption.ORIGINAL,
            secondaryPreferredAudioLanguage = "en",
            deviceLanguages = listOf("en"),
            contentOriginalLanguage = "ja",
        )

        assertEquals(listOf("ja", "en"), targets)
    }

    @Test
    fun countriesFromAddonMetadataResolveToTheirLanguage() {
        assertEquals("ja", resolveContentLanguage(language = null, country = "jpn"))
        assertEquals("en", resolveContentLanguage(language = null, country = "United States"))
        assertEquals("en", resolveContentLanguage(language = null, country = "USA"))
        assertEquals("en", resolveContentLanguage(language = null, country = "GB"))
        assertEquals("ko", resolveContentLanguage(language = null, country = "South Korea, United States"))
        assertEquals("ja", resolveContentLanguage(language = "ja", country = "United States"))
    }

    @Test
    fun originalAudioInAnotherLanguageGetsPreferredSubtitles() {
        val plan = assertNotNull(
            resolveSubtitleAutoSelectionPlan(
                selectedAudioTrack = audioTrack("Japanese", "jpn"),
                preferredAudioTargets = listOf("ja"),
                preferredSubtitleTargets = listOf("en"),
                useForcedSubtitles = false,
                onlyForOtherAudioLanguage = true,
            ),
        )

        assertEquals(listOf("en"), plan.targets)
        assertEquals(SubtitleAutoSelectionMode.NORMAL_ONLY, plan.mode)
    }

    @Test
    fun audioInThePreferredSubtitleLanguageTurnsSubtitlesOff() {
        val plan = assertNotNull(
            resolveSubtitleAutoSelectionPlan(
                selectedAudioTrack = audioTrack("English", "eng"),
                preferredAudioTargets = listOf("en"),
                preferredSubtitleTargets = listOf("en"),
                useForcedSubtitles = false,
                onlyForOtherAudioLanguage = true,
            ),
        )

        assertEquals(emptyList(), plan.targets)
    }

    @Test
    fun audioInThePreferredSubtitleLanguageStillGetsForcedSubtitles() {
        val plan = assertNotNull(
            resolveSubtitleAutoSelectionPlan(
                selectedAudioTrack = audioTrack("English", "eng"),
                preferredAudioTargets = listOf("en"),
                preferredSubtitleTargets = listOf("en"),
                useForcedSubtitles = true,
                onlyForOtherAudioLanguage = true,
            ),
        )

        assertEquals(listOf("en"), plan.targets)
        assertEquals(SubtitleAutoSelectionMode.FORCED_ONLY, plan.mode)
    }

    @Test
    fun audioWithUnknownLanguageKeepsSubtitles() {
        val plan = assertNotNull(
            resolveSubtitleAutoSelectionPlan(
                selectedAudioTrack = audioTrack("Track 1", null),
                preferredAudioTargets = emptyList(),
                preferredSubtitleTargets = listOf("en"),
                useForcedSubtitles = false,
                onlyForOtherAudioLanguage = true,
            ),
        )

        assertEquals(listOf("en"), plan.targets)
    }

    @Test
    fun otherAudioPreferencesKeepSubtitlesInTheAudioLanguage() {
        val plan = assertNotNull(
            resolveSubtitleAutoSelectionPlan(
                selectedAudioTrack = audioTrack("English", "eng"),
                preferredAudioTargets = listOf("en"),
                preferredSubtitleTargets = listOf("en"),
                useForcedSubtitles = false,
            ),
        )

        assertEquals(listOf("en"), plan.targets)
    }

    private fun audioTrack(label: String, language: String?) = AudioTrack(
        index = 0,
        id = "1",
        label = label,
        language = language,
        isSelected = true,
    )
}
