package io.github.hokanosekai.tensai.data.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEN-47. The interesting cases are all about the handover between the two sources: which one wins,
 * when, and what happens in the gaps where the engine says nothing.
 */
class ReplyStreamSplitterTest {

    @Test
    fun noEngineSplit_behavesExactlyLikeTheThinkTagParser() {
        val splitter = ReplyStreamSplitter()

        "<think>reasoning</think>the answer".forEach { splitter.accept(it.toString()) }
        splitter.finish()

        assertFalse(splitter.usingEngineSplit)
        assertEquals("the answer", splitter.content)
        assertEquals("reasoning", splitter.thinking)
    }

    @Test
    fun noEngineSplit_andNoThinkTags_isAllContent() {
        val splitter = ReplyStreamSplitter()

        splitter.accept("just an answer")
        splitter.finish()

        assertEquals("just an answer", splitter.content)
        assertNull(splitter.thinking)
    }

    @Test
    fun engineSplitWins_overWhatTheTagParserMadeOfTheSameText() {
        val splitter = ReplyStreamSplitter()

        // What a Gemma-4 / harmony style reply looks like to the tag parser: no <think> anywhere,
        // so all of it — markers included — would land in content. That was the TEN-47 bug.
        val raw = "<|channel|>thought reasoning<|channel|>final the answer"
        splitter.accept(raw)
        splitter.acceptParsed(content = "the answer", thinking = "reasoning")
        splitter.finish()

        assertTrue(splitter.usingEngineSplit)
        assertEquals("the answer", splitter.content)
        assertEquals("reasoning", splitter.thinking)
    }

    @Test
    fun engineSplitWithoutReasoning_reportsNoThinking() {
        val splitter = ReplyStreamSplitter()

        splitter.accept("the answer")
        splitter.acceptParsed(content = "the answer", thinking = null)
        splitter.finish()

        assertTrue(splitter.usingEngineSplit)
        assertEquals("the answer", splitter.content)
        assertNull(splitter.thinking)
    }

    @Test
    fun emptyEngineSplit_isIgnoredRatherThanBlankingTheFallback() {
        val splitter = ReplyStreamSplitter()

        splitter.accept("partial answer")
        splitter.acceptParsed(content = "", thinking = null)
        splitter.acceptParsed(content = "", thinking = "")

        assertFalse(splitter.usingEngineSplit)
        assertEquals("partial answer", splitter.content)
    }

    @Test
    fun engineSplitIsCumulative_lastOneWins() {
        val splitter = ReplyStreamSplitter()

        splitter.acceptParsed(content = "", thinking = "reason")
        splitter.acceptParsed(content = "", thinking = "reasoning so")
        splitter.acceptParsed(content = "the", thinking = "reasoning so far")
        splitter.acceptParsed(content = "the answer", thinking = "reasoning so far")
        splitter.finish()

        assertEquals("the answer", splitter.content)
        assertEquals("reasoning so far", splitter.thinking)
    }

    @Test
    fun onceEngineSplitTakesOver_aGapInItDoesNotFallBack() {
        val splitter = ReplyStreamSplitter()

        splitter.accept("<think>tag-parser reasoning</think>")
        splitter.acceptParsed(content = "", thinking = "engine reasoning")
        // Tokens keep arriving while the engine holds text back on an ambiguous marker: the
        // fallback keeps advancing, but nothing it produces may resurface.
        splitter.accept("tag-parser answer")
        splitter.finish()

        assertTrue(splitter.usingEngineSplit)
        assertEquals("", splitter.content)
        assertEquals("engine reasoning", splitter.thinking)
    }

    @Test
    fun engineSplitArrivingLate_stillReplacesWhatTheFallbackHadResolved() {
        val splitter = ReplyStreamSplitter()

        "<think>tag reasoning</think>tag answer".forEach { splitter.accept(it.toString()) }
        splitter.finish()
        assertEquals("tag answer", splitter.content)

        splitter.acceptParsed(content = "engine answer", thinking = "engine reasoning")

        assertEquals("engine answer", splitter.content)
        assertEquals("engine reasoning", splitter.thinking)
    }

    @Test
    fun engineSplitCanRetract_aShorterSnapshotIsNotMergedWithTheLongerOne() {
        val splitter = ReplyStreamSplitter()

        // The parser is allowed to change its mind: text it read as content can turn out to be the
        // start of a marker. Snapshots replace, they never accumulate.
        splitter.acceptParsed(content = "the answer <|chan", thinking = null)
        splitter.acceptParsed(content = "the answer ", thinking = "afterthought")

        assertEquals("the answer ", splitter.content)
        assertEquals("afterthought", splitter.thinking)
    }
}
