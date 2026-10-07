package com.nuvio.app.features.player

// A subtitle choice and its delay only carry over to another episode when both episodes play
// from the same binge group. Stream addons usually encode the release (group, quality, source)
// in the binge group, so the same group means the same timing family and the same offset fits.

internal fun isSameSubtitleBingeGroup(stored: String?, current: String?): Boolean {
    val storedGroup = stored?.trim()?.takeIf { it.isNotBlank() } ?: return false
    val currentGroup = current?.trim()?.takeIf { it.isNotBlank() } ?: return false
    return storedGroup == currentGroup
}

internal fun shouldCarryAddonSubtitleIntoItem(
    preference: PersistedPlayerTrackPreference,
    itemId: String,
    currentBingeGroup: String?,
): Boolean {
    if (preference.subtitleType != PersistedSubtitleSelectionType.ADDON) return false
    val storedItemId = preference.addonSubtitleItemId?.trim()?.takeIf { it.isNotBlank() } ?: return false
    if (storedItemId == itemId.trim()) return false
    if (preference.addonSubtitleAddonName.isNullOrBlank() || preference.subtitleLanguage.isNullOrBlank()) return false
    return isSameSubtitleBingeGroup(preference.addonSubtitleBingeGroup, currentBingeGroup)
}

// Subtitle ids and urls differ per episode, so the carried choice is matched by addon and language.
internal fun findCarriedOverAddonSubtitle(
    subtitles: List<AddonSubtitle>,
    preference: PersistedPlayerTrackPreference,
): AddonSubtitle? {
    val addonName = preference.addonSubtitleAddonName?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val language = preference.subtitleLanguage?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val candidates = subtitles.filter { subtitle ->
        subtitle.addonName?.trim().equals(addonName, ignoreCase = true) &&
            SubtitleLanguageMatching.matchesLanguageCode(subtitle.language, language)
    }
    return candidates.firstOrNull {
        it.display.equals(preference.subtitleName, ignoreCase = true)
    } ?: candidates.firstOrNull()
}

// A delay saved for the episode itself always wins; otherwise the last series delay applies
// when it was set on the same binge group.
internal fun resolveStartingSubtitleDelayMs(
    episodeDelayMs: Int?,
    seriesDelay: PersistedSeriesSubtitleDelay?,
    currentBingeGroup: String?,
): Int {
    if (episodeDelayMs != null) return episodeDelayMs
    if (seriesDelay == null) return 0
    if (!isSameSubtitleBingeGroup(seriesDelay.bingeGroup, currentBingeGroup)) return 0
    return seriesDelay.delayMs.coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
}
