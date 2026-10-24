package io.github.tensaiapp.agent

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The layers of a system message and the base prompt's lines, see [SystemPrompt] and [BasePrompt]. */
class SystemPromptTest {

    /** 2026-10-24 (a Saturday), 23:30 in UTC, already the 25th in Tokyo. */
    private val clock = Clock.fixed(Instant.parse("2026-10-24T23:30:00Z"), ZoneId.of("UTC"))
    private val day = LocalDate.of(2026, 10, 24)

    @Test
    fun noLayerIsNoSystemMessage() {
        assertNull(SystemPrompt(base = false, clock = clock).build(tools = null, startedAt = null, global = null, chat = null))
        assertNull(SystemPrompt(base = false, clock = clock).build(tools = setOf("datetime"), startedAt = null, global = " ", chat = "\n"))
    }

    @Test
    fun theBaseAloneIsTheBasePrompt() {
        val prompt = SystemPrompt(clock = clock).build(tools = null, startedAt = null, global = "", chat = null)

        assertEquals(BasePrompt.render(null, day), prompt)
    }

    @Test
    fun theLayersComeBaseThenGlobalThenChatEachLabelled() {
        val base = BasePrompt.render(setOf("datetime"), day)

        assertEquals(
            "$base\n\n${SystemPrompt.GLOBAL_LABEL}\nUse metric units.",
            SystemPrompt(clock = clock).build(setOf("datetime"), startedAt = null, global = "Use metric units.\n", chat = null),
        )
        assertEquals(
            "$base\n\n${SystemPrompt.GLOBAL_LABEL}\nUse metric units.\n\n${SystemPrompt.CHAT_LABEL}\nBe brief.",
            SystemPrompt(clock = clock).build(setOf("datetime"), startedAt = null, global = "Use metric units.", chat = "Be brief."),
        )
        // The chat's own still comes after the base when there is no global layer.
        assertEquals(
            "$base\n\n${SystemPrompt.CHAT_LABEL}\nBe brief.",
            SystemPrompt(clock = clock).build(setOf("datetime"), startedAt = null, global = null, chat = "Be brief."),
        )
        // Without the base: labelled only next to another layer, alone it is the text a chat sent before layers.
        assertEquals(
            "${SystemPrompt.GLOBAL_LABEL}\nUse metric units.\n\n${SystemPrompt.CHAT_LABEL}\nBe brief.",
            SystemPrompt(base = false, clock = clock).build(null, startedAt = null, global = "Use metric units.", chat = "Be brief."),
        )
        assertEquals("Be brief.", SystemPrompt(base = false, clock = clock).build(null, startedAt = null, global = null, chat = "Be brief."))
    }

    @Test
    fun everyLayerChangesTheText() {
        val prompts = SystemPrompt(clock = clock)
        val reference = prompts.build(setOf("datetime"), null, "a", "b")

        assertNotEquals(reference, SystemPrompt(base = false, clock = clock).build(setOf("datetime"), null, "a", "b"))
        assertNotEquals(reference, prompts.build(setOf("datetime"), null, "a2", "b"))
        assertNotEquals(reference, prompts.build(setOf("datetime"), null, "a", "b2"))
        assertNotEquals(reference, prompts.build(setOf("datetime", AskUserTool.NAME), null, "a", "b"))
    }

    @Test
    fun aPlainChatGetsTheLanguageAndTheDayButNoToolRule() {
        val text = BasePrompt.render(null, day)

        assertTrue(text.contains("Reply in the language of the user's last message."))
        assertTrue(text.contains("This conversation started on Saturday, 24 October 2026."))
        assertFalse(text.contains("tool"))
    }

    @Test
    fun aToolLineIsOnlyThereWhenItsToolIsOffered() {
        val bare = BasePrompt.render(setOf("calculate"), day)
        assertTrue(bare.contains("Never invent a tool result."))
        assertFalse(bare.contains("ask_user"))
        assertFalse(bare.contains("web_search"))
        assertFalse(bare.contains("call datetime"))

        val all = BasePrompt.render(BasePrompt.ToolsNamed, day)
        assertTrue(all.contains("Call ask_user only when the request is genuinely ambiguous."))
        assertTrue(all.contains("web_search finds pages. Call web_fetch on one when you need a precise fact from it."))
        assertTrue(all.contains("For the current date or time, call datetime."))

        assertTrue(BasePrompt.render(setOf(WebSearchTool.NAME), day).lines().contains("web_search finds pages."))
    }

    @Test
    fun theDateIsLeftToTheToolWhenItIsOfferedSoThePrefixIsTheSameEveryDay() {
        assertEquals(BasePrompt.render(setOf("datetime"), day), BasePrompt.render(setOf("datetime"), day.plusDays(1)))
        assertFalse(BasePrompt.render(setOf("datetime"), day).contains("2026"))
        assertNotEquals(BasePrompt.render(setOf("calculate"), day), BasePrompt.render(setOf("calculate"), day.plusDays(1)))
    }

    @Test
    fun theDayIsTheConversationsStartInTheClocksZoneOrTodayForADraft() {
        val tokyo = SystemPrompt(clock = clock.withZone(ZoneId.of("Asia/Tokyo")))
        assertTrue(tokyo.build(null, startedAt = null, global = null, chat = null)!!.contains("Sunday, 25 October 2026"))

        val started = Instant.parse("2026-10-20T12:00:00Z").toEpochMilli()
        assertTrue(SystemPrompt(clock = clock).build(null, started, null, null)!!.contains("Tuesday, 20 October 2026"))
    }

    @Test
    fun examplesAreOffByDefaultAndOnlyNameOfferedTools() {
        assertFalse(BasePrompt.render(setOf("datetime", "calculate"), day).contains("Examples:"))

        val withExamples = BasePrompt.render(setOf("datetime"), day, examples = true)
        assertTrue(withExamples.contains("Examples:"))
        assertTrue(withExamples.contains("call datetime, then answer from its result."))
        assertTrue("one exchange needs no tool", withExamples.contains("no tool needed"))
        assertFalse("calculate is not offered", withExamples.contains("call calculate"))
        // A plain chat has no tool to show the use of.
        assertFalse(BasePrompt.render(null, day, examples = true).contains("Examples:"))
    }
}
