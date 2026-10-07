package com.nuvio.app.features.player

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class PersistedPlayerTrackPreference(
    val subtitleType: String? = null,
    val subtitleLanguage: String? = null,
    val subtitleName: String? = null,
    val subtitleTrackId: String? = null,
    val addonSubtitleId: String? = null,
    val addonSubtitleUrl: String? = null,
    /** The item (episode) the subtitle choice was made on; set for every subtitle type. */
    val addonSubtitleItemId: String? = null,
    val addonSubtitleAddonName: String? = null,
    /** The binge group of the stream the subtitle choice was made on, if it had one. */
    val subtitleBingeGroup: String? = null,
    val audioLanguage: String? = null,
    val audioName: String? = null,
    val audioTrackId: String? = null,
    val subtitleIsForced: Boolean? = null,
)

/**
 * The subtitle the user chose for one release (binge group) of a series, and its delay.
 *
 * Stored per series and binge group, so every later episode played from the same release starts
 * with the same kind of subtitle, and switching between releases keeps each release's own choice.
 * [subtitleType] is null when only a delay was set while no subtitle was shown.
 */
@Serializable
data class PersistedReleaseSubtitleChoice(
    val subtitleType: String? = null,
    val language: String? = null,
    val name: String? = null,
    val isForced: Boolean? = null,
    val trackId: String? = null,
    val addonName: String? = null,
    val addonSubtitleUrl: String? = null,
    val isVideoHashMatch: Boolean = false,
    /** The item (episode) the choice was made on, so that item can reopen the exact subtitle. */
    val itemId: String? = null,
    val delayMs: Int? = null,
)

object PersistedSubtitleSelectionType {
    const val INTERNAL = "INTERNAL"
    const val ADDON = "ADDON"
    const val DISABLED = "DISABLED"
}

private val releaseSubtitleChoiceJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

internal fun PersistedReleaseSubtitleChoice.encodeForStorage(): String =
    releaseSubtitleChoiceJson.encodeToString(this)

internal fun decodeStoredReleaseSubtitleChoice(value: String?): PersistedReleaseSubtitleChoice? {
    val json = value?.takeIf { it.isNotBlank() } ?: return null
    return runCatching { releaseSubtitleChoiceJson.decodeFromString<PersistedReleaseSubtitleChoice>(json) }
        .getOrNull()
        ?.takeIf { it.subtitleType != null || it.delayMs != null }
}

/** Storage id of the per-release subtitle choice: `parentMetaId|bingeGroup`, or null without either. */
internal fun releaseSubtitleChoiceStorageId(contentId: String?, bingeGroup: String?): String? {
    val id = contentId?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val group = bingeGroup?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return "$id|$group"
}

internal expect object PlayerTrackPreferenceStorage {
    fun load(contentId: String): PersistedPlayerTrackPreference?
    fun save(contentId: String, preference: PersistedPlayerTrackPreference)
    fun loadSubtitleDelayMs(videoId: String): Int?
    fun saveSubtitleDelayMs(videoId: String, delayMs: Int)
    fun loadReleaseSubtitleChoice(contentId: String, bingeGroup: String): PersistedReleaseSubtitleChoice?
    fun saveReleaseSubtitleChoice(contentId: String, bingeGroup: String, choice: PersistedReleaseSubtitleChoice)
}
