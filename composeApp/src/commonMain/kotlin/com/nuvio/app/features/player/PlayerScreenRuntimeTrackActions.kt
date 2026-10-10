package com.nuvio.app.features.player

internal val PlayerScreenRuntime.subtitleStyle: SubtitleStyleState
    get() = playerSettingsUiState.subtitleStyle

private val PlayerScreenRuntime.subtitlePreferenceItemId: String
    get() = "${playbackSession.videoId}|${activeSeasonNumber ?: -1}|${activeEpisodeNumber ?: -1}"

internal val PlayerScreenRuntime.activeAddonSubtitleType: String
    get() = contentType ?: parentMetaType

internal val PlayerScreenRuntime.addonSubtitleFetchKey: String?
    get() = buildAddonSubtitleFetchKey(
        addons = addonsUiState.addons,
        type = activeAddonSubtitleType,
        videoId = activeVideoId,
    )

internal val PlayerScreenRuntime.visibleAddonSubtitles: List<AddonSubtitle>
    get() {
        val filtered = filterAddonSubtitlesForSettings(
            subtitles = addonSubtitles,
            settings = playerSettingsUiState,
        )
        val selectedId = selectedAddonSubtitleId ?: return filtered
        if (filtered.any { it.matchesSelection(selectedId) }) return filtered
        val selectedSub = addonSubtitles.findSelectedAddon(selectedId) ?: return filtered
        return listOf(selectedSub) + filtered
    }

internal val PlayerScreenRuntime.selectedAddonSubtitle: AddonSubtitle?
    get() {
        val selectedId = selectedAddonSubtitleId ?: return null
        return addonSubtitles.findSelectedAddon(selectedId)
            ?: visibleAddonSubtitles.findSelectedAddon(selectedId)
    }

internal fun PlayerScreenRuntime.updateTrackPreference(
    update: (PersistedPlayerTrackPreference) -> PersistedPlayerTrackPreference,
) {
    if (parentMetaId.isBlank()) return
    val current = PlayerTrackPreferenceStorage.load(parentMetaId) ?: PersistedPlayerTrackPreference()
    PlayerTrackPreferenceStorage.save(parentMetaId, update(current))
}

internal fun PlayerScreenRuntime.persistAudioPreference(track: AudioTrack?) {
    isUserExplicitAudioSelection = true
    preferredAudioSelectionApplied = true
    updateTrackPreference { current ->
        current.copy(
            audioLanguage = track?.language,
            audioName = track?.label,
            audioTrackId = track?.id,
        )
    }
}

// persistInternalSubtitlePreference / persistAddonSubtitlePreference record a subtitle the user
// picked: for reopening the same item, and as the remembered choice of the playing release.
// Automatic picks and restores never call them.

internal fun PlayerScreenRuntime.persistInternalSubtitlePreference(track: SubtitleTrack?) {
    updateTrackPreference { current ->
        current.copy(
            subtitleType = if (track == null) {
                PersistedSubtitleSelectionType.DISABLED
            } else {
                PersistedSubtitleSelectionType.INTERNAL
            },
            subtitleLanguage = track?.language,
            subtitleName = track?.label,
            subtitleTrackId = track?.id,
            subtitleIsForced = track?.isForced,
            addonSubtitleId = null,
            addonSubtitleUrl = null,
            addonSubtitleItemId = subtitlePreferenceItemId,
            addonSubtitleAddonName = null,
            subtitleBingeGroup = currentStreamBingeGroup.normalizedBingeGroup(),
        )
    }
    rememberReleaseSubtitleChoice(internalSubtitleChoice(track))
}

internal fun PlayerScreenRuntime.persistAddonSubtitlePreference(subtitle: AddonSubtitle) {
    updateTrackPreference { current ->
        current.copy(
            subtitleType = PersistedSubtitleSelectionType.ADDON,
            subtitleLanguage = subtitle.language,
            subtitleName = subtitle.display,
            subtitleTrackId = null,
            addonSubtitleId = subtitle.id,
            addonSubtitleUrl = subtitle.url,
            addonSubtitleItemId = subtitlePreferenceItemId,
            addonSubtitleAddonName = subtitle.addonName,
            subtitleBingeGroup = currentStreamBingeGroup.normalizedBingeGroup(),
            subtitleIsForced = null,
        )
    }
    rememberReleaseSubtitleChoice(addonSubtitleChoice(subtitle))
}

private fun PlayerScreenRuntime.internalSubtitleChoice(track: SubtitleTrack?) = PersistedReleaseSubtitleChoice(
    subtitleType = if (track == null) PersistedSubtitleSelectionType.DISABLED else PersistedSubtitleSelectionType.INTERNAL,
    language = track?.language,
    name = track?.label,
    isForced = track?.isForced,
    trackId = track?.id,
    itemId = subtitlePreferenceItemId,
    delayMs = subtitleDelayMs,
)

private fun PlayerScreenRuntime.addonSubtitleChoice(subtitle: AddonSubtitle) = PersistedReleaseSubtitleChoice(
    subtitleType = PersistedSubtitleSelectionType.ADDON,
    language = subtitle.language,
    name = subtitle.display,
    addonName = subtitle.addonName,
    addonSubtitleUrl = subtitle.url,
    isVideoHashMatch = subtitle.isVideoHashMatch,
    itemId = subtitlePreferenceItemId,
    delayMs = subtitleDelayMs,
)

/**
 * Records the delay the user set on the playing release, together with the subtitle it was set
 * for. With nothing shown, the release keeps its remembered subtitle choice (if any).
 */
internal fun PlayerScreenRuntime.rememberReleaseSubtitleDelay(delayMs: Int) {
    val current = selectedAddonSubtitle?.let(::addonSubtitleChoice)
        ?: subtitleTracks.firstOrNull { it.index == selectedSubtitleIndex }
            ?.takeUnless { useCustomSubtitles }
            ?.let(::internalSubtitleChoice)
        ?: storedReleaseSubtitleChoice()
        ?: PersistedReleaseSubtitleChoice()
    rememberReleaseSubtitleChoice(current.copy(delayMs = delayMs))
}

private fun PlayerScreenRuntime.rememberReleaseSubtitleChoice(choice: PersistedReleaseSubtitleChoice) {
    val bingeGroup = currentStreamBingeGroup.normalizedBingeGroup() ?: return
    if (parentMetaId.isBlank()) return
    PlayerTrackPreferenceStorage.saveReleaseSubtitleChoice(parentMetaId, bingeGroup, choice)
}

private fun PlayerScreenRuntime.storedReleaseSubtitleChoice(): PersistedReleaseSubtitleChoice? {
    val bingeGroup = currentStreamBingeGroup.normalizedBingeGroup() ?: return null
    if (parentMetaId.isBlank()) return null
    return PlayerTrackPreferenceStorage.loadReleaseSubtitleChoice(parentMetaId, bingeGroup)
}

/**
 * The subtitle choice and delay remembered for the playing release, when "Keep Subtitles Across
 * Episodes" is on. Streams without a binge group have none.
 */
internal fun PlayerScreenRuntime.rememberedReleaseSubtitleChoice(): PersistedReleaseSubtitleChoice? =
    storedReleaseSubtitleChoice()?.takeIf { playerSettingsUiState.keepSubtitlesAcrossEpisodes }

internal fun PlayerScreenRuntime.restorePersistedTrackPreferenceIfNeeded(): Boolean {
    if (trackPreferenceRestoreApplied) return true
    val preference = PlayerTrackPreferenceStorage.load(parentMetaId)
    val remembered = rememberedReleaseSubtitleChoice()?.takeIf { it.subtitleType != null }
    if (preference == null && remembered == null) {
        trackPreferenceRestoreApplied = true
        return true
    }

    preference?.let(::restorePersistedAudioPreference)

    if (!isUserExplicitSubtitleSelection) {
        // The release's remembered choice comes first: it is the newest pick for this release and
        // also covers reopening the item it was made on. Without one, the series preference only
        // reopens a subtitle on the item (and release) it was picked on.
        val subtitleReady = when {
            remembered != null -> restoreRememberedSubtitleChoice(remembered)
            preference != null && sameItemSubtitlePreferenceApplies(
                preference = preference,
                itemId = subtitlePreferenceItemId,
                currentBingeGroup = currentStreamBingeGroup,
            ) -> restoreSameItemSubtitlePreference(preference)
            else -> true
        }
        if (!subtitleReady) return false
    }

    trackPreferenceRestoreApplied = audioTracks.isNotEmpty() || preference == null ||
        (preference.audioTrackId.isNullOrBlank() && preference.audioLanguage.isNullOrBlank() &&
            preference.audioName.isNullOrBlank())
    return true
}

/**
 * Applies the release's remembered subtitle choice. Returns false while it cannot be decided yet
 * (tracks not scanned, or the remembered addon's subtitles not loaded). When the remembered
 * subtitle is not available on this item, auto-selection takes over.
 */
private fun PlayerScreenRuntime.restoreRememberedSubtitleChoice(choice: PersistedReleaseSubtitleChoice): Boolean {
    when (choice.subtitleType) {
        PersistedSubtitleSelectionType.DISABLED -> {
            hideSubtitles()
            markSubtitleChoiceRestored()
        }
        PersistedSubtitleSelectionType.INTERNAL -> {
            if (subtitleTracks.isEmpty() && !hasScannedTextTracksOnce) return false
            val index = findRememberedSubtitleTrackIndex(subtitleTracks, choice, subtitlePreferenceItemId)
            if (index >= 0) {
                showBuiltInSubtitleTrack(index)
                markSubtitleChoiceRestored()
            }
        }
        PersistedSubtitleSelectionType.ADDON -> {
            val exactUrl = choice.addonSubtitleUrl?.takeIf {
                it.isNotBlank() && choice.itemId == subtitlePreferenceItemId
            }
            if (exactUrl != null) {
                showAddonSubtitle(selectionId = exactUrl, url = exactUrl)
                markSubtitleChoiceRestored()
                return true
            }
            if (!addonSubtitlesFetchedForCurrentItem()) return false
            val subtitle = findRememberedAddonSubtitle(addonSubtitles, choice)
            // Wait for the remembered addon while other addons are still answering.
            if (subtitle == null && isLoadingAddonSubtitles) return false
            if (subtitle != null) {
                showAddonSubtitle(selectionId = subtitle.selectionKey, url = subtitle.url)
                markSubtitleChoiceRestored()
                if (subtitle.isVideoHashMatch && !choice.isVideoHashMatch) {
                    // The remembered delay was set for a subtitle not timed for this file; a hash
                    // match is. Not recorded: the release keeps the user's last manual choice.
                    applyStartingSubtitleDelayMs(loadStartingSubtitleDelayMs())
                }
            }
        }
    }
    return true
}

private fun PlayerScreenRuntime.restoreSameItemSubtitlePreference(preference: PersistedPlayerTrackPreference): Boolean {
    when (preference.subtitleType) {
        PersistedSubtitleSelectionType.DISABLED -> {
            hideSubtitles()
            markSubtitleChoiceRestored()
        }
        PersistedSubtitleSelectionType.INTERNAL -> {
            if (subtitleTracks.isEmpty() && !hasScannedTextTracksOnce) return false
            val restoredSubtitleIndex = findPersistedSubtitleTrackIndex(subtitleTracks, preference)
            if (restoredSubtitleIndex >= 0) {
                showBuiltInSubtitleTrack(restoredSubtitleIndex)
                markSubtitleChoiceRestored()
            }
        }
        PersistedSubtitleSelectionType.ADDON -> {
            val persistedUrl = persistedAddonSubtitleUrlForItem(
                preference = preference,
                itemId = subtitlePreferenceItemId,
            )
            if (persistedUrl != null) {
                showAddonSubtitle(selectionId = preference.addonSubtitleId ?: persistedUrl, url = persistedUrl)
                markSubtitleChoiceRestored()
                return true
            }
            if (!addonSubtitlesFetchedForCurrentItem()) return false
            val subtitle = findPersistedAddonSubtitle(addonSubtitles, preference)
            val canRestoreWhileLoading = subtitle != null && (
                preference.addonSubtitleAddonName.isNullOrBlank() ||
                    subtitle.addonName.equals(preference.addonSubtitleAddonName, ignoreCase = true)
                )
            if (isLoadingAddonSubtitles && !canRestoreWhileLoading) return false
            if (subtitle != null) {
                showAddonSubtitle(selectionId = subtitle.selectionKey, url = subtitle.url)
                markSubtitleChoiceRestored()
            }
        }
    }
    return true
}

private fun PlayerScreenRuntime.addonSubtitlesFetchedForCurrentItem(): Boolean {
    val fetchKey = addonSubtitleFetchKey.takeUnless {
        activeSourceUrl.startsWith("file:") && externalSubtitles.isNotEmpty()
    } ?: return true
    return autoFetchedAddonSubtitlesForKey == fetchKey
}

/** A restored choice counts as the user's: auto-selection and hash matches never replace it. */
private fun PlayerScreenRuntime.markSubtitleChoiceRestored() {
    preferredSubtitleSelectionApplied = true
    isUserExplicitSubtitleSelection = true
}

private fun PlayerScreenRuntime.showBuiltInSubtitleTrack(index: Int) {
    if (useCustomSubtitles) {
        playerController?.clearExternalSubtitleAndSelect(index)
    } else {
        playerController?.selectSubtitleTrack(index)
    }
    syncSubtitleTrackSelection(index)
    selectedSubtitleIndex = index
    selectedAddonSubtitleId = null
    useCustomSubtitles = false
}

private fun PlayerScreenRuntime.showAddonSubtitle(selectionId: String, url: String) {
    selectedAddonSubtitleId = selectionId
    selectedSubtitleIndex = -1
    useCustomSubtitles = true
    playerController?.setSubtitleUri(url)
    syncSubtitleTrackSelection(-1)
}

private fun PlayerScreenRuntime.hideSubtitles() {
    playerController?.selectSubtitleTrack(-1)
    syncSubtitleTrackSelection(-1)
    selectedSubtitleIndex = -1
    selectedAddonSubtitleId = null
    useCustomSubtitles = false
}

internal fun PlayerScreenRuntime.refreshTracks() {
    val ctrl = playerController ?: return
    val previousAudioIndex = selectedAudioIndex
    audioTracks = ctrl.getAudioTracks()
    subtitleTracks = ctrl.getSubtitleTracks()
    val selectedAudio = audioTracks.firstOrNull { it.isSelected }
    if (selectedAudio != null) selectedAudioIndex = selectedAudio.index
    val selectedSub = subtitleTracks.firstOrNull { it.isSelected }
    if (selectedSub != null && !useCustomSubtitles) selectedSubtitleIndex = selectedSub.index
    if (!playbackSnapshot.isLoading) {
        hasScannedTextTracksOnce = true
    }

    if (selectedAudioIndex != previousAudioIndex && !isUserExplicitSubtitleSelection) {
        preferredSubtitleSelectionApplied = false
    }

    val subtitlePreferenceReady = restorePersistedTrackPreferenceIfNeeded()

    val preferredAudioTargets = preferredAudioLanguageTargets

    applyPreferredAudioTrack(preferredAudioTargets)

    if (subtitlePreferenceReady && (preferredAudioSelectionApplied || audioTracks.isEmpty())) {
        tryAutoSelectPreferredSubtitleFromAvailableTracks(preferredAudioTargets)
    }
}

// Automatic subtitle selection, used when there is no remembered or restored choice:
//  1. an embedded full subtitle (not forced, not signs/songs/commentary) in the preferred language;
//  2. an addon subtitle in that language the addon matched by the video hash (in sync with this
//     file; it may arrive after playback started and then replaces an automatic addon pick);
//  3. the fallback: the first addon subtitle in that language, then the same for the secondary
//     language (an embedded secondary-language subtitle still beats a secondary addon one).
// With no preferred subtitle language, subtitles stay off.

/**
 * The single decision point for step 3 above: picking an addon subtitle the addon did not match by
 * the video hash. Return false to hold that pick back for now (for example while the hash of the
 * playing file is still being computed, so a hash match can win); selection is retried on the next
 * track refresh and whenever the addon subtitle list changes. Embedded full subtitles, hash matches
 * and remembered choices never go through here.
 */
internal fun PlayerScreenRuntime.isFallbackAddonSubtitlePickAllowed(): Boolean =
    // Bounded wait for the hash-matched list (see holdAddonSubtitleAutoPickWhileVideoHashPending).
    !holdAddonSubtitleAutoPickForVideoHash

private fun PlayerScreenRuntime.tryAutoSelectPreferredSubtitleFromAvailableTracks(
    preferredAudioTargets: List<String>,
) {
    if (isUserExplicitSubtitleSelection) return

    val preferredSubtitleLanguage = normalizeLanguageCode(
        playerSettingsUiState.preferredSubtitleLanguage,
    )
    val preferredSubtitleTargets = if (
        preferredSubtitleLanguage == SubtitleLanguageOption.NONE ||
        preferredSubtitleLanguage == SubtitleLanguageOption.FORCED
    ) {
        emptyList()
    } else {
        resolvePreferredSubtitleLanguageTargets(
            preferredSubtitleLanguage = playerSettingsUiState.preferredSubtitleLanguage,
            secondaryPreferredSubtitleLanguage = playerSettingsUiState.secondaryPreferredSubtitleLanguage,
            deviceLanguages = DeviceLanguagePreferences.preferredLanguageCodes(),
        )
    }
    val primaryTarget = preferredSubtitleTargets.firstOrNull()

    if (preferredSubtitleSelectionApplied) {
        val currentSelectedLang = when {
            selectedAddonSubtitle != null -> selectedAddonSubtitle?.language
            selectedSubtitleIndex >= 0 -> subtitleTracks.firstOrNull { it.index == selectedSubtitleIndex }?.language
            else -> null
        }
        val isPrimarySatisfied = primaryTarget != null && currentSelectedLang != null &&
            SubtitleLanguageMatching.matchesLanguageCode(currentSelectedLang, primaryTarget)
        val hasBetterAddonMatch = !isPrimarySatisfied && primaryTarget != null &&
            addonSubtitles.any { SubtitleLanguageMatching.matchesLanguageCode(it.language, primaryTarget) }
        if (!hasBetterAddonMatch) return
    }

    val selectedAudioTrack = audioTracks.firstOrNull { track -> track.index == selectedAudioIndex }
        ?: audioTracks.firstOrNull { it.isSelected }
    val selectionPlan = resolveSubtitleAutoSelectionPlan(
        selectedAudioTrack = selectedAudioTrack,
        preferredAudioTargets = preferredAudioTargets,
        preferredSubtitleTargets = preferredSubtitleTargets,
        useForcedSubtitles = subtitleStyle.useForcedSubtitles,
        onlyForOtherAudioLanguage = isOriginalAudioPreference,
    )
    if (selectionPlan == null) {
        disableAutomaticSubtitleSelection()
        return
    }
    if (selectionPlan.targets.isEmpty()) {
        disableAutomaticSubtitleSelection()
        preferredSubtitleSelectionApplied = true
        return
    }

    val internalIndex = findPreferredSubtitleTrackIndex(
        tracks = subtitleTracks,
        targets = selectionPlan.targets,
        mode = selectionPlan.mode,
        selectedAudioTrack = selectedAudioTrack,
    )
    if (internalIndex >= 0 && hasScannedTextTracksOnce) {
        val matchedTrack = subtitleTracks[internalIndex]
        val trackVariant = SubtitleLanguageMatching.detectTrackLanguageVariant(
            language = matchedTrack.language,
            name = matchedTrack.label,
            trackId = matchedTrack.id,
        )
        val matchedTargetPosition = selectionPlan.targets.indexOfFirst { target ->
            val normalizedTarget = SubtitleLanguageMatching.normalizeLanguageCode(target)
            trackVariant == normalizedTarget ||
                SubtitleLanguageMatching.matchesLanguageCode(trackVariant, target)
        }
        if (matchedTargetPosition > 0 && isLoadingAddonSubtitles) {
            return
        }
        if (matchedTargetPosition > 0 && !isLoadingAddonSubtitles) {
            val primaryAddonMatch = addonSubtitles.preferringVideoHashMatches().firstOrNull { subtitle ->
                SubtitleLanguageMatching.matchesLanguageCode(subtitle.language, selectionPlan.targets.first())
            }
            if (primaryAddonMatch != null) {
                if (!primaryAddonMatch.isVideoHashMatch && !isFallbackAddonSubtitlePickAllowed()) return
                preferredSubtitleSelectionApplied = true
                selectedAddonSubtitleId = primaryAddonMatch.selectionKey
                selectedSubtitleIndex = -1
                useCustomSubtitles = true
                playerController?.setSubtitleUri(primaryAddonMatch.url)
                syncSubtitleTrackSelection(-1)
                return
            }
        }
        preferredSubtitleSelectionApplied = true
        if (selectedSubtitleIndex != internalIndex || selectedAddonSubtitleId != null) {
            if (useCustomSubtitles) {
                playerController?.clearExternalSubtitleAndSelect(internalIndex)
            } else {
                playerController?.selectSubtitleTrack(internalIndex)
            }
            syncSubtitleTrackSelection(internalIndex)
            selectedSubtitleIndex = internalIndex
            selectedAddonSubtitleId = null
            useCustomSubtitles = false
        }
        return
    }

    if (selectionPlan.mode == SubtitleAutoSelectionMode.FORCED_ONLY) {
        val requiredForcedTarget = selectionPlan.targets.firstOrNull() ?: return
        if (!hasScannedTextTracksOnce) return
        if (isLoadingAddonSubtitles) {
            val currentTrack = subtitleTracks.firstOrNull { it.index == selectedSubtitleIndex }
            if (currentTrack != null && !currentTrack.isForced) {
                disableAutomaticSubtitleSelection()
            }
            return
        }
        val forcedAddonMatch = if (selectedAudioTrack != null) {
            addonSubtitles.preferringVideoHashMatches().firstOrNull { subtitle ->
                addonSubtitleIsForced(subtitle) &&
                    addonSubtitleMatchesLanguage(subtitle, requiredForcedTarget) &&
                    addonSubtitleMatchesSelectedAudioLanguage(subtitle, selectedAudioTrack)
            }
        } else {
            null
        }
        preferredSubtitleSelectionApplied = true
        if (forcedAddonMatch != null) {
            selectedAddonSubtitleId = forcedAddonMatch.selectionKey
            selectedSubtitleIndex = -1
            useCustomSubtitles = true
            playerController?.setSubtitleUri(forcedAddonMatch.url)
            syncSubtitleTrackSelection(-1)
        } else {
            disableAutomaticSubtitleSelection()
        }
        return
    }

    val selectedAddon = selectedAddonSubtitle
    val selectedAddonMatchesTarget = selectedAddon != null &&
        (!subtitleStyle.useForcedSubtitles || !addonSubtitleIsForced(selectedAddon)) &&
        selectionPlan.targets.any { target ->
            SubtitleLanguageMatching.matchesLanguageCode(selectedAddon.language, target)
        }
    if (selectedAddonMatchesTarget && selectedAddon != null) {
        val selectedMatchesPrimary = SubtitleLanguageMatching.matchesLanguageCode(
            selectedAddon.language,
            selectionPlan.targets.first(),
        )
        if (selectedMatchesPrimary) {
            preferredSubtitleSelectionApplied = true
            return
        }
    }

    if (!hasScannedTextTracksOnce) return
    if (playbackSnapshot.isLoading) return

    val addonMatch = selectionPlan.targets.firstNotNullOfOrNull { target ->
        addonSubtitles.preferringVideoHashMatches().firstOrNull { subtitle ->
            (!subtitleStyle.useForcedSubtitles || !addonSubtitleIsForced(subtitle)) &&
                SubtitleLanguageMatching.matchesLanguageCode(subtitle.language, target)
        }
    }
    if (addonMatch != null) {
        if (!addonMatch.isVideoHashMatch && !isFallbackAddonSubtitlePickAllowed()) return
        preferredSubtitleSelectionApplied = true
        selectedAddonSubtitleId = addonMatch.selectionKey
        selectedSubtitleIndex = -1
        useCustomSubtitles = true
        playerController?.setSubtitleUri(addonMatch.url)
        syncSubtitleTrackSelection(-1)
    } else if (!preferredSubtitleSelectionApplied) {
        disableAutomaticSubtitleSelection()
        preferredSubtitleSelectionApplied = true
    }
}

/**
 * After a refetch with the video hash, swaps an automatically chosen addon subtitle for one the
 * addon matched by hash (same language), since that one is in sync with this file.
 * Never overrides a subtitle the user picked or restored.
 */
internal fun PlayerScreenRuntime.tryReplaceAutoSelectedSubtitleWithVideoHashMatch() {
    if (isUserExplicitSubtitleSelection || !preferredSubtitleSelectionApplied) return
    val replacement = findVideoHashMatchedReplacement(
        selected = selectedAddonSubtitle,
        subtitles = addonSubtitles,
    ) ?: return
    selectedAddonSubtitleId = replacement.selectionKey
    selectedSubtitleIndex = -1
    useCustomSubtitles = true
    playerController?.setSubtitleUri(replacement.url)
    syncSubtitleTrackSelection(-1)
}

private fun PlayerScreenRuntime.disableAutomaticSubtitleSelection() {
    if (selectedSubtitleIndex != -1 || subtitleTracks.any { it.isSelected }) {
        playerController?.selectSubtitleTrack(-1)
    }
    selectedSubtitleIndex = -1
    selectedAddonSubtitleId = null
    useCustomSubtitles = false
    syncSubtitleTrackSelection(-1)
}

/**
 * Mirrors a subtitle selection the runtime just sent to the player in [subtitleTracks].
 *
 * The track list is only re-read from the player in [refreshTracks], and the subtitle menus fall
 * back to the track the player reports as selected when no index is chosen. Without this, turning
 * off a track the player had auto-selected (for example a default-flagged embedded track while the
 * preferred subtitle language is None) left that track marked as selected in the menu although
 * nothing was shown. [selectedIndex] is -1 for "off" and for addon subtitles, which are separate
 * external tracks in the player.
 */
internal fun PlayerScreenRuntime.syncSubtitleTrackSelection(selectedIndex: Int) {
    if (subtitleTracks.all { it.isSelected == (it.index == selectedIndex) }) return
    subtitleTracks = subtitleTracks.map { it.copy(isSelected = it.index == selectedIndex) }
}
