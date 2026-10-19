package io.github.tensaiapp.compat

import io.github.tensaiapp.data.models.ModelEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** TEN-167: which models this build can run at all, and the draft-head name heuristic. */
class ModelSupportTest {

    private val known = setOf("llama", "qwen3", "gemma3")

    private val profile = DeviceProfile(
        totalRamBytes = 12L * GB,
        availableRamBytes = 8L * GB,
        socModel = "test",
        bigCoreCount = 4,
        hasVulkan = false,
        hasOpenCl = false,
    )

    @Before
    fun fakeNativeList() {
        SupportedArchitectures.source = { known }
        SupportedArchitectures.reset()
    }

    @After
    fun restore() {
        SupportedArchitectures.source = { null }
        SupportedArchitectures.reset()
    }

    private fun model(architecture: String, name: String = "Some model", file: String = "some.gguf", repo: String? = null) = ModelEntity(
        id = "m",
        displayName = name,
        fileName = file,
        filePath = "/nonexistent/$file",
        sizeBytes = 400_000_000,
        architecture = architecture,
        quantLabel = "Q4_K_M",
        paramCount = 1_700_000_000,
        contextLength = 8192,
        embeddingLength = 2048,
        blockCount = 28,
        supportsThinking = false,
        importedAt = 0L,
        sourceRepo = repo,
    )

    // ---- supported architectures

    @Test
    fun aKnownArchitectureIsSupported() {
        assertTrue(ModelSupport.isSupported("qwen3", known))
    }

    @Test
    fun anUnknownArchitectureIsNot() {
        assertFalse(ModelSupport.isSupported("spark2_5", known))
    }

    @Test
    fun nothingIsRefusedWhenTheListCouldNotBeRead() {
        assertTrue(ModelSupport.isSupported("spark2_5", null))
    }

    @Test
    fun aBlankArchitectureIsNeverGuessedUnsupported() {
        assertTrue(ModelSupport.isSupported("", known))
    }

    // ---- verdict at the checker level, the one source every screen reads

    @Test
    fun checkerSaysUnsupportedForAnUnknownArchitecture() {
        val result = CompatibilityChecker.check(model("spark2_5", "Spark-X2.5-1.7B"), profile)
        assertEquals(Verdict.Unsupported, result.verdict)
        assertEquals("spark2_5", result.architecture)
    }

    @Test
    fun checkerSaysDraftModelForADraftHead() {
        val result = CompatibilityChecker.check(model("qwen3", "Qwen3.8-27B-DFlash2"), profile)
        assertEquals(Verdict.DraftModel, result.verdict)
    }

    @Test
    fun checkerSaysDraftModelFromTheRepoNameToo() {
        val result = CompatibilityChecker.check(model("qwen3", "qwen3", "x.gguf", repo = "z-lab/Qwen3-8B-DFlash-b16"), profile)
        assertEquals(Verdict.DraftModel, result.verdict)
    }

    @Test
    fun aDraftHeadWinsOverAnUnknownArchitecture() {
        assertEquals(Verdict.DraftModel, ModelSupport.blockingVerdict("dflash_draft", listOf("Qwen3.8-27B-DFlash2"), known))
    }

    @Test
    fun aNormalModelKeepsItsMemoryVerdict() {
        val result = CompatibilityChecker.check(model("qwen3", "Qwen3 1.7B"), profile)
        assertEquals(Verdict.RunsWell, result.verdict)
        assertNull(ModelSupport.blockingVerdict("qwen3", listOf("Qwen3 1.7B"), known))
    }

    @Test
    fun theBlockedVerdictsAreExactlyTheTwoNewOnes() {
        assertEquals(setOf(Verdict.Unsupported, Verdict.DraftModel), Verdict.entries.filter { it.isBlocked }.toSet())
    }

    // ---- draft heuristic

    @Test
    fun theDflashTagMatches() {
        listOf("Qwen3.8-27B-DFlash2", "z-lab/Qwen3-8B-DFlash-b16", "Qwen3 8B DFlash", "qwen3-dflash.gguf")
            .forEach { assertTrue("$it should be a draft head", ModelSupport.isDraftOnly(it)) }
    }

    @Test
    fun draftArchitecturesBlockWhateverTheName() {
        listOf("dflash", "eagle3", "gemma4-assistant").forEach {
            assertEquals(Verdict.DraftModel, ModelSupport.blockingVerdict(it, listOf("Plain name"), known))
        }
    }

    @Test
    fun ordinaryNamesDoNotMatch() {
        listOf(
            "Qwen2.5-1.5B-Instruct",
            "Qwen3.8-27B",
            // Read from names these are too ambiguous to block on: real models carry them.
            "Story-Draft-v2",
            "Qwen3-30B-A3B-MTP",
            "Qwen3-0.6B-Draft",
            "Eagle2-8B",
            "Llama-3.1-8B-EAGLE3",
            // The word leads the name: a writing assistant, not a draft head.
            "Draft-Writer-7B",
            "someone/Draft-Writer-7B",
            // Inside a longer word.
            "Drafter-7B",
            "Redrafted-3B",
            "Eaglet-1B",
            // RWKV's Eagle is a real standalone model.
            "RWKV-5-Eagle-7B",
            "Eagle-7B-GGUF",
            "mtpx-1B",
            "",
        ).forEach { assertFalse("$it should not be a draft head", ModelSupport.isDraftOnly(it)) }
    }

    @Test
    fun theOwnerOfARepoSaysNothingAboutTheModel() {
        assertFalse(ModelSupport.isDraftOnly("draft-labs/Qwen3-8B"))
    }

    private companion object {
        const val GB = 1024L * 1024 * 1024
    }
}
