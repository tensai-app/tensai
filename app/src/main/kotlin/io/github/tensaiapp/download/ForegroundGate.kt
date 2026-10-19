package io.github.tensaiapp.download

import kotlinx.coroutines.CancellationException

/**
 * Decides when the progress notification may be touched. The pure half of the worker's foreground
 * handling, so the rules are tested on the JVM:
 *
 * - the foreground service is started **once**, when the first row actually runs (not when a drainer
 *   merely starts and finds nothing to do);
 * - [update] only happens after that start succeeded. If Android refused the foreground start, an
 *   update would post an ongoing notification that no service owns and nothing would ever remove;
 * - updates are throttled to [everyMs].
 *
 * @param start `setForeground` with the first row; may throw when refused.
 * @param update re-posts the notification for the running row.
 */
class ForegroundGate(
    private val start: suspend (DownloadEntity, DownloadProgress) -> Unit,
    private val update: (DownloadEntity, DownloadProgress) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val everyMs: Long = 1_000,
) {
    private var attempted = false
    private var started = false
    private var lastUpdate = 0L

    suspend fun onProgress(row: DownloadEntity, progress: DownloadProgress) {
        if (!attempted) {
            attempted = true
            try {
                start(row, progress)
                started = true
                lastUpdate = clock()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Refused (Android 12+ from the background): the queue still drains, silently.
            }
            return
        }
        if (!started) return
        val now = clock()
        if (now - lastUpdate >= everyMs) {
            lastUpdate = now
            update(row, progress)
        }
    }
}
