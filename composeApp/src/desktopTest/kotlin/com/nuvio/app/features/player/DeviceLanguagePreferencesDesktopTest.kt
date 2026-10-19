package com.nuvio.app.features.player

import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

class DeviceLanguagePreferencesDesktopTest {
    private lateinit var originalLocale: Locale

    @BeforeTest
    fun saveLocale() {
        originalLocale = Locale.getDefault()
    }

    @AfterTest
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `device subtitle language matches English subtitles before the secondary language`() {
        Locale.setDefault(Locale.US)

        val targets = resolvePreferredSubtitleLanguageTargets(
            preferredSubtitleLanguage = SubtitleLanguageOption.DEVICE,
            secondaryPreferredSubtitleLanguage = "es",
            deviceLanguages = DeviceLanguagePreferences.preferredLanguageCodes(),
        )

        val englishTarget = targets.indexOfFirst { SubtitleLanguageMatching.matchesLanguageCode("eng", it) }
        assertTrue(englishTarget in 0 until targets.indexOf("es"), "targets: $targets")
    }
}
