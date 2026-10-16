package io.github.hokanosekai.tensai.ui.screens.models

import io.github.hokanosekai.tensai.data.models.ModelEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When the Models screen warns about disk space (TEN-39, state 2).
 *
 * The threshold is relative — free space against the mean size of a model *this* device actually
 * holds — so the interesting cases are all about what counts towards that mean and what happens at
 * its edges. Same injectable-file-access shape as `SettingsStorageTest`, so every
 * disk arrangement below is stated rather than staged.
 */
class StoragePressureTest {

    private val oneGb = 1024L * 1024 * 1024

    @Test
    fun withNothingImportedThereIsNoAverageAndThereforeNoWarning() {
        val state = StoragePressure.state(models = emptyList(), freeBytes = 0, fileSize = { null })

        assertEquals(
            "no model imported means no idea what one costs — an empty phone with no free space " +
                "is the import flow's problem to report, with the real number, at the moment it bites",
            StoragePressureState.Ok,
            state,
        )
    }

    @Test
    fun oneModelIsItsOwnAverage() {
        val state = StoragePressure.state(
            models = listOf(model("a")),
            freeBytes = oneGb,
            fileSize = { 2 * oneGb },
        )

        assertEquals(StoragePressureState.Low(freeBytes = oneGb, averageModelBytes = 2 * oneGb), state)
    }

    @Test
    fun noFreeSpaceAtAllWarns() {
        val state = StoragePressure.state(
            models = listOf(model("a")),
            freeBytes = 0,
            fileSize = { 2 * oneGb },
        )

        assertEquals(StoragePressureState.Low(freeBytes = 0, averageModelBytes = 2 * oneGb), state)
    }

    @Test
    fun exactlyOneModelsWorthOfFreeSpaceIsNotYetAWarning() {
        val state = StoragePressure.state(
            models = listOf(model("a")),
            freeBytes = 2 * oneGb,
            fileSize = { 2 * oneGb },
        )

        assertEquals(
            "the comparison is strict: room for exactly one more model is still room for one more",
            StoragePressureState.Ok,
            state,
        )
        assertEquals(
            "and one byte less is not",
            StoragePressureState.Low(freeBytes = 2 * oneGb - 1, averageModelBytes = 2 * oneGb),
            StoragePressure.state(listOf(model("a")), freeBytes = 2 * oneGb - 1) { 2 * oneGb },
        )
    }

    @Test
    fun theThresholdFollowsTheSizeOfModelsThisUserKeeps() {
        val models = listOf(model("a"), model("b"))
        val free = 2 * oneGb

        assertEquals(
            "2 GB free is plenty next to two 1 GB models",
            StoragePressureState.Ok,
            StoragePressure.state(models, free) { oneGb },
        )
        assertEquals(
            "the same 2 GB is not, next to two 6 GB ones — which is the whole reason this is a " +
                "mean and not a constant",
            StoragePressureState.Low(freeBytes = free, averageModelBytes = 6 * oneGb),
            StoragePressure.state(models, free) { 6 * oneGb },
        )
    }

    @Test
    fun rowsWhoseFileIsGoneDoNotCountTowardsTheAverage() {
        val models = listOf(model("here"), model("gone"))

        val state = StoragePressure.state(models, freeBytes = 3 * oneGb) { path ->
            if (path == "/models/here.gguf") 2 * oneGb else null
        }

        assertEquals(
            "same rule as SettingsStorage and OnboardingGate — a row is not a file. Averaging in " +
                "a model that is not on the phone would raise the bar on a device that just got emptier",
            StoragePressureState.Ok,
            state,
        )
    }

    @Test
    fun aLibraryWhoseFilesHaveAllGoneWarnsAboutNothing() {
        val state = StoragePressure.state(
            models = listOf(model("a"), model("b")),
            freeBytes = 0,
            fileSize = { null },
        )

        assertEquals(StoragePressureState.Ok, state)
    }

    @Test
    fun anEmptyFileIsNotAModelForAveragingPurposes() {
        val models = listOf(model("real"), model("empty"))

        val state = StoragePressure.state(models, freeBytes = oneGb) { path ->
            if (path == "/models/real.gguf") 4 * oneGb else 0
        }

        assertEquals(
            "a zero-byte file halves the mean and would switch the warning off exactly when a " +
                "phone is at its fullest",
            StoragePressureState.Low(freeBytes = oneGb, averageModelBytes = 4 * oneGb),
            state,
        )
    }

    @Test
    fun aNegativeFreeReadingIsClampedRatherThanPrinted() {
        val state = StoragePressure.state(
            models = listOf(model("a")),
            freeBytes = -200_000,
            fileSize = { oneGb },
        )

        assertEquals(
            "StatFs can go under on a volume past its reserve; \"-0.0 GB left\" reads as a bug, " +
                "not as urgency",
            StoragePressureState.Low(freeBytes = 0, averageModelBytes = oneGb),
            state,
        )
    }

    private fun model(name: String) = ModelEntity(
        id = name,
        displayName = name,
        fileName = "$name.gguf",
        filePath = "/models/$name.gguf",
        sizeBytes = 0,
        architecture = "qwen3",
        quantLabel = "Q4_K_M",
        paramCount = 1_700_000_000,
        contextLength = 32_768,
        embeddingLength = 2048,
        blockCount = 28,
        supportsThinking = true,
        importedAt = 0,
        kvFullElementsPerToken = 0,
        kvSwaElementsPerToken = 0,
        slidingWindow = 0,
    )
}
