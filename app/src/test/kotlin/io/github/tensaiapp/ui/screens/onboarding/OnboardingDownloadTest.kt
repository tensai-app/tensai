package io.github.tensaiapp.ui.screens.onboarding

import io.github.tensaiapp.compat.ModelFacts
import io.github.tensaiapp.download.DownloadError
import io.github.tensaiapp.download.DownloadItem
import io.github.tensaiapp.download.DownloadStatus
import io.github.tensaiapp.download.EnqueueResult
import io.github.tensaiapp.hf.HfError
import io.github.tensaiapp.hf.HfException
import io.github.tensaiapp.hf.HfGate
import io.github.tensaiapp.hf.HfQuantFile
import io.github.tensaiapp.hf.HfRepoDetail
import io.github.tensaiapp.hf.HfRepoSummary
import io.github.tensaiapp.ui.screens.models.DownloadCardState
import io.github.tensaiapp.ui.screens.models.FailedPrimary
import io.github.tensaiapp.ui.screens.models.RepoDownloads
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Onboarding step 2's download (TEN-145): what each enqueue answer does, and how the card follows the queue. */
class OnboardingDownloadTest {

    // One virtual-time scheduler for the test body and the code under test: no real thread, no real clock.
    private val dispatcher = StandardTestDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @After
    fun tearDown() = scope.cancel()

    private class FakeDownloads : RepoDownloads {
        val queue = MutableStateFlow<List<io.github.tensaiapp.download.DownloadItem>>(emptyList())
        override val items = queue
        val calls = Collections.synchronizedList(mutableListOf<String>())
        var answer: (Boolean) -> EnqueueResult = { EnqueueResult.Enqueued("d1") }

        override suspend fun enqueue(repo: String, quant: HfQuantFile, displayName: String, confirmMobileData: Boolean, commitSha: String?): EnqueueResult {
            calls += "enqueue:$repo:${quant.fileName}:${quant.sizeBytes}:${quant.sha256}:$confirmMobileData:$commitSha"
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

    private val sha = "a".repeat(40)
    private val fake = FakeDownloads()
    private val library = MutableStateFlow<Set<String>>(emptySet())
    private val allowMobile = Collections.synchronizedList(mutableListOf<Boolean>())
    private var detailError: HfException? = null
    private val details = Collections.synchronizedList(mutableListOf<String>())

    private fun model(path: String, file: String) = CatalogModel(
        name = path.substringAfter('/'),
        hfPath = path,
        quantLabel = "Q4_K_M",
        facts = ModelFacts(
            sizeBytes = 1_000,
            paramCount = 1_000_000_000,
            architecture = "llama",
            contextLength = 4096,
            embeddingLength = 2048,
            blockCount = 16,
        ),
        fileName = file,
        onboarding = true,
    )

    private val reco = model("bartowski/Small-GGUF", "Small-Q4_K_M.gguf")
    private val other = model("bartowski/Big-GGUF", "Big-Q4_K_M.gguf")

    private fun detailOf(repo: String, vararg files: String) = HfRepoDetail(
        summary = HfRepoSummary(repo, repo.substringBefore('/'), repo.substringAfter('/'), 0, 0, null, null, HfGate.None),
        paramCount = 1_000_000_000,
        architecture = "llama",
        contextLength = 4096,
        supportsTools = false,
        hasVision = false,
        quants = files.map { HfQuantFile(it, "Q4_K_M", 1_234, "feed") },
        sha = sha,
    )

    private fun controller() = OnboardingDownloads(
        scope = scope,
        downloads = fake,
        detail = { repo ->
            details += repo
            detailError?.let { throw it }
            detailOf(repo, reco.fileName, other.fileName)
        },
        libraryIds = library,
        setAllowMobileData = { allowMobile += it },
        now = { dispatcher.scheduler.currentTime },
    )

    private fun row(
        id: String,
        m: CatalogModel,
        status: DownloadStatus,
        done: Long = 0,
        error: DownloadError? = null,
        modelId: String? = null,
    ) = DownloadItem(
        id = id, repo = m.hfPath, fileName = m.fileName, quant = "Q4_K_M", displayName = m.name, sizeBytes = 1_234,
        sha256 = "feed", status = status, bytesDone = done, error = error, modelId = modelId,
    )

    private suspend fun OnboardingDownloads.await(what: String, predicate: (OnboardingDownloadState) -> Boolean): OnboardingDownloadState =
        try {
            withTimeout(5_000) { state.first(predicate) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting for $what, last state: ${state.value}")
        }

    private suspend fun until(condition: () -> Boolean) {
        withTimeout(5_000) { while (!condition()) delay(5) }
    }

    @Test
    fun downloadEnqueuesTheRealFileAndTheCommitOfTheRepoDetail() = runTest(dispatcher) {
        val c = controller()
        c.download(reco)

        until { fake.calls.isNotEmpty() }
        assertEquals(listOf(reco.hfPath), details.toList())
        assertEquals("enqueue:bartowski/Small-GGUF:Small-Q4_K_M.gguf:1234:feed:false:$sha", fake.calls.single())
    }

    @Test
    fun offlineKeepsTheCardIdleAndSaysSo() = runTest(dispatcher) {
        detailError = HfException(HfError.Offline)
        val c = controller()
        c.download(reco)

        val s = c.await("offline lookup") { it.lookup == OnboardingLookup.Offline }

        assertEquals(OnboardingPhase.Idle, s.phase)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun aFileMissingFromTheRepoIsAFailedLookup() = runTest(dispatcher) {
        val c = controller()
        c.download(model("bartowski/Small-GGUF", "Gone-Q4_K_M.gguf"))

        c.await("failed lookup") { it.lookup == OnboardingLookup.Failed }
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun theMobileDataQuestionIsAskedThenReplayedConfirmedWithAlwaysAllow() = runTest(dispatcher) {
        fake.answer = { confirmed -> if (confirmed) EnqueueResult.Enqueued("d1") else EnqueueResult.NeedsMobileDataConfirmation(1_234) }
        val c = controller()
        c.download(reco)

        val asked = c.await("mobile dialog") { it.dialog != null }
        assertEquals(1_234L, (asked.dialog as OnboardingDialog.MobileData).sizeBytes)

        c.confirmMobileData(always = true)
        until { fake.calls.size == 2 }

        assertEquals(listOf(true), allowMobile.toList())
        assertTrue(fake.calls.last().contains(":true:$sha"))
        assertNull(c.state.value.dialog)
    }

    @Test
    fun notEnoughStorageRaisesItsDialog() = runTest(dispatcher) {
        fake.answer = { EnqueueResult.NotEnoughStorage(neededBytes = 5_000, freeBytes = 100) }
        val c = controller()
        c.download(reco)

        val s = c.await("storage dialog") { it.dialog != null }

        assertEquals(OnboardingDialog.Storage(5_000, 100), s.dialog)
        assertEquals(OnboardingPhase.Idle, s.phase)
    }

    @Test
    fun alreadyOnDeviceIsDoneWithThatModel() = runTest(dispatcher) {
        library.value = setOf("m1")
        fake.answer = { EnqueueResult.AlreadyOnDevice("m1") }
        val c = controller()
        c.start()
        c.download(reco)

        val s = c.await("done") { it.phase is OnboardingPhase.Done }

        assertEquals(OnboardingPhase.Done("m1"), s.phase)
    }

    @Test
    fun alreadyQueuedFollowsTheExistingRow() = runTest(dispatcher) {
        fake.answer = { EnqueueResult.AlreadyQueued("d9") }
        fake.queue.value = listOf(row("d9", reco, DownloadStatus.Downloading, done = 300))
        val c = controller()
        c.start()
        c.download(reco)

        val s = c.await("active") { it.phase is OnboardingPhase.Active }

        val active = s.phase as OnboardingPhase.Active
        assertEquals("d9", active.id)
        assertTrue(active.card is DownloadCardState.Downloading)
    }

    @Test
    fun notAllowedIsFlagged() = runTest(dispatcher) {
        fake.answer = { EnqueueResult.NotAllowed }
        val c = controller()
        c.download(reco)

        c.await("not allowed") { it.notAllowed }
        Unit
    }

    @Test
    fun aDoneRowTurnsTheCardIntoTheModelOnceItIsInTheLibrary() = runTest(dispatcher) {
        val c = controller()
        c.start()
        c.follow(reco)
        fake.queue.value = listOf(row("d1", reco, DownloadStatus.Downloading, done = 1_000))
        c.await("downloading") { it.phase is OnboardingPhase.Active }

        // Row done but the model is not in the list yet: still a full bar, never "done".
        fake.queue.value = listOf(row("d1", reco, DownloadStatus.Done, done = 1_234, modelId = "m7"))
        val waiting = c.await("done row") { it.queue.firstOrNull()?.status == DownloadStatus.Done }
        val bar = (waiting.phase as OnboardingPhase.Active).card as DownloadCardState.Downloading
        assertEquals(bar.totalBytes, bar.doneBytes)

        library.value = setOf("m7")
        val s = c.await("registered") { it.phase is OnboardingPhase.Done }
        assertEquals(OnboardingPhase.Done("m7"), s.phase)
    }

    @Test
    fun aFailedRowShowsItsReasonAndRetryAsksTheQueue() = runTest(dispatcher) {
        val c = controller()
        c.start()
        c.follow(reco)
        fake.queue.value = listOf(row("d1", reco, DownloadStatus.Failed, error = DownloadError.Network))

        val s = c.await("failed") { it.phase is OnboardingPhase.Active }
        val card = (s.phase as OnboardingPhase.Active).card as DownloadCardState.Failed
        assertEquals(DownloadError.Network, card.error)
        assertEquals(FailedPrimary.Retry, card.primary)

        c.retry("d1")
        until { fake.calls.isNotEmpty() }
        assertEquals("retry:d1:false", fake.calls.single())
    }

    @Test
    fun aRestoredScreenFindsItsRowAgainFromTheQueueByRepoAndFile() = runTest(dispatcher) {
        // Process death: a new controller, the saved key resolved to the entry, the row already there.
        fake.queue.value = listOf(
            row("x", other, DownloadStatus.Queued),
            row("d1", reco, DownloadStatus.Paused, done = 600),
        )
        val c = controller()
        c.start()
        c.follow(reco)

        val s = c.await("paused row") { it.phase is OnboardingPhase.Active }

        val active = s.phase as OnboardingPhase.Active
        assertEquals("d1", active.id)
        assertEquals(DownloadCardState.Paused(600, 1_234), active.card)
    }

    @Test
    fun theCatalogKeyIsRepoAndFile() {
        assertEquals("bartowski/Small-GGUF::Small-Q4_K_M.gguf", reco.key)
    }

    @Test
    fun startingAnotherEntryDropsTheFirstOnesDownload() = runTest(dispatcher) {
        fake.queue.value = listOf(row("d1", reco, DownloadStatus.Downloading, done = 100))
        val c = controller()
        c.start()
        c.download(reco)
        c.await("first row") { it.phase is OnboardingPhase.Active }
        // The first download is still resolving its lookup: let it finish before dropping it.
        advanceUntilIdle()
        fake.calls.clear()

        c.download(other)
        advanceUntilIdle()

        assertTrue(fake.calls.contains("cancel:d1"))
        assertTrue(fake.calls.any { it.startsWith("enqueue:") })
        assertEquals(other.key, c.state.value.target?.key)
    }

    @Test
    fun phaseIsIdleWithoutASelection() {
        assertEquals(
            OnboardingPhase.Idle,
            OnboardingDownloadLogic.phase(null, emptyList(), emptySet(), null, OnboardingLookup.None, 0),
        )
    }
}
