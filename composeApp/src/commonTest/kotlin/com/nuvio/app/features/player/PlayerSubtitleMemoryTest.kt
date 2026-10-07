package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerSubtitleMemoryTest {

    @Test
    fun sameItemPreferenceAppliesOnlyToItsItemAndRelease() {
        val preference = PersistedPlayerTrackPreference(
            subtitleType = PersistedSubtitleSelectionType.ADDON,
            addonSubtitleItemId = "tt1:1:1|1|1",
            subtitleBingeGroup = GROUP_A,
        )

        assertTrue(sameItemSubtitlePreferenceApplies(preference, "tt1:1:1|1|1", GROUP_A))
        assertFalse(sameItemSubtitlePreferenceApplies(preference, "tt1:1:2|1|2", GROUP_A))
        assertFalse(sameItemSubtitlePreferenceApplies(preference, "tt1:1:1|1|1", GROUP_B))
        // Without a binge group on either side the release cannot be told apart.
        assertTrue(sameItemSubtitlePreferenceApplies(preference, "tt1:1:1|1|1", null))
        assertTrue(
            sameItemSubtitlePreferenceApplies(preference.copy(subtitleBingeGroup = null), "tt1:1:1|1|1", GROUP_B),
        )
    }

    @Test
    fun preferencesWithoutAnItemAreIgnored() {
        val legacy = PersistedPlayerTrackPreference(subtitleType = PersistedSubtitleSelectionType.DISABLED)

        assertFalse(sameItemSubtitlePreferenceApplies(legacy, "tt1:1:2|1|2", GROUP_A))
    }

    @Test
    fun rememberedEmbeddedTrackIsMatchedByLanguageAndForcedFlagThenTitle() {
        val tracks = listOf(
            SubtitleTrack(index = 0, id = "3", label = "English Forced", language = "eng", isForced = true),
            SubtitleTrack(index = 1, id = "4", label = "English", language = "eng"),
            SubtitleTrack(index = 2, id = "5", label = "English SDH", language = "eng"),
            SubtitleTrack(index = 3, id = "6", label = "Japanese", language = "jpn"),
        )

        val sdh = PersistedReleaseSubtitleChoice(
            subtitleType = PersistedSubtitleSelectionType.INTERNAL,
            language = "eng",
            name = "English SDH",
            isForced = false,
            trackId = "9",
            itemId = "tt1:1:1|1|1",
        )
        assertEquals(2, findRememberedSubtitleTrackIndex(tracks, sdh, "tt1:1:2|1|2"))
        assertEquals(1, findRememberedSubtitleTrackIndex(tracks, sdh.copy(name = "Full"), "tt1:1:2|1|2"))
        assertEquals(0, findRememberedSubtitleTrackIndex(tracks, sdh.copy(name = null, isForced = true), "tt1:1:2|1|2"))
        assertEquals(-1, findRememberedSubtitleTrackIndex(tracks, sdh.copy(language = "fr", name = null), "tt1:1:2|1|2"))
    }

    @Test
    fun rememberedEmbeddedTrackUsesItsIdOnTheSameItem() {
        val tracks = listOf(
            SubtitleTrack(index = 0, id = "3", label = "English", language = "eng"),
            SubtitleTrack(index = 1, id = "4", label = "English", language = "eng"),
        )
        val choice = PersistedReleaseSubtitleChoice(
            subtitleType = PersistedSubtitleSelectionType.INTERNAL,
            language = "eng",
            name = "English",
            trackId = "4",
            itemId = "tt1:1:1|1|1",
        )

        assertEquals(1, findRememberedSubtitleTrackIndex(tracks, choice, "tt1:1:1|1|1"))
        assertEquals(0, findRememberedSubtitleTrackIndex(tracks, choice, "tt1:1:2|1|2"))
    }

    @Test
    fun rememberedAddonSubtitlePrefersAHashMatchThenTheSameName() {
        val subtitles = listOf(
            AddonSubtitle(id = "a", url = "https://other.example/ep2-en.srt", language = "en", display = "English", addonName = "Other", isVideoHashMatch = true),
            AddonSubtitle(id = "b", url = "https://subs.example/ep2-fr.srt", language = "fr", display = "French", addonName = "Subs"),
            AddonSubtitle(id = "c", url = "https://subs.example/ep2-en.srt", language = "eng", display = "English 2", addonName = "Subs"),
            AddonSubtitle(id = "d", url = "https://subs.example/ep2-en-2.srt", language = "en", display = "English", addonName = "Subs"),
            AddonSubtitle(id = "e", url = "https://subs.example/ep2-en-3.srt", language = "en", display = "English 3", addonName = "Subs", isVideoHashMatch = true),
        )

        assertEquals("e", findRememberedAddonSubtitle(subtitles, addonChoice())?.id)
        assertEquals("d", findRememberedAddonSubtitle(subtitles.filterNot { it.id == "e" }, addonChoice())?.id)
        assertEquals("c", findRememberedAddonSubtitle(subtitles.filterNot { it.id == "e" }, addonChoice(name = "Other"))?.id)
    }

    @Test
    fun rememberedAddonSubtitleNeedsTheSameAddon() {
        val subtitles = listOf(
            AddonSubtitle(id = "a", url = "https://other.example/ep2-en.srt", language = "en", display = "English", addonName = "Other"),
        )

        assertNull(findRememberedAddonSubtitle(subtitles, addonChoice()))
    }

    @Test
    fun episodeDelayWinsOverTheRememberedDelay() {
        assertEquals(-250, resolveStartingSubtitleDelayMs(episodeDelayMs = -250, rememberedDelayMs = 1_500))
        assertEquals(1_500, resolveStartingSubtitleDelayMs(episodeDelayMs = null, rememberedDelayMs = 1_500))
        assertEquals(0, resolveStartingSubtitleDelayMs(episodeDelayMs = null, rememberedDelayMs = null))
    }

    @Test
    fun releaseChoiceSurvivesAStorageRoundTrip() {
        val choice = addonChoice().copy(isVideoHashMatch = true, itemId = "tt1:1:1|1|1", delayMs = 1_000)

        assertEquals(choice, decodeStoredReleaseSubtitleChoice(choice.encodeForStorage()))
        assertNull(decodeStoredReleaseSubtitleChoice("not json"))
        assertNull(decodeStoredReleaseSubtitleChoice(PersistedReleaseSubtitleChoice().encodeForStorage()))
        assertNull(releaseSubtitleChoiceStorageId("tt1", " "))
        assertEquals("tt1|$GROUP_A", releaseSubtitleChoiceStorageId("tt1", GROUP_A))
    }

    @Test
    fun partialEmbeddedTracksAreNotFullSubtitles() {
        fun track(label: String, forced: Boolean = false) =
            SubtitleTrack(index = 0, id = "1", label = label, language = "eng", isForced = forced)

        assertFalse(track("English Signs").isFullSubtitleTrack())
        assertFalse(track("Songs (ass)").isFullSubtitleTrack())
        assertFalse(track("Director's Commentary").isFullSubtitleTrack())
        assertFalse(track("English", forced = true).isFullSubtitleTrack())
        assertTrue(track("en (subrip)").isFullSubtitleTrack())
        assertTrue(track("English SDH").isFullSubtitleTrack())
        assertTrue(track("Full + Songs").isFullSubtitleTrack())
    }

    private fun addonChoice(name: String = "English") = PersistedReleaseSubtitleChoice(
        subtitleType = PersistedSubtitleSelectionType.ADDON,
        language = "en",
        name = name,
        addonName = "Subs",
    )

    private companion object {
        const val GROUP_A = "addon|1080p|group-a"
        const val GROUP_B = "addon|2160p|group-b"
    }
}
