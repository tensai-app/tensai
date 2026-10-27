package io.github.tensaiapp.engine

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEN-166's rule: a reply reasons on [ReasoningBudget.SimpleReasoningTokens] unless the message reads as a problem to
 * work through, which gets the whole split. Checked against the agent bench's own prompts, where the budgets were
 * measured.
 */
class ReasoningBudgetForMessageTest {

    private val marker = "</think>"

    @Test
    fun ofTheBenchPromptsOnlyTheLongReasoningOnesReadAsProblems() {
        val prompts = Json.parseToJsonElement(File("src/debug/assets/bench/agent-prompts.json").readText())
            .jsonObject.getValue("prompts").jsonArray.associate { it.jsonObject.getValue("id").jsonPrimitive.content to it.jsonObject.getValue("prompt").jsonPrimitive.content }

        val problems = prompts.filterValues(ReasoningBudget::looksLikeProblem).keys

        // reason-letter-count is a short question with no number in it: 256 did as well there.
        assertEquals(setOf("reason-money-left", "reason-ages", "reason-seats", "reason-laundry"), problems)
    }

    @Test
    fun aProblemNeedsBothTheWordsAndTheNumbers() {
        val twentyTwoWords = "one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen " +
            "seventeen eighteen nineteen twenty twentyone twentytwo"

        assertFalse(ReasoningBudget.looksLikeProblem(""))
        assertFalse(ReasoningBudget.looksLikeProblem("   "))
        assertFalse("24 words", ReasoningBudget.looksLikeProblem("$twentyTwoWords 3 4"))
        assertTrue("25 words", ReasoningBudget.looksLikeProblem("$twentyTwoWords 3 and 4"))
        assertFalse("25 words, one number", ReasoningBudget.looksLikeProblem("$twentyTwoWords 3 and four"))
        assertFalse("a number inside a word is not one", ReasoningBudget.looksLikeProblem("$twentyTwoWords mp3 and 4"))
        assertFalse("26.2 is one number", ReasoningBudget.looksLikeProblem("$twentyTwoWords 26.2 miles away"))
        assertFalse("355,614,924 is one number", ReasoningBudget.looksLikeProblem("$twentyTwoWords 355,614,924 people here"))
        assertFalse("10:30 is one time", ReasoningBudget.looksLikeProblem("$twentyTwoWords at 10:30 sharp"))
        assertTrue(ReasoningBudget.looksLikeProblem("$twentyTwoWords 26.2, then 4"))
    }

    @Test
    fun aNumberWithItsUnitCountsOneAtTheEndOfAWordDoesNot() {
        assertEquals(2, ReasoningBudget.countNumbers("10km and 5km"))
        assertEquals(2, ReasoningBudget.countNumbers("from 3pm at 60mph"))
        assertEquals(0, ReasoningBudget.countNumbers("an mp3 of h264"))
        assertEquals(1, ReasoningBudget.countNumbers("26.2"))
        assertEquals(1, ReasoningBudget.countNumbers("355,614,924"))
    }

    @Test
    fun aSimpleMessageReasonsOn256AndTheAnswerKeepsTheRest() {
        assertEquals(ReasoningBudget(256, 1792, marker), ReasoningBudget.forMessage("What is the capital of Japan?", 2048, marker))
        assertEquals(ReasoningBudget(256, 1792, marker), ReasoningBudget.forMessage("", 2048, marker))
    }

    @Test
    fun aProblemGetsTheWholeSplit() {
        val laundry = "Two loads of laundry each need washing (20 minutes), then drying (30 minutes), then folding (10 minutes), in " +
            "that order. There is one washer, one dryer and one person folding, and each handles one load at a time."

        assertEquals(ReasoningBudget.splitting(2048, marker), ReasoningBudget.forMessage(laundry, 2048, marker))
        assertEquals(ReasoningBudget(1024, 1024, marker), ReasoningBudget.forMessage(laundry, 2048, marker))
    }

    @Test
    fun aSmallMaxTokensIsNeverGivenMoreReasoningThanItsSplitAndTheCapsStillSumToIt() {
        assertEquals(ReasoningBudget(150, 150, marker), ReasoningBudget.forMessage("hi", 300, marker))
        listOf(1, 2, 300, 511, 512, 513, 2048, 8192).forEach { max ->
            val budget = ReasoningBudget.forMessage("hi", max, marker)
            assertEquals("max $max", ReasoningBudget.splitting(max, marker).let { it.reasoningMaxTokens + it.responseMaxTokens }, budget.reasoningMaxTokens + budget.responseMaxTokens)
            assertTrue("max $max", budget.reasoningMaxTokens <= ReasoningBudget.SimpleReasoningTokens)
        }
    }
}
