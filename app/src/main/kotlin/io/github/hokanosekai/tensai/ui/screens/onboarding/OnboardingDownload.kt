package io.github.hokanosekai.tensai.ui.screens.onboarding

import io.github.hokanosekai.tensai.download.DownloadItem
import io.github.hokanosekai.tensai.download.DownloadStatus
import io.github.hokanosekai.tensai.download.EnqueueResult
import io.github.hokanosekai.tensai.hf.HfError
import io.github.hokanosekai.tensai.hf.HfException
import io.github.hokanosekai.tensai.hf.HfQuantFile
import io.github.hokanosekai.tensai.hf.HfRepoDetail
import io.github.hokanosekai.tensai.runtime.RuntimeGraph
import io.github.hokanosekai.tensai.ui.screens.models.DownloadCardState
import io.github.hokanosekai.tensai.ui.screens.models.DownloadCards
import io.github.hokanosekai.tensai.ui.screens.models.ManagerDownloads
import io.github.hokanosekai.tensai.ui.screens.models.RepoDownloads
import io.github.hokanosekai.tensai.ui.screens.models.SpeedMeter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Stable id of an onboarding catalog entry, what the screen saves across process death. */
internal val CatalogModel.key: String get() = "$hfPath::$fileName"

/** Why the repo lookup that precedes a download did not give a file to enqueue. */
internal sealed interface OnboardingLookup {
    data object None : OnboardingLookup
    data object Resolving : OnboardingLookup

    /** No connection: the message says so, and Import stays available. */
    data object Offline : OnboardingLookup

    /** Any other reason (Hub error, the file is not in the repo any more). */
    data object Failed : OnboardingLookup
}

/** What the model card of the selected entry shows. */
internal sealed interface OnboardingPhase {
    /** Nothing started (or no entry selected). */
    data object Idle : OnboardingPhase

    /** The repo detail is being fetched. */
    data object Resolving : OnboardingPhase

    /** A queue row exists: queued, running, paused or failed ([card] says which). */
    data class Active(val id: String, val card: DownloadCardState) : OnboardingPhase

    /** The file is in the library as [modelId]. */
    data class Done(val modelId: String) : OnboardingPhase
}

/** A tap that may need the user's answer before it can run, replayed once they gave it. */
internal sealed interface OnboardingOp {
    val targetKey: String

    data class Download(
        override val targetKey: String,
        val repo: String,
        val quant: HfQuantFile,
        val displayName: String,
        val commitSha: String?,
    ) : OnboardingOp

    data class Resume(override val targetKey: String, val id: String) : OnboardingOp
    data class Retry(override val targetKey: String, val id: String) : OnboardingOp
}

internal sealed interface OnboardingDialog {
    data class MobileData(val sizeBytes: Long, val op: OnboardingOp) : OnboardingDialog
    data class Storage(val neededBytes: Long, val freeBytes: Long) : OnboardingDialog
}

internal data class OnboardingDownloadState(
    val target: CatalogModel? = null,
    val queue: List<DownloadItem> = emptyList(),
    val libraryIds: Set<String> = emptySet(),
    val bytesPerSecond: Long = 0,
    val lookup: OnboardingLookup = OnboardingLookup.None,
    /** Model id answered by an enqueue that found the file already on the device. */
    val knownModelId: String? = null,
    val dialog: OnboardingDialog? = null,
    val notAllowed: Boolean = false,
) {
    val phase: OnboardingPhase
        get() = OnboardingDownloadLogic.phase(target, queue, libraryIds, knownModelId, lookup, bytesPerSecond)
}

internal object OnboardingDownloadLogic {

    /** The download row of [target]: the live one, else the finished one (a done row stays until the next app start). */
    fun rowFor(target: CatalogModel, queue: List<DownloadItem>): DownloadItem? {
        val rows = queue.filter { it.repo.equals(target.hfPath, ignoreCase = true) && it.fileName == target.fileName }
        return rows.lastOrNull { it.status != DownloadStatus.Done } ?: rows.lastOrNull()
    }

    /**
     * What [target]'s card shows. A done row only counts once the model it produced is in the library
     * ([libraryIds]); until then the card stays on a full bar, so Continue never opens on a model the
     * Ready step cannot find.
     */
    fun phase(
        target: CatalogModel?,
        queue: List<DownloadItem>,
        libraryIds: Set<String>,
        knownModelId: String?,
        lookup: OnboardingLookup,
        bytesPerSecond: Long,
    ): OnboardingPhase {
        if (target == null) return OnboardingPhase.Idle
        if (knownModelId != null && knownModelId in libraryIds) return OnboardingPhase.Done(knownModelId)
        val row = rowFor(target, queue)
        if (row != null) {
            if (row.status == DownloadStatus.Done) {
                val id = row.modelId
                if (id != null && id in libraryIds) return OnboardingPhase.Done(id)
                return OnboardingPhase.Active(row.id, DownloadCardState.Downloading(row.sizeBytes, row.sizeBytes, 0, null))
            }
            val card = DownloadCards.from(queue, bytesPerSecond).firstOrNull { it.id == row.id }?.state
            if (card != null) return OnboardingPhase.Active(row.id, card)
        }
        return if (lookup == OnboardingLookup.Resolving) OnboardingPhase.Resolving else OnboardingPhase.Idle
    }
}

/**
 * Download controls of onboarding step 2 (TEN-145): resolves the catalog entry's repo (commit sha,
 * the file's real size and sha256), enqueues it through [downloads] and follows its row. Plain class
 * like [io.github.hokanosekai.tensai.ui.screens.models.ModelDownloads], driven on the screen's [scope].
 *
 * One download at a time: [download] on another entry cancels the previous entry's row. The selection
 * is whatever [follow] was given, so a screen restored after process death picks its row up again
 * from the queue by repo and file.
 */
internal class OnboardingDownloads(
    private val scope: CoroutineScope,
    private val downloads: RepoDownloads = ManagerDownloads(RuntimeGraph.downloadManager),
    private val detail: suspend (String) -> HfRepoDetail = { RuntimeGraph.hfClient.detail(it) },
    private val libraryIds: Flow<Set<String>> = emptyFlow(),
    private val setAllowMobileData: suspend (Boolean) -> Unit = { RuntimeGraph.downloadPreferences.setAllowMobileData(it) },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(OnboardingDownloadState())
    val state: StateFlow<OnboardingDownloadState> = _state.asStateFlow()

    private val meter = SpeedMeter()
    private var started = false

    /** Starts following the queue and the library; later calls do nothing. */
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
        scope.launch { libraryIds.collect { ids -> _state.update { it.copy(libraryIds = ids) } } }
    }

    /** Selects [target] without starting anything: its row, if the queue has one, shows up. */
    fun follow(target: CatalogModel?) {
        _state.update {
            if (it.target?.key == target?.key) it else it.copy(target = target, lookup = OnboardingLookup.None, knownModelId = null, notAllowed = false)
        }
    }

    /** The Download button of [target] (the recommended card or a row of "other options"). */
    fun download(target: CatalogModel) {
        val s = _state.value
        if (s.lookup == OnboardingLookup.Resolving) return
        _state.update {
            it.copy(target = target, lookup = OnboardingLookup.Resolving, knownModelId = null, notAllowed = false, dialog = null)
        }
        scope.launch {
            // One download at a time: what the previous selection had going is dropped.
            val previous = s.target?.takeIf { it.key != target.key }
            if (previous != null) {
                s.queue
                    .filter { row -> row.status != DownloadStatus.Done && previous.matches(row) }
                    .forEach { row -> downloads.cancel(row.id) }
            }
            val repo = try {
                detail(target.hfPath)
            } catch (e: HfException) {
                settleLookup(target.key, if (e.error == HfError.Offline) OnboardingLookup.Offline else OnboardingLookup.Failed)
                return@launch
            }
            val quant = repo.quants.firstOrNull { it.fileName == target.fileName }
            if (quant == null) {
                settleLookup(target.key, OnboardingLookup.Failed)
                return@launch
            }
            perform(
                OnboardingOp.Download(target.key, target.hfPath, quant, target.name, repo.sha),
                confirmMobileData = false,
            )
        }
    }

    fun pause(id: String) {
        scope.launch { downloads.pause(id) }
    }

    fun cancel(id: String) {
        scope.launch { downloads.cancel(id) }
    }

    fun resume(id: String) = replay(OnboardingOp.Resume(_state.value.target?.key.orEmpty(), id))

    fun retry(id: String) = replay(OnboardingOp.Retry(_state.value.target?.key.orEmpty(), id))

    /** "Try again" after a lookup that failed. */
    fun retryLookup() {
        _state.value.target?.let(::download)
    }

    /** "Download" in the mobile data dialog; [always] is its "Always allow" box. */
    fun confirmMobileData(always: Boolean) {
        val dialog = _state.value.dialog as? OnboardingDialog.MobileData ?: return
        _state.update { it.copy(dialog = null) }
        scope.launch {
            if (always) setAllowMobileData(true)
            perform(dialog.op, confirmMobileData = true)
        }
    }

    fun dismissDialog() = _state.update { it.copy(dialog = null) }

    private fun replay(op: OnboardingOp) {
        _state.update { it.copy(notAllowed = false) }
        scope.launch { perform(op, confirmMobileData = false) }
    }

    private suspend fun perform(op: OnboardingOp, confirmMobileData: Boolean) {
        val result = when (op) {
            is OnboardingOp.Download -> downloads.enqueue(op.repo, op.quant, op.displayName, confirmMobileData, op.commitSha)
            is OnboardingOp.Resume -> downloads.resume(op.id, confirmMobileData)
            is OnboardingOp.Retry -> downloads.retry(op.id, confirmMobileData)
        }
        handle(op, result)
    }

    private fun settleLookup(key: String, lookup: OnboardingLookup) {
        _state.update { if (it.target?.key == key) it.copy(lookup = lookup) else it }
    }

    /** What each answer of the download manager does to the card. */
    private fun handle(op: OnboardingOp, result: EnqueueResult) {
        _state.update { s ->
            // The selection moved while the answer was on its way: it is not this card's any more.
            if (s.target?.key != op.targetKey) return@update s
            val settled = s.copy(lookup = OnboardingLookup.None)
            when (result) {
                // The queue flow shows the row.
                is EnqueueResult.Enqueued, is EnqueueResult.AlreadyQueued -> settled
                is EnqueueResult.AlreadyOnDevice -> settled.copy(knownModelId = result.modelId)
                is EnqueueResult.NeedsMobileDataConfirmation -> settled.copy(dialog = OnboardingDialog.MobileData(result.sizeBytes, op))
                is EnqueueResult.NotEnoughStorage -> settled.copy(dialog = OnboardingDialog.Storage(result.neededBytes, result.freeBytes))
                EnqueueResult.NotAllowed -> settled.copy(notAllowed = true)
                EnqueueResult.NotFound -> settled
            }
        }
    }

    private fun CatalogModel.matches(row: DownloadItem) = row.repo.equals(hfPath, ignoreCase = true) && row.fileName == fileName
}
