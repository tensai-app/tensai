package io.github.hokanosekai.tensai.download

import io.github.hokanosekai.tensai.hf.HfQuantFile
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** What a download needs from the network, decided at enqueue. */
enum class NetworkPolicy {
    /** Wi-Fi or any other unmetered link: the default. */
    Unmetered,

    /** Any connection, mobile data included: the user confirmed it or allowed it in Settings. */
    Connected,
}

/** What [DownloadManager.enqueue], [DownloadManager.resume] and [DownloadManager.retry] answer. */
sealed interface EnqueueResult {
    /** Queued as [id]. */
    data class Enqueued(val id: String) : EnqueueResult

    /** The same file is already queued, running or paused as [id]. */
    data class AlreadyQueued(val id: String) : EnqueueResult

    /** The library already holds this file (same name and size, file on disk) as model [modelId]. */
    data class AlreadyOnDevice(val modelId: String) : EnqueueResult

    /**
     * On a metered network with "allow mobile data" off: nothing started. Ask the user (with
     * [sizeBytes] to show), then call again with `confirmMobileData = true`.
     */
    data class NeedsMobileDataConfirmation(val sizeBytes: Long) : EnqueueResult

    /** Not enough room: [neededBytes] includes the margin and what the queue already owes. */
    data class NotEnoughStorage(val neededBytes: Long, val freeBytes: Long) : EnqueueResult

    /** Not a huggingface.co file (or not a valid repo/file/size): refused, nothing recorded. */
    data object NotAllowed : EnqueueResult

    /** [resume]/[retry] on an id that is gone or in a state that does not allow it. */
    data object NotFound : EnqueueResult
}

/** Pure decision of [DownloadManager.enqueue]'s network rule, so the matrix is testable alone. */
internal fun networkPolicyFor(metered: Boolean, allowMobilePref: Boolean, confirmed: Boolean): NetworkPolicy? = when {
    allowMobilePref || confirmed -> NetworkPolicy.Connected
    metered -> null
    else -> NetworkPolicy.Unmetered
}

/**
 * Keeps the one queue drainer alive. WorkManager in the app, a recorder in tests.
 */
interface DownloadScheduler {
    /** Makes sure a drainer will run soon: starts one now, or leaves a live one alone. */
    fun ensureDrainer()

    /** The drainer stopped with rows still waiting: wake the queue as [DrainResult.Rest] says. */
    fun restDrainer(wakeAfterMs: Long?, wakeOnUnmetered: Boolean)
}

/**
 * The model download queue (TEN-141), the one entry point the UI talks to.
 *
 * Owns the rules that do not need Android: the huggingface.co-only check, dedup, free space, the
 * metered network decision, queue rows and what pause/resume/cancel/retry do to them. The bytes are
 * moved by [DownloadRunner], looped over by [DownloadDrainer] inside a `DownloadWorker`.
 *
 * **The DB is the queue.** Rows are FIFO by `queuedAt`. enqueue/resume/retry write the row and then
 * [ensure][DownloadScheduler.ensureDrainer] a drainer exists; pause and cancel only touch the row
 * (the runner watching it stops, the drainer moves on). There is no per-item work request, so
 * there is nothing to cancel and nothing for a stale request to block.
 *
 * **Atomicity.** The dedup check and the insert of [enqueue] run under one [Mutex], so two taps
 * cannot both pass the check. Room cannot express a partial unique index (`WHERE status IN ...`)
 * through annotations, so the mutex is the guard: it covers every writer because [enqueue] is the
 * only code that inserts rows.
 *
 * **Threads.** Every public suspend function runs its DB, file and StatFs work on [io].
 *
 * @param isMetered is the active network metered (mobile data, hotspot)?
 * @param allowMobileData the Settings preference, default false (ask).
 * @param freeBytes free space on the models volume.
 * @param urlFor `(repo, commitSha or null, fileName)` direct file URL, normally
 *   [io.github.hokanosekai.tensai.hf.HfClient.fileUrl].
 * @param listParts every `*.part` file in the models directory, for [reconcile].
 * @param findInstalled id of the library model with this file name and size whose file is on disk, or null.
 * @param registry tells whether a drain loop is active in this process (see [DrainerRegistry]).
 * @param hostAllowed privacy gate: only huggingface.co. Injected so tests can allow MockWebServer.
 */
class DownloadManager(
    private val dao: DownloadDao,
    private val scheduler: DownloadScheduler,
    private val urlFor: (repo: String, commitSha: String?, fileName: String) -> String,
    private val partFor: (id: String) -> File,
    private val listParts: () -> List<File>,
    private val freeBytes: () -> Long,
    private val isMetered: () -> Boolean,
    private val allowMobileData: suspend () -> Boolean,
    private val findInstalled: suspend (fileName: String, sizeBytes: Long) -> String? = { _, _ -> null },
    private val registry: DrainerRegistry = DrainerRegistry(),
    private val hostAllowed: (HttpUrl) -> Boolean = ::isHuggingFaceUrl,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val marginBytes: Long = STORAGE_MARGIN,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val enqueueLock = Mutex()

    /** Every download, oldest first, finished ones included. */
    val downloads: Flow<List<DownloadItem>> = dao.observeAll().map { rows -> rows.map { it.toItem() } }

    /**
     * @param commitSha the repo commit the listing came from (`HfRepoDetail.sha`): the URL is pinned
     *   to it so the file cannot change between attempts. Anything that is not 40 hex is ignored and
     *   the download falls back to `main`.
     */
    suspend fun enqueue(
        repo: String,
        quantFile: HfQuantFile,
        displayName: String,
        confirmMobileData: Boolean = false,
        commitSha: String? = null,
    ): EnqueueResult = withContext(io) {
        if (quantFile.sizeBytes <= 0) return@withContext EnqueueResult.NotAllowed
        val sha = commitSha?.takeIf { COMMIT_SHA.matches(it) }
        val url = runCatching { urlFor(repo, sha, quantFile.fileName) }.getOrNull()?.toHttpUrlOrNull()
        if (url == null || !hostAllowed(url)) return@withContext EnqueueResult.NotAllowed

        enqueueLock.withLock {
            findInstalled(quantFile.fileName.substringAfterLast('/'), quantFile.sizeBytes)
                ?.let { return@withLock EnqueueResult.AlreadyOnDevice(it) }

            val active = dao.getActive()
            active.firstOrNull { it.repo == repo && it.fileName == quantFile.fileName }
                ?.let { return@withLock EnqueueResult.AlreadyQueued(it.id) }

            // An earlier failed attempt at the same file whose partial is still good (same commit,
            // size and hash, not longer than the file) is resumed as a retry. Anything else of the
            // kind is replaced, not stacked.
            val earlier = dao.getByFile(repo, quantFile.fileName).filter { it.status != DownloadStatus.Done.code }
            val reusable = earlier.firstOrNull { old ->
                val part = partFor(old.id)
                old.status == DownloadStatus.Failed.code && old.commitSha == sha &&
                    old.sizeBytes == quantFile.sizeBytes && old.sha256 == quantFile.sha256 &&
                    part.exists() && part.length() <= old.sizeBytes
            }
            val have = reusable?.let { partFor(it.id).length() } ?: 0L
            checkStorage(quantFile.sizeBytes - have + owed(active) + marginBytes)?.let { return@withLock it }

            val policy = networkPolicyFor(isMetered(), allowMobileData(), confirmMobileData)
                ?: return@withLock EnqueueResult.NeedsMobileDataConfirmation(quantFile.sizeBytes - have)

            earlier.filter { it.id != reusable?.id }.forEach { old ->
                dao.delete(old.id)
                partFor(old.id).delete()
            }
            if (reusable != null) {
                dao.requeue(reusable.id, listOf(DownloadStatus.Failed.code), clock(), policy == NetworkPolicy.Connected)
                scheduler.ensureDrainer()
                return@withLock EnqueueResult.Enqueued(reusable.id)
            }

            val id = newId()
            dao.insert(
                DownloadEntity(
                    id = id,
                    repo = repo,
                    fileName = quantFile.fileName,
                    quant = quantFile.quant,
                    displayName = displayName,
                    sizeBytes = quantFile.sizeBytes,
                    sha256 = quantFile.sha256,
                    status = DownloadStatus.Queued.code,
                    bytesDone = 0,
                    error = null,
                    queuedAt = clock(),
                    modelId = null,
                    commitSha = sha,
                    allowMobile = policy == NetworkPolicy.Connected,
                ),
            )
            scheduler.ensureDrainer()
            EnqueueResult.Enqueued(id)
        }
    }

    /**
     * Stops [id], keeping the partial file. A running download stops within one buffer; a queued
     * one is simply not picked.
     */
    suspend fun pause(id: String) = withContext(io) {
        dao.transition(
            id,
            listOf(DownloadStatus.Queued.code, DownloadStatus.Downloading.code),
            DownloadStatus.Paused.code,
            null,
            null,
        )
        Unit
    }

    /** Puts a paused download back at the end of the queue; it continues from its partial file. */
    suspend fun resume(id: String, confirmMobileData: Boolean = false): EnqueueResult =
        requeue(id, DownloadStatus.Paused, confirmMobileData)

    /** Same for a failed download (disk was full, network gave up). Not for gated/not found: those fail again. */
    suspend fun retry(id: String, confirmMobileData: Boolean = false): EnqueueResult =
        requeue(id, DownloadStatus.Failed, confirmMobileData)

    /** Deletes the partial file and the row, whatever the state. A running download stops. */
    suspend fun cancel(id: String) = withContext(io) {
        // Row first: that is what tells a running runner to stop. It deletes the part again on its
        // way out, in case it wrote after this delete.
        dao.delete(id)
        partFor(id).delete()
        Unit
    }

    /**
     * Brings the queue back to a coherent state, called at app start (and cheap enough to call
     * again). Idempotent.
     * - Rows a dead process left in `downloading` go back to `queued`, unless a drainer is running
     *   here (then they are its own).
     * - `*.part` files that no row references are deleted. The files are listed before the rows are
     *   read: a part always belongs to a row inserted earlier, so a row created in between can only
     *   make the snapshot newer, never leave a live part looking orphaned.
     * - If anything is runnable, a drainer is ensured.
     */
    suspend fun reconcile() = withContext(io) {
        val files = listParts()
        val ids = dao.getAll().map { it.id }.toSet()
        if (!registry.isRunning) dao.resetStuck()
        files.filter { it.name.endsWith(PART_SUFFIX) && it.name.removeSuffix(PART_SUFFIX) !in ids }
            .forEach { it.delete() }
        // Only a row that could run now earns a drainer (and its foreground service). Rows that are
        // merely waiting (backoff, Wi-Fi only on mobile data) get a wake-up instead.
        if (!registry.isRunning) {
            val rows = dao.getRunnable()
            if (DownloadQueue.pick(rows, clock(), isMetered()) != null) {
                scheduler.ensureDrainer()
            } else {
                DownloadQueue.rest(rows, clock(), isMetered())?.let { scheduler.restDrainer(it.wakeAfterMs, it.wakeOnUnmetered) }
            }
        }
    }

    private suspend fun requeue(id: String, expected: DownloadStatus, confirmMobileData: Boolean): EnqueueResult =
        withContext(io) {
            enqueueLock.withLock {
                val row = dao.get(id)?.takeIf { it.status == expected.code } ?: return@withLock EnqueueResult.NotFound
                val url = runCatching { urlFor(row.repo, row.commitSha, row.fileName) }.getOrNull()?.toHttpUrlOrNull()
                if (url == null || !hostAllowed(url)) return@withLock EnqueueResult.NotAllowed

                val part = partFor(id)
                val remaining = row.sizeBytes - (if (part.exists()) part.length() else 0L)
                checkStorage(remaining + owed(dao.getActive().filter { it.id != id }) + marginBytes)
                    ?.let { return@withLock it }

                val policy = networkPolicyFor(isMetered(), allowMobileData(), confirmMobileData)
                    ?: return@withLock EnqueueResult.NeedsMobileDataConfirmation(row.sizeBytes - row.bytesDone)
                val moved = dao.requeue(id, listOf(expected.code), clock(), policy == NetworkPolicy.Connected)
                if (moved == 0) return@withLock EnqueueResult.NotFound
                scheduler.ensureDrainer()
                EnqueueResult.Enqueued(id)
            }
        }

    private fun checkStorage(needed: Long): EnqueueResult.NotEnoughStorage? {
        val free = freeBytes()
        return if (free < needed) EnqueueResult.NotEnoughStorage(neededBytes = needed, freeBytes = free) else null
    }

    /** Bytes the unfinished downloads in [rows] still have to write. */
    private fun owed(rows: List<DownloadEntity>): Long = rows
        .filter { it.status != DownloadStatus.Paused.code }
        .sumOf { (it.sizeBytes - it.bytesDone).coerceAtLeast(0) }

    companion object {
        /** Kept free on top of the file: room for the header parse, the filesystem and the user. */
        const val STORAGE_MARGIN = 200L * 1024 * 1024
        const val PART_SUFFIX = ".part"
        private val COMMIT_SHA = Regex("[0-9a-f]{40}")
    }
}

/** huggingface.co only: the site itself and its subdomains, over https. Redirects are OkHttp's. */
internal fun isHuggingFaceUrl(url: HttpUrl): Boolean =
    url.isHttps && (url.host == "huggingface.co" || url.host.endsWith(".huggingface.co"))
