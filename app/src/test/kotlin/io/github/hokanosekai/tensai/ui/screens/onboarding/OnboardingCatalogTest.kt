package io.github.hokanosekai.tensai.ui.screens.onboarding

import io.github.hokanosekai.tensai.compat.DeviceProfile
import io.github.hokanosekai.tensai.compat.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The onboarding's pick rule against devices of different sizes, with the real catalog. */
class OnboardingCatalogTest {

    @Test
    fun eightGigPhonePicksTheBiggestOneThatRunsWell() {
        val result = OnboardingCatalog.recommend(device(totalGb = 8, availableGb = 4.0))

        // Qwen3 4B is over the ~3.5B-param slowness line (TightFit), so 1.7B is the biggest RunsWell.
        assertEquals("Qwen3 1.7B Instruct", result.recommended.model.name)
        assertEquals(Verdict.RunsWell, result.recommended.compatibility.verdict)
        assertEquals(listOf("Llama 3.2 1B Instruct", "Qwen3 4B Instruct"), result.others.map { it.model.name })
        assertEquals(Verdict.TightFit, result.others.last().compatibility.verdict)
    }

    @Test
    fun freeMemoryAtLaunchDoesNotChangeThePickOrTheVerdicts() {
        val roomy = OnboardingCatalog.recommend(device(totalGb = 8, availableGb = 5.0))
        val busy = OnboardingCatalog.recommend(device(totalGb = 8, availableGb = 0.5))

        assertEquals(roomy, busy)
    }

    @Test
    fun fallsBackToTheSmallestWhenNothingRunsWell() {
        val result = OnboardingCatalog.recommend(device(totalGb = 2, availableGb = 0.5))

        assertEquals("Llama 3.2 1B Instruct", result.recommended.model.name)
        assertTrue(result.recommended.compatibility.verdict != Verdict.RunsWell)
        assertEquals(2, result.others.size)
    }

    @Test
    fun recommendationAndOthersCoverTheWholeCatalogOnce() {
        val result = OnboardingCatalog.recommend(device(totalGb = 12, availableGb = 8.0))

        val names = (listOf(result.recommended) + result.others).map { it.model.name }
        assertEquals(OnboardingCatalog.models.map { it.name }.sorted(), names.sorted())
    }

    @Test
    fun linksPointAtTheHuggingFaceRepos() {
        val model = OnboardingCatalog.models.first { it.name == "Qwen3 1.7B Instruct" }

        assertEquals("hf.co/bartowski/Qwen_Qwen3-1.7B-GGUF", model.linkLabel)
        assertEquals("https://huggingface.co/bartowski/Qwen_Qwen3-1.7B-GGUF", model.linkUrl)
    }

    private fun device(totalGb: Long, availableGb: Double) = DeviceProfile(
        totalRamBytes = totalGb * GB,
        availableRamBytes = (availableGb * GB).toLong(),
        socModel = "test",
        bigCoreCount = 4,
        hasVulkan = true,
        hasOpenCl = false,
    )

    private companion object {
        const val GB = 1024L * 1024 * 1024
    }
}
