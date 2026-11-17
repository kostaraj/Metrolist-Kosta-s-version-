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
    fun unshinedWordsGetEnoughTimeForEachGlyphToRevealSmoothly() {
        val shortWord = WordTimestamp("stutter", 1.0, 1.2, hasTrailingSpace = false)
        val duration = EXPRESSIVE_UNSHINED_GLYPH_REVEAL_MS * 7
        val beforeSecondGlyph = experimentalGlyphProgress(shortWord, 1_064L, 1, 7)
        val atSecondGlyphStart = experimentalGlyphProgress(shortWord, 1_064L, 1, 7)
        val halfwayThroughSecondGlyph = experimentalGlyphProgress(shortWord, 1_096L, 1, 7)

        assertEquals(0f, beforeSecondGlyph)
        assertTrue(halfwayThroughSecondGlyph > atSecondGlyphStart)
        assertTrue(halfwayThroughSecondGlyph < 1f)
        assertTrue(duration > 500.0)
    }

    @Test
    fun dimAndBrightShineThresholdsAreIndependent() {
        assertEquals(0f, expressiveWordShineStrength(499L))
        assertEquals(0.42f, expressiveWordShineStrength(500L))
        assertEquals(0.42f, expressiveWordShineStrength(649L))
        assertEquals(1f, expressiveWordShineStrength(650L))
    }

    @Test
    fun wipeEdgeRemainsVisibleWithoutShineAndBrightensAtShineThresholds() {
        val unlit = Color(0.2f, 0.2f, 0.2f, 0.35f)
        val lit = Color(0.8f, 0.5f, 0.3f, 1f)

        val noShine = expressiveGlyphWipeEdgeColor(unlit, lit, expressiveWordShineStrength(499L))
        val dimShine = expressiveGlyphWipeEdgeColor(unlit, lit, expressiveWordShineStrength(500L))
        val brightShine = expressiveGlyphWipeEdgeColor(unlit, lit, expressiveWordShineStrength(650L))

        assertTrue(noShine.alpha > unlit.alpha)
        assertTrue(noShine.alpha < lit.alpha)
        assertTrue(dimShine.alpha > noShine.alpha)
        assertTrue(brightShine.alpha > dimShine.alpha)
        assertTrue(brightShine.red > dimShine.red)
    }
}
