package com.metrolist.shared.ui.components.lyrics.words

import androidx.compose.ui.graphics.Color
import com.metrolist.shared.model.WordTimestamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LyricsSyllableGroupingTest {
    @Test
    fun sourceTextOverridesStaleFragmentSpaceFlags() {
        val words =
            listOf(
                WordTimestamp("call", 1.0, 1.3, hasTrailingSpace = true),
                WordTimestamp("ing", 1.3, 1.8, hasTrailingSpace = true),
                WordTimestamp("alone", 2.0, 3.0, hasTrailingSpace = false),
            )
        val sourceText = "calling alone"

        val grouped = groupSyllableFragments(words, sourceText)
        assertEquals(listOf("calling", "alone"), grouped.map { it.second.text })

        val reconstructed = reconstructWords(alignSyllablesForFormatting(words, sourceText), sourceText)
        assertEquals(
            listOf("calling", "alone"),
            reconstructed.map { word -> word.syllables.joinToString("") { it.syllable.text } },
        )
    }

    @Test
    fun realSpacesStillSeparateWords() {
        val words =
            listOf(
                WordTimestamp("call", 1.0, 1.3, hasTrailingSpace = false),
                WordTimestamp("ing", 1.3, 1.8, hasTrailingSpace = false),
            )
        val sourceText = "call ing"

        assertEquals(2, groupSyllableFragments(words, sourceText).size)
        assertEquals(2, reconstructWords(alignSyllablesForFormatting(words, sourceText).also { }, sourceText).size)
    }

    @Test
    fun sourceSpacesArePreservedRegardlessOfFragmentTiming() {
        val words =
            listOf(
                WordTimestamp("call", 1.0, 1.3, hasTrailingSpace = true),
                WordTimestamp("ing", 1.3, 1.65, hasTrailingSpace = true),
                WordTimestamp("alone", 1.66, 2.2, hasTrailingSpace = false),
            )

        assertEquals("call ing alone", normalizeSyllableFragmentSpacing("call ing alone", words))
    }

    @Test
    fun punctuationPreventsContiguousFragmentsFromBeingJoined() {
        val words =
            listOf(
                WordTimestamp("wait,", 1.0, 1.3, hasTrailingSpace = true),
                WordTimestamp("no", 1.3, 1.6, hasTrailingSpace = false),
            )

        assertEquals("wait, no", normalizeSyllableFragmentSpacing("wait, no", words))
    }

    @Test
    fun unshinedGlyphTransitionsOverlapForMoreRenderedFrames() {
        val shortWord = WordTimestamp("stutter", 1.0, 1.21, hasTrailingSpace = false)

        val secondGlyphAtStart = experimentalGlyphProgress(shortWord, 1_030L, glyphIndex = 1, glyphCount = 7)
        val secondGlyphHalfway = experimentalGlyphProgress(shortWord, 1_110L, glyphIndex = 1, glyphCount = 7)
        val firstGlyphAtSameTime = experimentalGlyphProgress(shortWord, 1_110L, glyphIndex = 0, glyphCount = 7)

        assertEquals(0f, secondGlyphAtStart)
        assertEquals(0.5f, secondGlyphHalfway)
        assertTrue(firstGlyphAtSameTime > secondGlyphHalfway)
        assertTrue(firstGlyphAtSameTime < 1f)
    }

    @Test
    fun wordCompletionWaitsForTheLastOverlappingUnshinedGlyph() {
        val shortWord = WordTimestamp("stutter", 1.0, 1.21, hasTrailingSpace = false)
        val revealDurationMs = expressiveGlyphRevealDurationMs(shortWord, glyphCount = 7)

        assertEquals(340L, revealDurationMs)
        assertTrue(experimentalGlyphProgress(shortWord, 1_339L, glyphIndex = 6, glyphCount = 7) < 1f)
        assertEquals(1f, experimentalGlyphProgress(shortWord, 1_340L, glyphIndex = 6, glyphCount = 7))
    }

    @Test
    fun shineEnabledGlyphTimingRemainsUnchanged() {
        val shinyWord = WordTimestamp("longerword", 1.0, 2.0, hasTrailingSpace = false)

        assertEquals(1_000L, expressiveGlyphRevealDurationMs(shinyWord, glyphCount = 10))
        assertEquals(0.5f, experimentalGlyphProgress(shinyWord, 1_050L, glyphIndex = 0, glyphCount = 10))
        assertEquals(0.5f, experimentalGlyphProgress(shinyWord, 1_150L, glyphIndex = 1, glyphCount = 10))
    }

    @Test
    fun dimAndBrightShineThresholdsAreIndependent() {
        assertEquals(0f, expressiveWordShineStrength(749L))
        assertEquals(0.42f, expressiveWordShineStrength(750L))
        assertEquals(0.42f, expressiveWordShineStrength(999L))
        assertEquals(1f, expressiveWordShineStrength(1_000L))
    }

    @Test
    fun unshinedGlyphColorRevealRemainsSmoothThroughCompletion() {
        val unlit = Color(0.2f, 0.2f, 0.2f, 0.35f)
        val lit = Color(0.8f, 0.5f, 0.3f, 1f)

        val atStart = expressiveGlyphRevealColor(unlit, lit, 0f)
        val halfway = expressiveGlyphRevealColor(unlit, lit, 0.5f)
        val almostDone = expressiveGlyphRevealColor(unlit, lit, 0.99f)
        val complete = expressiveGlyphRevealColor(unlit, lit, 1f)

        assertEquals(unlit, atStart)
        assertEquals(lit, complete)
        assertTrue(halfway.alpha > unlit.alpha)
        assertTrue(halfway.alpha < lit.alpha)
        assertTrue(almostDone.alpha > halfway.alpha)
        assertTrue(lit.alpha - almostDone.alpha < 0.001f)
        assertTrue(lit.red - almostDone.red < 0.001f)
    }

    @Test
    fun shineWipeEdgeBrightensFromDimToBrightShine() {
        val unlit = Color(0.2f, 0.2f, 0.2f, 0.35f)
        val lit = Color(0.8f, 0.5f, 0.3f, 1f)

        val dimShine = expressiveGlyphWipeEdgeColor(unlit, lit, expressiveWordShineStrength(750L))
        val brightShine = expressiveGlyphWipeEdgeColor(unlit, lit, expressiveWordShineStrength(1_000L))

        assertTrue(dimShine.alpha > unlit.alpha)
        assertTrue(dimShine.alpha < lit.alpha)
        assertTrue(brightShine.alpha > dimShine.alpha)
        assertTrue(brightShine.red > dimShine.red)
    }
}
