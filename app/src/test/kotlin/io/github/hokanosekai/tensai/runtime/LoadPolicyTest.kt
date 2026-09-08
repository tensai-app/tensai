package io.github.hokanosekai.tensai.runtime

import io.github.hokanosekai.tensai.compat.DeviceProfile
import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.engine.KvCacheType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first four cases share one synthetic model shape (embeddingLength=1000, blockCount=20,
 * sizeBytes=1GB, trained contextLength=32k) with no KV shape recorded, and vary only the KV budget
 * the device leaves, so the [LoadPolicy.compute] branches (cap, Q8_0 at full context, ladder
 * reduction, infeasible) are reachable with hand-checkable arithmetic rather than a real GGUF's
 * numbers. They also pin the pre-TEN-18 behaviour, which is what
 * [io.github.hokanosekai.tensai.data.models.KvCacheSize] still falls back to when a model's shape
 * is unknown.
 *
 * The cases after them use the measured shape of the Qwen3-1.7B TEN-18 was calibrated against on
 * device (OPPO CPH2251, 2026-09-04), plus one synthetic sliding-window shape that says so, to pin
 * what those shapes buy in planned context. The estimate itself is confronted with the
 * measurements in [io.github.hokanosekai.tensai.data.models.KvCacheSizeTest].
 *
 * Every profile below is built by [deviceWithKvBudget], which sets
 * [DeviceProfile.availableRamBytes] to 0 — a reading no real device reports, and the point:
 * since TEN-77 nothing in this class may read it.
 */
class LoadPolicyTest {

    @Test
    fun largeRam_capsAtLatencyCeiling_usingF16() {
        // 8GB of KV budget is comfortably more than the ~328MB an F16 KV cache needs at the 4096
        // cap, so the trained 32k context is capped, not RAM-reduced.
        val model = model(contextLength = 32_768)
        val profile = deviceWithKvBudget(8L * GB)

        val plan = LoadPolicy.compute(model, profile)

        assertEquals(LoadPlan.Feasible(LoadPolicy.CTX_LATENCY_CAP, KvCacheType.F16), plan)
    }

    @Test
    fun tightRam_fallsBackToQ8_0AtTheWantedContext() {
        // 200MB of KV budget: below the ~328MB F16 needs for 4096 tokens, but above the ~174MB
        // Q8_0 needs for the same 4096 — so Q8_0 kicks in without reducing the context. Not
        // 164MB: that figure is elements * 1 byte, the reading this class no longer uses.
        val model = model(contextLength = 32_768)
        val profile = deviceWithKvBudget(200_000_000L)

        val plan = LoadPolicy.compute(model, profile)

        assertEquals(LoadPlan.Feasible(LoadPolicy.CTX_LATENCY_CAP, KvCacheType.Q8_0), plan)
    }

    @Test
    fun veryTightRam_reducesContextEvenAtQ8_0() {
        // 50MB of KV budget, against 42 500 bytes per cell at Q8_0 (40 000 elements per token,
        // 34 bytes per 32 of them): 1176 cells' worth. The ladder's 2048 rung would need 87MB, so
        // the plan is its 1024 rung — the last one above MIN_USABLE_CTX.
        val model = model(contextLength = 32_768)
        val profile = deviceWithKvBudget(50_000_000L)

        val plan = LoadPolicy.compute(model, profile)

        assertEquals(LoadPlan.Feasible(1_024, KvCacheType.Q8_0), plan)
    }

    /**
     * TEN-77's first promise, and the bug this whole ticket is about: on the reference device the
     * old policy answered `n_ctx` 256 here and said nothing, so the user got a chat that took a
     * 31-token prompt, had 225 tokens of budget left to answer in, and returned nothing at all —
     * no error, no dialog, the model name and "256" in the top bar the only trace.
     *
     * 40MB of KV budget doesn't reach the 43.5MB the 1024 rung costs at Q8_0. There is no context
     * worth loading this model at, and that is now something [LoadPolicy] can say.
     */
    @Test
    fun belowTheUsableFloor_thePlanFailsInsteadOfShrinkingToADegenerateContext() {
        val model = model(contextLength = 32_768)
        val profile = deviceWithKvBudget(40_000_000L)

        val plan = LoadPolicy.compute(model, profile)

        assertTrue("expected an Infeasible plan, got $plan", plan is LoadPlan.Infeasible)
        plan as LoadPlan.Infeasible
        assertEquals(
            "the floor is what an explicit \"load anyway\" gets tried with",
            LoadPlan.Feasible(LoadPolicy.MIN_USABLE_CTX, KvCacheType.Q8_0),
            plan.floor,
        )
        assertEquals(profile.usableRamBytes, plan.budgetBytes)
        // 1GB of weights + 300MiB of overhead + 43 520 000 bytes of Q8_0 cache at 1024 cells.
        assertEquals(1_000_000_000L + OVERHEAD_BYTES + 43_520_000L, plan.requiredBytes)
        assertTrue(
            "an Infeasible plan has to be over budget, or it isn't one",
            plan.requiredBytes > plan.budgetBytes,
        )
    }

    /**
     * The same promise, stated as the invariant rather than as one case: sweep the KV budget from
     * nothing to plenty and no plan ever names a context below [LoadPolicy.MIN_USABLE_CTX]. Either
     * it is loadable or it is [LoadPlan.Infeasible] — the middle ground is what shipped the mute
     * chat.
     */
    @Test
    fun noBudgetEverProducesAFeasiblePlanBelowTheUsableFloor() {
        val model = model(contextLength = 32_768)

        for (budget in 0L..200_000_000L step 1_000_000L) {
            val plan = LoadPolicy.compute(model, deviceWithKvBudget(budget))
            if (plan is LoadPlan.Feasible) {
                assertTrue(
                    "budget $budget planned n_ctx ${plan.nCtx}, below the usable floor",
                    plan.nCtx >= LoadPolicy.MIN_USABLE_CTX,
                )
            }
        }
    }

    /**
     * TEN-77's second promise. Measured on the reference device: the same model, the same code and
     * the same phone planned 256 on one load (system log `SCENE_LOW_MEMORY ... availMem=1577`) and
     * 4096 on the next, purely because free RAM had recovered in between. `n_ctx` is part of
     * [io.github.hokanosekai.tensai.data.session.SessionModelKey], so every one of those flips
     * invalidated every persisted session on the device — 466MB of `.llamastate` files that could
     * never match, against a measured 23.5x time-to-first-token win when they do.
     *
     * The plan is now a pure function of the model and the phone's *total* RAM, so free RAM can do
     * whatever it likes.
     */
    @Test
    fun theSameModelOnTheSamePhonePlansTheSameContextWhateverFreeRamSays() {
        val model = model(contextLength = 32_768)
        val totalRamBytes = 8L * GB

        val plans = listOf(0L, 200L * MB, 1_577L * MB, 3L * GB, 6L * GB, 8L * GB).map { availMem ->
            availMem to LoadPolicy.compute(
                model,
                DeviceProfile(
                    totalRamBytes = totalRamBytes,
                    availableRamBytes = availMem,
                    socModel = "test-soc",
                    bigCoreCount = 4,
                    hasVulkan = false,
                    hasOpenCl = false,
                ),
            )
        }

        assertEquals(
            "free RAM must not reach the plan at all — it did, and it cost every saved session: $plans",
            1,
            plans.map { it.second }.distinct().size,
        )
    }

    /**
     * A GGUF with no `context_length` key records 0, and the engine turns a non-positive `n_ctx`
     * into 4096 at load time — so planning for the 0 would budget an empty cache and then load a
     * 4096-cell one. The plan has to name the context the engine will actually use.
     */
    @Test
    fun unknownTrainedContext_plansTheContextTheEngineWillUse() {
        val model = model(contextLength = 0)
        val profile = deviceWithKvBudget(8L * GB)

        val plan = LoadPolicy.compute(model, profile)

        assertEquals(LoadPlan.Feasible(LoadPolicy.CTX_LATENCY_CAP, KvCacheType.F16), plan)
    }

    /** ...and that stand-in goes through the same RAM budget as a trained one, it isn't a bypass. */
    @Test
    fun unknownTrainedContext_isStillCutToFitRam() {
        val profile = deviceWithKvBudget(50_000_000L)

        assertEquals(
            LoadPolicy.compute(model(contextLength = 4_096), profile),
            LoadPolicy.compute(model(contextLength = 0), profile),
        )
    }

    @Test
    fun trainedContextBelowCap_isNeverExceeded() {
        val model = model(contextLength = 2_048)
        val profile = deviceWithKvBudget(8L * GB)

        val plan = LoadPolicy.compute(model, profile)

        assertEquals(LoadPlan.Feasible(2_048, KvCacheType.F16), plan)
    }

    /**
     * A trained context that isn't on the ladder is still honoured in full when it fits: the rungs
     * exist to make a *RAM-forced reduction* reproducible, and a model's own header is already
     * that. Rounding 1536 down to 1024 here would throw away a third of the context for nothing.
     */
    @Test
    fun aTrainedContextOffTheLadderIsNotRoundedDownWhenItFits() {
        // F16 at 1536 costs 122 880 000, over the budget; Q8_0 at the same 1536 costs 65 280 000,
        // under it. So this lands on the Q8_0 branch, where the ladder lives, and still keeps 1536.
        val model = model(contextLength = 1_536)
        val profile = deviceWithKvBudget(70_000_000L)

        val plan = LoadPolicy.compute(model, profile)

        assertEquals(LoadPlan.Feasible(1_536, KvCacheType.Q8_0), plan)
    }

    /**
     * The measured shape of the Qwen3-1.7B on the test device: 28 layers, 8 KV heads of 128 (16
     * query heads, so grouped-query 2:1), no sliding window. `kvFullElementsPerToken` is
     * `28 * 8 * (128 + 128)` = 57 344, half of what `2 * embeddingLength * blockCount` = 114 688
     * assumed — the exact factor the old estimate was off by. Same RAM, and the corrected shape
     * buys the full 4096 where the old one has to cut the context to fit.
     */
    @Test
    fun groupedQueryShape_buysTheContextTheOldEstimateHadToCut() {
        val gqa = qwen3(kvFullElementsPerToken = 57_344L)
        val unknownShape = qwen3()
        val profile = deviceWithKvBudget(300_000_000L)

        // 57 344 * 4096 elements at Q8_0 = 249 561 088 bytes, inside the budget.
        assertEquals(LoadPlan.Feasible(LoadPolicy.CTX_LATENCY_CAP, KvCacheType.Q8_0), LoadPolicy.compute(gqa, profile))

        // Same device, same model, shape unknown: 121 856 bytes per cell at Q8_0 leaves room for
        // 2304 cells. TEN-77 snaps that down to the ladder's 2048 rung rather than planning the
        // 2304 the binary search reached — 256 tokens given up so that the answer survives the
        // next change to this app's overhead constant or to the model's recorded KV shape.
        assertEquals(LoadPlan.Feasible(2_048, KvCacheType.Q8_0), LoadPolicy.compute(unknownShape, profile))
    }

    /**
     * The sentinel path: a model imported before TEN-18 has `kvFullElementsPerToken = 0`, and must
     * plan exactly as it did before — never larger. Pinned by giving a second model the shape the
     * old formula assumed (`2 * 1000 * 20` elements per token) and requiring identical plans.
     */
    @Test
    fun unknownKvShape_plansExactlyAsThePreTen18EstimateDid() {
        val unknownShape = model(contextLength = 32_768)
        val spelledOut = model(contextLength = 32_768, kvFullElementsPerToken = 40_000L)

        for (budget in listOf(8L * GB, 200_000_000L, 50_000_000L, 40_000_000L)) {
            val profile = deviceWithKvBudget(budget)
            assertEquals(
                "unknown shape must not plan differently from the estimate it falls back to",
                LoadPolicy.compute(spelledOut, profile),
                LoadPolicy.compute(unknownShape, profile),
            )
        }
    }

    /**
     * Sliding-window layers stop growing past the window, so the plan shouldn't pay for them at
     * full context. The shape is synthetic — 25 windowed layers at 4 096 elements each and 5
     * full-attention at 2 048, window 1024 — picked so the two terms are far enough apart to check
     * by hand. It is not any model's real header; see
     * [io.github.hokanosekai.tensai.data.models.KvCacheSizeTest] for the same note.
     *
     * At 4096 the windowed cache is `min(4096, 1024 + 512)` = 1536 cells, so F16 costs
     * `2 * (10 240 * 4096 + 102 400 * 1536)` = 398 458 880 bytes — against 1 761 607 680 if those
     * 25 layers were charged at full context, which is the budget this device doesn't have.
     */
    @Test
    fun slidingWindowLayers_arentChargedAtFullContext() {
        val gemma = model(
            contextLength = 32_768,
            kvFullElementsPerToken = 10_240L,
            kvSwaElementsPerToken = 102_400L,
            slidingWindow = 1_024L,
        )
        val profile = deviceWithKvBudget(400_000_000L)

        val plan = LoadPolicy.compute(gemma, profile)

        assertEquals(LoadPlan.Feasible(LoadPolicy.CTX_LATENCY_CAP, KvCacheType.F16), plan)
    }

    /**
     * The Qwen3-1.7B's own dimensions, so the fallback branch computes the estimate this model
     * really used to get (`2 * 2048 * 28` elements per token) rather than the synthetic shape the
     * branch tests share.
     */
    private fun qwen3(kvFullElementsPerToken: Long = 0L) = model(
        contextLength = 32_768,
        kvFullElementsPerToken = kvFullElementsPerToken,
    ).copy(embeddingLength = 2_048L, blockCount = 28L)

    private fun model(
        contextLength: Long,
        kvFullElementsPerToken: Long = 0L,
        kvSwaElementsPerToken: Long = 0L,
        slidingWindow: Long = 0L,
    ) = ModelEntity(
        id = "test-model",
        displayName = "Test model",
        fileName = "test.gguf",
        filePath = "/nonexistent/test.gguf",
        sizeBytes = 1_000_000_000L,
        architecture = "qwen2",
        quantLabel = "Q4_K_M",
        paramCount = 1_000_000_000L,
        contextLength = contextLength,
        embeddingLength = 1_000L,
        blockCount = 20L,
        supportsThinking = false,
        importedAt = 0L,
        kvFullElementsPerToken = kvFullElementsPerToken,
        kvSwaElementsPerToken = kvSwaElementsPerToken,
        slidingWindow = slidingWindow,
    )

    /**
     * A device whose [DeviceProfile.usableRamBytes] leaves exactly [kvBudgetBytes] for the KV cache
     * of the 1GB model above, so each test names the one number its arithmetic turns on.
     *
     * [DeviceProfile.availableRamBytes] is 0: no device reports that, and any plan that changed
     * because of it would be reading a field TEN-77 took out of the budget.
     */
    private fun deviceWithKvBudget(kvBudgetBytes: Long) = DeviceProfile(
        totalRamBytes = kvBudgetBytes + 1_000_000_000L + OVERHEAD_BYTES + DeviceProfile.SYSTEM_RAM_MARGIN_BYTES,
        availableRamBytes = 0,
        socModel = "test-soc",
        bigCoreCount = 4,
        hasVulkan = false,
        hasOpenCl = false,
    )

    private companion object {
        const val MB = 1024L * 1024
        const val GB = 1024L * 1024 * 1024
        const val OVERHEAD_BYTES = 300L * 1024 * 1024
    }
}
