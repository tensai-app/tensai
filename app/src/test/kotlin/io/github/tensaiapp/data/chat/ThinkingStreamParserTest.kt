package io.github.tensaiapp.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThinkingStreamParserTest {

    @Test
    fun noThinkTagsGoStraightToContent() {
        val parser = ThinkingStreamParser()

        parser.accept("Hello, ")
        parser.accept("world.")
        parser.finish()

        assertEquals("Hello, world.", parser.content)
        assertNull(parser.thinking)
    }

    @Test
    fun nominalCase_wholeTagsInOneChunkEach() {
        val parser = ThinkingStreamParser()

        parser.accept("<think>")
        parser.accept("reasoning about the question")
        parser.accept("</think>")
        parser.accept("the answer")
        parser.finish()

        assertEquals("the answer", parser.content)
        assertEquals("reasoning about the question", parser.thinking)
    }

    @Test
    fun tagsFragmentedAcrossManyChunks_evenOneCharacterAtATime() {
        val parser = ThinkingStreamParser()
        val stream = "<think>step one, step two</think>final answer"

        stream.forEach { char -> parser.accept(char.toString()) }
        parser.finish()

        assertEquals("final answer", parser.content)
        assertEquals("step one, step two", parser.thinking)
    }

    @Test
    fun openingTagSplitAcrossChunkBoundary() {
        val parser = ThinkingStreamParser()

        parser.accept("<thi")
        parser.accept("nk>reasoning</think>answer")
        parser.finish()

        assertEquals("answer", parser.content)
        assertEquals("reasoning", parser.thinking)
    }

    @Test
    fun closingTagSplitAcrossChunkBoundary() {
        val parser = ThinkingStreamParser()

        parser.accept("<think>reasoning</th")
        parser.accept("ink>answer")
        parser.finish()

        assertEquals("answer", parser.content)
        assertEquals("reasoning", parser.thinking)
    }

    @Test
    fun textThatLooksLikeATagPrefixButNeverCompletesIsFlushedAsContentOnFinish() {
        val parser = ThinkingStreamParser()

        parser.accept("no tags here, just a stray <th")
        parser.finish()

        assertEquals("no tags here, just a stray <th", parser.content)
        assertNull(parser.thinking)
    }

    @Test
    fun neverClosedThinkTagLeavesContentEmptyAndThinkingActive() {
        val parser = ThinkingStreamParser()

        parser.accept("<think>still reasoning")
        parser.accept(", more of it")
        parser.finish()

        assertEquals("", parser.content)
        assertEquals("still reasoning, more of it", parser.thinking)
    }

    @Test
    fun textBeforeTheThinkTagCountsAsContent() {
        val parser = ThinkingStreamParser()

        parser.accept("preamble ")
        parser.accept("<think>reasoning</think>")
        parser.accept(" the answer")
        parser.finish()

        assertEquals("preamble  the answer", parser.content)
        assertEquals("reasoning", parser.thinking)
    }

    /**
     * Pins what the parser does once the pair has closed: nothing is a tag any more, so a second
     * `<think>` is text like any other. Only the *first* pair is a pair (see the class KDoc),
     * which is why `process()`'s state transition has no case for "a tag matched while already
     * past the think": it can't be reached, and this is the input that would reach it.
     */
    @Test
    fun aSecondThinkPairAfterTheFirstOneClosedIsPlainContent() {
        val parser = ThinkingStreamParser()

        parser.accept("<think>first</think>answer <think>second</think> tail")
        parser.finish()

        assertEquals("answer <think>second</think> tail", parser.content)
        assertEquals("first", parser.thinking)
    }

    @Test
    fun textAfterClosingTagAlsoFragmentedIsStillRoutedToContent() {
        val parser = ThinkingStreamParser()

        parser.accept("<think>r</think>")
        parser.accept("the ")
        parser.accept("answer")
        parser.finish()

        assertEquals("the answer", parser.content)
        assertEquals("r", parser.thinking)
    }
}
