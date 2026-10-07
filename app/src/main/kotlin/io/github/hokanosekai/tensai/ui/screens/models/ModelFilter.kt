package io.github.hokanosekai.tensai.ui.screens.models

import androidx.annotation.StringRes
import io.github.hokanosekai.tensai.R
import io.github.hokanosekai.tensai.compat.Compatibility
import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.engine.BackendHint

/** Backend filter above the Models list. */
internal enum class ModelFilter(@StringRes val labelRes: Int) {
    All(R.string.models_filter_all),
    Vulkan(R.string.models_filter_vulkan),
    Cpu(R.string.models_filter_cpu),
}

/**
 * Which models the list shows. [ModelFilter.All] keeps everything, including models whose file is
 * gone (no verdict, so no backend); the two backend filters keep only models whose [verdicts] entry
 * carries that backend, which drops missing-file ones. A model absent from [verdicts] counts as
 * having no verdict. Order is preserved.
 */
internal fun filterModels(
    models: List<ModelEntity>,
    verdicts: Map<String, Compatibility?>,
    filter: ModelFilter,
): List<ModelEntity> =
    if (filter == ModelFilter.All) models
    else models.filter { backendTag(verdicts[it.id]) == filter }

/**
 * The backend a card advertises: Vulkan when the verdict recommends [BackendHint.Gpu], CPU for
 * everything else ([BackendHint.Cpu], and [BackendHint.Auto], which is what the checker returns
 * for an unknown architecture and which the loader resolves to CPU unless a GPU is picked
 * explicitly). `null` when there is no verdict, i.e. the model's file is missing. One function for
 * the badge and the filter, so they cannot disagree.
 */
internal fun backendTag(compatibility: Compatibility?): ModelFilter? = when (compatibility?.recommendedBackend) {
    null -> null
    BackendHint.Gpu -> ModelFilter.Vulkan
    BackendHint.Cpu, BackendHint.Auto -> ModelFilter.Cpu
}

/** Filters offered for this device: Vulkan only when it has Vulkan. */
internal fun availableFilters(hasVulkan: Boolean): List<ModelFilter> =
    if (hasVulkan) ModelFilter.entries else listOf(ModelFilter.All, ModelFilter.Cpu)
