package io.github.tensaiapp.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * [DownloadScheduler] on WorkManager.
 *
 * - **The drainer** is one unique work, `tensai-model-downloads`: a [DownloadWorker] that drains the
 *   queue (see [DownloadDrainer]). [ensureDrainer] leaves a live drain alone (KEEP, decided by
 *   [DrainerRegistry]) and otherwise REPLACEs whatever request is there. It is **expedited**
 *   (`RUN_AS_NON_EXPEDITED_WORK_REQUEST`) and constrained to any connected network.
 * - **Wake-ups** are separate unique works (`...-wake-timer`, `...-wake-wifi`) whose worker only
 *   calls [ensureDrainer] and exits. They are how a drainer that stops with rows waiting comes
 *   back: after a backoff, or when the network turns unmetered, whichever comes first. They never
 *   touch the drainer's own unique name, so a hand-over cannot replace a drainer enqueued after it,
 *   and the drainer that actually downloads is always the expedited one (with its foreground
 *   service), never a delayed non-expedited request.
 *
 * **What Android does not promise.** An expedited request that is out of quota runs as ordinary
 * work. From the background on Android 12+, starting a foreground service can be refused
 * (`ForegroundServiceStartNotAllowedException`); the worker swallows that and downloads without the
 * foreground notification. Ordinary background work is then stopped by the system after about ten
 * minutes. That is survivable by design: a stopped drainer puts its row back to `queued` with the
 * partial file, WorkManager runs the same unique work again, and the download resumes with a `Range`
 * request from where the `.part` ended. Nothing was verified on a device for TEN-141.
 */
class WorkManagerScheduler(
    private val context: Context,
    private val registry: DrainerRegistry,
) : DownloadScheduler {

    override fun ensureDrainer() {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        val policy = if (registry.isRunning) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, policy, request)
    }

    override fun restDrainer(wakeAfterMs: Long?, wakeOnUnmetered: Boolean) {
        val manager = WorkManager.getInstance(context)
        if (wakeAfterMs != null) {
            manager.enqueueUniqueWork(
                WAKE_TIMER,
                ExistingWorkPolicy.REPLACE,
                wake(NetworkType.CONNECTED, wakeAfterMs),
            )
        }
        if (wakeOnUnmetered) {
            manager.enqueueUniqueWork(
                WAKE_WIFI,
                ExistingWorkPolicy.REPLACE,
                wake(NetworkType.UNMETERED, DownloadQueue.MIN_REST_MS),
            )
        }
    }

    private fun wake(network: NetworkType, delayMs: Long) = OneTimeWorkRequestBuilder<DownloadWorker>()
        .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
        .setInputData(workDataOf(DownloadWorker.KEY_WAKE to true))
        .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
        .build()

    companion object {
        const val UNIQUE_NAME = "tensai-model-downloads"
        const val WAKE_TIMER = "tensai-model-downloads-wake-timer"
        const val WAKE_WIFI = "tensai-model-downloads-wake-wifi"
    }
}
