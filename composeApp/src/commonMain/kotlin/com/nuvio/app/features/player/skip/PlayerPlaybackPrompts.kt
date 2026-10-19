package com.nuvio.app.features.player.skip

/**
 * Whether the desktop player offers the skip button for the active segment.
 *
 * Near the end of an episode both prompts can be due at once: when the outro runs to the end of the file,
 * [PlayerNextEpisodeRules.shouldShowNextEpisodeCard] starts the next-episode card at the outro start, while the
 * outro is still the active skip segment. Skipping it then only seeks to the end of the file, so a playable next
 * episode replaces the skip button and is the single action (and the Enter target). An unaired next episode
 * cannot be played, so the skip button stays in that case.
 */
internal fun shouldShowSkipPrompt(
    hasSkipSegment: Boolean,
    controlsLocked: Boolean,
    nextEpisodeCardVisible: Boolean,
    nextEpisodePlayable: Boolean,
): Boolean = hasSkipSegment && !controlsLocked && !(nextEpisodeCardVisible && nextEpisodePlayable)
