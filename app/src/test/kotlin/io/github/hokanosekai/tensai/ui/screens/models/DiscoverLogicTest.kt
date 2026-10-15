package io.github.hokanosekai.tensai.ui.screens.models

import io.github.hokanosekai.tensai.compat.DeviceProfile
import io.github.hokanosekai.tensai.compat.Verdict
import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.hf.HfGate
import io.github.hokanosekai.tensai.hf.HfQuantFile
import io.github.hokanosekai.tensai.hf.HfRepoDetail
import io.github.hokanosekai.tensai.hf.HfRepoSummary
import io.github.hokanosekai.tensai.ui.screens.onboarding.OnboardingCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverLogicTest {

    private val gib = 1024L * 1024 * 1024

    private fun device(totalGb: Long, availableGb: Long = totalGb) = DeviceProfile(
        totalRamBytes = totalGb * gib,
        availableRamBytes = availableGb * gib,
        socModel = "test",
        bigCoreCount = 4,
        hasVulkan = false,
        hasOpenCl = false,
    )

    private fun names(profile: DeviceProfile) =
        DiscoverLogic.recommended(OnboardingCatalog.all, profile).map { it.name }

    @Test
    fun aBigPhoneGetsEveryEntryRunsWellFirstThenTheTightOnesBiggestFirst() {
        val cards = DiscoverLogic.recommended(OnboardingCatalog.all, device(totalGb = 12))

        assertEquals(OnboardingCatalog.all.size, cards.size)
        // Qwen3 4B is over the slowness line: TightFit, so it comes last.
        assertEquals("Qwen3 4B Instruct", cards.last().name)
        assertEquals(Verdict.TightFit, cards.last().verdict)
        val runsWell = cards.dropLast(1)
        assertTrue(runsWell.all { it.verdict == Verdict.RunsWell })
        assertEquals(runsWell.map { it.paramCount }.sortedDescending(), runsWell.map { it.paramCount })
        assertEquals("Llama 3.2 3B Instruct", runsWell.first().name)
    }

    @Test
    fun entriesThatDoNotFitAreLeftOut() {
        // 3 GB phone: 1.5 GB usable once the system margin is out, only the smallest models fit.
        val small = names(device(totalGb = 3))

        assertTrue("Qwen3 0.6B Instruct" in small)
        assertTrue("Qwen3 4B Instruct" !in small)
        assertTrue("Llama 3.2 3B Instruct" !in small)
    }

    @Test
    fun aPhoneThatFitsNothingGetsAnEmptyList() {
        assertTrue(names(device(totalGb = 1)).isEmpty())
    }

    @Test
    fun theListDoesNotChangeWithTheFreeMemoryAtThatMoment() {
        assertEquals(names(device(totalGb = 8, availableGb = 5)), names(device(totalGb = 8, availableGb = 1)))
    }

    @Test
    fun aCatalogCardHasNoCountsAndTheRepoAuthor() {
        val card = DiscoverLogic.recommended(OnboardingCatalog.all, device(totalGb = 12)).first { it.name == "Qwen3 1.7B Instruct" }

        assertEquals("bartowski/Qwen_Qwen3-1.7B-GGUF", card.id)
        assertEquals("bartowski", card.author)
        assertEquals("Q4_K_M", card.quant)
        assertEquals(1_282_439_584L, card.sizeBytes)
        assertNull(card.downloads)
        assertNull(card.likes)
        assertTrue(card.tools)
    }

    private fun summary(gate: HfGate = HfGate.None) =
        HfRepoSummary("unsloth/Qwen3-4B-GGUF", "unsloth", "Qwen3-4B-GGUF", 512_000, 204, null, null, gate)

    private fun detail(vararg quants: HfQuantFile, params: Long = 2_031_739_904, tools: Boolean = true, vision: Boolean = false) =
        HfRepoDetail(
            summary = summary(),
            paramCount = params,
            architecture = "qwen3",
            contextLength = 32_768,
            supportsTools = tools,
            hasVision = vision,
            quants = quants.sortedBy { it.sizeBytes },
        )

    private fun quant(label: String, sizeMib: Long) = HfQuantFile("m-$label.gguf", label, sizeMib * 1024 * 1024, null)

    @Test
    fun aRepoCardShowsTheQuantTheRecommendationPicksWithItsVerdict() {
        val card = DiscoverLogic.cardFor(
            summary(),
            detail(quant("Q2_K", 800), quant("Q4_K_M", 1200), quant("Q8_0", 2100), vision = true),
            device(totalGb = 8),
        )

        assertNotNull(card)
        card!!
        assertEquals("unsloth/Qwen3-4B-GGUF", card.id)
        assertEquals("Qwen3-4B", card.name)
        assertEquals("unsloth", card.author)
        assertEquals("Q4_K_M", card.quant)
        assertEquals(1200L * 1024 * 1024, card.sizeBytes)
        assertEquals(Verdict.RunsWell, card.verdict)
        assertEquals(512_000L, card.downloads)
        assertEquals(204L, card.likes)
        assertTrue(card.tools)
        assertTrue(card.vision)
        assertEquals(false, card.gated)
    }

    @Test
    fun aGatedRepoKeepsItsBadge() {
        val card = DiscoverLogic.cardFor(summary(HfGate.Manual), detail(quant("Q4_K_M", 1200)), device(totalGb = 8))

        assertEquals(true, card?.gated)
    }

    @Test
    fun aRepoWhoseNearestQuantStillDoesNotFitKeepsItsCardAsWontFit() {
        // 9 GiB of weights on a 4 GB phone: the "Fits my phone" filter hides it, the card is still there.
        val card = DiscoverLogic.cardFor(summary(), detail(quant("Q4_K_M", 9 * 1024)), device(totalGb = 4))

        assertEquals(Verdict.WontFit, card?.verdict)
    }

    @Test
    fun aRepoWithNoUsableQuantHasNoCard() {
        assertNull(DiscoverLogic.cardFor(summary(), detail(), device(totalGb = 8)))
    }

    @Test
    fun aRepoWithoutHeaderSummaryOrToySizedIsNotAChatModel() {
        val q = quant("Q4_0", 3500)
        assertNull(DiscoverLogic.cardFor(summary(), detail(q, params = 0).copy(architecture = ""), device(totalGb = 8)))
        assertNull(DiscoverLogic.cardFor(summary(), detail(q, params = 40_000_000), device(totalGb = 8)))
        assertNotNull(DiscoverLogic.cardFor(summary(), detail(q, params = 0), device(totalGb = 8)))
    }

    @Test
    fun theGgufSuffixIsDroppedFromTheName() {
        assertEquals("Qwen3-4B", DiscoverLogic.displayName("Qwen3-4B-GGUF"))
        assertEquals("Llama-3.2-3B-Instruct", DiscoverLogic.displayName("Llama-3.2-3B-Instruct-gguf"))
        assertEquals("gemma-3-1b-it-qat-q4_0", DiscoverLogic.displayName("gemma-3-1b-it-qat-q4_0-gguf"))
        assertEquals("GGUF", DiscoverLogic.displayName("GGUF"))
        assertEquals("Phi-4-mini", DiscoverLogic.displayName("Phi-4-mini"))
    }

    @Test
    fun countsAreShortened() {
        assertEquals("0", DiscoverLogic.formatCount(0))
        assertEquals("96", DiscoverLogic.formatCount(96))
        assertEquals("999", DiscoverLogic.formatCount(999))
        assertEquals("1k", DiscoverLogic.formatCount(1_000))
        assertEquals("1.5k", DiscoverLogic.formatCount(1_500))
        assertEquals("64.9k", DiscoverLogic.formatCount(64_900))
        assertEquals("182k", DiscoverLogic.formatCount(182_400))
        assertEquals("999k", DiscoverLogic.formatCount(999_999))
        assertEquals("1M", DiscoverLogic.formatCount(1_000_000))
        assertEquals("2.3M", DiscoverLogic.formatCount(2_340_000))
    }

    private fun model(id: String, repo: String?) = ModelEntity(
        id = id, displayName = id, fileName = "$id.gguf", filePath = "/x/$id.gguf", sizeBytes = 1, architecture = "llama",
        quantLabel = "Q4_K_M", paramCount = 1, contextLength = 1, embeddingLength = 1, blockCount = 1,
        supportsThinking = false, importedAt = 1, sourceRepo = repo,
    )

    @Test
    fun severalQuantsOfOneRepoGiveOneEntryMatchedWithoutCase() {
        val repos = DiscoverLogic.onDeviceRepos(
            listOf(model("a", "bartowski/Llama-3.2-1B-Instruct-GGUF"), model("b", "bartowski/Llama-3.2-1B-Instruct-GGUF")),
        )
        assertEquals(setOf("bartowski/llama-3.2-1b-instruct-gguf"), repos)
        assertTrue("Bartowski/Llama-3.2-1B-Instruct-GGUF".lowercase() in repos)
    }

    @Test
    fun importsWithoutAnOriginAreNotMatched() {
        assertTrue(DiscoverLogic.onDeviceRepos(listOf(model("a", null), model("b", ""))).isEmpty())
    }
}
