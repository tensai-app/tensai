package io.github.hokanosekai.tensai.data.chat

import io.github.hokanosekai.tensai.engine.ReasoningBudget
import io.github.hokanosekai.tensai.engine.SamplingConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEN-35. [SamplingJson] is the only thing standing between a persisted blob and a decode, and the
 * blob outlives the build that wrote it — so what is tested here is less "does it serialize" than
 * "what happens when what comes back is not what was written". A conversation whose settings were
 * saved by a newer build, or half-written by a crash, must still open.
 */
class SamplingJsonTest {

    /**
     * A config with nothing at its default, and stop sequences chosen to break a naive
     * string-concatenation encoder: a double quote, a backslash, a newline, a leading space that
     * must survive, and a non-ASCII character.
     */
    private val nonTrivial = SamplingConfig(
        temperature = 0.31f,
        topP = 0.72f,
        topK = 7,
        minP = 0.11f,
        repeatPenalty = 1.27f,
        stopSequences = listOf("\nUser:", " assistant said \"no\"", "back\\slash", "終わり"),
        maxTokens = 512,
    )

    @Test
    fun aNonTrivialConfigSurvivesTheRoundTripUnchanged() {
        val decoded = SamplingJson.decode(SamplingJson.encode(nonTrivial))

        assertEquals(nonTrivial, decoded)
        assertEquals(
            "stop sequences must come back byte for byte, whitespace and quoting included",
            nonTrivial.stopSequences,
            decoded.stopSequences,
        )
    }

    /**
     * The two fields the blob deliberately does not carry — see [SamplingJson]'s KDoc. Pinned as a
     * test rather than left to the KDoc because the failure mode is silent: were `enableThinking`
     * to start round-tripping here, it would begin to disagree with
     * [ConversationEntity.enableThinking], and nothing would say which of the two rows won.
     */
    @Test
    fun enableThinkingAndTheReasoningBudgetAreNotCarriedByTheBlob() {
        val config = nonTrivial.copy(
            enableThinking = false,
            reasoningBudget = ReasoningBudget(reasoningMaxTokens = 100, responseMaxTokens = 200, endMarker = "</think>"),
        )

        val decoded = SamplingJson.decode(SamplingJson.encode(config))

        assertEquals("enableThinking belongs to its own column", true, decoded.enableThinking)
        assertEquals("the reasoning split is derived at decode time, never stored", null, decoded.reasoningBudget)
        assertEquals("the knobs it does carry are unaffected", nonTrivial, decoded)
    }

    @Test
    fun anAbsentOrEmptyBlobReadsAsTheDefaults() {
        assertEquals(SamplingConfig(), SamplingJson.decode(null))
        assertEquals(SamplingConfig(), SamplingJson.decode(""))
        assertEquals(SamplingConfig(), SamplingJson.decode("   "))
    }

    /** A blob cut off mid-write — a crash between two Room pages, a partially restored backup. */
    @Test
    fun aTruncatedBlobReadsAsTheDefaultsRatherThanThrowing() {
        val full = SamplingJson.encode(nonTrivial)
        for (cut in 1 until full.length) {
            assertEquals(
                "truncation at $cut must fall back, not throw",
                SamplingConfig(),
                SamplingJson.decode(full.take(cut)),
            )
        }
    }

    @Test
    fun inputThatIsNotJsonAtAllReadsAsTheDefaults() {
        assertEquals(SamplingConfig(), SamplingJson.decode("not json"))
        assertEquals(SamplingConfig(), SamplingJson.decode("[1, 2, 3]"))
        assertEquals(SamplingConfig(), SamplingJson.decode("null"))
    }

    /**
     * A blob from a build that knows fields this one doesn't. The unknown keys are ignored and the
     * known ones still apply — a downgrade must cost the user the settings that genuinely cannot be
     * represented here, and not one more.
     */
    /** TEN-114: rows written before the field existed decode to the new default, 1.10, on purpose. */
    @Test
    fun aBlobWithoutRepeatPenaltyReadsAsTheDefault() {
        val old = """{"v":1,"temperature":0.3,"topP":0.9,"topK":20,"minP":0.1,"maxTokens":512,"stopSequences":[]}"""

        val decoded = SamplingJson.decode(old)

        assertEquals(1.10f, decoded.repeatPenalty, 0f)
        assertEquals(0.3f, decoded.temperature, 1e-6f)
    }

    @Test
    fun repeatPenaltyIsClampedIntoItsRange() {
        assertEquals(1.5f, SamplingJson.decode("""{"repeatPenalty":9}""").repeatPenalty, 0f)
        assertEquals(1.0f, SamplingJson.decode("""{"repeatPenalty":0.2}""").repeatPenalty, 0f)
    }

    @Test
    fun aBlobFromAFutureVersionKeepsTheFieldsThisVersionUnderstands() {
        val future = """
            {"v":99,"temperature":0.42,"topK":3,"stopSequences":["END"],
             "mirostat":2,"mirostatTau":5.0,"dryMultiplier":0.8,"nested":{"a":[1,2]}}
        """.trimIndent()

        val decoded = SamplingJson.decode(future)

        assertEquals(0.42f, decoded.temperature, 1e-6f)
        assertEquals(3, decoded.topK)
        assertEquals(listOf("END"), decoded.stopSequences)
        assertEquals("keys it never saw leave the rest at their defaults", SamplingConfig().topP, decoded.topP)
        assertEquals(SamplingConfig().maxTokens, decoded.maxTokens)
    }

    /**
     * The mirror case: a blob from *before* a field existed. Nothing distinguishes it from the
     * future case at parse time, which is the point — every key is resolved independently, so a
     * missing one is not an error, just a default.
     */
    @Test
    fun aBlobMissingKeysFallsBackPerKey() {
        val decoded = SamplingJson.decode("""{"v":1,"temperature":1.5}""")

        assertEquals(1.5f, decoded.temperature, 1e-6f)
        assertEquals(SamplingConfig().topP, decoded.topP)
        assertEquals(SamplingConfig().topK, decoded.topK)
        assertEquals(SamplingConfig().minP, decoded.minP)
        assertEquals(SamplingConfig().maxTokens, decoded.maxTokens)
        assertEquals(SamplingConfig().stopSequences, decoded.stopSequences)
    }

    /**
     * Values of the wrong shape entirely. Each falls back on its own — one bad key must not cost
     * the good ones, since the alternative is a conversation losing every setting because of a
     * single field.
     */
    @Test
    fun valuesOfTheWrongTypeFallBackKeyByKey() {
        val decoded = SamplingJson.decode(
            """{"temperature":"hot","topK":{"nope":1},"topP":0.5,"stopSequences":"END","maxTokens":null}""",
        )

        assertEquals(SamplingConfig().temperature, decoded.temperature, 1e-6f)
        assertEquals(SamplingConfig().topK, decoded.topK)
        assertEquals("a well-formed neighbour still applies", 0.5f, decoded.topP, 1e-6f)
        assertEquals("a string where an array belongs is not half a list", SamplingConfig().stopSequences, decoded.stopSequences)
        assertEquals(SamplingConfig().maxTokens, decoded.maxTokens)
    }

    /** Out-of-range figures are clamped into [SamplingRanges] rather than handed to llama.cpp. */
    @Test
    fun outOfRangeValuesAreCoercedIntoTheirRange() {
        val decoded = SamplingJson.decode(
            """{"temperature":99.0,"topP":-4.0,"topK":100000,"minP":8.0,"maxTokens":0}""",
        )

        assertEquals(SamplingRanges.Temperature.endInclusive, decoded.temperature, 1e-6f)
        assertEquals(SamplingRanges.TopP.start, decoded.topP, 1e-6f)
        assertEquals(SamplingRanges.TopK.last, decoded.topK)
        assertEquals(SamplingRanges.MinP.endInclusive, decoded.minP, 1e-6f)
        assertEquals(
            "a max-tokens of 0 is a decode that cannot produce a token",
            SamplingRanges.MaxTokens.first,
            decoded.maxTokens,
        )
    }

    /**
     * NaN needs its own case: JSON has no literal for it, but `org.json`'s parser is lenient enough
     * to hand one back, and a clamp does nothing to NaN — every comparison against it is false, so
     * it would come out the other side unchanged and reach the sampler.
     */
    @Test
    fun nanAndInfinityFallBackRatherThanBeingClamped() {
        val decoded = SamplingJson.decode("""{"temperature":NaN,"topP":Infinity,"minP":-Infinity}""")

        assertTrue("NaN must not survive the decode", !decoded.temperature.isNaN())
        assertEquals(SamplingConfig().temperature, decoded.temperature, 1e-6f)
        assertTrue(decoded.topP in SamplingRanges.TopP)
        assertTrue(decoded.minP in SamplingRanges.MinP)
    }

    @Test
    fun emptyAndNonStringStopSequencesAreDroppedIndividually() {
        val decoded = SamplingJson.decode("""{"stopSequences":["END","",42,null,"STOP"]}""")

        assertEquals(listOf("END", "STOP"), decoded.stopSequences)
    }

    @Test
    fun theStopSequenceListIsCappedOnRead() {
        val many = (1..50).joinToString(",") { "\"s$it\"" }

        val decoded = SamplingJson.decode("""{"stopSequences":[$many]}""")

        assertEquals(SamplingRanges.MaxStopSequences, decoded.stopSequences.size)
    }

    /**
     * The version field is written so a future breaking change has something to dispatch on — see
     * [SamplingJson]'s KDoc. It cannot be added retroactively to blobs already on disk, which is
     * why it is checked here rather than left for whoever needs it.
     */
    @Test
    fun everyEncodedBlobCarriesItsVersion() {
        val encoded = SamplingJson.encode(SamplingConfig())

        assertTrue("blob should carry the version key: $encoded", encoded.contains("\"v\":${SamplingJson.Version}"))
        assertNotEquals("", encoded)
    }
}
