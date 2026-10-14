package io.github.hokanosekai.tensai.ui.screens.onboarding

import io.github.hokanosekai.tensai.compat.Compatibility
import io.github.hokanosekai.tensai.compat.CompatibilityChecker
import io.github.hokanosekai.tensai.compat.DeviceProfile
import io.github.hokanosekai.tensai.compat.ModelFacts
import io.github.hokanosekai.tensai.compat.Verdict
import io.github.hokanosekai.tensai.data.models.ModelEntity

/**
 * A model worth pointing a new user at, from the embedded catalog (`catalog.json`, see
 * [CatalogParser]). Everything here is a fact about the [quantLabel] build, so the same
 * [CompatibilityChecker] that judges imported files can judge it before any file exists.
 *
 * @param hfPath repository path under huggingface.co, shown as `hf.co/<hfPath>` and opened as a link.
 * @param fileName the GGUF file of that quant in the repo.
 * @param supportsTools its chat template takes tools (checked against the Hub when the entry was added).
 * @param onboarding offered by the first-run onboarding (the three it has always offered); the rest
 *   only shows in Discover's "Recommended for your device".
 */
internal data class CatalogModel(
    val name: String,
    val hfPath: String,
    val quantLabel: String,
    val facts: ModelFacts,
    val fileName: String = "",
    val supportsTools: Boolean = false,
    val onboarding: Boolean = false,
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
 * The embedded catalog and the rule that picks from it. No network: `catalog.json` ships in the
 * APK, sizes are the real bartowski Q4_K_M files, attention shapes are each model's published
 * config (2 x layers x kv heads x head dim elements per token), all dense so there is no windowed
 * cache.
 */
internal object OnboardingCatalog {

    /** Every entry of `catalog.json`, in file order. Discover's "Recommended for your device" reads this. */
    val all: List<CatalogModel> by lazy { CatalogParser.loadEmbedded() }

    /** The entries the first-run onboarding offers (flagged `onboarding` in the file). */
    val models: List<CatalogModel> by lazy { all.filter { it.onboarding } }

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
