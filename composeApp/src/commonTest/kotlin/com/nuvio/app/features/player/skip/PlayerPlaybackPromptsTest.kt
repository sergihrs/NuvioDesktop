package com.nuvio.app.features.player.skip

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlayerPlaybackPromptsTest {
    private val durationMs = 3_300_000L
    private val outroToEnd = SkipInterval(startTime = 3_195.0, endTime = 3_299.0, type = "outro", provider = "introdb")

    @Test
    fun outroRunningToTheEndStartsTheNextEpisodeCardWhileTheOutroIsActive() {
        // The overlap this rule resolves: inside the outro, the card is already due.
        val positionMs = 3_240_000L
        assertTrue(positionMs / 1000.0 >= outroToEnd.startTime && positionMs / 1000.0 < outroToEnd.endTime)
        assertTrue(
            PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
                positionMs = positionMs,
                durationMs = durationMs,
                skipIntervals = listOf(outroToEnd),
                thresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
                thresholdPercent = 99f,
                thresholdMinutesBeforeEnd = 2f,
            ),
        )
    }

    @Test
    fun playableNextEpisodeReplacesTheSkipButton() {
        assertFalse(
            shouldShowSkipPrompt(
                hasSkipSegment = true,
                controlsLocked = false,
                nextEpisodeCardVisible = true,
                nextEpisodePlayable = true,
            ),
        )
    }

    @Test
    fun unairedNextEpisodeKeepsTheSkipButton() {
        assertTrue(
            shouldShowSkipPrompt(
                hasSkipSegment = true,
                controlsLocked = false,
                nextEpisodeCardVisible = true,
                nextEpisodePlayable = false,
            ),
        )
    }

    @Test
    fun skipButtonShowsWhenNoNextEpisodeCardIsDue() {
        // Mid-episode intro/recap, or the credits of a movie or a last episode.
        assertTrue(
            shouldShowSkipPrompt(
                hasSkipSegment = true,
                controlsLocked = false,
                nextEpisodeCardVisible = false,
                nextEpisodePlayable = true,
            ),
        )
    }

    @Test
    fun noSkipButtonWithoutASegmentOrWhileLocked() {
        assertFalse(shouldShowSkipPrompt(false, controlsLocked = false, nextEpisodeCardVisible = false, nextEpisodePlayable = false))
        assertFalse(shouldShowSkipPrompt(true, controlsLocked = true, nextEpisodeCardVisible = false, nextEpisodePlayable = false))
    }
}
