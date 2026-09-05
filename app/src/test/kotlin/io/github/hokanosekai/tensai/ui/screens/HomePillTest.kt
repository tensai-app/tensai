package io.github.hokanosekai.tensai.ui.screens

import io.github.hokanosekai.tensai.data.models.ModelEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which face Home's input pill wears, as a function of state (TEN-37) — the ticket's three cases
 * plus the edge `OnboardingGate` already rules on: a model row whose file is gone.
 *
 * Same rule as `OnboardingGateTest`, on purpose. A row without its file cannot be loaded, so
 * offering "Load X to start" would send the user at a load that fails; both screens have to agree
 * that such an install has no model at all.
 */
class HomePillTest {

    @Test
    fun noModelsAtAllIsACallToAction() {
        val state = HomePill.state(loadedModelName = null, models = emptyList(), fileExists = { true })

        assertEquals(HomePillState.NoModel, state)
    }

    @Test
    fun modelsButNoneLoadedOffersToLoadOne() {
        val state = HomePill.state(loadedModelName = null, models = listOf(model("a")), fileExists = { true })

        assertEquals(HomePillState.LoadNeeded(modelId = "a", displayName = "a"), state)
    }

    @Test
    fun aLoadedModelGetsTheRealPill() {
        val state = HomePill.state(loadedModelName = "Qwen3-4B", models = listOf(model("a")), fileExists = { true })

        assertEquals(HomePillState.Ready("Qwen3-4B"), state)
    }

    @Test
    fun aModelWhoseFileIsGoneDoesNotCountAsAModel() {
        val state = HomePill.state(loadedModelName = null, models = listOf(model("a")), fileExists = { false })

        assertEquals(
            "same rule as OnboardingGate — a row without its file cannot be loaded, so there is " +
                "nothing to offer and the pill has to send the user to import",
            HomePillState.NoModel,
            state,
        )
    }

    @Test
    fun theOfferSkipsMissingFilesAndLandsOnASurvivingOne() {
        // observeAll() orders by importedAt DESC, so the newest import comes first — and the offer
        // has to walk past it if its file is gone rather than giving up.
        val models = listOf(model("gone"), model("here"), model("also-gone"))

        val state = HomePill.state(
            loadedModelName = null,
            models = models,
            fileExists = { path -> path == "/models/here.gguf" },
        )

        assertEquals(HomePillState.LoadNeeded(modelId = "here", displayName = "here"), state)
    }

    @Test
    fun aResidentModelWinsEvenWhenItsOwnFileHasGone() {
        val state = HomePill.state(loadedModelName = "Qwen3-4B", models = listOf(model("a")), fileExists = { false })

        assertEquals(
            "it is in RAM and can answer — whatever happened to the file on disk, the honest " +
                "thing to show is a working pill",
            HomePillState.Ready("Qwen3-4B"),
            state,
        )
    }

    @Test
    fun aResidentModelWithNoRowBehindItIsStillReady() {
        val state = HomePill.state(loadedModelName = "Qwen3-4B", models = emptyList(), fileExists = { true })

        assertEquals(HomePillState.Ready("Qwen3-4B"), state)
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
