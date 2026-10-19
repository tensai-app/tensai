package io.github.tensaiapp.ui.screens.models

import io.github.tensaiapp.compat.Compatibility
import io.github.tensaiapp.compat.Verdict
import io.github.tensaiapp.data.BackendPreference
import io.github.tensaiapp.data.models.ModelEntity
import io.github.tensaiapp.engine.BackendHint
import io.github.tensaiapp.runtime.EffectiveBackend
import io.github.tensaiapp.runtime.LoadProgressMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * TEN-121, TEN-146: the Models filter and the card badge resolve the same backend the loader will use
 * (preference, then device), through one function, so they cannot disagree.
 */
class ModelFilterTest {

    private fun model(id: String) = ModelEntity(
        id = id, displayName = id, fileName = "$id.gguf", filePath = "/x/$id.gguf", sizeBytes = 1L,
        architecture = "qwen2", quantLabel = "Q4_K_M", paramCount = 1L, contextLength = 4096L,
        embeddingLength = 1L, blockCount = 1L, supportsThinking = false, importedAt = 0L,
    )

    private fun verdict(backend: BackendHint) = Compatibility(Verdict.RunsWell, 1L, 1L, backend)

    private val gpu = model("gpu")
    private val cpu = model("cpu")
    private val auto = model("auto")
    private val missing = model("missing")
    private val all = listOf(gpu, cpu, auto, missing)
    private fun filter(models: List<ModelEntity>, filter: ModelFilter, preference: BackendPreference = BackendPreference.Auto, hasVulkan: Boolean = true, hasOpenCl: Boolean = false) =
        filterModels(models, verdicts, filter, preference, hasVulkan, hasOpenCl)

    private val verdicts = mapOf(
        gpu.id to verdict(BackendHint.Gpu),
        cpu.id to verdict(BackendHint.Cpu),
        auto.id to verdict(BackendHint.Auto),
        missing.id to null,
    )

    @Test
    fun allKeepsEverythingIncludingMissingFileModels() {
        assertEquals(all, filter(all, ModelFilter.All))
    }

    @Test
    fun vulkanKeepsGpuAndAutoModelsOnAVulkanDevice() {
        assertEquals(listOf(gpu, auto), filter(all, ModelFilter.Vulkan))
    }

    @Test
    fun cpuKeepsOnlyCpuRecommendedModelsOnAVulkanDevice() {
        assertEquals(listOf(cpu), filter(all, ModelFilter.Cpu))
    }

    @Test
    fun withoutAGpuBackendEveryModelWithAVerdictIsCpu() {
        assertEquals(emptyList<ModelEntity>(), filter(all, ModelFilter.Vulkan, hasVulkan = false))
        assertEquals(listOf(gpu, cpu, auto), filter(all, ModelFilter.Cpu, hasVulkan = false))
    }

    @Test
    fun forcedCpuPreferenceMovesEverythingToCpu() {
        assertEquals(emptyList<ModelEntity>(), filter(all, ModelFilter.Vulkan, BackendPreference.Cpu))
        assertEquals(listOf(gpu, cpu, auto), filter(all, ModelFilter.Cpu, BackendPreference.Cpu))
    }

    @Test
    fun forcedGpuPreferenceMovesEverythingToVulkan() {
        assertEquals(listOf(gpu, cpu, auto), filter(all, ModelFilter.Vulkan, BackendPreference.Gpu))
        assertEquals(emptyList<ModelEntity>(), filter(all, ModelFilter.Cpu, BackendPreference.Gpu))
    }

    @Test
    fun missingFileModelsOnlyShowUnderAll() {
        assertEquals(emptyList<ModelEntity>(), filter(listOf(missing), ModelFilter.Vulkan))
        assertEquals(emptyList<ModelEntity>(), filter(listOf(missing), ModelFilter.Cpu))
        assertEquals(listOf(missing), filter(listOf(missing), ModelFilter.All))
    }

    @Test
    fun modelWithoutAVerdictEntryCountsAsMissing() {
        assertEquals(emptyList<ModelEntity>(), filterModels(listOf(gpu), emptyMap(), ModelFilter.Vulkan, BackendPreference.Auto, true, false))
    }

    @Test
    fun filterWithNoMatchIsEmptyAndKeepsOrderOtherwise() {
        assertEquals(emptyList<ModelEntity>(), filter(listOf(cpu), ModelFilter.Vulkan))
        assertEquals(listOf(cpu), filter(listOf(auto, cpu), ModelFilter.Cpu))
    }

    @Test
    fun autoRecommendationFollowsTheDevice() {
        assertEquals(EffectiveBackend.Vulkan, tag(BackendHint.Auto, hasVulkan = true))
        assertEquals(EffectiveBackend.Cpu, tag(BackendHint.Auto, hasVulkan = false))
        assertEquals(EffectiveBackend.OpenCl, tag(BackendHint.Auto, hasVulkan = false, hasOpenCl = true))
    }

    @Test
    fun gpuRecommendationWithAutoPreferenceIsVulkan() {
        assertEquals(EffectiveBackend.Vulkan, tag(BackendHint.Gpu))
    }

    @Test
    fun cpuRecommendationWithAutoPreferenceStaysCpu() {
        assertEquals(EffectiveBackend.Cpu, tag(BackendHint.Cpu))
    }

    @Test
    fun cpuPreferenceForcesCpu() {
        assertEquals(EffectiveBackend.Cpu, tag(BackendHint.Gpu, BackendPreference.Cpu))
        assertEquals(EffectiveBackend.Cpu, tag(BackendHint.Auto, BackendPreference.Cpu))
    }

    @Test
    fun gpuPreferenceFallsBackToCpuWithoutAGpuBackend() {
        assertEquals(EffectiveBackend.Vulkan, tag(BackendHint.Cpu, BackendPreference.Gpu))
        assertEquals(EffectiveBackend.Cpu, tag(BackendHint.Cpu, BackendPreference.Gpu, hasVulkan = false))
    }

    @Test
    fun noVerdictMeansNoBackend() {
        assertNull(backendTag(null, BackendPreference.Auto, hasVulkan = true, hasOpenCl = true))
    }

    @Test
    fun vulkanWinsOverOpenClWhenBothArePresent() {
        assertEquals(EffectiveBackend.Vulkan, tag(BackendHint.Auto, hasVulkan = true, hasOpenCl = true))
    }

    @Test
    fun cpuHintAndCpuPreferenceStayCpuOnAnOpenClDevice() {
        assertEquals(EffectiveBackend.Cpu, tag(BackendHint.Cpu, hasVulkan = false, hasOpenCl = true))
        assertEquals(EffectiveBackend.Cpu, tag(BackendHint.Gpu, BackendPreference.Cpu, hasVulkan = false, hasOpenCl = true))
    }

    @Test
    fun openClModelsShowUnderAllAndOpenCl() {
        assertEquals(all, filter(all, ModelFilter.All, hasVulkan = false, hasOpenCl = true))
        assertEquals(listOf(cpu), filter(all, ModelFilter.Cpu, hasVulkan = false, hasOpenCl = true))
        assertEquals(emptyList<ModelEntity>(), filter(all, ModelFilter.Vulkan, hasVulkan = false, hasOpenCl = true))
        assertEquals(listOf(gpu, auto), filter(all, ModelFilter.OpenCl, hasVulkan = false, hasOpenCl = true))
        assertEquals(emptyList<ModelEntity>(), filter(all, ModelFilter.OpenCl, hasVulkan = true, hasOpenCl = false))
    }

    @Test
    fun forcedCpuPreferenceOnAnOpenClDeviceIsAllCpu() {
        assertEquals(listOf(gpu, cpu, auto), filter(all, ModelFilter.Cpu, BackendPreference.Cpu, hasVulkan = false, hasOpenCl = true))
    }

    @Test
    fun tagLabelMatchesTheLoaderLabelForEveryCombination() {
        for (preference in BackendPreference.entries) for (recommended in BackendHint.entries) {
            for (hasVulkan in listOf(false, true)) for (hasOpenCl in listOf(false, true)) {
                val loaderLabel = LoadProgressMap.backendLabel(preference.resolve(recommended), hasVulkan, hasOpenCl)
                assertEquals(
                    "$preference $recommended vulkan=$hasVulkan opencl=$hasOpenCl",
                    loaderLabel,
                    tag(recommended, preference, hasVulkan, hasOpenCl)?.label,
                )
            }
        }
    }

    private fun tag(
        recommended: BackendHint,
        preference: BackendPreference = BackendPreference.Auto,
        hasVulkan: Boolean = true,
        hasOpenCl: Boolean = false,
    ) = backendTag(verdict(recommended), preference, hasVulkan, hasOpenCl)

    @Test
    fun vulkanChipIsHiddenWithoutVulkan() {
        assertEquals(listOf(ModelFilter.All, ModelFilter.Vulkan, ModelFilter.Cpu), availableFilters(hasVulkan = true))
        assertEquals(listOf(ModelFilter.All, ModelFilter.Cpu), availableFilters(hasVulkan = false))
    }

    @Test
    fun openClChipOnlyWithOpenCl() {
        assertEquals(
            listOf(ModelFilter.All, ModelFilter.Vulkan, ModelFilter.Cpu, ModelFilter.OpenCl),
            availableFilters(hasVulkan = true, hasOpenCl = true),
        )
        assertEquals(listOf(ModelFilter.All, ModelFilter.Cpu, ModelFilter.OpenCl), availableFilters(hasVulkan = false, hasOpenCl = true))
    }
}
