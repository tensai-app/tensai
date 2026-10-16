package io.github.hokanosekai.tensai.ui.screens.models

import io.github.hokanosekai.tensai.compat.DeviceProfile
import io.github.hokanosekai.tensai.compat.Verdict
import io.github.hokanosekai.tensai.data.models.ModelRepository
import io.github.hokanosekai.tensai.download.DownloadItem
import io.github.hokanosekai.tensai.download.DownloadManager
import io.github.hokanosekai.tensai.download.DownloadStatus
import io.github.hokanosekai.tensai.download.EnqueueResult
import io.github.hokanosekai.tensai.hf.HfCompatibility
import io.github.hokanosekai.tensai.hf.HfError
import io.github.hokanosekai.tensai.hf.HfException
import io.github.hokanosekai.tensai.hf.HfQuantFile
import io.github.hokanosekai.tensai.hf.HfRepoDetail
import io.github.hokanosekai.tensai.hf.QuantLabel
import io.github.hokanosekai.tensai.hf.RatedQuant
import io.github.hokanosekai.tensai.runtime.RuntimeGraph
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the repo screen needs of [DownloadManager], so a test can stand in for it. */
internal interface RepoDownloads {
    /** Every download, oldest first. */
    val items: Flow<List<DownloadItem>>

    suspend fun enqueue(repo: String, quant: HfQuantFile, displayName: String, confirmMobileData: Boolean, commitSha: String?): EnqueueResult
    suspend fun pause(id: String)
    suspend fun resume(id: String, confirmMobileData: Boolean): EnqueueResult
    suspend fun retry(id: String, confirmMobileData: Boolean): EnqueueResult
    suspend fun cancel(id: String)
}

internal class ManagerDownloads(private val manager: DownloadManager) : RepoDownloads {
    override val items: Flow<List<DownloadItem>> get() = manager.downloads

    override suspend fun enqueue(repo: String, quant: HfQuantFile, displayName: String, confirmMobileData: Boolean, commitSha: String?) =
        manager.enqueue(repo, quant, displayName, confirmMobileData, commitSha)

    override suspend fun pause(id: String) = manager.pause(id)
    override suspend fun resume(id: String, confirmMobileData: Boolean) = manager.resume(id, confirmMobileData)
    override suspend fun retry(id: String, confirmMobileData: Boolean) = manager.retry(id, confirmMobileData)
    override suspend fun cancel(id: String) = manager.cancel(id)
}

/** The library's files that are really on disk, re-read whenever the library changes. */
internal fun libraryFiles(repository: ModelRepository): Flow<List<LibraryFile>> =
    repository.models.map { models ->
        withContext(Dispatchers.IO) {
            models.filter { File(it.filePath).exists() }.map { LibraryFile(it.id, it.fileName, it.sizeBytes) }
        }
    }

/** How the repo loaded. [Failed] carries the Hub's reason, [Loaded] the rated quants smallest first. */
internal sealed interface RepoLoad {
    data object Loading : RepoLoad
    data class Failed(val error: HfError) : RepoLoad

    /** @param recommendedFile the quant tagged "Recommended": only when it really runs well, null otherwise. */
    data class Loaded(val detail: HfRepoDetail, val rated: List<RatedQuant>, val recommendedFile: String?) : RepoLoad
}

internal sealed interface ReadmeState {
    data object Loading : ReadmeState

    /** No README, or it could not be fetched: the section is left out. */
    data object Unavailable : ReadmeState
    data class Loaded(val text: String) : ReadmeState
}

/** What a download tap is, so the same one can be played again once the user confirmed. */
internal sealed interface RepoOp {
    /** [fileName] is the file the tap was on: an answer that comes back after the selection moved still belongs to it. */
    data class Download(val fileName: String) : RepoOp
    data class Resume(val id: String) : RepoOp
    data class Retry(val id: String) : RepoOp
}

internal sealed interface RepoDialog {
    /** "Download <size> on mobile data?" */
    data class MobileData(val sizeBytes: Long, val op: RepoOp) : RepoDialog

    /** "Not enough storage": [neededBytes] includes the safety margin and the queue's debt. */
    data class Storage(val neededBytes: Long, val freeBytes: Long) : RepoDialog
}

/** One row of "Choose a file". */
internal data class QuantRow(
    val quant: HfQuantFile,
    val label: String,
    val verdict: Verdict,
    val selected: Boolean,
    val recommended: Boolean,
    val onDevice: Boolean,
    val veryLowQuality: Boolean = false,
)

/**
 * @param selected file name (repo path) of the chosen quant.
 * @param knownOnDevice file name to model id, from an enqueue that answered "already on device": it
 *   switches the button at once, before the library flow has said so.
 * @param queue every download of the app, oldest first.
 * @param notAllowed the download manager refused the file (not a huggingface.co file): nothing was started.
 * @param requestedUnsupported the pasted link pointed at a file llama.cpp can no longer load: it is not listed.
 */
internal data class RepoState(
    val load: RepoLoad = RepoLoad.Loading,
    val selected: String? = null,
    val library: List<LibraryFile> = emptyList(),
    val knownOnDevice: Map<String, String> = emptyMap(),
    val queue: List<DownloadItem> = emptyList(),
    val bytesPerSecond: Long = 0,
    val hasToken: Boolean = false,
    val dialog: RepoDialog? = null,
    val notAllowed: Boolean = false,
    val requestedUnsupported: Boolean = false,
    val readme: ReadmeState = ReadmeState.Loading,
    val readmeExpanded: Boolean = false,
) {
    val loaded: RepoLoad.Loaded? get() = load as? RepoLoad.Loaded

    val selectedQuant: RatedQuant? get() = loaded?.rated?.firstOrNull { it.quant.fileName == selected }

    private fun onDeviceId(quant: HfQuantFile): String? =
        knownOnDevice[quant.fileName] ?: RepoLogic.onDeviceModelId(quant, library)

    /** The quants, smallest first, each with its verdict and badges. */
    val rows: List<QuantRow>
        get() {
            val l = loaded ?: return emptyList()
            val all = l.rated.map { it.quant }
            return l.rated.map { rated ->
                QuantRow(
                    quant = rated.quant,
                    label = RepoLogic.rowLabel(rated.quant, all),
                    verdict = rated.compatibility.verdict,
                    selected = rated.quant.fileName == selected,
                    recommended = rated.quant.fileName == l.recommendedFile,
                    onDevice = onDeviceId(rated.quant) != null,
                    veryLowQuality = QuantLabel.isVeryLowQuality(rated.quant.quant),
                )
            }
        }

    /** The pinned button for the selected file. */
    val action: RepoAction
        get() {
            val l = loaded ?: return RepoAction.None
            val quant = selectedQuant?.quant
            val item = quant?.let { RepoLogic.itemFor(l.detail.summary.id, it.fileName, queue) }
            return RepoLogic.action(
                quant = quant,
                gated = l.detail.summary.isGated,
                hasToken = hasToken,
                onDeviceModelId = quant?.let(::onDeviceId),
                item = item,
                ahead = item?.let { RepoLogic.queueAhead(it, queue) } ?: 0,
                bytesPerSecond = bytesPerSecond,
                blocked = selectedQuant?.compatibility,
            )
        }
}

/**
 * State holder of the repo screen (TEN-140), plain class like the other screens' (the project has
 * no androidx ViewModel); driven on its [scope], which is the screen's.
 *
 * Network: one [detail] (a cache hit when the Discover card was already shown), one [readme]. The
 * download button follows the [downloads] queue for the selected file and the library [library] for
 * "On device"; taps go to [downloads] and their answers are handled in [handle].
 *
 * @param repoId `author/name` as navigated to; [preselectFile] the file a pasted link pointed at, or null.
 * @param hasToken whether a Hugging Face token is set (TEN-144), followed live so a token added in
 *   Settings turns the "token needed" card of a gated repo into a Download button on return.
 */
internal class RepoViewModel(
    private val scope: CoroutineScope,
    private val repoId: String,
    private val preselectFile: String? = null,
    private val detail: suspend (String) -> HfRepoDetail = { RuntimeGraph.hfClient.detail(it) },
    private val readme: suspend (String) -> String = { RuntimeGraph.hfClient.readme(it) },
    private val profile: () -> DeviceProfile = { RuntimeGraph.deviceProfiler.profile() },
    private val downloads: RepoDownloads = ManagerDownloads(RuntimeGraph.downloadManager),
    private val library: Flow<List<LibraryFile>> = libraryFiles(RuntimeGraph.modelRepository),
    private val setAllowMobileData: suspend (Boolean) -> Unit = { RuntimeGraph.downloadPreferences.setAllowMobileData(it) },
    private val hasToken: Flow<Boolean> = RuntimeGraph.tokenStore.hasToken,
    private val now: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state = MutableStateFlow(
        RepoState(requestedUnsupported = preselectFile?.let(QuantLabel::isUnsupportedFile) == true),
    )
    val state: StateFlow<RepoState> = _state.asStateFlow()

    private val meter = SpeedMeter()
    private var started = false
    private var loadJob: Job? = null

    /** First call loads the repo and starts following the queue and the library; later ones do nothing. */
    fun start() {
        if (started) return
        started = true
        scope.launch { hasToken.collect { has -> _state.update { it.copy(hasToken = has) } } }
        scope.launch {
            downloads.items.collect { items ->
                val running = items.firstOrNull { it.status == DownloadStatus.Downloading }
                val speed = meter.sample(running?.id, running?.bytesDone ?: 0, now())
                _state.update { it.copy(queue = items, bytesPerSecond = speed) }
            }
        }
        scope.launch { library.collect { files -> _state.update { it.copy(library = files) } } }
        load()
    }

    /** First load, and "Try again". */
    fun load() {
        loadJob?.cancel()
        _state.update { it.copy(load = RepoLoad.Loading, readme = ReadmeState.Loading) }
        loadJob = scope.launch {
            val loaded = try {
                val repo = detail(repoId)
                withContext(dispatcher) {
                    val device = safeProfile(profile)
                    val rated = HfCompatibility.rate(repo, device).sortedBy { it.quant.sizeBytes }
                    val recommendation = HfCompatibility.recommendation(repo, device)
                    Triple(repo, rated, recommendation)
                }
            } catch (e: HfException) {
                _state.update { it.copy(load = RepoLoad.Failed(e.error)) }
                return@launch
            }
            val (repo, rated, recommendation) = loaded
            _state.update {
                it.copy(
                    load = RepoLoad.Loaded(repo, rated, recommendation?.takeIf { r -> r.isRecommended }?.quant?.fileName),
                    selected = it.selected?.takeIf { s -> rated.any { r -> r.quant.fileName == s } }
                        ?: RepoLogic.preselect(rated, recommendation, preselectFile),
                )
            }
            val text = try {
                val raw = readme(repoId)
                // Off the main thread, and never fatal: the raw text, cut, is the fallback.
                withContext(dispatcher) {
                    try {
                        RepoLogic.cleanReadme(raw)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        raw.take(RepoLogic.README_MAX_CHARS)
                    }
                }.takeIf { it.isNotBlank() }
            } catch (e: HfException) {
                null
            }
            _state.update { it.copy(readme = if (text != null) ReadmeState.Loaded(text) else ReadmeState.Unavailable) }
        }
    }

    fun select(fileName: String) = _state.update { it.copy(selected = fileName, notAllowed = false) }

    fun toggleReadme() = _state.update { it.copy(readmeExpanded = !it.readmeExpanded) }

    /** The Download button. */
    fun download() {
        val file = _state.value.selectedQuant?.quant?.fileName ?: return
        run(RepoOp.Download(file), confirmMobileData = false)
    }

    fun resume(id: String) = run(RepoOp.Resume(id), confirmMobileData = false)

    fun retry(id: String) = run(RepoOp.Retry(id), confirmMobileData = false)

    fun pause(id: String) {
        scope.launch { downloads.pause(id) }
    }

    fun cancel(id: String) {
        scope.launch { downloads.cancel(id) }
    }

    /**
     * "Download" in the mobile data dialog. [always] is its "Always allow" box: it sets the Settings
     * preference, then the download starts with the confirmation.
     */
    fun confirmMobileData(always: Boolean) {
        val dialog = _state.value.dialog as? RepoDialog.MobileData ?: return
        _state.update { it.copy(dialog = null) }
        scope.launch {
            if (always) setAllowMobileData(true)
            perform(dialog.op, confirmMobileData = true)
        }
    }

    fun dismissDialog() = _state.update { it.copy(dialog = null) }

    private fun run(op: RepoOp, confirmMobileData: Boolean) {
        _state.update { it.copy(notAllowed = false) }
        scope.launch { perform(op, confirmMobileData) }
    }

    private suspend fun perform(op: RepoOp, confirmMobileData: Boolean) {
        val s = _state.value
        val result = when (op) {
            is RepoOp.Download -> {
                val loaded = s.loaded ?: return
                val repo = loaded.detail
                val quant = loaded.rated.firstOrNull { it.quant.fileName == op.fileName }?.quant ?: return
                downloads.enqueue(
                    repo = repo.summary.id,
                    quant = quant,
                    displayName = DiscoverLogic.displayName(repo.summary.name),
                    confirmMobileData = confirmMobileData,
                    commitSha = repo.sha,
                )
            }
            is RepoOp.Resume -> downloads.resume(op.id, confirmMobileData)
            is RepoOp.Retry -> downloads.retry(op.id, confirmMobileData)
        }
        handle(op, result)
    }

    /** What each answer of the download manager does to the screen. */
    private fun handle(op: RepoOp, result: EnqueueResult) {
        _state.update { s ->
            when (result) {
                // The queue flow shows the new item.
                is EnqueueResult.Enqueued, is EnqueueResult.AlreadyQueued -> s
                is EnqueueResult.AlreadyOnDevice -> {
                    if (op is RepoOp.Download) s.copy(knownOnDevice = s.knownOnDevice + (op.fileName to result.modelId)) else s
                }
                is EnqueueResult.NeedsMobileDataConfirmation -> s.copy(dialog = RepoDialog.MobileData(result.sizeBytes, op))
                is EnqueueResult.NotEnoughStorage -> s.copy(dialog = RepoDialog.Storage(result.neededBytes, result.freeBytes))
                EnqueueResult.NotAllowed -> s.copy(notAllowed = true)
                // Resume/retry on a row that is gone or moved on: the queue flow already shows the truth.
                EnqueueResult.NotFound -> s
            }
        }
    }
}
