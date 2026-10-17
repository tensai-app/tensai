package io.github.hokanosekai.tensai.ui.screens.settings

import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.data.session.SessionEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The numbers Settings > Storage puts in front of the user (TEN-38), against every disk
 * arrangement worth naming — including the ones where the tables and the disk disagree, which is
 * the whole reason this is a function of [SettingsStorage.figures]'s `fileSize` and not a `SUM()`
 * over a column.
 *
 * Same injectable-file-access shape, and the same "a row whose file is gone is not a thing" rule,
 * as `OnboardingGateTest`.
 */
class SettingsStorageTest {

    private val volume = 128_000_000_000L

    @Test
    fun anEmptyInstallReportsZeroesRatherThanNothing() {
        val figures = SettingsStorage.figures(
            models = emptyList(),
            sessions = emptyList(),
            volumeTotalBytes = volume,
            freeBytes = volume,
            fileSize = { error("nothing to size") },
        )

        assertEquals(0L, figures.modelBytes)
        assertEquals(0, figures.modelCount)
        assertEquals(0L, figures.sessionBytes)
        assertEquals(0, figures.sessionCount)
    }

    @Test
    fun modelsAreSummedFromTheDiskAndCounted() {
        val figures = SettingsStorage.figures(
            models = listOf(model("a"), model("b")),
            sessions = emptyList(),
            volumeTotalBytes = volume,
            freeBytes = volume,
            fileSize = { path -> if (path == "/models/a.gguf") 1_000L else 2_500L },
        )

        assertEquals(3_500L, figures.modelBytes)
        assertEquals(2, figures.modelCount)
    }

    @Test
    fun sessionsAreSummedAndCountedSeparatelyFromModels() {
        val figures = SettingsStorage.figures(
            models = listOf(model("a")),
            sessions = listOf(session("c1"), session("c2")),
            volumeTotalBytes = volume,
            freeBytes = volume,
            fileSize = { path -> if (path.endsWith(".gguf")) 10L else 200L },
        )

        assertEquals(10L, figures.modelBytes)
        assertEquals(1, figures.modelCount)
        assertEquals(400L, figures.sessionBytes)
        assertEquals(2, figures.sessionCount)
    }

    @Test
    fun aModelsWithNoModelsButSavedSessionsStillReportsTheSessions() {
        val figures = SettingsStorage.figures(
            models = emptyList(),
            sessions = listOf(session("c1")),
            volumeTotalBytes = volume,
            freeBytes = volume,
            fileSize = { 4_096L },
        )

        assertEquals(0, figures.modelCount)
        assertEquals(1, figures.sessionCount)
        assertEquals(4_096L, figures.sessionBytes)
    }

    @Test
    fun aRowWhoseFileIsGoneIsBilledToNobody() {
        val figures = SettingsStorage.figures(
            models = listOf(model("here"), model("gone")),
            sessions = listOf(session("here"), session("gone")),
            volumeTotalBytes = volume,
            freeBytes = volume,
            // Storage wiped from the system settings, or an app-data restore that brought rows
            // back without files — the table still says these exist.
            fileSize = { path -> if (path.contains("gone")) null else 100L },
        )

        assertEquals(
            "a model row without its file is not taking up any space, so it cannot be billed",
            100L,
            figures.modelBytes,
        )
        assertEquals(1, figures.modelCount)
        assertEquals(100L, figures.sessionBytes)
        assertEquals(1, figures.sessionCount)
    }

    @Test
    fun everyFileMissingReadsExactlyLikeAnEmptyInstall() {
        val figures = SettingsStorage.figures(
            models = listOf(model("a"), model("b")),
            sessions = listOf(session("c1")),
            volumeTotalBytes = volume,
            freeBytes = volume,
            fileSize = { null },
        )

        assertEquals(0L, figures.modelBytes)
        assertEquals(0, figures.modelCount)
        assertEquals(0L, figures.sessionBytes)
        assertEquals(0, figures.sessionCount)
    }

    @Test
    fun aZeroByteFileIsCountedButWeighsNothing() {
        val figures = SettingsStorage.figures(
            models = listOf(model("empty")),
            sessions = listOf(session("empty")),
            volumeTotalBytes = volume,
            freeBytes = volume,
            // Distinct from the missing-file case above on purpose: an interrupted import leaves a
            // real, empty file the user can still see listed, and dropping it from the count would
            // make Settings disagree with the Models screen.
            fileSize = { 0L },
        )

        assertEquals(0L, figures.modelBytes)
        assertEquals(1, figures.modelCount)
        assertEquals(0L, figures.sessionBytes)
        assertEquals(1, figures.sessionCount)
    }

    @Test
    fun theRecordedSizeIsIgnoredWhenTheDiskDisagrees() {
        // sizeBytes was written at import time; the file has since been truncated under the app.
        val figures = SettingsStorage.figures(
            models = listOf(model("a", sizeBytes = 4_000_000_000L)),
            sessions = emptyList(),
            volumeTotalBytes = volume,
            freeBytes = volume,
            fileSize = { 12L },
        )

        assertEquals(12L, figures.modelBytes)
    }

    @Test
    fun theVolumeSplitsIntoModelsOtherAndFree() {
        val shares = SettingsStorage.shares(modelBytes = 100L, freeBytes = 300L, totalBytes = 1_000L, lowFree = false)

        assertEquals(0.1f, shares.models, 0.0001f)
        assertEquals(0.6f, shares.other, 0.0001f)
        assertEquals(0.3f, shares.free, 0.0001f)
        assertEquals(false, shares.lowFree)
    }

    @Test
    fun anUnknownVolumeGivesAnEmptyBar() {
        val shares = SettingsStorage.shares(modelBytes = 100L, freeBytes = 300L, totalBytes = 0L, lowFree = true)

        assertEquals(StorageShares(0f, 0f, 0f, lowFree = false), shares)
    }

    @Test
    fun negativeAndOversizedInputsNeverOverflowTheTrack() {
        val negative = SettingsStorage.shares(modelBytes = -5L, freeBytes = -1L, totalBytes = 100L, lowFree = false)
        assertEquals(0f, negative.models, 0f)
        assertEquals(1f, negative.other, 0f)
        assertEquals(0f, negative.free, 0f)

        val over = SettingsStorage.shares(modelBytes = 80L, freeBytes = 80L, totalBytes = 100L, lowFree = false)
        assertEquals(0.8f, over.models, 0.0001f)
        assertEquals(0f, over.other, 0f)
        assertEquals(0.2f, over.free, 0.0001f)
    }

    @Test
    fun freeSpaceUnderTheModelsBannerThresholdIsLow() {
        // One 400-byte model: the Models banner warns below 400 free bytes.
        fun low(free: Long) = SettingsStorage.figures(
            models = listOf(model("a")),
            sessions = emptyList(),
            volumeTotalBytes = 1_000L,
            freeBytes = free,
            fileSize = { 400L },
        )

        assertEquals(true, low(399L).lowSpace)
        assertEquals(true, low(399L).shares.lowFree)
        assertEquals(false, low(400L).lowSpace)
    }

    @Test
    fun anEmptyLibraryOrUnknownVolumeNeverWarns() {
        val empty = SettingsStorage.figures(
            models = emptyList(),
            sessions = emptyList(),
            volumeTotalBytes = 1_000L,
            freeBytes = 0L,
            fileSize = { error("nothing to size") },
        )
        val unknown = SettingsStorage.figures(
            models = listOf(model("a")),
            sessions = emptyList(),
            volumeTotalBytes = 0L,
            freeBytes = 0L,
            fileSize = { 400L },
        )

        assertEquals(false, empty.lowSpace)
        assertEquals(false, unknown.lowSpace)
    }

    private fun model(name: String, sizeBytes: Long = 1_000_000) = ModelEntity(
        id = name,
        displayName = name,
        fileName = "$name.gguf",
        filePath = "/models/$name.gguf",
        sizeBytes = sizeBytes,
        architecture = "qwen2",
        quantLabel = "Q4_K_M",
        paramCount = 1_500_000_000,
        contextLength = 4096,
        embeddingLength = 1536,
        blockCount = 28,
        supportsThinking = false,
        importedAt = 0L,
    )

    private fun session(conversationId: String) = SessionEntity(
        conversationId = conversationId,
        modelId = "model-1",
        filePath = "/sessions/$conversationId.llamastate",
        nCtx = 4096,
        kvCacheType = "F16",
        backend = "CPU",
        tokenCount = 128,
        sizeBytes = 999_999,
        createdAt = 0L,
        lastAccessedAt = 0L,
    )
}
