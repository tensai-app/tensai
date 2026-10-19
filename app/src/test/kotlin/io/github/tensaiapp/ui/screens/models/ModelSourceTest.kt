package io.github.tensaiapp.ui.screens.models

import io.github.tensaiapp.data.models.ModelEntity
import io.github.tensaiapp.data.models.ModelOrigin
import io.github.tensaiapp.hf.HfQuantFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The "Source" row and the "Download again" request of the model detail screen (TEN-143). */
class ModelSourceTest {

    private val commit = "c".repeat(40)

    private val origin = ModelOrigin(
        repo = "bartowski/Qwen3-GGUF",
        file = "sub/Qwen3-Q4_K_M.gguf",
        quant = "Q4_K_M",
        sha256 = "ab".repeat(32),
        commit = commit,
        downloadedAt = 9,
    )

    private fun model(origin: ModelOrigin? = null) = ModelEntity(
        id = "m1", displayName = "Qwen3", fileName = "Qwen3-Q4_K_M.gguf", filePath = "/x/Qwen3-Q4_K_M.gguf",
        sizeBytes = 1_234_567, architecture = "qwen3", quantLabel = "Q4_K_M", paramCount = 1, contextLength = 1,
        embeddingLength = 1, blockCount = 1, supportsThinking = false, importedAt = 1,
    ).withOrigin(origin)

    @Test
    fun theLabelIsHfCoRepoDotQuant() {
        assertEquals("hf.co/bartowski/Qwen3-GGUF · Q4_K_M", ModelSource.label(origin))
    }

    @Test
    fun theLabelDropsTheQuantWhenThereIsNone() {
        assertEquals("hf.co/bartowski/Qwen3-GGUF", ModelSource.label(origin.copy(quant = "")))
    }

    @Test
    fun aDownloadedModelCanBeDownloadedAgainFromTheSameRepoFileAndCommit() {
        val request = ModelSource.again(model(origin))!!

        assertEquals("bartowski/Qwen3-GGUF", request.repo)
        assertEquals(
            HfQuantFile("sub/Qwen3-Q4_K_M.gguf", "Q4_K_M", sizeBytes = 1_234_567, sha256 = "ab".repeat(32)),
            request.quant,
        )
        assertEquals(commit, request.commitSha)
        assertEquals("Qwen3", request.displayName)
    }

    @Test
    fun anOriginWithoutCommitOrHashDownloadsFromMainWithoutAHashCheck() {
        val request = ModelSource.again(model(origin.copy(commit = null, sha256 = null)))!!

        assertNull(request.commitSha)
        assertNull(request.quant.sha256)
    }

    @Test
    fun aBlankQuantFallsBackToTheModelsOwnLabel() {
        assertEquals("Q4_K_M", ModelSource.again(model(origin.copy(quant = "")))!!.quant.quant)
    }

    @Test
    fun anImportedModelHasNothingToDownloadAgain() {
        assertNull(ModelSource.again(model()))
    }

    private fun card(id: String, repo: String, file: String) = DownloadCardModel(
        id = id, repo = repo, fileName = file, name = "n", quant = "Q4_K_M", sizeBytes = 1,
        state = DownloadCardState.Queued(0),
    )

    @Test
    fun theActiveDownloadIsTheCardOfTheSameRepoAndFile() {
        val cards = listOf(
            card("a", "bartowski/Qwen3-GGUF", "other.gguf"),
            card("b", "someone/else", "sub/Qwen3-Q4_K_M.gguf"),
            card("c", "Bartowski/qwen3-gguf", "sub/Qwen3-Q4_K_M.gguf"),
        )

        assertEquals("c", ModelSource.activeDownload(origin, cards)?.id)
    }

    @Test
    fun noMatchingCardMeansNoActiveDownload() {
        assertNull(ModelSource.activeDownload(origin, listOf(card("a", "bartowski/Qwen3-GGUF", "other.gguf"))))
        assertNull(ModelSource.activeDownload(origin, emptyList()))
    }
}
