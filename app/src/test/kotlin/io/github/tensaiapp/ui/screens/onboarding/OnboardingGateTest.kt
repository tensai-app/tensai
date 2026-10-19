package io.github.tensaiapp.ui.screens.onboarding

import io.github.tensaiapp.data.models.ModelEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The onboarding's re-trigger rule (TEN-36), as a function of state: which launches go through
 * the first-run flow, and at which step. Nothing here needs a device — that is the point, since
 * "does it come back when it should" is the part of this ticket that can actually be proven.
 */
class OnboardingGateTest {

    @Test
    fun aFreshInstallStartsAtTheWelcomeScreen() {
        val entry = OnboardingGate.entry(models = emptyList(), skipped = false, fileExists = { true })

        assertEquals(OnboardingEntry.Show(OnboardingStep.Welcome), entry)
    }

    @Test
    fun skippingDoesNotStopTheOnboardingComingBackWhileThereIsStillNoModel() {
        val entry = OnboardingGate.entry(models = emptyList(), skipped = true, fileExists = { true })

        assertEquals(
            "an install with no model cannot chat — skipping postpones the flow, it does not cancel it",
            OnboardingEntry.Show(OnboardingStep.Import),
            entry,
        )
    }

    @Test
    fun oneImportedModelIsEnoughToSkipTheOnboardingEntirely() {
        val entry = OnboardingGate.entry(models = listOf(model("a")), skipped = false, fileExists = { true })

        assertEquals(OnboardingEntry.None, entry)
    }

    @Test
    fun aModelWhoseFileIsGoneDoesNotCountAsAModel() {
        val entry = OnboardingGate.entry(models = listOf(model("a")), skipped = false, fileExists = { false })

        assertEquals(
            "a row without its file cannot be loaded, so that install is as blank as a fresh one",
            OnboardingEntry.Show(OnboardingStep.Welcome),
            entry,
        )
    }

    @Test
    fun aSingleSurvivingFileAmongMissingOnesStillCountsAsAModel() {
        val models = listOf(model("gone-1"), model("here"), model("gone-2"))

        val entry = OnboardingGate.entry(
            models = models,
            skipped = false,
            fileExists = { path -> path == "/models/here.gguf" },
        )

        assertEquals(OnboardingEntry.None, entry)
    }

    @Test
    fun havingAModelWinsOverHavingSkipped() {
        val entry = OnboardingGate.entry(models = listOf(model("a")), skipped = true, fileExists = { true })

        assertEquals(OnboardingEntry.None, entry)
    }

    private fun model(name: String) = ModelEntity(
        id = name,
        displayName = name,
        fileName = "$name.gguf",
        filePath = "/models/$name.gguf",
        sizeBytes = 1_000_000,
        architecture = "qwen2",
        quantLabel = "Q4_K_M",
        paramCount = 1_500_000_000,
        contextLength = 4096,
        embeddingLength = 1536,
        blockCount = 28,
        supportsThinking = false,
        importedAt = 0L,
    )
}
