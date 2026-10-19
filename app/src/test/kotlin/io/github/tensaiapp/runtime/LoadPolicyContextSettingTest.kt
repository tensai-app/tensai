package io.github.tensaiapp.runtime

import io.github.tensaiapp.compat.DeviceProfile
import io.github.tensaiapp.data.AutoMemoryShare
import io.github.tensaiapp.data.ContextSetting
import io.github.tensaiapp.data.models.KvCacheSize
import io.github.tensaiapp.data.models.ModelEntity
import io.github.tensaiapp.engine.KvCacheType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The context a load asks for once the user can choose it (TEN-150): [ContextSetting.Auto] with no
 * latency cap, the fixed sizes and [ContextSetting.Max] clamped to the trained context, the global
 * setting against a model's own override, the ladder a manual choice steps down, and the
 * [LoadPolicy.preview] figures the pickers print.
 *
 * One synthetic shape throughout: 10 000 KV elements per token, no sliding window. That is
 * 20 000 bytes a token at F16 and 10 625 at Q8_0 (34 bytes per 32 elements), so every budget below
 * can be checked by hand. Weights are 1 GB; [deviceWithKvBudget] leaves exactly the named number for
 * the cache, same device as [LoadPolicyTest]'s.
 */
class LoadPolicyContextSettingTest {

    // Auto

    @Test
    fun auto_isNotCappedAt4096_theTrainedContextIsWhatItAsksFor() {
        val trained = model(contextLength = 32_768)
        // Exactly what F16 at the trained context costs, as the share's limit.
        val limit = LoadPolicy.estimate(trained, 32_768, KvCacheType.F16)

        val plan = LoadPolicy.compute(trained, deviceWithAutoLimit(limit), ContextSetting.Auto)

        assertEquals(LoadPlan.Feasible(32_768, KvCacheType.F16), plan)
    }

    @Test
    fun auto_isBoundedByTheTrainedContextWhateverTheRam() {
        val plan = LoadPolicy.compute(model(contextLength = 8_192), deviceWithAutoLimit(64 * GB), ContextSetting.Auto)

        assertEquals(LoadPlan.Feasible(8_192, KvCacheType.F16), plan)
    }

    @Test
    fun auto_isTheLargestMultipleOf1024ThatFitsTheShare() {
        val trained = model(contextLength = 131_072)
        // 5 MB over what F16 at 18 432 costs, well under the ~23 MB the next 1024 would add.
        val limit = LoadPolicy.estimate(trained, 18_432, KvCacheType.F16) + 5_000_000L

        val plan = LoadPolicy.compute(trained, deviceWithAutoLimit(limit), ContextSetting.Auto)

        assertEquals(LoadPlan.Feasible(18_432, KvCacheType.F16), plan)
    }

    @Test
    fun auto_neverSpendsMoreThanItsShare_andTheNextStepWouldHaveBeenOver() {
        val trained = model(contextLength = 131_072)

        for (limit in 1_500_000_000L..6_000_000_000L step 137_000_000L) {
            val plan = LoadPolicy.compute(trained, deviceWithAutoLimit(limit), ContextSetting.Auto) as LoadPlan.Feasible

            val trainedItself = plan.nCtx == 131_072
            assertEquals("a multiple of 1024 for limit $limit", 0, plan.nCtx % 1_024)
            assertTrue("over the share at $limit", LoadPolicy.estimate(trained, plan.nCtx.toLong(), plan.kvCacheType) <= limit)
            assertTrue(
                "one more step would still have fit at $limit",
                trainedItself || LoadPolicy.estimate(trained, plan.nCtx + 1_024L, plan.kvCacheType) > limit,
            )
        }
    }

    @Test
    fun auto_staysPutWhenTheBudgetMovesByLessThanAStep() {
        // The reason for the rounding: n_ctx is part of the saved-session key, so a plan that follows
        // every block the budget crosses would drop every saved session.
        val trained = model(contextLength = 131_072)
        val base = LoadPolicy.estimate(trained, 18_432, KvCacheType.F16)

        val plans = listOf(0L, 5_000_000L, 10_000_000L).map {
            LoadPolicy.compute(trained, deviceWithAutoLimit(base + it), ContextSetting.Auto)
        }

        assertEquals(1, plans.distinct().size)
    }

    @Test
    fun auto_prefersMoreContextAtQ8_overATinyContextAtF16() {
        val trained = model(contextLength = 32_768)
        // F16 only reaches 3 072 tokens here, short of the 4 096 worth keeping the precision for.
        val limit = LoadPolicy.estimate(trained, 3_072, KvCacheType.F16)

        val plan = LoadPolicy.compute(trained, deviceWithAutoLimit(limit), ContextSetting.Auto) as LoadPlan.Feasible

        assertEquals(KvCacheType.Q8_0, plan.kvCacheType)
        assertTrue("Q8_0 has to buy more than the 3 072 F16 did, got ${plan.nCtx}", plan.nCtx > 3_072)
    }

    @Test
    fun auto_fallsBackToTheFloorOnTheFullBudgetWhenTheShareCannotHoldIt() {
        val trained = model(contextLength = 131_072)
        // The whole budget holds the 1024 floor at Q8_0, but half of it does not.
        val usable = LoadPolicy.estimate(trained, 1_024, KvCacheType.Q8_0) + 1_000_000L

        val plan = LoadPolicy.compute(trained, deviceWithUsable(usable), ContextSetting.Auto)

        assertEquals(LoadPlan.Feasible(1_024, KvCacheType.Q8_0), plan)
    }

    @Test
    fun auto_isInfeasibleWhenNotEvenTheFloorFitsTheFullBudget() {
        val trained = model(contextLength = 131_072)
        val usable = LoadPolicy.estimate(trained, 1_024, KvCacheType.Q8_0) - 1L

        val plan = LoadPolicy.compute(trained, deviceWithUsable(usable), ContextSetting.Auto)

        assertTrue("expected Infeasible, got $plan", plan is LoadPlan.Infeasible)
        assertEquals(LoadPlan.Feasible(LoadPolicy.MIN_USABLE_CTX, KvCacheType.Q8_0), (plan as LoadPlan.Infeasible).floor)
    }

    @Test
    fun auto_followsTheShareTheUserPicked() {
        val trained = model(contextLength = 131_072)
        val profile = deviceWithUsable(6L * GB)

        val ctx = AutoMemoryShare.entries.map {
            (LoadPolicy.compute(trained, profile, ContextSetting.Auto, it) as LoadPlan.Feasible).nCtx
        }

        assertTrue("30% < 50% < 70% in context, got $ctx", ctx[0] < ctx[1] && ctx[1] < ctx[2])
    }

    /**
     * The sanity check behind TEN-150's Auto: Qwen3-0.6B (28 layers, 8 KV heads of 128, so 57 344 KV
     * elements per token, trained 40 960) on a 7.4 GB phone must not be handed its whole trained
     * context, which at F16 is 4.7 GB of cache on top of the weights. At the default 50% share it
     * lands in the 16K to 22K range.
     */
    @Test
    fun auto_qwen3_0_6b_onA7_4GbPhone_isAFewTensOfThousandsNotTheTrainedContext() {
        val qwen = model(contextLength = 40_960, kvFullElementsPerToken = 57_344L, sizeBytes = 639_000_000L)
        val phone = phone7_4Gb()

        val at50 = (LoadPolicy.compute(qwen, phone, ContextSetting.Auto, AutoMemoryShare.P50) as LoadPlan.Feasible)

        assertEquals(KvCacheType.F16, at50.kvCacheType)
        assertTrue("expected 16K to 22K, got ${at50.nCtx}", at50.nCtx in 16_384..22_528)
        assertTrue(
            "30% < 50% < 70%",
            (LoadPolicy.compute(qwen, phone, ContextSetting.Auto, AutoMemoryShare.P30) as LoadPlan.Feasible).nCtx < at50.nCtx &&
                at50.nCtx < (LoadPolicy.compute(qwen, phone, ContextSetting.Auto, AutoMemoryShare.P70) as LoadPlan.Feasible).nCtx,
        )
    }

    // The compute buffer

    @Test
    fun theComputeBufferGrowsWithTheContext_andIsTheMaskNotTheScoreMatrix() {
        // 6 bytes (F32 mask + F16 copy) for each of n_kv x n_ubatch (512) cells, over padded cells.
        assertEquals(0L, LoadPolicy.computeBufferBytes(0))
        assertEquals(1_024L * 512 * 6, LoadPolicy.computeBufferBytes(1_024))
        assertEquals(1_024L * 512 * 6, LoadPolicy.computeBufferBytes(1_000))
        assertEquals(32_768L * 512 * 6, LoadPolicy.computeBufferBytes(32_768))
    }

    @Test
    fun theEstimateIsWeightsPlusKvPlusBaseOverheadPlusTheComputeBuffer() {
        val trained = model(contextLength = 32_768)

        assertEquals(
            1_000_000_000L + OVERHEAD_BYTES + LoadPolicy.computeBufferBytes(8_192) + 163_840_000L,
            LoadPolicy.estimate(trained, 8_192, KvCacheType.F16),
        )
    }

    // Stepping down after an out-of-memory load

    @Test
    fun stepDown_goesOneLadderRungLowerAndStopsAtTheFloor() {
        val trained = model(contextLength = 131_072)

        assertEquals(16_384, LoadPolicy.stepDown(trained, 18_432))
        assertEquals(16_384, LoadPolicy.stepDown(trained, 32_768))
        assertEquals(3_072, LoadPolicy.stepDown(trained, 4_096))
        assertEquals(1_024, LoadPolicy.stepDown(trained, 2_048))
        assertNull("1024 is the floor", LoadPolicy.stepDown(trained, 1_024))
        assertNull(LoadPolicy.stepDown(trained, 512))
    }

    @Test
    fun stepDown_ofAShortContextModelStopsAtItsOwnMaximum() {
        val short = model(contextLength = 768)

        assertNull(LoadPolicy.stepDown(short, 768))
    }

    // Manual values

    @Test
    fun aFixedSize_isRequestedAsIs_whenTheModelWasTrainedForItAndTheRamBacksIt() {
        val plan = LoadPolicy.compute(model(contextLength = 131_072), deviceWithKvBudget(8 * GB), ContextSetting.K16)

        assertEquals(LoadPlan.Feasible(16_384, KvCacheType.F16), plan)
    }

    @Test
    fun aFixedSize_isClampedDownToTheTrainedContext() {
        val roomy = deviceWithKvBudget(8 * GB)

        assertEquals(LoadPlan.Feasible(6_144, KvCacheType.F16), LoadPolicy.compute(model(6_144), roomy, ContextSetting.K32))
        // A header with no context_length plans as the 4096 the engine would load.
        assertEquals(LoadPlan.Feasible(4_096, KvCacheType.F16), LoadPolicy.compute(model(0), roomy, ContextSetting.K32))
    }

    @Test
    fun max_isTheTrainedContext() {
        // 2 GiB holds F16 at 65 536 (1 310 720 000 bytes).
        val plan = LoadPolicy.compute(model(contextLength = 65_536), deviceWithKvBudget(2 * GB), ContextSetting.Max)

        assertEquals(LoadPlan.Feasible(65_536, KvCacheType.F16), plan)
    }

    @Test
    fun aManualValueTheRamCannotBack_stepsDownTheLadderFromTheChosenValue() {
        // 300 MB: neither F16 (655 360 000) nor Q8_0 (348 160 000) holds 32 768, so the plan goes
        // down from the choice: next rung 16 384, which Q8_0 holds (174 080 000).
        val plan = LoadPolicy.compute(model(contextLength = 131_072), deviceWithKvBudget(300_000_000L), ContextSetting.K32)

        assertEquals(LoadPlan.Feasible(16_384, KvCacheType.Q8_0), plan)
    }

    @Test
    fun theLadderOnlyEverGoesDown_neverAboveTheChoice() {
        // The same 300 MB would hold 28 160 tokens, but a manual 8K asked for 8 192 and gets it.
        val plan = LoadPolicy.compute(model(contextLength = 131_072), deviceWithKvBudget(300_000_000L), ContextSetting.K8)

        assertEquals(LoadPlan.Feasible(8_192, KvCacheType.F16), plan)
    }

    // Global against override

    @Test
    fun aModelWithNoOverrideFollowsTheGlobalSetting() {
        val plan = LoadPolicy.compute(model(contextLength = 131_072), deviceWithKvBudget(8 * GB), ContextSetting.K8)

        assertEquals(LoadPlan.Feasible(8_192, KvCacheType.F16), plan)
    }

    @Test
    fun aModelsOwnOverrideBeatsTheGlobalSetting() {
        val own = model(contextLength = 131_072, contextOverride = ContextSetting.K16.name)

        assertEquals(ContextSetting.K16, LoadPolicy.effectiveSetting(own, global = ContextSetting.K8))
        assertEquals(
            LoadPlan.Feasible(16_384, KvCacheType.F16),
            LoadPolicy.compute(own, deviceWithKvBudget(8 * GB), ContextSetting.K8),
        )
    }

    @Test
    fun anOverrideThisBuildDoesNotKnowFallsBackToTheGlobalSetting() {
        val unknown = model(contextLength = 131_072, contextOverride = "K64")

        assertEquals(ContextSetting.K8, LoadPolicy.effectiveSetting(unknown, global = ContextSetting.K8))
    }

    @Test
    fun theDefaultGlobalSettingIsAuto() {
        val roomy = deviceWithKvBudget(8 * GB)
        val trained = model(contextLength = 16_384)

        assertEquals(LoadPolicy.compute(trained, roomy, ContextSetting.Auto), LoadPolicy.compute(trained, roomy))
    }

    // Memory estimate

    @Test
    fun preview_pricesAChoiceThatFitsFromTheSameKvFormulaTheLoadUses() {
        val trained = model(contextLength = 131_072)
        val profile = deviceWithKvBudget(8 * GB)

        val preview = LoadPolicy.preview(trained, profile, ContextSetting.K8)

        // 8192 cells * 10 000 elements * 2 bytes.
        assertEquals(163_840_000L, preview.kvBytes)
        assertEquals(
            1_000_000_000L + OVERHEAD_BYTES + LoadPolicy.computeBufferBytes(8_192) + 163_840_000L,
            preview.totalBytes,
        )
        assertEquals(profile.usableRamBytes, preview.budgetBytes)
        assertEquals(KvCacheType.F16, preview.kvCacheType)
        assertEquals(8_192, preview.requestedCtx)
        assertEquals(8_192, preview.plannedCtx)
        assertTrue(preview.fits)
    }

    @Test
    fun preview_ofAutoReportsWhatAutoResolvesTo() {
        val trained = model(contextLength = 131_072)
        val limit = LoadPolicy.estimate(trained, 18_432, KvCacheType.F16) + 5_000_000L

        val preview = LoadPolicy.preview(trained, deviceWithAutoLimit(limit), ContextSetting.Auto)

        assertEquals(18_432, preview.requestedCtx)
        assertEquals(18_432, preview.plannedCtx)
        assertTrue(preview.fits)
    }

    @Test
    fun preview_ofAChoiceTheRamCannotBackWarnsAndPricesTheRequestedContextAtTheCheapestPrecision() {
        val preview = LoadPolicy.preview(model(contextLength = 131_072), deviceWithKvBudget(300_000_000L), ContextSetting.K32)

        assertFalse("the picker's warning", preview.fits)
        assertTrue(preview.feasible)
        assertEquals(32_768, preview.requestedCtx)
        assertEquals(16_384, preview.plannedCtx)
        assertEquals(KvCacheType.Q8_0, preview.kvCacheType)
        assertEquals(348_160_000L, preview.kvBytes)
        assertTrue("the figure has to show why it warned", preview.totalBytes > preview.budgetBytes)
    }

    @Test
    fun preview_ofAFixedSizeAboveTheTrainedContextIsThatContext() {
        val preview = LoadPolicy.preview(model(contextLength = 6_144), deviceWithKvBudget(8 * GB), ContextSetting.K32)

        assertEquals(6_144, preview.requestedCtx)
        assertTrue(preview.fits)
    }

    @Test
    fun preview_ofAnInfeasibleModelDoesNotFitAnything() {
        val preview = LoadPolicy.preview(model(contextLength = 131_072), deviceWithUsable(100_000_000L), ContextSetting.Auto)

        assertFalse(preview.fits)
        assertEquals(LoadPolicy.MIN_USABLE_CTX, preview.plannedCtx)
        assertFalse("the picker says it cannot load, not that it starts lower", preview.feasible)
    }

    private fun model(
        contextLength: Long,
        contextOverride: String? = null,
        kvFullElementsPerToken: Long = 10_000L,
        sizeBytes: Long = 1_000_000_000L,
    ) = ModelEntity(
        id = "test-model",
        displayName = "Test model",
        fileName = "test.gguf",
        filePath = "/nonexistent/test.gguf",
        sizeBytes = sizeBytes,
        architecture = "qwen2",
        quantLabel = "Q4_K_M",
        paramCount = 1_000_000_000L,
        contextLength = contextLength,
        embeddingLength = 1_000L,
        blockCount = 20L,
        supportsThinking = false,
        importedAt = 0L,
        kvFullElementsPerToken = kvFullElementsPerToken,
        contextOverride = contextOverride,
    )

    /** Leaves exactly [kvBudgetBytes] for the KV cache of the 1 GB model above, see [LoadPolicyTest]. */
    private fun deviceWithKvBudget(kvBudgetBytes: Long) = DeviceProfile(
        totalRamBytes = kvBudgetBytes + 1_000_000_000L + OVERHEAD_BYTES + DeviceProfile.SYSTEM_RAM_MARGIN_BYTES,
        availableRamBytes = 0,
        socModel = "test-soc",
        bigCoreCount = 4,
        hasVulkan = false,
        hasOpenCl = false,
    )

    /** A device whose usable RAM, at the default 50% share, gives Auto exactly [limitBytes]. */
    private fun deviceWithAutoLimit(limitBytes: Long) = deviceWithUsable(limitBytes * 2)

    private fun deviceWithUsable(usableBytes: Long) = DeviceProfile(
        totalRamBytes = usableBytes + DeviceProfile.SYSTEM_RAM_MARGIN_BYTES,
        availableRamBytes = 0,
        socModel = "test-soc",
        bigCoreCount = 4,
        hasVulkan = false,
        hasOpenCl = false,
    )

    /** 7.4 GiB of RAM, what a nominal '8 GB' phone reports. */
    private fun phone7_4Gb() = DeviceProfile(
        totalRamBytes = (7.4 * GB).toLong(),
        availableRamBytes = 0,
        socModel = "test-soc",
        bigCoreCount = 4,
        hasVulkan = false,
        hasOpenCl = false,
    )

    private companion object {
        const val GB = 1024L * 1024 * 1024
        const val OVERHEAD_BYTES = 300L * 1024 * 1024
    }
}
