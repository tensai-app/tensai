package io.github.hokanosekai.tensai.ui.screens.models

import androidx.annotation.StringRes
import io.github.hokanosekai.tensai.R
import io.github.hokanosekai.tensai.compat.Compatibility
import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.data.BackendPreference
import io.github.hokanosekai.tensai.runtime.EffectiveBackend
import io.github.hokanosekai.tensai.runtime.LoadProgressMap

/** Backend filter above the Models list. */
internal enum class ModelFilter(@StringRes val labelRes: Int) {
    All(R.string.models_filter_all),
    Vulkan(R.string.models_filter_vulkan),
    Cpu(R.string.models_filter_cpu),
}

/**
 * Which models the list shows. [ModelFilter.All] keeps everything, including models whose file is
 * gone (no verdict, so no backend); the two backend filters keep only models whose [verdicts] entry
 * resolves to that backend through [backendTag] (same [preference] and device flags as the badge),
 * which drops missing-file ones. A model absent from [verdicts] counts as having no verdict. An
 * OpenCL model matches neither backend filter (there is no OpenCL chip, no OpenCL-only build ships,
 * TEN-19) and only shows under [ModelFilter.All]. Order is preserved.
 */
internal fun filterModels(
    models: List<ModelEntity>,
    verdicts: Map<String, Compatibility?>,
    filter: ModelFilter,
    preference: BackendPreference,
    hasVulkan: Boolean,
    hasOpenCl: Boolean,
): List<ModelEntity> =
    if (filter == ModelFilter.All) models
    else models.filter { model ->
        val tag = backendTag(verdicts[model.id], preference, hasVulkan, hasOpenCl)
        when (filter) {
            ModelFilter.Vulkan -> tag == EffectiveBackend.Vulkan
            ModelFilter.Cpu -> tag == EffectiveBackend.Cpu
            ModelFilter.All -> true
        }
    }

/**
 * The backend a load of this model will actually use, the exact rule of the loader: the user's
 * [preference] resolves the checker's recommendation ([BackendPreference.resolve]), then the engine
 * runs [io.github.hokanosekai.tensai.engine.BackendHint.Cpu] on CPU and everything else, Auto
 * included (what the checker returns for an unknown architecture), on the device's GPU backend when
 * it has one ([LoadProgressMap.effectiveBackend]). `null` when there is no verdict, i.e. the model's
 * file is missing. One function for the badge, the detail row and the filter, so they cannot
 * disagree.
 */
internal fun backendTag(
    compatibility: Compatibility?,
    preference: BackendPreference,
    hasVulkan: Boolean,
    hasOpenCl: Boolean,
): EffectiveBackend? = compatibility?.let {
    LoadProgressMap.effectiveBackend(preference.resolve(it.recommendedBackend), hasVulkan, hasOpenCl)
}

/**
 * Filters offered for this device: Vulkan only when it has Vulkan. There is no OpenCL chip, so a
 * model that resolves to OpenCL only shows under [ModelFilter.All] (TEN-19).
 */
internal fun availableFilters(hasVulkan: Boolean): List<ModelFilter> =
    if (hasVulkan) ModelFilter.entries else listOf(ModelFilter.All, ModelFilter.Cpu)
