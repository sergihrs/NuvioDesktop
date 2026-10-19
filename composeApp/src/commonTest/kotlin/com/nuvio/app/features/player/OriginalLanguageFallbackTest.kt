package com.nuvio.app.features.player

import kotlin.test.Test
import kotlin.test.assertEquals

/** "Original language" audio: the title's own language, else the file's default track. */
class OriginalLanguageFallbackTest {

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
}
