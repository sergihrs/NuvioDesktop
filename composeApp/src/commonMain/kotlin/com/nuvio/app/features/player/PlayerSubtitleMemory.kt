package com.nuvio.app.features.player

// Subtitle memory across episodes.
//
// A subtitle the user picks (embedded track, addon subtitle or "off") and the delay they set are
// remembered per series and release: stream addons usually encode the release (group, quality,
// source) in the stream's binge group, so the same binge group means the same timing family and
// the same kind of subtitle tracks. Every later episode played from that release starts with the
// remembered choice; another release has its own record (or none, and then auto-selection runs).

internal fun String?.normalizedBingeGroup(): String? = this?.trim()?.takeIf { it.isNotBlank() }

/**
 * Whether the single-slot subtitle preference stored for the series belongs to the playing item.
 * It only reopens the subtitle on the item it was chosen on, and not after switching to another
 * release of that item. Preferences without an item (saved by older versions) are ignored.
 */
internal fun sameItemSubtitlePreferenceApplies(
    preference: PersistedPlayerTrackPreference,
    itemId: String,
    currentBingeGroup: String?,
): Boolean {
    if (preference.subtitleType == null) return false
    val storedItemId = preference.addonSubtitleItemId?.trim()?.takeIf { it.isNotBlank() } ?: return false
    if (storedItemId != itemId.trim()) return false
    val storedGroup = preference.subtitleBingeGroup.normalizedBingeGroup() ?: return true
    val currentGroup = currentBingeGroup.normalizedBingeGroup() ?: return true
    return storedGroup == currentGroup
}

/**
 * Finds the embedded track matching a remembered choice on another episode of the same release:
 * same language and forced flag first (track ids are not stable across files), then the same title.
 */
internal fun findRememberedSubtitleTrackIndex(
    tracks: List<SubtitleTrack>,
    choice: PersistedReleaseSubtitleChoice,
    itemId: String,
): Int {
    if (choice.itemId != null && choice.itemId == itemId) {
        choice.trackId?.takeIf { it.isNotBlank() }?.let { trackId ->
            tracks.firstOrNull { it.id == trackId }?.let { return it.index }
        }
    }
    val wantsForced = choice.isForced == true
    val name = choice.name?.trim()?.takeIf { it.isNotBlank() }
    val languageCandidates = choice.language?.takeIf { it.isNotBlank() }?.let { language ->
        tracks.filter { track ->
            SubtitleLanguageMatching.matchesLanguageCode(track.language, language) ||
                subtitleTrackMatchesLanguage(track, language)
        }
    }.orEmpty()
    if (languageCandidates.isNotEmpty()) {
        val sameKind = languageCandidates.filter { it.isForced == wantsForced }.ifEmpty { languageCandidates }
        return (
            sameKind.firstOrNull { name != null && it.label.equals(name, ignoreCase = true) }
                ?: sameKind.firstOrNull { !wantsForced && !isPartialSubtitleTrack(it) }
                ?: sameKind.first()
            ).index
    }
    if (name == null) return -1
    return tracks.firstOrNull { it.label.equals(name, ignoreCase = true) }?.index ?: -1
}

/**
 * Finds the addon subtitle matching a remembered choice: the same addon and language (subtitle ids
 * and urls differ per episode), preferring one the addon matched by the video hash, then one with
 * the same name, then the addon's first one in that language.
 */
internal fun findRememberedAddonSubtitle(
    subtitles: List<AddonSubtitle>,
    choice: PersistedReleaseSubtitleChoice,
): AddonSubtitle? {
    val addonName = choice.addonName?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val language = choice.language?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val candidates = subtitles.filter { subtitle ->
        subtitle.addonName?.trim().equals(addonName, ignoreCase = true) &&
            SubtitleLanguageMatching.matchesLanguageCode(subtitle.language, language)
    }
    return candidates.firstOrNull { it.isVideoHashMatch }
        ?: candidates.firstOrNull { it.display.equals(choice.name, ignoreCase = true) }
        ?: candidates.firstOrNull()
}

/** A delay saved for the episode itself wins; otherwise the release's remembered delay applies. */
internal fun resolveStartingSubtitleDelayMs(
    episodeDelayMs: Int?,
    rememberedDelayMs: Int?,
): Int = (episodeDelayMs ?: rememberedDelayMs ?: 0).coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)
