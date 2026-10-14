package io.github.hokanosekai.tensai.ui.screens.models

import io.github.hokanosekai.tensai.download.DownloadError
import io.github.hokanosekai.tensai.download.DownloadItem
import io.github.hokanosekai.tensai.download.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Download rows to the cards of "On device" (TEN-142): which rows, which state, what each carries. */
class DownloadCardsTest {

    private fun item(
        id: String,
        status: DownloadStatus,
        done: Long = 0,
        size: Long = 1000,
        error: DownloadError? = null,
        repo: String = "org/repo",
    ) = DownloadItem(
        id = id, repo = repo, fileName = "$id.gguf", quant = "Q4_K_M", displayName = "Model $id", sizeBytes = size,
        sha256 = null, status = status, bytesDone = done, error = error, modelId = null,
    )

    @Test
    fun finishedRowsAreNotCards() {
        val cards = DownloadCards.from(
            listOf(item("a", DownloadStatus.Done, done = 1000), item("b", DownloadStatus.Queued)),
            bytesPerSecond = 0,
        )

        assertEquals(listOf("b"), cards.map { it.id })
    }

    @Test
    fun nothingButFinishedRowsMeansNoCard() {
        assertTrue(DownloadCards.from(listOf(item("a", DownloadStatus.Done)), 0).isEmpty())
        assertTrue(DownloadCards.from(emptyList(), 0).isEmpty())
    }

    @Test
    fun aCardCarriesTheRowsOwnFacts() {
        val card = DownloadCards.from(listOf(item("a", DownloadStatus.Queued, repo = "bartowski/Qwen3-GGUF")), 0).single()

        assertEquals("a", card.id)
        assertEquals("bartowski/Qwen3-GGUF", card.repo)
        assertEquals("a.gguf", card.fileName)
        assertEquals("Model a", card.name)
        assertEquals("Q4_K_M", card.quant)
        assertEquals(1000L, card.sizeBytes)
    }

    @Test
    fun downloadingCarriesBytesSpeedAndTimeLeft() {
        val card = DownloadCards.from(listOf(item("a", DownloadStatus.Downloading, done = 400)), bytesPerSecond = 100).single()

        assertEquals(DownloadCardState.Downloading(400, 1000, 100, etaSeconds = 6), card.state)
    }

    @Test
    fun theTimeLeftRoundsUpAndIsUnknownWithoutASpeed() {
        val rounding = DownloadCards.from(listOf(item("a", DownloadStatus.Downloading, done = 400)), bytesPerSecond = 70).single()
        val unknown = DownloadCards.from(listOf(item("a", DownloadStatus.Downloading, done = 400)), bytesPerSecond = 0).single()

        assertEquals(9L, (rounding.state as DownloadCardState.Downloading).etaSeconds)
        assertNull((unknown.state as DownloadCardState.Downloading).etaSeconds)
    }

    @Test
    fun pausedKeepsItsBytesAndNoSpeed() {
        val card = DownloadCards.from(listOf(item("a", DownloadStatus.Paused, done = 250)), bytesPerSecond = 100).single()

        assertEquals(DownloadCardState.Paused(250, 1000), card.state)
    }

    @Test
    fun queuedCountsWhatRunsOrWaitsBeforeItAndNotPausedOnes() {
        val cards = DownloadCards.from(
            listOf(
                item("a", DownloadStatus.Downloading, done = 10),
                item("b", DownloadStatus.Paused, done = 10),
                item("c", DownloadStatus.Queued),
                item("d", DownloadStatus.Queued),
            ),
            bytesPerSecond = 0,
        )

        assertEquals(listOf("a", "b", "c", "d"), cards.map { it.id })
        assertEquals(DownloadCardState.Queued(ahead = 1), cards[2].state)
        assertEquals(DownloadCardState.Queued(ahead = 2), cards[3].state)
    }

    @Test
    fun aFinishedRowAheadDoesNotCountAsWaiting() {
        val cards = DownloadCards.from(listOf(item("a", DownloadStatus.Done), item("b", DownloadStatus.Queued)), 0)

        assertEquals(DownloadCardState.Queued(ahead = 0), cards.single().state)
    }

    @Test
    fun failedKeepsTheReasonAndRetryIsThePrimaryButtonForNetworkTrouble() {
        val card = DownloadCards.from(listOf(item("a", DownloadStatus.Failed, error = DownloadError.Network)), 0).single()

        assertEquals(DownloadCardState.Failed(DownloadError.Network, FailedPrimary.Retry), card.state)
    }

    @Test
    fun accessErrorsAndAnAlreadyImportedFileSendTheUserToTheRepoScreenInsteadOfRetrying() {
        listOf(
            DownloadError.Gated,
            DownloadError.LicenseNotAccepted,
            DownloadError.InvalidToken,
            DownloadError.AlreadyImported,
        ).forEach { assertEquals(it.code, FailedPrimary.OpenRepo, DownloadCards.failedPrimary(it)) }
        listOf(
            DownloadError.Network,
            DownloadError.DiskFull,
            DownloadError.ChecksumMismatch,
            DownloadError.Unknown,
            DownloadError.Http(503),
            null,
        ).forEach { assertEquals(it?.code, FailedPrimary.Retry, DownloadCards.failedPrimary(it)) }
    }
}
