package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerSubtitleCarryOverTest {

    @Test
    fun addonSubtitleCarriesIntoAnotherEpisodeOfTheSameBingeGroup() {
        assertTrue(shouldCarryAddonSubtitleIntoItem(preference(), "tt1:1:2|1|2", "addon|1080p|group-a"))
    }

    @Test
    fun addonSubtitleDoesNotCarryIntoAnotherBingeGroup() {
        assertFalse(shouldCarryAddonSubtitleIntoItem(preference(), "tt1:1:2|1|2", "addon|2160p|group-b"))
    }

    @Test
    fun addonSubtitleDoesNotCarryWithoutABingeGroup() {
        assertFalse(shouldCarryAddonSubtitleIntoItem(preference(), "tt1:1:2|1|2", null))
        assertFalse(shouldCarryAddonSubtitleIntoItem(preference(), "tt1:1:2|1|2", " "))
        assertFalse(
            shouldCarryAddonSubtitleIntoItem(
                preference(bingeGroup = null),
                "tt1:1:2|1|2",
                "addon|1080p|group-a",
            ),
        )
    }

    @Test
    fun theSameEpisodeIsARestoreNotACarryOver() {
        assertFalse(shouldCarryAddonSubtitleIntoItem(preference(), "tt1:1:1|1|1", "addon|1080p|group-a"))
    }

    @Test
    fun onlyAddonSelectionsCarryOver() {
        assertFalse(
            shouldCarryAddonSubtitleIntoItem(
                preference().copy(subtitleType = PersistedSubtitleSelectionType.INTERNAL),
                "tt1:1:2|1|2",
                "addon|1080p|group-a",
            ),
        )
    }

    @Test
    fun carriedSubtitleIsMatchedByAddonAndLanguage() {
        val subtitles = listOf(
            AddonSubtitle(id = "a", url = "https://other.example/ep2-en.srt", language = "en", display = "English", addonName = "Other"),
            AddonSubtitle(id = "b", url = "https://subs.example/ep2-fr.srt", language = "fr", display = "French", addonName = "Subs"),
            AddonSubtitle(id = "c", url = "https://subs.example/ep2-en.srt", language = "eng", display = "English", addonName = "Subs"),
            AddonSubtitle(id = "d", url = "https://subs.example/ep2-en-2.srt", language = "en", display = "English", addonName = "Subs"),
        )

        assertEquals("c", findCarriedOverAddonSubtitle(subtitles, preference())?.id)
    }

    @Test
    fun carriedSubtitleNeedsTheSameAddon() {
        val subtitles = listOf(
            AddonSubtitle(id = "a", url = "https://other.example/ep2-en.srt", language = "en", display = "English", addonName = "Other"),
        )

        assertNull(findCarriedOverAddonSubtitle(subtitles, preference()))
    }

    @Test
    fun episodeDelayWinsOverTheSeriesDelay() {
        assertEquals(
            -250,
            resolveStartingSubtitleDelayMs(
                episodeDelayMs = -250,
                seriesDelay = PersistedSeriesSubtitleDelay(delayMs = 1_500, bingeGroup = "addon|1080p|group-a"),
                currentBingeGroup = "addon|1080p|group-a",
            ),
        )
    }

    @Test
    fun seriesDelayAppliesToAnotherEpisodeOfTheSameBingeGroup() {
        assertEquals(
            1_500,
            resolveStartingSubtitleDelayMs(
                episodeDelayMs = null,
                seriesDelay = PersistedSeriesSubtitleDelay(delayMs = 1_500, bingeGroup = "addon|1080p|group-a"),
                currentBingeGroup = "addon|1080p|group-a",
            ),
        )
    }

    @Test
    fun seriesDelayIsIgnoredForAnotherBingeGroup() {
        val seriesDelay = PersistedSeriesSubtitleDelay(delayMs = 1_500, bingeGroup = "addon|1080p|group-a")

        assertEquals(0, resolveStartingSubtitleDelayMs(null, seriesDelay, "addon|2160p|group-b"))
        assertEquals(0, resolveStartingSubtitleDelayMs(null, seriesDelay, null))
        assertEquals(0, resolveStartingSubtitleDelayMs(null, null, "addon|1080p|group-a"))
    }

    private fun preference(bingeGroup: String? = "addon|1080p|group-a") = PersistedPlayerTrackPreference(
        subtitleType = PersistedSubtitleSelectionType.ADDON,
        subtitleLanguage = "en",
        subtitleName = "English",
        addonSubtitleId = "ep1",
        addonSubtitleUrl = "https://subs.example/ep1-en.srt",
        addonSubtitleItemId = "tt1:1:1|1|1",
        addonSubtitleAddonName = "Subs",
        addonSubtitleBingeGroup = bingeGroup,
    )
}
