package io.github.hokanosekai.tensai.ui.screens.settings

import io.github.hokanosekai.tensai.compat.DeviceProfile
import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.engine.KvCacheType
import io.github.hokanosekai.tensai.engine.LoadedModelInfo
import io.github.hokanosekai.tensai.runtime.LoadPlan
import io.github.hokanosekai.tensai.runtime.LoadPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What Settings > Inference reports for context size and KV-cache precision (TEN-38): the engine's
 * own answer when there is one, [LoadPolicy]'s plan when there isn't, and nothing at all when
 * neither exists.
 *
 * The point being defended here is that neither row is a constant. `4096` and `f16` are what a
 * roomy device happens to land on, and a smaller device lands somewhere else — see
 * `theSameLibraryOnAPhoneWithLessRamReportsDifferentNumbers`. Since TEN-77 "smaller" means fewer
 * gigabytes soldered onto the phone, not less free RAM at this instant: the plan no longer moves
 * with memory pressure, and `profile` below pins free RAM at a figure low enough that a row still
 * reading it would show it.
 */
class SettingsInferenceTest {

    private val roomy = profile(totalRamBytes = 8L * 1024 * 1024 * 1024)

    @Test
    fun aResidentModelReportsWhatTheEngineActuallyLoadedWith() {
        val facts = SettingsInference.facts(
            resident = LoadedModelInfo(
                name = "Qwen3-1.7B",
                nCtxEffective = 2048,
                backend = "CPU",
                kvCacheType = KvCacheType.Q8_0,
            ),
            models = listOf(model("a")),
            profile = roomy,
            fileExists = { true },
        )

        assertEquals(
            "the loaded model's own figures win over any plan — a plan is a guess about a load " +
                "that has already happened",
            InferenceFacts.InUse(modelName = "Qwen3-1.7B", nCtx = 2048, kvCacheType = KvCacheType.Q8_0),
            facts,
        )
    }

    @Test
    fun nothingLoadedFallsBackToTheLoadPolicysPlanForTheNextModel() {
        val next = model("a", contextLength = 4096)

        val facts = SettingsInference.facts(
            resident = null,
            models = listOf(next),
            profile = roomy,
            fileExists = { true },
        )

        val expected = LoadPolicy.compute(next, roomy) as LoadPlan.Feasible
        assertEquals(
            InferenceFacts.Planned(modelName = "a", nCtx = expected.nCtx, kvCacheType = expected.kvCacheType),
            facts,
        )
    }

    @Test
    fun theSameLibraryOnAPhoneWithLessRamReportsDifferentNumbers() {
        // The one assertion that would fail if either row were ever hard-coded: same model, same
        // code path, two phones, two answers. 5408 MiB of total RAM leaves 500 MiB for the KV
        // cache after the system margin, this model's 3 GB of weights and the 300 MiB overhead —
        // short of the 672 MiB F16 wants at 4096, past the 357 MiB Q8_0 wants for the same 4096.
        val big = model("big", sizeBytes = 3L * 1024 * 1024 * 1024, contextLength = 4096)

        val roomyFacts = SettingsInference.facts(
            resident = null,
            models = listOf(big),
            profile = profile(totalRamBytes = 8L * 1024 * 1024 * 1024),
            fileExists = { true },
        ) as InferenceFacts.Planned
        val tightFacts = SettingsInference.facts(
            resident = null,
            models = listOf(big),
            profile = profile(totalRamBytes = 5408L * 1024 * 1024),
            fileExists = { true },
        ) as InferenceFacts.Planned

        assertEquals(KvCacheType.F16, roomyFacts.kvCacheType)
        assertEquals(4096, roomyFacts.nCtx)
        assertEquals(KvCacheType.Q8_0, tightFacts.kvCacheType)
    }

    @Test
    fun aModelWhoseFileIsGoneIsNotPlannedFor() {
        val facts = SettingsInference.facts(
            resident = null,
            models = listOf(model("gone")),
            profile = roomy,
            fileExists = { false },
        )

        assertEquals(
            "same rule as HomePill and OnboardingGate — a row without its file can never be " +
                "loaded, so there is no load to plan",
            InferenceFacts.Unknown,
            facts,
        )
    }

    @Test
    fun thePlanSkipsMissingFilesAndLandsOnASurvivingModel() {
        val facts = SettingsInference.facts(
            resident = null,
            models = listOf(model("gone"), model("here")),
            profile = roomy,
            fileExists = { path -> path == "/models/here.gguf" },
        )

        assertEquals("here", (facts as InferenceFacts.Planned).modelName)
    }

    @Test
    fun anEmptyLibraryHasNothingToReport() {
        val facts = SettingsInference.facts(resident = null, models = emptyList(), profile = roomy)

        assertEquals(InferenceFacts.Unknown, facts)
    }

    @Test
    fun anUnreadableDeviceProfileReportsNothingRatherThanAMadeUpPlan() {
        val facts = SettingsInference.facts(
            resident = null,
            models = listOf(model("a")),
            profile = null,
            fileExists = { true },
        )

        assertEquals(InferenceFacts.Unknown, facts)
    }

    @Test
    fun aResidentModelIsReportedEvenWithNoRowsAndNoProfile() {
        val facts = SettingsInference.facts(
            resident = LoadedModelInfo("Phi 4 Mini", 1024, "Vulkan", KvCacheType.F16),
            models = emptyList(),
            profile = null,
            fileExists = { false },
        )

        assertEquals(
            InferenceFacts.InUse(modelName = "Phi 4 Mini", nCtx = 1024, kvCacheType = KvCacheType.F16),
            facts,
        )
    }

    /**
     * Free RAM is pinned at 512 MiB — well under any of these models — so a row that still sized
     * itself against [DeviceProfile.availableRamBytes] would report a context none of these tests
     * expect (TEN-77).
     */
    private fun profile(totalRamBytes: Long) = DeviceProfile(
        totalRamBytes = totalRamBytes,
        availableRamBytes = 512L * 1024 * 1024,
        socModel = "test-soc",
        bigCoreCount = 4,
        hasVulkan = true,
        hasOpenCl = false,
    )

    private fun model(
        name: String,
        sizeBytes: Long = 1_000_000,
        contextLength: Long = 4096,
    ) = ModelEntity(
        id = name,
        displayName = name,
        fileName = "$name.gguf",
        filePath = "/models/$name.gguf",
        sizeBytes = sizeBytes,
        architecture = "qwen2",
        quantLabel = "Q4_K_M",
        paramCount = 1_500_000_000,
        contextLength = contextLength,
        embeddingLength = 1536,
        blockCount = 28,
        supportsThinking = false,
        importedAt = 0L,
        kvFullElementsPerToken = 2 * 28 * 1536,
    )
}
