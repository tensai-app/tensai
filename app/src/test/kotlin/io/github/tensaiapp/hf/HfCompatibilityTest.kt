package io.github.tensaiapp.hf

import io.github.tensaiapp.compat.CompatibilityChecker
import io.github.tensaiapp.compat.DeviceProfile
import io.github.tensaiapp.compat.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HfCompatibilityTest {

    private val mib = 1024L * 1024

    /** A phone whose usable RAM (total minus the system margin) is [usableMib]. */
    private fun phone(usableMib: Long, freeMib: Long = usableMib) = DeviceProfile(
        totalRamBytes = usableMib * mib + DeviceProfile.SYSTEM_RAM_MARGIN_BYTES,
        availableRamBytes = freeMib * mib,
        socModel = "test",
        bigCoreCount = 4,
        hasVulkan = false,
        hasOpenCl = false,
    )

    private fun quant(label: String, sizeMib: Long) =
        HfQuantFile("m-$label.gguf", label, sizeMib * mib, null)

    private fun detail(vararg quants: HfQuantFile, params: Long = 2_031_739_904) = HfRepoDetail(
        summary = HfRepoSummary("a/b", "a", "b", 0, 0, null, null, HfGate.None),
        paramCount = params,
        architecture = "qwen3",
        contextLength = 32_768,
        supportsTools = false,
        hasVision = false,
        quants = quants.sortedBy { it.sizeBytes },
    )

    // KV for the 2B class is ~896 MiB at 4096 tokens plus 300 MiB overhead, so a quant needs its
    // size + ~1196 MiB.
    private val repo = detail(
        quant("Q2_K", 800),
        quant("Q4_K_M", 1200),
        quant("Q6_K", 1600),
        quant("Q8_0", 2100),
    )

    @Test
    fun `prefers Q4_K_M when it runs well even if bigger ones do too`() {
        assertEquals("Q4_K_M", HfCompatibility.recommendedQuant(repo, phone(4000))?.quant)
    }

    @Test
    fun `falls back to the biggest that runs well when Q4_K_M does not`() {
        // 2.4 GB of usable RAM: Q4_K_M needs ~2.4 GB, Q2_K ~2.0 GB.
        val noQ4 = detail(quant("Q2_K", 800), quant("IQ3_M", 900), quant("Q8_0", 4000))
        assertEquals("IQ3_M", HfCompatibility.recommendedQuant(noQ4, phone(2400))?.quant)
        // Q4_K_M present but over the budget: biggest one under it.
        assertEquals("Q2_K", HfCompatibility.recommendedQuant(repo, phone(2100))?.quant)
    }

    @Test
    fun `picks the smallest when nothing fits`() {
        assertEquals("Q2_K", HfCompatibility.recommendedQuant(repo, phone(1000))?.quant)
    }

    @Test
    fun `no quant gives no recommendation`() {
        assertNull(HfCompatibility.recommendedQuant(detail(), phone(4000)))
    }

    @Test
    fun `verdicts use the device class basis, not the free memory right now`() {
        val busy = phone(usableMib = 4000, freeMib = 500)
        val rated = HfCompatibility.rate(repo, busy)
        assertTrue(rated.all { it.compatibility.verdict == Verdict.RunsWell })
        // The live check would have softened it.
        val live = CompatibilityChecker.check(HfCompatibility.factsFor(repo, repo.quants[0]), busy)
        assertEquals(Verdict.TightFit, live.verdict)
    }

    @Test
    fun `KV is never priced at zero when the shape is unknown`() {
        val q = repo.quants[0]
        val facts = HfCompatibility.factsFor(repo, q)
        assertTrue(facts.embeddingLength > 0 && facts.blockCount > 0)
        val est = CompatibilityChecker.check(facts, phone(8000)).estimatedBytes
        assertTrue("KV + overhead must come on top of the weights", est > q.sizeBytes + 300 * mib + 500 * mib)
    }

    @Test
    fun `shape estimate grows with the parameter count`() {
        val sizes = listOf(500_000_000L, 2_000_000_000L, 4_000_000_000L, 8_000_000_000L, 30_000_000_000L)
        val products = sizes.map { HfCompatibility.estimateShape(it).let { (e, l) -> e * l } }
        assertEquals(products.sorted(), products)
    }

    @Test
    fun `missing gguf block derives params from the file size`() {
        val d = detail(quant("Q4_K_M", 1200), params = 0)
        val facts = HfCompatibility.factsFor(d, d.quants[0])
        assertTrue(facts.paramCount > 1_000_000_000)
        assertTrue(facts.embeddingLength > 0)
    }

    @Test
    fun `real fixture gets a Q4_K_M on an 8 GB phone and nothing runs on a 2 GB one`() {
        val d = HfParser.parseDetail(fixture("detail_qwen3_1_7b.json"))
        assertEquals("Q4_K_M", HfCompatibility.recommendedQuant(d, phone(6500))?.quant)
        val tiny = HfCompatibility.rate(d, phone(500))
        assertTrue(tiny.all { it.compatibility.verdict == Verdict.WontFit })
        assertEquals(d.quants.first(), HfCompatibility.recommendedQuant(d, phone(500)))
    }

    @Test
    fun `never preselects F16 or unlabelled files, however much RAM`() {
        val d = detail(quant("Q8_0", 2100), quant("F16", 4000), quant("BF16", 4000), quant(QuantLabel.UNKNOWN, 3000))
        assertEquals("Q8_0", HfCompatibility.recommendedQuant(d, phone(64_000))?.quant)
        val onlyF16 = detail(quant("F16", 4000))
        val rec = HfCompatibility.recommendation(onlyF16, phone(64_000))!!
        assertTrue(!rec.isRecommended)
    }

    @Test
    fun `prefers the K-quant and Q4 to Q6 family over a bigger Q8_0`() {
        val d = detail(quant("Q3_K_M", 1000), quant("Q6_K", 1600), quant("Q8_0", 2100))
        assertEquals("Q6_K", HfCompatibility.recommendedQuant(d, phone(64_000))?.quant)
    }

    @Test
    fun `recommendation says when nothing runs well`() {
        val rec = HfCompatibility.recommendation(repo, phone(1000))!!
        assertEquals("Q2_K", rec.quant.quant)
        assertEquals(Verdict.WontFit, rec.compatibility.verdict)
        assertTrue(!rec.isRecommended)
        assertTrue(HfCompatibility.recommendation(repo, phone(4000))!!.isRecommended)
    }

    @Test
    fun `size-derived params use the quant family bits per weight`() {
        val q2 = detail(quant("Q2_K", 1000), params = 0)
        val q8 = detail(quant("Q8_0", 1000), params = 0)
        val p2 = HfCompatibility.factsFor(q2, q2.quants[0]).paramCount
        val p8 = HfCompatibility.factsFor(q8, q8.quants[0]).paramCount
        assertTrue(p2 > p8 * 3)
    }

    @Test
    fun `quants under 2 bits are very low quality`() {
        listOf("IQ1_S", "IQ1_M", "IQ2_XXS", "TQ1_0", "TQ2_0").forEach { assertTrue(it, QuantLabel.isVeryLowQuality(it)) }
        listOf("IQ2_XS", "IQ2_M", "Q2_K", "IQ3_XXS", "Q4_K_M", "Q8_0", "F16", QuantLabel.UNKNOWN)
            .forEach { assertTrue(it, !QuantLabel.isVeryLowQuality(it)) }
        assertTrue(QuantLabel.isVeryLowQuality(QuantLabel.parse("mathstral-7B-v0.1.IQ1_S.gguf")))
    }

    @Test
    fun `a very low quality quant is never the recommendation when something else runs well`() {
        val d = detail(quant("IQ1_S", 500), quant("IQ2_XXS", 600), quant("Q2_K", 800), params = 2_031_739_904)
        val rec = HfCompatibility.recommendation(d, phone(4000))!!
        assertEquals("Q2_K", rec.quant.quant)
        assertTrue(rec.isRecommended)
    }

    @Test
    fun `a very low quality quant is skipped even when it is the only one that runs well`() {
        // 2.4 GB usable: only the smallest runs well, the rest are over the budget.
        val d = detail(quant("IQ1_S", 500), quant("Q6_K", 4000), quant("Q8_0", 5000))
        val rec = HfCompatibility.recommendation(d, phone(2400))!!
        assertEquals("Q6_K", rec.quant.quant)
        assertTrue(!rec.isRecommended)
    }

    @Test
    fun `a repo that only has very low quality quants still gets a pick, never tagged recommended`() {
        val d = detail(quant("IQ1_S", 500), quant("TQ1_0", 450))
        val rec = HfCompatibility.recommendation(d, phone(4000))!!
        assertEquals("TQ1_0", rec.quant.quant)
        assertTrue(!rec.isRecommended)
    }
}
