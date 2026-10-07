package io.github.hokanosekai.tensai.ui.screens.models

import io.github.hokanosekai.tensai.compat.Compatibility
import io.github.hokanosekai.tensai.compat.Verdict
import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.engine.BackendHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** TEN-121: the Models filter and the card badge read the same verdict, so they cannot disagree. */
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
    private val verdicts = mapOf(
        gpu.id to verdict(BackendHint.Gpu),
        cpu.id to verdict(BackendHint.Cpu),
        auto.id to verdict(BackendHint.Auto),
        missing.id to null,
    )

    @Test
    fun allKeepsEverythingIncludingMissingFileModels() {
        assertEquals(all, filterModels(all, verdicts, ModelFilter.All))
    }

    @Test
    fun vulkanKeepsOnlyGpuRecommendedModels() {
        assertEquals(listOf(gpu), filterModels(all, verdicts, ModelFilter.Vulkan))
    }

    @Test
    fun cpuKeepsCpuAndAutoButNotMissingFileModels() {
        assertEquals(listOf(cpu, auto), filterModels(all, verdicts, ModelFilter.Cpu))
    }

    @Test
    fun missingFileModelsOnlyShowUnderAll() {
        assertEquals(emptyList<ModelEntity>(), filterModels(listOf(missing), verdicts, ModelFilter.Vulkan))
        assertEquals(emptyList<ModelEntity>(), filterModels(listOf(missing), verdicts, ModelFilter.Cpu))
        assertEquals(listOf(missing), filterModels(listOf(missing), verdicts, ModelFilter.All))
    }

    @Test
    fun modelWithoutAVerdictEntryCountsAsMissing() {
        assertEquals(emptyList<ModelEntity>(), filterModels(listOf(gpu), emptyMap(), ModelFilter.Vulkan))
    }

    @Test
    fun filterWithNoMatchIsEmptyAndKeepsOrderOtherwise() {
        assertEquals(emptyList<ModelEntity>(), filterModels(listOf(cpu), verdicts, ModelFilter.Vulkan))
        assertEquals(listOf(auto, cpu), filterModels(listOf(auto, cpu), verdicts, ModelFilter.Cpu))
    }

    @Test
    fun badgeTagMatchesTheFilterBucket() {
        assertEquals(ModelFilter.Vulkan, backendTag(verdict(BackendHint.Gpu)))
        assertEquals(ModelFilter.Cpu, backendTag(verdict(BackendHint.Cpu)))
        assertEquals(ModelFilter.Cpu, backendTag(verdict(BackendHint.Auto)))
        assertNull(backendTag(null))
    }

    @Test
    fun vulkanChipIsHiddenWithoutVulkan() {
        assertEquals(listOf(ModelFilter.All, ModelFilter.Vulkan, ModelFilter.Cpu), availableFilters(hasVulkan = true))
        assertEquals(listOf(ModelFilter.All, ModelFilter.Cpu), availableFilters(hasVulkan = false))
    }
}
