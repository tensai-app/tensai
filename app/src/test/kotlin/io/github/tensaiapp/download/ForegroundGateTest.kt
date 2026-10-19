package io.github.tensaiapp.download

import java.lang.IllegalStateException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** [ForegroundGate]: the notification is only updated when the foreground start worked. */
class ForegroundGateTest {

    private var now = 0L
    private val started = mutableListOf<String>()
    private val updated = mutableListOf<String>()
    private var refuse = false

    private fun row(id: String) = DownloadEntity(
        id = id, repo = "o/r", fileName = "f", quant = "Q", displayName = id, sizeBytes = 1, sha256 = null,
        status = "downloading", bytesDone = 0, error = null, queuedAt = 0, modelId = null,
    )

    private val progress = DownloadProgress(1, 10, 1, 9)

    private fun gate() = ForegroundGate(
        start = { r, _ ->
            if (refuse) throw IllegalStateException("ForegroundServiceStartNotAllowedException")
            started += r.id
        },
        update = { r, _ -> updated += r.id },
        clock = { now },
        everyMs = 1_000,
    )

    @Test
    fun startsOnTheFirstRowThenUpdatesThrottled() = runBlocking {
        val g = gate()

        g.onProgress(row("a"), progress)
        now = 500
        g.onProgress(row("a"), progress)
        now = 1_200
        g.onProgress(row("a"), progress)
        g.onProgress(row("b"), progress)

        assertEquals(listOf("a"), started)
        assertEquals("one update after a second, none inside it", listOf("a"), updated)
    }

    @Test
    fun aRefusedForegroundStartMeansNoNotificationUpdateEver() = runBlocking {
        refuse = true
        val g = gate()

        repeat(5) {
            now += 2_000
            g.onProgress(row("a"), progress)
        }

        assertTrue("an update would post an ongoing notification nothing owns", updated.isEmpty())
        assertTrue(started.isEmpty())
    }

    @Test
    fun theStartIsAttemptedOnlyOnce() = runBlocking {
        refuse = true
        var tries = 0
        val g = ForegroundGate(start = { _, _ -> tries++; throw IllegalStateException() }, update = { _, _ -> }, clock = { now })

        repeat(3) { g.onProgress(row("a"), progress) }

        assertEquals(1, tries)
    }

    @Test
    fun nothingHappensUntilARowRuns() {
        gate()
        assertTrue(started.isEmpty() && updated.isEmpty())
    }

    @Test
    fun cancellationDuringTheStartPropagates() {
        val g = ForegroundGate(start = { _, _ -> throw CancellationException("stopped") }, update = { _, _ -> })
        try {
            runBlocking { g.onProgress(row("a"), progress) }
            fail("must propagate")
        } catch (_: CancellationException) {
        }
    }
}
