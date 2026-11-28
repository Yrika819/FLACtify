package com.flactify.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun `fractional precision is scaled to milliseconds`() {
        assertEquals(
            listOf(1_000L to "four", 1_234L to "one", 1_250L to "two", 1_300L to "three"),
            LrcParser.parse("[00:01.234]one\n[00:01.25]two\n[00:01.3]three\n[00:01]four")
        )
    }

    @Test
    fun `multiple timestamps create stable line entries and offset is applied`() {
        assertEquals(
            listOf(1_500L to "same", 2_500L to "same", 3_000L to "later"),
            LrcParser.parse("[offset:+500]\n[00:01.00][00:02.00]same\n[00:02.50]later")
        )
    }

    @Test
    fun `bom, untimed text and malformed bracket lines are handled`() {
        assertEquals(
            listOf(1_000L to "timed", -1L to "plain text"),
            LrcParser.parse("\uFEFF[00:01.00]timed\nplain text\n[not-a-timestamp]ignored")
        )
    }

    @Test
    fun `enhanced lrc strips word markers and retains their timings`() {
        val lines = LrcParser.parseDetailed(
            "[00:01.00]<00:01.10>Hello <00:01.20>world"
        )

        assertEquals(1_000L, lines.single().timestampMs)
        assertEquals("Hello world", lines.single().text)
        assertEquals(
            listOf(LrcWord(1_100L, "Hello"), LrcWord(1_200L, "world")),
            lines.single().words
        )
    }

    @Test
    fun `standalone enhanced lrc uses first word timestamp and offset once`() {
        val lines = LrcParser.parseDetailed("[offset:-100]\n<00:01.00>hello <00:01.50>there")

        assertEquals(900L, lines.single().timestampMs)
        assertEquals(listOf(LrcWord(900L, "hello"), LrcWord(1_400L, "there")), lines.single().words)
    }

    @Test
    fun `invalid seconds do not become timed lyrics`() {
        val result = LrcParser.parse("[00:60.00]bad\n[00:01.00]good")

        assertEquals(listOf(1_000L to "good"), result)
        assertTrue(result.none { it.second == "bad" })
    }
}
