package com.metrolist.shared.api.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BetterLyricsTtmlSpacingTest {
    @Test
    fun contiguousTimedFragmentsStayJoinedButARealPauseSeparatesWords() {
        val ttml =
            """
            <tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="1s" end="3s"><span begin="1s" end="1.4s">call</span> <span begin="1.4s" end="1.8s">ing</span> <span begin="2s" end="3s">out</span></p></div></body></tt>
            """.trimIndent()

        val line = assertNotNull(MlxParser.parse(parseTtmlToMlx(ttml, "Song", "Artist", "test"))).lines.single()
        val words = assertNotNull(line.words)
        assertEquals("calling out", line.text)
        assertEquals(listOf("call", "ing", "out"), words.map { it.text })
        assertEquals(listOf(false, true, false), words.map { it.hasTrailingSpace })
    }

    @Test
    fun explicitSpaceInsideTimedSpanRemainsAWordBoundary() {
        val ttml =
            """
            <tt xmlns="http://www.w3.org/ns/ttml"><body><div><p begin="1s" end="2s"><span begin="1s" end="1.5s">call </span><span begin="1.5s" end="2s">ing</span></p></div></body></tt>
            """.trimIndent()

        val line = assertNotNull(MlxParser.parse(parseTtmlToMlx(ttml, "Song", "Artist", "test"))).lines.single()
        val words = assertNotNull(line.words)
        assertEquals("call ing", line.text)
        assertEquals(listOf(true, false), words.map { it.hasTrailingSpace })
    }
}
