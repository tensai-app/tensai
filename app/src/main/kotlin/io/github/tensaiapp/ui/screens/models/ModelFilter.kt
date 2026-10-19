package io.github.tensaiapp.ui.screens.models

import androidx.annotation.StringRes
import io.github.tensaiapp.R
import io.github.tensaiapp.compat.Compatibility
import io.github.tensaiapp.data.models.ModelEntity
import io.github.tensaiapp.data.BackendPreference
import io.github.tensaiapp.runtime.EffectiveBackend
import io.github.tensaiapp.runtime.LoadProgressMap

/** Backend filter above the Models list. */
internal enum class ModelFilter(@StringRes val labelRes: Int) {
    All(R.string.models_filter_all),
    Vulkan(R.string.models_filter_vulkan),
    Cpu(R.string.models_filter_cpu),
    OpenCl(R.string.models_filter_opencl),
}

/**
 * Which models the list shows. [ModelFilter.All] keeps everything, including models whose file is
 * gone (no verdict, so no backend); the two backend filters keep only models whose [verdicts] entry
 * resolves to that backend through [backendTag] (same [preference] and device flags as the badge),
 * which drops missing-file ones. A model absent from [verdicts] counts as having no verdict. An
 * OpenCL model matches [ModelFilter.OpenCl], which is only offered on a phone that has the backend
 * ([availableFilters]). Order is preserved.
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
            ModelFilter.OpenCl -> tag == EffectiveBackend.OpenCl
            ModelFilter.All -> true
        }
    }

/**
 * The backend a load of this model will actually use, the exact rule of the loader: the user's
 * [preference] resolves the checker's recommendation ([BackendPreference.resolve]), then the engine
 * runs [io.github.tensaiapp.engine.BackendHint.Cpu] on CPU and everything else, Auto
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

/** Filters offered for this device: All and CPU, Vulkan and OpenCL only when the phone has them. */
internal fun availableFilters(hasVulkan: Boolean, hasOpenCl: Boolean = false): List<ModelFilter> = buildList {
    add(ModelFilter.All)
    if (hasVulkan) add(ModelFilter.Vulkan)
    add(ModelFilter.Cpu)
    if (hasOpenCl) add(ModelFilter.OpenCl)
}
