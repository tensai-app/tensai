package io.github.tensaiapp.download

import io.github.tensaiapp.data.models.DuplicateModelException
import io.github.tensaiapp.data.models.ModelEntity
import io.github.tensaiapp.data.models.RegisteredModel
import io.github.tensaiapp.data.models.ModelImportException
import io.github.tensaiapp.data.models.ModelMetadataException
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Speed and time left, for the notification (and later the cards). */
data class DownloadProgress(
    val bytesDone: Long,
    val totalBytes: Long,
    val bytesPerSecond: Long,
    val etaSeconds: Long?,
)

/** Smoothed throughput: an exponential moving average over the progress samples. */
class SpeedMeter(private val clock: () -> Long, private val alpha: Double = 0.3) {
    private var lastAt = -1L
    private var lastBytes = 0L
    private var speed = 0.0

    fun sample(bytesDone: Long, totalBytes: Long): DownloadProgress {
        val now = clock()
        if (lastAt >= 0 && now > lastAt && bytesDone >= lastBytes) {
            val instant = (bytesDone - lastBytes) * 1000.0 / (now - lastAt)
            speed = if (speed == 0.0) instant else alpha * instant + (1 - alpha) * speed
        }
        lastAt = now
        lastBytes = bytesDone
        val eta = if (speed >= 1.0) ((totalBytes - bytesDone).coerceAtLeast(0) / speed).toLong() else null
        return DownloadProgress(bytesDone, totalBytes, speed.toLong(), eta)
    }
}

/** How [DownloadRunner.run] ended; the drainer decides what to do next from it. */
sealed interface RunOutcome {
    /** Nothing to do: the row is gone, paused, failed or done already. */
    data object Skipped : RunOutcome

    /** Registered as [model]. */
    data class Done(val item: DownloadEntity, val model: ModelEntity) : RunOutcome

    data class Failed(val error: DownloadError) : RunOutcome

    /** Stopped by [DownloadManager.pause]; the partial file is kept. */
    data object Paused : RunOutcome

    /** Stopped by [DownloadManager.cancel] (or cancelled while finishing); nothing is left behind. */
    data object Cancelled : RunOutcome

    /** Network trouble: the row is queued again, partial kept, not to be picked before [notBefore]. */
    data class Retry(val cause: IOException, val notBefore: Long) : RunOutcome
}

/**
 * Runs one queued download from start to a registered model. Everything the worker does except
 * Android: row state, the engine, pause/cancel detection, verifying, finalising. See `DownloadRunnerTest`.
 *
 * **Pause and cancel are row changes.** The runner watches its row while the engine streams: when
 * the row is no longer `downloading` (paused, or deleted by cancel) it cancels the engine, so a
 * notification action, a screen and a test all stop a download the same way.
 *
 * **Order of the end game:** the engine checks the size, [DownloadEngine.verifySha256] reads the
 * file once, the size is checked against the row again, then [finalize] renames and registers.
 * With no expected hash, the pinned commit in the URL and the exact size are the integrity.
 *
 * **Transient errors** do not block anything: the row goes back to `queued` with `ioRetries + 1`
 * and a `notBefore` (exponential backoff), and the drainer simply picks something else meanwhile.
 * An attempt that moved bytes resets the count to 1, so a flaky link that keeps progressing never
 * runs out of retries, while one that gets nowhere gives up as [DownloadError.Network] after
 * [maxIoRetries].
 *
 * @param urlFor `(repo, commitSha or null, fileName)`, normally on [io.github.tensaiapp.hf.HfClient.fileUrl].
 * @param finalize turns the finished `.part` into a model through the import pipeline
 *   ([io.github.tensaiapp.data.models.ModelRepository.registerDownload]).
 * @param unregister undoes [finalize] when the row vanished meanwhile (cancel racing the end).
 */
class DownloadRunner(
    private val dao: DownloadDao,
    private val engine: DownloadEngine,
    private val urlFor: (repo: String, commitSha: String?, fileName: String) -> String,
    private val partFor: (id: String) -> File,
    private val finalize: suspend (part: File, row: DownloadEntity) -> RegisteredModel,
    private val unregister: suspend (RegisteredModel) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val persistEveryMs: Long = 2_000,
    private val maxIoRetries: Int = MAX_IO_RETRIES,
    private val backoffBaseMs: Long = BACKOFF_BASE_MS,
) {

    // Rows being run by this runner right now. Two drainers can overlap for a moment (a cancelled
    // one still finishing, its replacement already started): the second must not download, verify
    // or register the same row over the first.
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** @param onProgress called with the row at the start and then with each throttled sample. */
    suspend fun run(
        id: String,
        onProgress: suspend (DownloadEntity, DownloadProgress) -> Unit = { _, _ -> },
    ): RunOutcome {
        if (!inFlight.add(id)) return RunOutcome.Skipped
        try {
            return runGuarded(id, onProgress)
        } finally {
            inFlight.remove(id)
        }
    }

    private suspend fun runGuarded(
        id: String,
        onProgress: suspend (DownloadEntity, DownloadProgress) -> Unit,
    ): RunOutcome {
        val row = dao.get(id) ?: return RunOutcome.Skipped
        val from = listOf(DownloadStatus.Queued.code, DownloadStatus.Downloading.code)
        if (dao.transition(id, from, DownloadStatus.Downloading.code, null, null) == 0) return RunOutcome.Skipped

        val part = partFor(id)
        val startBytes = if (part.exists()) part.length() else 0L
        val meter = SpeedMeter(clock)
        var lastPersist = clock()
        var stopped = false

        try {
            coroutineScope {
                val download = async {
                    engine.download(
                        url = urlFor(row.repo, row.commitSha, row.fileName),
                        part = part,
                        totalBytes = row.sizeBytes,
                    ) { done ->
                        val now = clock()
                        if (now - lastPersist >= persistEveryMs) {
                            lastPersist = now
                            dao.setProgress(id, done)
                        }
                        onProgress(row, meter.sample(done, row.sizeBytes))
                    }
                    // Still under the watcher: a pause or cancel during the hash pass is honoured too.
                    engine.verifySha256(part, row.sha256)
                    if (part.length() != row.sizeBytes) {
                        part.delete()
                        throw DownloadException(DownloadError.SizeMismatch)
                    }
                }
                val watcher = launch {
                    dao.observe(id).first { it == null || it.status != DownloadStatus.Downloading.code }
                    stopped = true
                    download.cancel(CancellationException("download stopped"))
                }
                try {
                    download.await()
                } finally {
                    watcher.cancel()
                }
            }
            // The rename and the row insert are not interruptible, so a stop that landed after the
            // watcher finished is caught here, before they start.
            if (dao.get(id)?.status != DownloadStatus.Downloading.code) {
                return withContext(NonCancellable) { stoppedOutcome(id, part) }
            }
            return complete(row, part)
        } catch (e: CancellationException) {
            if (!stopped) {
                // The worker itself was stopped (constraints lost, process going away): go back to
                // the queue with the partial file, WorkManager runs the drainer again.
                withContext(NonCancellable) { requeue(id, part) }
                throw e
            }
            return withContext(NonCancellable) { stoppedOutcome(id, part) }
        } catch (e: DownloadException) {
            return withContext(NonCancellable) { fail(id, part, e.error) }
        } catch (e: IOException) {
            return withContext(NonCancellable) {
                val progressed = part.exists() && part.length() > startBytes
                val failures = if (progressed) 1 else row.ioRetries + 1
                if (failures >= maxIoRetries) {
                    fail(id, part, DownloadError.Network)
                } else {
                    val notBefore = clock() + backoff(failures)
                    if (dao.scheduleRetry(id, failures, notBefore) == 0) {
                        // Paused or cancelled while the error was happening: that wins.
                        stoppedOutcome(id, part)
                    } else {
                        dao.syncBytes(id, if (part.exists()) part.length() else 0L)
                        RunOutcome.Retry(e, notBefore)
                    }
                }
            }
        } catch (e: Exception) {
            return withContext(NonCancellable) { fail(id, part, DownloadError.Unknown) }
        }
    }

    private fun backoff(failures: Int): Long =
        minOf(backoffBaseMs shl (failures - 1).coerceIn(0, 20), MAX_BACKOFF_MS)

    private suspend fun complete(row: DownloadEntity, part: File): RunOutcome =
        withContext(NonCancellable) {
            try {
                val registered = finalize(part, row)
                val model = registered.model
                if (dao.markDone(row.id, row.sizeBytes, model.id) == 0) {
                    // Cancelled (row deleted) while the file was being registered: no download is
                    // asking for this model any more, so it goes, file and row, and nobody is told.
                    unregister(registered)
                    RunOutcome.Cancelled
                } else {
                    RunOutcome.Done(dao.get(row.id) ?: row.copy(status = DownloadStatus.Done.code, modelId = model.id), model)
                }
            } catch (e: DuplicateModelException) {
                part.delete()
                fail(row.id, part, DownloadError.AlreadyImported)
            } catch (e: ModelMetadataException) {
                part.delete()
                fail(row.id, part, DownloadError.InvalidModel)
            } catch (e: ModelImportException) {
                // Moving or reserving the destination failed: the part is intact, a retry can redo it.
                fail(row.id, part, DownloadError.DiskFull)
            }
            // Anything else (a database error on insert) propagates to run(): the importer has put
            // the file back as the .part, so the row ends failed Unknown and retry() finishes the job.
        }

    private suspend fun stoppedOutcome(id: String, part: File): RunOutcome {
        val now = dao.get(id)
        return if (now == null) {
            part.delete()
            RunOutcome.Cancelled
        } else if (now.status == DownloadStatus.Done.code) {
            // Finished by someone else (an overlapping drainer): nothing may be left behind.
            part.delete()
            RunOutcome.Skipped
        } else {
            dao.syncBytes(id, if (part.exists()) part.length() else 0L)
            RunOutcome.Paused
        }
    }

    private suspend fun fail(id: String, part: File, error: DownloadError): RunOutcome {
        val moved = dao.transition(id, listOf(DownloadStatus.Downloading.code, DownloadStatus.Queued.code), DownloadStatus.Failed.code, error.code, null)
        // Nothing moved: the user paused or cancelled while this was failing. That is the outcome, not
        // a failure to announce.
        if (moved == 0) return stoppedOutcome(id, part)
        if (error == DownloadError.ChecksumMismatch || error == DownloadError.NotFound || error == DownloadError.SizeMismatch) {
            part.delete()
        } else if (part.exists()) {
            dao.syncBytes(id, part.length())
        }
        return RunOutcome.Failed(error)
    }

    /** Back to queued with the real bytes on disk as progress. */
    private suspend fun requeue(id: String, part: File) {
        if (dao.transition(id, listOf(DownloadStatus.Downloading.code), DownloadStatus.Queued.code, null, null) > 0) {
            dao.syncBytes(id, if (part.exists()) part.length() else 0L)
        }
    }

    companion object {
        const val MAX_IO_RETRIES = 8
        const val BACKOFF_BASE_MS = 30_000L
        const val MAX_BACKOFF_MS = 30L * 60 * 1000
    }
}
