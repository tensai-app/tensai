package io.github.tensaiapp.download

import io.github.tensaiapp.data.models.ModelEntity
import io.github.tensaiapp.data.models.ModelOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What a finished download row hands the model it becomes (TEN-142), and how the entity reads it back. */
class DownloadOriginTest {

    private fun row(sha256: String? = "ab".repeat(32), commitSha: String? = "c".repeat(40)) = DownloadEntity(
        id = "d1", repo = "bartowski/Qwen3-GGUF", fileName = "sub/Qwen3-Q4_K_M.gguf", quant = "Q4_K_M",
        displayName = "Qwen3", sizeBytes = 100, sha256 = sha256, status = "downloading", bytesDone = 100,
        error = null, queuedAt = 1, modelId = null, commitSha = commitSha,
    )

    private fun model() = ModelEntity(
        id = "m", displayName = "m", fileName = "m.gguf", filePath = "/x", sizeBytes = 1, architecture = "a",
        quantLabel = "Q4_K_M", paramCount = 1, contextLength = 1, embeddingLength = 1, blockCount = 1,
        supportsThinking = false, importedAt = 5,
    )

    @Test
    fun theOriginIsTheRowsRepoFileQuantHashAndCommit() {
        assertEquals(
            ModelOrigin("bartowski/Qwen3-GGUF", "sub/Qwen3-Q4_K_M.gguf", "Q4_K_M", "ab".repeat(32), "c".repeat(40), 99),
            row().toOrigin(downloadedAt = 99),
        )
    }

    @Test
    fun aRowWithoutHashOrCommitGivesAnOriginWithoutThem() {
        val origin = row(sha256 = null, commitSha = null).toOrigin(1)

        assertNull(origin.sha256)
        assertNull(origin.commit)
    }

    @Test
    fun anEntityWithoutARepoOrFileHasNoOrigin() {
        assertNull(model().origin)
        assertNull(model().copy(sourceRepo = "a/b").origin)
        assertNull(model().copy(sourceFile = "f.gguf").origin)
    }

    @Test
    fun withOriginRoundTripsAndNullClears() {
        val origin = row().toOrigin(99)
        val withOrigin = model().withOrigin(origin)

        assertEquals(origin, withOrigin.origin)
        assertNull(withOrigin.withOrigin(null).origin)
        assertNull(withOrigin.withOrigin(null).downloadedAt)
    }
}
