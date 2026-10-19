package io.github.tensaiapp.ui.screens.models

import io.github.tensaiapp.download.DownloadItem
import io.github.tensaiapp.download.DownloadStatus
import io.github.tensaiapp.download.EnqueueResult
import io.github.tensaiapp.runtime.RuntimeGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A tap on a download card that may need the user's answer before it can run, replayed once they gave it. */
internal sealed interface DownloadOp {
    data class Resume(val id: String) : DownloadOp
    data class Retry(val id: String) : DownloadOp

    /** "Download again" on the detail screen of a model whose file is missing. */
    data class Again(val request: DownloadAgainRequest) : DownloadOp
}

internal sealed interface DownloadsDialog {
    /** "Download <size> on mobile data?" */
    data class MobileData(val sizeBytes: Long, val op: DownloadOp) : DownloadsDialog

    /** "Not enough storage": [neededBytes] includes the safety margin and the queue's debt. */
    data class Storage(val neededBytes: Long, val freeBytes: Long) : DownloadsDialog
}

/**
 * @param queue every download of the app, oldest first, finished ones included (they are not cards).
 * @param bytesPerSecond smoothed speed of the running download, 0 until it has two readings.
 * @param notAllowed the manager refused the file (not a huggingface.co file): nothing was started.
 */
internal data class DownloadsState(
    val queue: List<DownloadItem> = emptyList(),
    val bytesPerSecond: Long = 0,
    val dialog: DownloadsDialog? = null,
    val notAllowed: Boolean = false,
) {
    /** The cards "On device" lists above the installed models. */
    val cards: List<DownloadCardModel> get() = DownloadCards.from(queue, bytesPerSecond)
}

/**
 * Download controls of the Models library (TEN-142): follows the queue for the cards and answers
 * the card buttons. Plain class like [RepoViewModel] (no androidx ViewModel in this project), driven
 * on the screen's [scope]. Pause and Cancel go straight to the manager; Resume and Retry can be
 * refused (mobile data not confirmed, not enough room) and then raise [DownloadsState.dialog].
 */
internal class ModelDownloads(
    private val scope: CoroutineScope,
    private val downloads: RepoDownloads = ManagerDownloads(RuntimeGraph.downloadManager),
    private val setAllowMobileData: suspend (Boolean) -> Unit = { RuntimeGraph.downloadPreferences.setAllowMobileData(it) },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(DownloadsState())
    val state: StateFlow<DownloadsState> = _state.asStateFlow()

    private val meter = SpeedMeter()
    private var started = false

    /** Starts following the queue; later calls do nothing. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            downloads.items.collect { items ->
                val running = items.firstOrNull { it.status == DownloadStatus.Downloading }
                val speed = meter.sample(running?.id, running?.bytesDone ?: 0, now())
                _state.update { it.copy(queue = items, bytesPerSecond = speed) }
            }
        }
    }

    fun pause(id: String) {
        scope.launch { downloads.pause(id) }
    }

    fun cancel(id: String) {
        scope.launch { downloads.cancel(id) }
    }

    fun resume(id: String) = run(DownloadOp.Resume(id), confirmMobileData = false)

    fun retry(id: String) = run(DownloadOp.Retry(id), confirmMobileData = false)

    /** "Download again": enqueues [request], answers are handled like the repo screen's Download button. */
    fun again(request: DownloadAgainRequest) = run(DownloadOp.Again(request), confirmMobileData = false)

    /** "Download" in the mobile data dialog; [always] is its "Always allow" box (sets the Settings preference). */
    fun confirmMobileData(always: Boolean) {
        val dialog = _state.value.dialog as? DownloadsDialog.MobileData ?: return
        _state.update { it.copy(dialog = null) }
        scope.launch {
            if (always) setAllowMobileData(true)
            perform(dialog.op, confirmMobileData = true)
        }
    }

    fun dismissDialog() = _state.update { it.copy(dialog = null) }

    private fun run(op: DownloadOp, confirmMobileData: Boolean) {
        _state.update { it.copy(notAllowed = false) }
        scope.launch { perform(op, confirmMobileData) }
    }

    private suspend fun perform(op: DownloadOp, confirmMobileData: Boolean) {
        val result = when (op) {
            is DownloadOp.Resume -> downloads.resume(op.id, confirmMobileData)
            is DownloadOp.Retry -> downloads.retry(op.id, confirmMobileData)
            is DownloadOp.Again -> downloads.enqueue(
                repo = op.request.repo,
                quant = op.request.quant,
                displayName = op.request.displayName,
                confirmMobileData = confirmMobileData,
                commitSha = op.request.commitSha,
            )
        }
        handle(op, result)
    }

    /** What each answer of the download manager does to the screen. */
    private fun handle(op: DownloadOp, result: EnqueueResult) {
        _state.update { s ->
            when (result) {
                is EnqueueResult.NeedsMobileDataConfirmation -> s.copy(dialog = DownloadsDialog.MobileData(result.sizeBytes, op))
                is EnqueueResult.NotEnoughStorage -> s.copy(dialog = DownloadsDialog.Storage(result.neededBytes, result.freeBytes))
                EnqueueResult.NotAllowed -> s.copy(notAllowed = true)
                // The queue flow shows everything else: a new state, a row that is gone, or the file already queued.
                // AlreadyOnDevice needs nothing here: the library flow turns the screen to the file that is there.
                else -> s
            }
        }
    }
}
