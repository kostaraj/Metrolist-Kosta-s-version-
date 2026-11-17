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
    fun dimAndBrightShineThresholdsAreIndependent() {
        assertEquals(0f, expressiveWordShineStrength(499L))
        assertEquals(0.42f, expressiveWordShineStrength(500L))
        assertEquals(0.42f, expressiveWordShineStrength(649L))
        assertEquals(1f, expressiveWordShineStrength(650L))
    }

    @Test
    fun glyphRevealIsContinuousWithoutShine() {
        val unlit = Color(0.2f, 0.2f, 0.2f, 0.35f)
        val lit = Color(0.8f, 0.5f, 0.3f, 1f)

        assertEquals(unlit, expressiveGlyphRevealColor(unlit, lit, 0f))
        assertEquals(lit, expressiveGlyphRevealColor(unlit, lit, 1f))
        val middle = expressiveGlyphRevealColor(unlit, lit, 0.5f)
        assertTrue(middle.alpha > unlit.alpha)
        assertTrue(middle.alpha < lit.alpha)
    }
}
