package com.metrolist.shared.ui.components.lyrics.words

import kotlin.test.Test
import kotlin.test.assertEquals

class LyricsAnimationTimingTest {
    @Test
    fun shortWordFillsAcrossMinimumAnimationDuration() {
        assertEquals(0.5f, lyricsWordAnimationProgress(positionMs = 50L, startMs = 0L, endMs = 20L))
        assertEquals(1f, lyricsWordAnimationProgress(positionMs = 100L, startMs = 0L, endMs = 20L))
    }

    @Test
    fun longerWordKeepsItsTimestampDuration() {
        assertEquals(0.5f, lyricsWordAnimationProgress(positionMs = 150L, startMs = 100L, endMs = 200L))
    }

    @Test
    fun lineHighlightStartsDuringPredictiveOverlapBeforeLineTimestamp() {
        assertEquals(
            true,
            shouldAnimateLineHighlight(
                isActiveLine = true,
                currentPositionMs = 900L,
                effectiveEndTimeMs = 2_000L,
                isFinishing = false,
            ),
        )
    }

    @Test
    fun inactiveLineDoesNotHighlightEarlyAndCompletedLineDoesNotRestart() {
        assertEquals(
            false,
            shouldAnimateLineHighlight(
                isActiveLine = false,
                currentPositionMs = 900L,
                effectiveEndTimeMs = 2_000L,
                isFinishing = false,
            ),
        )
        assertEquals(
            false,
            shouldAnimateLineHighlight(
                isActiveLine = true,
                currentPositionMs = 2_001L,
                effectiveEndTimeMs = 2_000L,
                isFinishing = false,
            ),
        )
        assertEquals(
            true,
            shouldAnimateLineHighlight(
                isActiveLine = false,
                currentPositionMs = 2_001L,
                effectiveEndTimeMs = 2_000L,
                isFinishing = true,
            ),
        )
    }
}
