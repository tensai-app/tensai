package io.github.hokanosekai.tensai.ui.screens.models

import io.github.hokanosekai.tensai.download.DownloadItem
import io.github.hokanosekai.tensai.download.DownloadStatus
import io.github.hokanosekai.tensai.download.EnqueueResult
import io.github.hokanosekai.tensai.hf.HfQuantFile
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Models library's download controls (TEN-142): the queue as cards, and what each card button does. */
class ModelDownloadsTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private class FakeDownloads : RepoDownloads {
        val queue = MutableStateFlow<List<DownloadItem>>(emptyList())
        override val items = queue
        val calls = Collections.synchronizedList(mutableListOf<String>())
        var answer: (Boolean) -> EnqueueResult = { EnqueueResult.Enqueued("d1") }

        override suspend fun enqueue(repo: String, quant: HfQuantFile, displayName: String, confirmMobileData: Boolean, commitSha: String?): EnqueueResult {
            calls += "enqueue:$repo:${quant.fileName}:$confirmMobileData:$commitSha"
            return answer(confirmMobileData)
        }

        override suspend fun pause(id: String) {
            calls += "pause:$id"
        }

        override suspend fun resume(id: String, confirmMobileData: Boolean): EnqueueResult {
            calls += "resume:$id:$confirmMobileData"
            return answer(confirmMobileData)
        }

        override suspend fun retry(id: String, confirmMobileData: Boolean): EnqueueResult {
            calls += "retry:$id:$confirmMobileData"
            return answer(confirmMobileData)
        }

        override suspend fun cancel(id: String) {
            calls += "cancel:$id"
        }
    }

    private val fake = FakeDownloads()
    private val allowMobile = Collections.synchronizedList(mutableListOf<Boolean>())
    private var clock = 0L

    private fun controller() = ModelDownloads(
        scope = scope,
        downloads = fake,
        setAllowMobileData = { allowMobile += it },
        now = { clock },
    )

    private fun item(id: String, status: DownloadStatus, done: Long = 0) = DownloadItem(
        id = id, repo = "org/repo", fileName = "$id.gguf", quant = "Q4_K_M", displayName = "Model $id", sizeBytes = 1000,
        sha256 = null, status = status, bytesDone = done, error = null, modelId = null,
    )

    private suspend fun ModelDownloads.await(what: String, predicate: (DownloadsState) -> Boolean): DownloadsState =
        try {
            withTimeout(5_000) { state.first(predicate) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting for $what, last state: ${state.value}")
        }

    private suspend fun until(condition: () -> Boolean) {
        withTimeout(5_000) { while (!condition()) delay(5) }
    }

    @Test
    fun theQueueBecomesCardsAndFinishedRowsDoNot() = runBlocking {
        val c = controller()
        c.start()
        fake.queue.value = listOf(item("a", DownloadStatus.Done, 1000), item("b", DownloadStatus.Downloading, 300), item("c", DownloadStatus.Queued))

        val s = c.await("two cards") { it.cards.size == 2 }

        assertEquals(listOf("b", "c"), s.cards.map { it.id })
        assertEquals(DownloadCardState.Queued(ahead = 1), s.cards[1].state)
    }

    @Test
    fun theSpeedOfTheRunningDownloadIsMeasuredFromTheQueue() = runBlocking {
        val c = controller()
        c.start()
        clock = 0
        fake.queue.value = listOf(item("a", DownloadStatus.Downloading, 0))
        c.await("first reading") { it.queue.isNotEmpty() }
        clock = 1_000
        fake.queue.value = listOf(item("a", DownloadStatus.Downloading, 500))

        val s = c.await("a speed") { it.bytesPerSecond > 0 }

        assertEquals(500L, s.bytesPerSecond)
        assertEquals(1L, (s.cards.single().state as DownloadCardState.Downloading).etaSeconds)
    }

    @Test
    fun pauseAndCancelGoStraightToTheQueue() = runBlocking {
        val c = controller()
        c.pause("a")
        c.cancel("b")

        until { fake.calls.size == 2 }
        assertEquals(setOf("pause:a", "cancel:b"), fake.calls.toSet())
    }

    @Test
    fun resumeAndRetryAskTheQueueWithoutAConfirmation() = runBlocking {
        val c = controller()
        c.resume("a")
        until { fake.calls.size == 1 }
        c.retry("b")
        until { fake.calls.size == 2 }

        assertEquals(listOf("resume:a:false", "retry:b:false"), fake.calls.toList())
        assertNull(c.state.value.dialog)
    }

    @Test
    fun resumeOnMobileDataAsksFirstAndReplaysTheSameTapOnceConfirmed() = runBlocking {
        fake.answer = { confirmed -> if (confirmed) EnqueueResult.Enqueued("a") else EnqueueResult.NeedsMobileDataConfirmation(700) }
        val c = controller()

        c.resume("a")
        val asked = c.await("the dialog") { it.dialog != null }
        assertEquals(DownloadsDialog.MobileData(700, DownloadOp.Resume("a")), asked.dialog)

        c.confirmMobileData(always = false)
        until { fake.calls.size == 2 }
        assertEquals(listOf("resume:a:false", "resume:a:true"), fake.calls.toList())
        assertNull(c.state.value.dialog)
        assertTrue("without the box ticked the preference stays as it was", allowMobile.isEmpty())
    }

    @Test
    fun alwaysAllowSetsThePreferenceBeforeRetrying() = runBlocking {
        fake.answer = { confirmed -> if (confirmed) EnqueueResult.Enqueued("a") else EnqueueResult.NeedsMobileDataConfirmation(700) }
        val c = controller()
        c.retry("a")
        c.await("the dialog") { it.dialog != null }

        c.confirmMobileData(always = true)

        until { fake.calls.size == 2 }
        assertEquals(listOf(true), allowMobile.toList())
        assertEquals("retry:a:true", fake.calls.last())
    }

    @Test
    fun notEnoughStorageRaisesTheStorageDialogAndDismissClosesIt() = runBlocking {
        fake.answer = { EnqueueResult.NotEnoughStorage(neededBytes = 5_000, freeBytes = 1_000) }
        val c = controller()

        c.resume("a")
        val s = c.await("the dialog") { it.dialog != null }
        assertEquals(DownloadsDialog.Storage(5_000, 1_000), s.dialog)

        c.dismissDialog()
        assertNull(c.state.value.dialog)
    }

    @Test
    fun aRowThatIsGoneOrAnAlreadyQueuedAnswerShowsNoDialog() = runBlocking {
        fake.answer = { EnqueueResult.NotFound }
        val c = controller()
        c.resume("a")
        until { fake.calls.size == 1 }
        delay(50)

        assertNull(c.state.value.dialog)
    }
}
