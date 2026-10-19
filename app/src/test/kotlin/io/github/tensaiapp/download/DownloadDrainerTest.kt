package io.github.tensaiapp.download

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** [DownloadQueue] selection and the [DownloadDrainer] loop around a scripted runner. */
class DownloadDrainerTest {

    private val dao = FakeDownloadDao()
    private var now = 100_000L
    private var metered = false
    private val registry = DrainerRegistry()
    private val ran = mutableListOf<String>()
    private val logged = mutableListOf<String>()

    private fun row(
        id: String,
        at: Long,
        status: String = "queued",
        allowMobile: Boolean = false,
        notBefore: Long = 0,
    ) = DownloadEntity(
        id = id, repo = "o/r", fileName = "$id.gguf", quant = "Q4", displayName = id, sizeBytes = 10, sha256 = null,
        status = status, bytesDone = 0, error = null, queuedAt = at, modelId = null,
        allowMobile = allowMobile, notBefore = notBefore,
    )

    private fun pick(rows: List<DownloadEntity>) = DownloadQueue.pick(rows, now, metered)?.id

    // ---- selection ----

    @Test
    fun oldestQueuedFirstAndADownloadingLeftoverBeforeAnyQueued() {
        val rows = listOf(row("c", 3), row("a", 1), row("b", 2), row("z", 9, status = "downloading"))

        assertEquals("a row a dead process left running resumes first", "z", pick(rows))
        assertEquals("a", pick(rows.filter { it.id != "z" }))
    }

    @Test
    fun pausedFailedAndDoneRowsAreNeverPicked() {
        val rows = listOf(row("p", 1, status = "paused"), row("f", 2, status = "failed"), row("d", 3, status = "done"))

        assertNull(pick(rows))
        assertNull("and nothing waits", DownloadQueue.rest(rows, now, metered))
    }

    @Test
    fun aRowInBackoffIsSkippedNotWaitedOn() {
        val rows = listOf(row("a", 1, notBefore = now + 60_000), row("b", 2))

        assertEquals("b", pick(rows))
        assertEquals("a", pick(listOf(row("a", 1, notBefore = now))))
    }

    @Test
    fun wifiOnlyRowsAreSkippedOnMeteredButMobileRowsRun() {
        metered = true
        val rows = listOf(row("wifi", 1), row("mobile", 2, allowMobile = true))

        assertEquals("mobile", pick(rows))
        assertNull(pick(rows.filter { it.id == "wifi" }))
        metered = false
        assertEquals("wifi", pick(rows))
    }

    @Test
    fun restWaitsForTheEarliestBackoffOrForUnmetered() {
        val backoff = listOf(row("a", 1, notBefore = now + 90_000), row("b", 2, notBefore = now + 40_000))
        assertEquals(DrainResult.Rest(40_000, wakeOnUnmetered = false), DownloadQueue.rest(backoff, now, metered))

        assertEquals(
            "never sooner than the floor, so a metered disagreement cannot spin",
            DrainResult.Rest(DownloadQueue.MIN_REST_MS, wakeOnUnmetered = false),
            DownloadQueue.rest(listOf(row("a", 1, notBefore = now + 1)), now, metered),
        )

        metered = true
        assertEquals(
            DrainResult.Rest(null, wakeOnUnmetered = true),
            DownloadQueue.rest(listOf(row("w", 1)), now, metered),
        )
        assertEquals(
            "a mobile row in backoff and a Wi-Fi-only row: wake for whichever comes first",
            DrainResult.Rest(30_000, wakeOnUnmetered = true),
            DownloadQueue.rest(listOf(row("w", 1), row("m", 2, allowMobile = true, notBefore = now + 30_000)), now, metered),
        )
    }

    // ---- the loop ----

    /** A runner that finishes rows by marking them done, or does whatever [script] says. */
    private fun drainer(script: suspend (String) -> RunOutcome = { RunOutcome.Skipped }) = DownloadDrainer(
        dao = dao,
        runOne = { id, _ ->
            ran += id
            val outcome = script(id)
            if (outcome is RunOutcome.Skipped) dao.markDone(id, 10, "model-$id")
            outcome
        },
        isMetered = { metered },
        registry = registry,
        clock = { now },
        log = { m, t -> logged += "$m: ${t.message}" },
    )

    @Test
    fun drainsInFifoOrderUntilNothingIsLeftThenFinishes() = runBlocking {
        dao.insert(row("b", 2))
        dao.insert(row("a", 1))
        dao.insert(row("c", 3))

        val result = drainer().drain()

        assertEquals(listOf("a", "b", "c"), ran)
        assertEquals(DrainResult.Finished, result)
        assertFalse("the claim is released", registry.isRunning)
    }

    @Test
    fun aRowAddedWhileItRunsIsPickedByTheSameLoop() = runBlocking {
        dao.insert(row("a", 1))

        drainer { id ->
            if (id == "a") dao.insert(row("late", 2))
            RunOutcome.Skipped
        }.drain()

        assertEquals(listOf("a", "late"), ran)
    }

    @Test
    fun pausedAndBackoffRowsAreLeftAndReportedAsRest() = runBlocking {
        dao.insert(row("paused", 1, status = "paused"))
        dao.insert(row("later", 2, notBefore = now + 45_000))
        dao.insert(row("now", 3))

        val result = drainer().drain()

        assertEquals(listOf("now"), ran)
        assertEquals(DrainResult.Rest(45_000, wakeOnUnmetered = false), result)
    }

    @Test
    fun onlyWifiRowsOnMobileDataReturnsAnUnmeteredRest() = runBlocking {
        metered = true
        dao.insert(row("w1", 1))
        dao.insert(row("w2", 2))

        val result = drainer().drain()

        assertTrue(ran.isEmpty())
        assertEquals(DrainResult.Rest(null, wakeOnUnmetered = true), result)
        assertEquals("they stay queued", listOf("queued", "queued"), dao.rows.map { it.status })
    }

    @Test
    fun aRunnerThatThrowsFailsOnlyItsRowAndTheLoopGoesOn() = runBlocking {
        dao.insert(row("boom", 1))
        dao.insert(row("ok", 2))

        val outcomes = mutableListOf<Pair<String, RunOutcome>>()
        val result = drainer { id -> if (id == "boom") throw IllegalStateException("bug") else RunOutcome.Skipped }
            .drain(onOutcome = { r, o -> outcomes += r.id to o })

        assertEquals(DrainResult.Finished, result)
        assertEquals("failed", dao.row("boom")!!.status)
        assertEquals("unknown", dao.row("boom")!!.error)
        assertEquals("done", dao.row("ok")!!.status)
        assertEquals(RunOutcome.Failed(DownloadError.Unknown), outcomes.first().second)
        assertTrue(logged.single().contains("bug"))
    }

    @Test
    fun aDatabaseThatThrowsEndsTheLoopWithoutThrowing() = runBlocking {
        dao.insert(row("a", 1))
        dao.failReads = IOException("disk I/O error")

        val result = drainer().drain()

        assertEquals("a short wake-up, so the queue is not dropped until the next start", DrainResult.Rest(DownloadQueue.MIN_REST_MS, wakeOnUnmetered = false), result)
        assertFalse(registry.isRunning)
        assertTrue(logged.single().contains("disk I/O error"))
    }

    @Test
    fun aThrowingOutcomeListenerDoesNotStopTheQueue() = runBlocking {
        dao.insert(row("a", 1))
        dao.insert(row("b", 2))

        drainer().drain(onOutcome = { _, _ -> throw IllegalStateException("notification broke") })

        assertEquals(listOf("a", "b"), ran)
    }

    @Test
    fun cancellationPropagatesAndReleasesTheFlag() {
        runBlocking {
            dao.insert(row("a", 1))
            try {
                drainer { throw CancellationException("worker stopped") }.drain()
                fail("cancellation must go through")
            } catch (_: CancellationException) {
            }
            assertFalse(registry.isRunning)
        }
    }

    @Test
    fun aFinishingLoopNeverReleasesANewerOnesClaim() {
        val older = registry.begin()
        val newer = registry.begin()

        registry.end(older)
        assertTrue("the newer drainer still holds it", registry.isRunning)
        registry.end(newer)
        assertFalse(registry.isRunning)
    }

    @Test
    fun twoLoopsOverlappingEachKeepTheirOwnClaim() = runBlocking {
        dao.insert(row("a", 1))
        var duringInner = false
        val outer = drainer {
            // A second drain begins and ends while this one is mid-run.
            drainer().drain()
            duringInner = registry.isRunning
            RunOutcome.Skipped
        }

        outer.drain()

        assertTrue("the inner loop finishing did not release the outer one", duringInner)
        assertFalse(registry.isRunning)
    }

    @Test
    fun theFlagIsSetWhileRunning() = runBlocking {
        dao.insert(row("a", 1))
        var seen = false

        drainer {
            seen = registry.isRunning
            RunOutcome.Skipped
        }.drain()

        assertTrue(seen)
    }
}

