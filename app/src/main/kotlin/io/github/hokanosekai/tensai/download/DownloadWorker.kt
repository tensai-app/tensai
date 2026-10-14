package io.github.hokanosekai.tensai.download

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import io.github.hokanosekai.tensai.data.withAppLanguage
import io.github.hokanosekai.tensai.runtime.RuntimeGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Two jobs, told apart by the input data:
 *
 * - **Wake-up** (`KEY_WAKE`): a delayed or Wi-Fi-gated request left by a drainer that stopped with
 *   rows waiting. It only calls `ensureDrainer()` and exits, so the work that downloads is always
 *   the expedited drainer, never this one (see [WorkManagerScheduler]).
 * - **Drainer**: runs [DownloadDrainer] until nothing is runnable, as a foreground worker whose one
 *   notification follows the download currently running ([ForegroundGate]).
 *
 * **Never fails.** Everything except cancellation is caught: the result is always `success` (or
 * `retry` when the app graph is not up yet), so WorkManager can never leave the unique work in
 * `FAILED`. Per-download failures live in their rows, see [DownloadDrainer]. A failure that is not
 * a row's (the notifier, the database) leaves a short wake-up behind so the queue is not dropped
 * until the next app start.
 *
 * **Foreground.** `setForeground` is called when the first row runs. If Android refuses it, the
 * queue still drains and the notification is never updated (see [ForegroundGate]); the progress
 * notification is cancelled when the worker ends in any case.
 *
 * It never asks for notification permission: without it the service still runs and notifications
 * are simply not shown ([DownloadNotifier] checks before posting).
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = notifier().idleForegroundInfo()

    override suspend fun doWork(): Result {
        // A worker can be scheduled before Application.onCreate has built the graph.
        if (!RuntimeGraph.isInitialized) return Result.retry()
        if (inputData.getBoolean(KEY_WAKE, false)) {
            withContext(NonCancellable) { runCatching { RuntimeGraph.downloadScheduler.ensureDrainer() } }
            return Result.success()
        }

        var notifier: DownloadNotifier? = null
        return try {
            val n = notifier()
            notifier = n
            val gate = ForegroundGate(
                start = { row, progress -> setForeground(n.foregroundInfo(row, progress)) },
                update = n::update,
            )
            val drainer = RuntimeGraph.downloadDrainer
            val result = drainer.drain(
                onProgress = gate::onProgress,
                onOutcome = { row, outcome ->
                    when (outcome) {
                        is RunOutcome.Done -> n.notifyReady(outcome.item, outcome.model.id)
                        is RunOutcome.Failed -> n.notifyFailed(row, outcome.error)
                        else -> Unit
                    }
                },
            )
            // Wake-ups live under their own names, so this cannot replace a drainer enqueued since.
            withContext(NonCancellable) {
                if (result is DrainResult.Rest) {
                    RuntimeGraph.downloadScheduler.restDrainer(result.wakeAfterMs, result.wakeOnUnmetered)
                }
                // A row added between the last look and now must not wait for a wake-up.
                if (drainer.hasRunnable()) RuntimeGraph.downloadScheduler.ensureDrainer()
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.e(TAG, "download drainer crashed", t)
            withContext(NonCancellable) {
                runCatching { RuntimeGraph.downloadScheduler.restDrainer(DownloadQueue.MIN_REST_MS, false) }
            }
            Result.success()
        } finally {
            runCatching { notifier?.cancelProgress() }
        }
    }

    private suspend fun notifier(): DownloadNotifier {
        val language = RuntimeGraph.languageRepository.language.first()
        return DownloadNotifier(applicationContext.withAppLanguage(language))
    }

    companion object {
        const val KEY_WAKE = "wake"
        private const val TAG = "tensai.download"
    }
}
