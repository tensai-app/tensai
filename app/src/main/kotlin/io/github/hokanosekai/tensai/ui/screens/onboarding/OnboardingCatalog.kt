package io.github.hokanosekai.tensai.ui.screens.onboarding

import io.github.hokanosekai.tensai.compat.Compatibility
import io.github.hokanosekai.tensai.compat.CompatibilityChecker
import io.github.hokanosekai.tensai.compat.DeviceProfile
import io.github.hokanosekai.tensai.compat.ModelFacts
import io.github.hokanosekai.tensai.compat.Verdict
import io.github.hokanosekai.tensai.data.models.ModelEntity

/**
 * A model worth pointing a new user at: downloaded on a computer and imported, since V1 has no
 * in-app download (TEN-45). Everything here is a fact about the Q4_K_M build, so the same
 * [CompatibilityChecker] that judges imported files can judge it before any file exists.
 *
 * @param hfPath repository path under huggingface.co, shown as `hf.co/<hfPath>` and opened as a link.
 */
internal data class CatalogModel(
    val name: String,
    val hfPath: String,
    val quantLabel: String,
    val facts: ModelFacts,
) {
    val linkLabel: String get() = "hf.co/$hfPath"
    val linkUrl: String get() = "https://huggingface.co/$hfPath"

    /** The card composable takes a [ModelEntity]; this one never touches disk, so id and path are inert. */
    fun toDisplayEntity() = ModelEntity(
        id = "catalog/$hfPath",
        displayName = name,
        fileName = "",
        filePath = "",
        sizeBytes = facts.sizeBytes,
        architecture = facts.architecture,
        quantLabel = quantLabel,
        paramCount = facts.paramCount,
        contextLength = facts.contextLength,
        embeddingLength = facts.embeddingLength,
        blockCount = facts.blockCount,
        supportsThinking = false,
        importedAt = 0,
        kvFullElementsPerToken = facts.kvFullElementsPerToken,
        kvSwaElementsPerToken = facts.kvSwaElementsPerToken,
        slidingWindow = facts.slidingWindow,
    )
}

/** A [CatalogModel] with the verdict it got on this device. */
internal data class RatedModel(val model: CatalogModel, val compatibility: Compatibility)

/** The recommendation and the rest of the catalog, each with its verdict. */
internal data class Recommendation(val recommended: RatedModel, val others: List<RatedModel>)

/**
 * The static list and the rule that picks from it. No network: sizes are the real bartowski
 * Q4_K_M files, attention shapes are each model's published config (2 x layers x kv heads x
 * head dim elements per token), all dense so there is no windowed cache.
 */
internal object OnboardingCatalog {

    private const val QUANT = "Q4_K_M"

    val models: List<CatalogModel> = listOf(
        CatalogModel(
            name = "Llama 3.2 1B Instruct",
            hfPath = "bartowski/Llama-3.2-1B-Instruct-GGUF",
            quantLabel = QUANT,
            facts = ModelFacts(
                sizeBytes = 807_694_464,
                paramCount = 1_240_000_000,
                architecture = "llama",
                contextLength = 131_072,
                embeddingLength = 2048,
                blockCount = 16,
                kvFullElementsPerToken = 2L * 16 * 8 * 64,
            ),
        ),
        CatalogModel(
            name = "Qwen3 1.7B Instruct",
            hfPath = "bartowski/Qwen_Qwen3-1.7B-GGUF",
            quantLabel = QUANT,
            facts = ModelFacts(
                sizeBytes = 1_282_439_584,
                paramCount = 1_720_000_000,
                architecture = "qwen3",
                contextLength = 40_960,
                embeddingLength = 2048,
                blockCount = 28,
                kvFullElementsPerToken = 2L * 28 * 8 * 128,
            ),
        ),
        CatalogModel(
            name = "Qwen3 4B Instruct",
            hfPath = "bartowski/Qwen_Qwen3-4B-GGUF",
            quantLabel = QUANT,
            facts = ModelFacts(
                sizeBytes = 2_497_280_960,
                paramCount = 4_020_000_000,
                architecture = "qwen3",
                contextLength = 40_960,
                embeddingLength = 2560,
                blockCount = 36,
                kvFullElementsPerToken = 2L * 36 * 8 * 128,
            ),
        ),
    )

    /**
     * [profile] judged on the device class, see [recommend]. Shared with the Hugging Face
     * quant ratings so every "does it fit" answer in the app uses the same basis.
     */
    fun steadyProfile(profile: DeviceProfile): DeviceProfile = profile.copy(availableRamBytes = profile.usableRamBytes)

    /**
     * The biggest model that [Verdict.RunsWell] on [profile] (parameter count is the size proxy,
     * as in [CompatibilityChecker]), or the smallest one when none does. The others are the rest
     * of the catalog, smallest first, each with its own verdict.
     *
     * Judged on the device class, not on the moment: the verdict is computed with the free memory
     * set to the usable memory ([DeviceProfile.usableRamBytes]), because [CompatibilityChecker]
     * softens a verdict to TightFit whenever the estimate exceeds what is free *right now*, and a
     * recommendation that changed with the apps open at launch would be a different answer on
     * every start. Imported models keep the live check; this one is a catalog, not a load.
     */
    fun recommend(profile: DeviceProfile, catalog: List<CatalogModel> = models): Recommendation {
        require(catalog.isNotEmpty()) { "empty catalog" }
        val steady = steadyProfile(profile)
        val rated = catalog.sortedBy { it.facts.paramCount }
            .map { RatedModel(it, CompatibilityChecker.check(it.facts, steady)) }
        val pick = rated.lastOrNull { it.compatibility.verdict == Verdict.RunsWell } ?: rated.first()
        return Recommendation(pick, rated.filter { it !== pick })
    }
}
