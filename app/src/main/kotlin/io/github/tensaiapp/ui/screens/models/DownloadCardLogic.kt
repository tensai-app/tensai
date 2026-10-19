package io.github.tensaiapp.ui.screens.models

import io.github.tensaiapp.download.DownloadError
import io.github.tensaiapp.download.DownloadItem
import io.github.tensaiapp.download.DownloadStatus

/** What a download card shows and offers, per state of the row (TEN-142). */
internal sealed interface DownloadCardState {
    /** Running: bytes, speed (0 until two readings exist) and time left (null while unknown). */
    data class Downloading(
        val doneBytes: Long,
        val totalBytes: Long,
        val bytesPerSecond: Long,
        val etaSeconds: Long?,
    ) : DownloadCardState

    data class Paused(val doneBytes: Long, val totalBytes: Long) : DownloadCardState

    /** Waiting behind [ahead] downloads (0 when it is the next one). */
    data class Queued(val ahead: Int) : DownloadCardState

    /** [primary] is the main button: Retry, or Open when only the repo screen can fix it. */
    data class Failed(val error: DownloadError?, val primary: FailedPrimary) : DownloadCardState
}

internal enum class FailedPrimary { Retry, OpenRepo }

/** One download as a card of "On device": the row's own facts, nothing guessed about the model. */
internal data class DownloadCardModel(
    val id: String,
    val repo: String,
    val fileName: String,
    val name: String,
    val quant: String,
    val sizeBytes: Long,
    val state: DownloadCardState,
)

internal object DownloadCards {

    /**
     * The cards for [queue] (every row of the download queue, oldest first), in queue order. Finished
     * rows are skipped: the model they produced is a card of its own, the row is not shown twice.
     *
     * [bytesPerSecond] is the speed of the running download; only the row that is
     * [DownloadStatus.Downloading] gets it, and the time left is derived from it.
     */
    fun from(queue: List<DownloadItem>, bytesPerSecond: Long): List<DownloadCardModel> =
        queue.filter { it.status != DownloadStatus.Done }.map { item ->
            DownloadCardModel(
                id = item.id,
                repo = item.repo,
                fileName = item.fileName,
                name = item.displayName,
                quant = item.quant,
                sizeBytes = item.sizeBytes,
                state = stateOf(item, RepoLogic.queueAhead(item, queue), bytesPerSecond),
            )
        }

    private fun stateOf(item: DownloadItem, ahead: Int, bytesPerSecond: Long): DownloadCardState = when (item.status) {
        DownloadStatus.Downloading -> {
            val remaining = (item.sizeBytes - item.bytesDone).coerceAtLeast(0)
            val eta = if (bytesPerSecond > 0) (remaining + bytesPerSecond - 1) / bytesPerSecond else null
            DownloadCardState.Downloading(item.bytesDone, item.sizeBytes, bytesPerSecond, eta)
        }
        DownloadStatus.Paused -> DownloadCardState.Paused(item.bytesDone, item.sizeBytes)
        DownloadStatus.Queued -> DownloadCardState.Queued(ahead)
        DownloadStatus.Failed -> DownloadCardState.Failed(item.error, failedPrimary(item.error))
        // Filtered out by from(); a card cannot be built for it.
        DownloadStatus.Done -> DownloadCardState.Queued(0)
    }

    /**
     * Retry would only fail the same way for access errors (a token or a licence is missing) and for a
     * file that is already in the library: those send the user to the repo screen, which explains it.
     */
    fun failedPrimary(error: DownloadError?): FailedPrimary = when (error) {
        DownloadError.Gated,
        DownloadError.LicenseNotAccepted,
        DownloadError.InvalidToken,
        DownloadError.AlreadyImported,
        -> FailedPrimary.OpenRepo
        else -> FailedPrimary.Retry
    }
}
