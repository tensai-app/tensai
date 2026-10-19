package io.github.tensaiapp.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** What the drainer asks WorkManager for when it stops with rows still waiting. */
sealed interface DrainResult {
    /** Nothing left to run. */
    data object Finished : DrainResult

    /**
     * Rows are waiting. Wake the queue again, whichever of these comes first:
     * - after [wakeAfterMs] on any network (a row in backoff), if not null;
     * - when the network is next not metered, if [wakeOnUnmetered] (Wi-Fi-only rows on mobile data).
     * A wake-up only re-ensures the drainer, it does not download.
     */
    data class Rest(val wakeAfterMs: Long?, val wakeOnUnmetered: Boolean) : DrainResult
}

/**
 * Who is draining. Each [begin] hands out a token and only [end] with that token releases it, so a
 * finishing drainer can never clear the claim of a newer one (WorkManager's REPLACE cancels the old
 * request asynchronously, so two loops can overlap for a moment).
 */
class DrainerRegistry {
    private val lock = Any()
    private var next = 0L
    private val active = HashSet<Long>()

    fun begin(): Long = synchronized(lock) { (++next).also { active += it } }

    fun end(token: Long) {
        synchronized(lock) { active -= token }
    }

    /** Some drain loop is active in this process. */
    val isRunning: Boolean get() = synchronized(lock) { active.isNotEmpty() }
}

/** The pure selection rules of the queue: who runs next, and when to come back. */
object DownloadQueue {

    /** Least time between two wake-ups, so a disagreement about "metered" cannot spin. */
    const val MIN_REST_MS = 15_000L

    /**
     * The next row to run: a `downloading` row first (a killed process left it), then the oldest
     * `queued` one, skipping rows still in backoff (`notBefore`) and Wi-Fi-only rows while [metered].
     */
    fun pick(rows: List<DownloadEntity>, now: Long, metered: Boolean): DownloadEntity? =
        rows.asSequence()
            .filter { it.status == DownloadStatus.Queued.code || it.status == DownloadStatus.Downloading.code }
            .filter { it.notBefore <= now && (it.allowMobile || !metered) }
            .sortedWith(
                compareBy<DownloadEntity> { if (it.status == DownloadStatus.Downloading.code) 0 else 1 }
                    .thenBy { it.queuedAt }
                    .thenBy { it.id },
            )
            .firstOrNull()

    /** What to wait for when [pick] found nothing: null if no row is waiting at all. */
    fun rest(rows: List<DownloadEntity>, now: Long, metered: Boolean): DrainResult.Rest? {
        val waiting = rows.filter { it.status == DownloadStatus.Queued.code || it.status == DownloadStatus.Downloading.code }
        if (waiting.isEmpty()) return null
        // Rows that could run on this network but are in backoff: a timer for the earliest.
        val usable = waiting.filter { it.allowMobile || !metered }
        val timer = usable.minOfOrNull { it.notBefore }?.let { (it - now).coerceAtLeast(MIN_REST_MS) }
        // Wi-Fi-only rows on mobile data: wake as soon as the network is unmetered. Independent of
        // the timer, so whichever comes first wakes the queue.
        val wifi = waiting.any { !it.allowMobile && metered }
        return DrainResult.Rest(wakeAfterMs = timer, wakeOnUnmetered = wifi)
    }
}

/**
 * The queue loop: the DB is the queue, this runs it. One drainer at a time (the unique work
 * `tensai-model-downloads`), looping until nothing is runnable.
 *
 * It never throws except for cancellation: a failure of one download marks that row failed
 * [DownloadError.Unknown] and the loop moves on; a failure of the database itself ends the loop
 * with a short [DrainResult.Rest] (logged) so the queue is not dropped until the next app start.
 * Nothing here can make the worker `FAILED`.
 *
 * Ownership: the loop holds a token from [registry] while active ([DrainerRegistry]). Before giving
 * it up the loop looks once more, so a row inserted in that window is not stranded.
 *
 * @param runOne runs one row to an outcome, normally [DownloadRunner.run].
 * @param isMetered is the active network metered (mobile data, hotspot)?
 */
class DownloadDrainer(
    private val dao: DownloadDao,
    private val runOne: suspend (id: String, onProgress: suspend (DownloadEntity, DownloadProgress) -> Unit) -> RunOutcome,
    private val isMetered: () -> Boolean,
    private val registry: DrainerRegistry = DrainerRegistry(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String, Throwable) -> Unit = { _, _ -> },
) {

    /**
     * @param onProgress the running row and its progress, for the notification.
     * @param onOutcome every finished attempt with the row it was for, for "ready" and "failed" notifications.
     */
    suspend fun drain(
        onProgress: suspend (DownloadEntity, DownloadProgress) -> Unit = { _, _ -> },
        onOutcome: suspend (DownloadEntity, RunOutcome) -> Unit = { _, _ -> },
    ): DrainResult {
        var token = registry.begin()
        try {
            while (true) {
                val rows = try {
                    dao.getRunnable()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    log("drainer: reading the queue failed", e)
                    return DrainResult.Rest(DownloadQueue.MIN_REST_MS, wakeOnUnmetered = false)
                }
                val pick = DownloadQueue.pick(rows, clock(), isMetered())
                if (pick == null) {
                    // Give up the claim, then look once more: an enqueue that saw it held only
                    // inserted its row and relied on us.
                    registry.end(token)
                    val again = try {
                        dao.getRunnable()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        log("drainer: reading the queue failed", e)
                        return DrainResult.Rest(DownloadQueue.MIN_REST_MS, wakeOnUnmetered = false)
                    }
                    if (DownloadQueue.pick(again, clock(), isMetered()) != null) {
                        token = registry.begin()
                        continue
                    }
                    return DownloadQueue.rest(again, clock(), isMetered()) ?: DrainResult.Finished
                }
                val outcome = try {
                    runOne(pick.id) { row, progress -> onProgress(row, progress) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    log("drainer: download ${pick.id} crashed", e)
                    withContext(NonCancellable) { markCrashed(pick.id) }
                    RunOutcome.Failed(DownloadError.Unknown)
                }
                try {
                    onOutcome(pick, outcome)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    log("drainer: reporting ${pick.id} failed", e)
                }
            }
        } finally {
            registry.end(token)
        }
    }

    /** True if [drain] would find something to run right now. */
    suspend fun hasRunnable(): Boolean = try {
        DownloadQueue.pick(dao.getRunnable(), clock(), isMetered()) != null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        false
    }

    private suspend fun markCrashed(id: String) {
        try {
            dao.transition(
                id,
                listOf(DownloadStatus.Queued.code, DownloadStatus.Downloading.code),
                DownloadStatus.Failed.code,
                DownloadError.Unknown.code,
                null,
            )
        } catch (e: Throwable) {
            log("drainer: could not mark $id failed", e)
        }
    }
}
