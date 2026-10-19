package io.github.tensaiapp.ui.screens.chat

import io.github.tensaiapp.data.chat.SamplingRanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TEN-35. The settings sheet itself cannot be exercised without a device, so everything about it
 * that can be *wrong* rather than merely ugly lives in `SamplingText.kt` and is tested here.
 */
class SamplingTextTest {

    @Test
    fun stopSequencesRoundTripThroughTheField() {
        val sequences = listOf("END", "  padded  ", "終わり")

        assertEquals(sequences, stopSequencesFromText(stopSequencesToText(sequences)))
    }

    /**
     * The format's one lossy case, pinned so it stays a known limitation rather than becoming a
     * surprise: one line is one sequence, so a sequence *containing* a newline cannot be written in
     * the field and does not survive a round trip through it — `"\nUser:"` comes back as `"User:"`.
     *
     * Scoped to the field alone. The persisted format has no such limit (see
     * `SamplingJsonTest.aNonTrivialConfigSurvivesTheRoundTripUnchanged`, whose fixture includes
     * exactly this sequence): a config carrying a multi-line stop sequence stores and reloads
     * intact, it just cannot be *typed* here. Adding an escape syntax to a plain text field would
     * cost more than it buys — a Windows path pasted into it would silently become a newline.
     */
    @Test
    fun aSequenceContainingANewlineCannotBeExpressedInTheField() {
        assertEquals(listOf("User:"), stopSequencesFromText(stopSequencesToText(listOf("\nUser:"))))
    }

    /**
     * Whitespace is not decoration in a stop sequence — `"\nUser:"` and `"User:"` stop at different
     * points — so lines must not be trimmed.
     */
    @Test
    fun linesAreTakenVerbatimAndNotTrimmed() {
        assertEquals(listOf(" leading", "trailing "), stopSequencesFromText(" leading\ntrailing "))
    }

    /** An empty stop sequence matches at the first token and would end every generation. */
    @Test
    fun emptyLinesAreDropped() {
        assertEquals(listOf("A", "B"), stopSequencesFromText("A\n\n\nB\n"))
        assertEquals(emptyList<String>(), stopSequencesFromText(""))
        assertEquals(emptyList<String>(), stopSequencesFromText("\n\n"))
    }

    /** Some soft keyboards emit CRLF; a stray `\r` would silently become part of every sequence. */
    @Test
    fun carriageReturnsAreNormalizedAway() {
        assertEquals(listOf("A", "B"), stopSequencesFromText("A\r\nB\r\n"))
    }

    /** The field must not be able to save more than [SamplingRanges.MaxStopSequences], which is all the decode will read back. */
    @Test
    fun theFieldCannotSaveMoreSequencesThanTheDecodeWillRead() {
        val typed = (1..30).joinToString("\n") { "s$it" }

        assertEquals(SamplingRanges.MaxStopSequences, stopSequencesFromText(typed).size)
    }

    @Test
    fun intFieldsAcceptOnlyWholeNumbersInsideTheirRange() {
        assertEquals(512, parseIntField("512", SamplingRanges.MaxTokens))
        assertEquals(512, parseIntField("  512  ", SamplingRanges.MaxTokens))
        assertNull("below the floor", parseIntField("0", SamplingRanges.MaxTokens))
        assertNull("above the ceiling", parseIntField("999999", SamplingRanges.MaxTokens))
        assertNull("not a number", parseIntField("abc", SamplingRanges.MaxTokens))
    }

    /**
     * The states a field passes through while being edited. Each returns `null`, meaning "commit
     * nothing yet" — the stored value keeps its last valid figure rather than snapping to a default
     * because the user cleared the field to retype it.
     */
    @Test
    fun aFieldMidEditCommitsNothing() {
        assertNull(parseIntField("", SamplingRanges.MaxTokens))
        assertNull(parseIntField("-", SamplingRanges.MaxTokens))
        assertNull(parseFloatField("", SamplingRanges.Temperature))
        assertNull(parseFloatField(".", SamplingRanges.Temperature))
    }

    @Test
    fun floatFieldsAcceptOnlyValuesInsideTheirRange() {
        assertEquals(0.7f, parseFloatField("0.7", SamplingRanges.Temperature))
        assertEquals(2f, parseFloatField("2", SamplingRanges.Temperature))
        assertNull(parseFloatField("2.5", SamplingRanges.Temperature))
        assertNull(parseFloatField("-0.1", SamplingRanges.Temperature))
        assertNull("NaN is in no range", parseFloatField("NaN", SamplingRanges.Temperature))
    }

    /**
     * `.` is the only decimal separator, on both sides. Parsing the device locale's separator here
     * would give a field that shows `0.80` and rejects `0,80` on a French keyboard — or the
     * reverse, depending on which of the two sides guessed the locale.
     */
    @Test
    fun formattingIsLocaleIndependentAndMatchesWhatTheFieldParses() {
        val formatted = formatFloatField(0.8f)

        assertEquals("0.80", formatted)
        assertEquals(0.8f, parseFloatField(formatted, SamplingRanges.Temperature))
        assertNull("a comma separator is not accepted, and is not produced either", parseFloatField("0,80", SamplingRanges.Temperature))
    }
}
