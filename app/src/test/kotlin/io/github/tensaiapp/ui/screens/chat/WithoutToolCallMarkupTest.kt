package io.github.tensaiapp.ui.screens.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for [withoutToolCallMarkup] (TEN-221): a call written as text in a final answer is not shown. */
class WithoutToolCallMarkupTest {

    /** The phone's case: LFM3 writing Qwen3-Coder's shape, its closing parameter and function tags missing. */
    @Test
    fun aForeignCallWithMissingClosingTagsLeavesNothing() {
        assertEquals("", withoutToolCallMarkup("<tool_call> <function=web_search> <parameter=query> Lyon current weather </tool_call>"))
    }

    @Test
    fun proseAroundAStrayCallIsKept() {
        val text = "I could not check the weather.\n\n<tool_call>\n{\"name\": \"weather\", \"arguments\": {\"city\": \"Lyon\"}}\n</tool_call>\n\nTry again later."
        assertEquals("I could not check the weather.\n\nTry again later.", withoutToolCallMarkup(text))
    }

    @Test
    fun anUnclosedCallAtTheEndIsStripped() {
        assertEquals("Let me look that up.", withoutToolCallMarkup("Let me look that up.\n<tool_call>\n{\"name\": \"web_search\", \"arguments\": {\"query\": \"Lyon"))
    }

    @Test
    fun lfmOwnMarkersAreStrippedClosedOrNot() {
        assertEquals("Checking.", withoutToolCallMarkup("Checking. <|tool_call_start|>[weather(city=\"Lyon\")]<|tool_call_end|>"))
        assertEquals("Checking.", withoutToolCallMarkup("Checking. <|tool_call_start|>[weather(city=\"Ly"))
    }

    @Test
    fun aBareFunctionTagIsStripped() {
        assertEquals("Sure.", withoutToolCallMarkup("Sure. <function=weather>{\"city\": \"Lyon\"}</function>"))
    }

    @Test
    fun proseMentioningAFunctionIsUntouched() {
        val text = "A function maps each input to one output.\n\n\nIn Kotlin, `fun` declares a function; tool_call is just a word here."
        assertEquals(text, withoutToolCallMarkup(text))
    }

    @Test
    fun proseBetweenTwoCallsIsKept() {
        assertEquals("A B C", withoutToolCallMarkup("A <tool_call>x</tool_call> B <tool_call>y</tool_call> C"))
    }

    @Test
    fun aLoneClosingTagIsDropped() {
        assertEquals("Done.", withoutToolCallMarkup("Done.</tool_call>"))
    }

    @Test
    fun aFencedExampleIsKeptVerbatim() {
        val text = "Hermes writes a call like this:\n\n```\n<tool_call>\n{\"name\": \"weather\", \"arguments\": {}}\n</tool_call>\n```\n\nThe JSON goes inside."
        assertEquals(text, withoutToolCallMarkup(text))
    }

    @Test
    fun anInlineFunctionMentionIsKept() {
        val text = "Llama uses `<function=name>`, then the JSON arguments."
        assertEquals(text, withoutToolCallMarkup(text))
    }

    @Test
    fun anAnswerOpeningWithAnInlineToolCallMentionIsKept() {
        val text = "`<tool_call>` is the tag Hermes wraps the JSON in, then it closes it."
        assertEquals(text, withoutToolCallMarkup(text))
    }

    /** Outside backticks a mention cannot be told from a call: it is stripped, to the end of its prose. */
    @Test
    fun aBareMentionOutsideBackticksIsTakenForACall() {
        assertEquals("Llama uses", withoutToolCallMarkup("Llama uses <function=name> tags."))
    }
}
