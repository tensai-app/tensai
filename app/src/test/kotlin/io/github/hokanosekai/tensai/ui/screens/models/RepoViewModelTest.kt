package io.github.hokanosekai.tensai.ui.screens.models

import io.github.hokanosekai.tensai.compat.DeviceProfile
import io.github.hokanosekai.tensai.download.DownloadError
import io.github.hokanosekai.tensai.download.DownloadItem
import io.github.hokanosekai.tensai.download.DownloadStatus
import io.github.hokanosekai.tensai.download.EnqueueResult
import io.github.hokanosekai.tensai.hf.HfError
import io.github.hokanosekai.tensai.hf.HfException
import io.github.hokanosekai.tensai.hf.HfGate
import io.github.hokanosekai.tensai.hf.HfQuantFile
import io.github.hokanosekai.tensai.hf.HfRepoDetail
import io.github.hokanosekai.tensai.hf.HfRepoSummary
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The repo screen's state holder against a fake Hub and a fake download queue (TEN-140): loading,
 * preselection, the button per download status, and what each enqueue answer does.
 */
class RepoViewModelTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private val gib = 1024L * 1024 * 1024
    private val mib = 1024L * 1024
    private val phone = DeviceProfile(
        totalRamBytes = 8 * gib,
        availableRamBytes = 4 * gib,
        socModel = "test",
        bigCoreCount = 4,
        hasVulkan = false,
        hasOpenCl = false,
    )
    private val sha = "a".repeat(40)

    private fun quant(label: String, sizeMib: Long) = HfQuantFile("m-$label.gguf", label, sizeMib * mib, "h-$label")

    private val q2 = quant("Q2_K", 700)
    private val q4 = quant("Q4_K_M", 1200)
    private val q8 = quant("Q8_0", 2100)
    private val huge = quant("F16", 40_000)

    private fun detail(gate: HfGate = HfGate.None, vararg quants: HfQuantFile) = HfRepoDetail(
        summary = HfRepoSummary("bartowski/Qwen3-GGUF", "bartowski", "Qwen3-GGUF", 1000, 10, null, "apache-2.0", gate),
        paramCount = 2_000_000_000,
        architecture = "qwen3",
        contextLength = 32_768,
        supportsTools = true,
        hasVision = false,
        quants = quants.sortedBy { it.sizeBytes },
        sha = sha,
    )

    private class FakeDownloads : RepoDownloads {
        val queue = MutableStateFlow<List<DownloadItem>>(emptyList())
        override val items = queue
        val calls = Collections.synchronizedList(mutableListOf<String>())
        var answer: (Boolean) -> EnqueueResult = { EnqueueResult.Enqueued("d1") }
        var enqueuedSha: String? = null
        var enqueuedFile: String? = null
        var enqueuedName: String? = null

        override suspend fun enqueue(repo: String, quant: HfQuantFile, displayName: String, confirmMobileData: Boolean, commitSha: String?): EnqueueResult {
            calls += "enqueue:$repo:${quant.fileName}:$confirmMobileData"
            enqueuedSha = commitSha
            enqueuedFile = quant.fileName
            enqueuedName = displayName
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

    private class Env(val downloads: FakeDownloads = FakeDownloads()) {
        val library = MutableStateFlow<List<LibraryFile>>(emptyList())
        var detail: suspend () -> HfRepoDetail = { error("no detail") }
        var readme: suspend () -> String = { throw HfException(HfError.NotFound) }
        val allowMobile = Collections.synchronizedList(mutableListOf<Boolean>())
        val hasToken = MutableStateFlow(false)
        var clock = 0L
    }

    private fun viewModel(env: Env, file: String? = null) = RepoViewModel(
        scope = scope,
        repoId = "bartowski/Qwen3-GGUF",
        preselectFile = file,
        detail = { env.detail() },
        readme = { env.readme() },
        profile = { phone },
        downloads = env.downloads,
        library = env.library,
        setAllowMobileData = { env.allowMobile += it },
        hasToken = env.hasToken,
        now = { env.clock },
    )

    private suspend fun RepoViewModel.await(what: String = "state", predicate: (RepoState) -> Boolean): RepoState =
        try {
            withTimeout(5_000) { state.first(predicate) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting for $what, last state: ${state.value}")
        }

    // For what the state does not show (calls into the fake queue): polls.
    private suspend fun until(condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) delay(5)
        }
    }

    private suspend fun loaded(env: Env, file: String? = null): RepoViewModel =
        viewModel(env, file).also {
            it.start()
            it.await { s -> s.loaded != null }
        }

    private fun item(
        status: DownloadStatus,
        id: String = "d1",
        file: String = q4.fileName,
        done: Long = 0,
        error: DownloadError? = null,
        size: Long = q4.sizeBytes,
    ) = DownloadItem(id, "bartowski/Qwen3-GGUF", file, "Q4_K_M", "Qwen3", size, null, status, done, error, null)

    private fun env(gate: HfGate = HfGate.None, quants: List<HfQuantFile> = listOf(q2, q4, q8, huge)) =
        Env().apply { detail = { detail(gate, *quants.toTypedArray()) } }

    // ---- loading

    @Test
    fun theQuantsAreListedSmallestFirstWithTheirVerdictAndTheRecommendedOneIsSelected() = runBlocking {
        val vm = loaded(env())

        val s = vm.state.value
        assertEquals(listOf("Q2_K", "Q4_K_M", "Q8_0", "F16"), s.rows.map { it.label })
        assertEquals(q4.fileName, s.selected)
        assertEquals(listOf(false, true, false, false), s.rows.map { it.recommended })
        assertEquals(q4.fileName, s.rows.single { it.selected }.quant.fileName)
        // The 40 GB file is listed (dimmed by the screen), not dropped.
        assertEquals(io.github.hokanosekai.tensai.compat.Verdict.WontFit, s.rows.last().verdict)
        assertEquals(RepoAction.Download(q4.sizeBytes), s.action)
    }

    @Test
    fun aLinkedFileIsPreselectedInsteadOfTheRecommendation() = runBlocking {
        val vm = loaded(env(), file = q8.fileName)

        assertEquals(q8.fileName, vm.state.value.selected)
        assertEquals(RepoAction.Download(q8.sizeBytes), vm.state.value.action)
    }

    @Test
    fun whenNothingFitsTheNearestIsSelectedButNotRecommended() = runBlocking {
        val vm = loaded(env(quants = listOf(huge, quant("F32", 80_000))))

        assertEquals(huge.fileName, vm.state.value.selected)
        assertTrue(vm.state.value.rows.none { it.recommended })
    }

    @Test
    fun selectingAnotherRowChangesTheButton() = runBlocking {
        val vm = loaded(env())

        vm.select(q8.fileName)

        assertEquals(q8.fileName, vm.state.value.rows.single { it.selected }.quant.fileName)
        assertEquals(RepoAction.Download(q8.sizeBytes), vm.state.value.action)
    }

    @Test
    fun theReadmeIsFetchedAfterTheDetailAndCleaned() = runBlocking {
        val e = env().apply { readme = { "---\nlicense: mit\n---\n# Hello" } }
        val vm = loaded(e)

        val s = vm.await { it.readme !is ReadmeState.Loading }

        assertEquals(ReadmeState.Loaded("# Hello"), s.readme)
        assertFalse(s.readmeExpanded)
        vm.toggleReadme()
        assertTrue(vm.state.value.readmeExpanded)
    }

    @Test
    fun aMissingReadmeLeavesTheSectionOutWithoutFailingTheScreen() = runBlocking {
        val vm = loaded(env())

        val s = vm.await { it.readme !is ReadmeState.Loading }

        assertEquals(ReadmeState.Unavailable, s.readme)
        assertNotNull(s.loaded)
    }

    @Test
    fun hubFailuresAreReportedAndTryAgainLoadsAgain() = runBlocking {
        var online = false
        val e = env().apply {
            detail = { if (online) detail(HfGate.None, q4) else throw HfException(HfError.Offline) }
        }
        val vm = viewModel(e)
        vm.start()
        assertEquals(RepoLoad.Failed(HfError.Offline), vm.await { it.load is RepoLoad.Failed }.load)

        online = true
        vm.load()
        assertNotNull(vm.await { it.loaded != null }.loaded)
    }

    @Test
    fun notFoundGatedAndInvalidTokenAreReportedAsTheyCame() = runBlocking {
        for (error in listOf(HfError.NotFound, HfError.Gated(401), HfError.InvalidToken, HfError.RateLimited(30))) {
            val e = env().apply { detail = { throw HfException(error) } }
            val vm = viewModel(e)
            vm.start()
            assertEquals(RepoLoad.Failed(error), vm.await { it.load is RepoLoad.Failed }.load)
        }
    }

    // ---- on device

    @Test
    fun aFileTheLibraryHasIsOnDeviceAndTheButtonLoadsIt() = runBlocking {
        val e = env()
        val vm = loaded(e)

        e.library.value = listOf(LibraryFile("m1", q4.fileName, q4.sizeBytes))
        val s = vm.await { it.library.isNotEmpty() }

        assertEquals(listOf(false, true, false, false), s.rows.map { it.onDevice })
        assertEquals(RepoAction.Load("m1"), s.action)
        // Another row is still a download.
        vm.select(q8.fileName)
        assertEquals(RepoAction.Download(q8.sizeBytes), vm.state.value.action)
    }

    // ---- the button per download status

    @Test
    fun theButtonFollowsTheQueueForTheSelectedFile() = runBlocking {
        val e = env()
        val vm = loaded(e)

        e.downloads.queue.value = listOf(item(DownloadStatus.Queued))
        assertEquals(RepoAction.Queued("d1", 0), vm.await { it.queue.isNotEmpty() }.action)

        e.downloads.queue.value = listOf(item(DownloadStatus.Downloading, done = 300 * mib))
        assertEquals(
            RepoAction.Downloading("d1", 300 * mib, q4.sizeBytes, 0, null),
            vm.await { it.queue.firstOrNull()?.status == DownloadStatus.Downloading }.action,
        )

        e.downloads.queue.value = listOf(item(DownloadStatus.Paused, done = 300 * mib))
        assertEquals(RepoAction.Paused("d1", 300 * mib, q4.sizeBytes), vm.await { it.queue.firstOrNull()?.status == DownloadStatus.Paused }.action)

        e.downloads.queue.value = listOf(item(DownloadStatus.Failed, error = DownloadError.Network))
        assertEquals(
            RepoAction.Failed("d1", DownloadError.Network),
            vm.await { it.queue.firstOrNull()?.status == DownloadStatus.Failed }.action,
        )

        e.downloads.queue.value = emptyList()
        assertEquals(RepoAction.Download(q4.sizeBytes), vm.await { it.queue.isEmpty() }.action)
    }

    @Test
    fun aDownloadOfAnotherFileDoesNotChangeTheSelectedFilesButton() = runBlocking {
        val e = env()
        val vm = loaded(e)

        e.downloads.queue.value = listOf(item(DownloadStatus.Downloading, id = "x", file = q8.fileName, done = 5))
        vm.await { it.queue.isNotEmpty() }

        assertEquals(RepoAction.Download(q4.sizeBytes), vm.state.value.action)
        vm.select(q8.fileName)
        assertTrue(vm.state.value.action is RepoAction.Downloading)
    }

    @Test
    fun aQueuedFileCountsWhatIsAheadOfIt() = runBlocking {
        val e = env()
        val vm = loaded(e)

        e.downloads.queue.value = listOf(
            item(DownloadStatus.Downloading, id = "x", file = q8.fileName),
            item(DownloadStatus.Queued, id = "y", file = q2.fileName),
            item(DownloadStatus.Queued, id = "d1"),
        )

        assertEquals(RepoAction.Queued("d1", 2), vm.await { it.queue.size == 3 }.action)
    }

    @Test
    fun speedAndTimeLeftComeFromSuccessiveProgressReadings() = runBlocking {
        val e = env()
        val vm = loaded(e)

        e.clock = 0
        e.downloads.queue.value = listOf(item(DownloadStatus.Downloading, done = 0))
        vm.await { it.queue.isNotEmpty() }
        // The first jump only re-bases the meter (a first reading can be stale).
        e.clock = 500
        e.downloads.queue.value = listOf(item(DownloadStatus.Downloading, done = 50 * mib))
        vm.await { (it.queue.singleOrNull()?.bytesDone ?: 0) == 50 * mib }
        e.clock = 1_000
        e.downloads.queue.value = listOf(item(DownloadStatus.Downloading, done = 100 * mib))
        val s = vm.await { it.bytesPerSecond > 0 }

        assertEquals(100 * mib, s.bytesPerSecond)
        val action = s.action as RepoAction.Downloading
        assertEquals((q4.sizeBytes - 100 * mib + 100 * mib - 1) / (100 * mib), action.etaSeconds)
    }

    @Test
    fun aGatedFailureAndAnInvalidTokenShowTheTokenCard() = runBlocking {
        val e = env()
        val vm = loaded(e)

        e.downloads.queue.value = listOf(item(DownloadStatus.Failed, error = DownloadError.Gated))
        assertEquals(RepoAction.NeedsToken(false), vm.await { it.queue.isNotEmpty() }.action)

        e.downloads.queue.value = listOf(item(DownloadStatus.Failed, error = DownloadError.InvalidToken))
        assertEquals(RepoAction.NeedsToken(true), vm.await { it.queue.firstOrNull()?.error == DownloadError.InvalidToken }.action)
    }

    @Test
    fun aGatedRepoWithoutATokenAsksForOneBeforeAnyDownloadAndWithOneItDownloads() = runBlocking {
        val locked = loaded(env(HfGate.Auto))
        assertEquals(RepoAction.NeedsToken(false), locked.state.value.action)

        val e = env(HfGate.Auto).apply { hasToken.value = true }
        assertEquals(RepoAction.Download(q4.sizeBytes), loaded(e).state.value.action)
    }

    @Test
    fun aGatedFailureWithATokenAsksToAcceptTheLicenseInsteadOfAskingForAToken() = runBlocking {
        val e = env().apply { hasToken.value = true }
        val vm = loaded(e)
        vm.await { it.hasToken }

        e.downloads.queue.value = listOf(item(DownloadStatus.Failed, id = "d9", error = DownloadError.LicenseNotAccepted))
        assertEquals(RepoAction.NeedsLicense("d9"), vm.await { it.queue.isNotEmpty() }.action)

        // No token and refused anonymously: back to asking for one.
        e.hasToken.value = false
        e.downloads.queue.value = listOf(item(DownloadStatus.Failed, id = "d9", error = DownloadError.Gated))
        assertEquals(
            RepoAction.NeedsToken(false),
            vm.await { !it.hasToken && it.queue.firstOrNull()?.error == DownloadError.Gated }.action,
        )
    }

    @Test
    fun aTokenAddedInSettingsUnlocksAGatedRepoWithoutReloading() = runBlocking {
        val e = env(HfGate.Auto)
        val vm = loaded(e)
        assertEquals(RepoAction.NeedsToken(false), vm.state.value.action)

        e.hasToken.value = true
        assertEquals(RepoAction.Download(q4.sizeBytes), vm.await { it.hasToken }.action)
    }

    // ---- taps and enqueue answers

    @Test
    fun downloadEnqueuesTheSelectedFileWithTheCommitShaAndTheRepoName() = runBlocking {
        val e = env()
        val vm = loaded(e, file = q8.fileName)

        vm.download()
        until { e.downloads.calls.isNotEmpty()  }

        assertEquals(listOf("enqueue:bartowski/Qwen3-GGUF:${q8.fileName}:false"), e.downloads.calls.toList())
        assertEquals(sha, e.downloads.enqueuedSha)
        assertEquals("Qwen3", e.downloads.enqueuedName)
        assertNull(vm.state.value.dialog)
    }

    @Test
    fun theMobileDataAnswerOpensTheDialogAndConfirmingEnqueuesAgainWithConfirmation() = runBlocking {
        val e = env()
        e.downloads.answer = { confirmed -> if (confirmed) EnqueueResult.Enqueued("d1") else EnqueueResult.NeedsMobileDataConfirmation(q4.sizeBytes) }
        val vm = loaded(e)

        vm.download()
        val s = vm.await { it.dialog != null }

        assertEquals(RepoDialog.MobileData(q4.sizeBytes, RepoOp.Download(q4.fileName)), s.dialog)
        vm.confirmMobileData(always = false)
        until { e.downloads.calls.size == 2  }

        assertEquals("enqueue:bartowski/Qwen3-GGUF:${q4.fileName}:true", e.downloads.calls.last())
        assertTrue(e.allowMobile.isEmpty())
        assertNull(vm.state.value.dialog)
    }

    @Test
    fun alwaysAllowSetsThePreferenceBeforeEnqueueing() = runBlocking {
        val e = env()
        e.downloads.answer = { confirmed -> if (confirmed) EnqueueResult.Enqueued("d1") else EnqueueResult.NeedsMobileDataConfirmation(q4.sizeBytes) }
        val vm = loaded(e)
        vm.download()
        vm.await { it.dialog != null }

        vm.confirmMobileData(always = true)
        until { e.downloads.calls.size == 2 }

        assertEquals(listOf(true), e.allowMobile.toList())
    }

    @Test
    fun cancellingTheMobileDataDialogStartsNothing() = runBlocking {
        val e = env()
        e.downloads.answer = { EnqueueResult.NeedsMobileDataConfirmation(q4.sizeBytes) }
        val vm = loaded(e)
        vm.download()
        vm.await { it.dialog != null }

        vm.dismissDialog()
        delay(100)

        assertNull(vm.state.value.dialog)
        assertEquals(1, e.downloads.calls.size)
    }

    @Test
    fun resumingOnMobileDataAsksToo() = runBlocking {
        val e = env()
        e.downloads.answer = { confirmed -> if (confirmed) EnqueueResult.Enqueued("d1") else EnqueueResult.NeedsMobileDataConfirmation(5 * mib) }
        val vm = loaded(e)

        vm.resume("d1")
        assertEquals(RepoDialog.MobileData(5 * mib, RepoOp.Resume("d1")), vm.await { it.dialog != null }.dialog)
        vm.confirmMobileData(always = false)
        until { e.downloads.calls.size == 2 }

        assertEquals(listOf("resume:d1:false", "resume:d1:true"), e.downloads.calls.toList())
    }

    @Test
    fun notEnoughStorageOpensTheDialogWithNeededAndFree() = runBlocking {
        val e = env()
        e.downloads.answer = { EnqueueResult.NotEnoughStorage(3 * gib, 1 * gib) }
        val vm = loaded(e)

        vm.download()

        assertEquals(RepoDialog.Storage(3 * gib, 1 * gib), vm.await { it.dialog != null }.dialog)
        vm.dismissDialog()
        assertNull(vm.state.value.dialog)
    }

    @Test
    fun alreadyOnDeviceSwitchesTheButtonToLoadAtOnce() = runBlocking {
        val e = env()
        e.downloads.answer = { EnqueueResult.AlreadyOnDevice("m9") }
        val vm = loaded(e)

        vm.download()

        assertEquals(RepoAction.Load("m9"), vm.await { it.knownOnDevice.isNotEmpty() }.action)
        assertTrue(vm.state.value.rows.single { it.selected }.onDevice)
    }

    @Test
    fun alreadyQueuedChangesNothingAndTheQueueShowsTheItem() = runBlocking {
        val e = env()
        e.downloads.answer = { EnqueueResult.AlreadyQueued("d1") }
        val vm = loaded(e)

        vm.download()
        until { e.downloads.calls.isNotEmpty() }
        delay(50)

        assertNull(vm.state.value.dialog)
        assertFalse(vm.state.value.notAllowed)
        assertEquals(RepoAction.Download(q4.sizeBytes), vm.state.value.action)
    }

    @Test
    fun aRefusedFileIsFlaggedUntilTheNextTapOrSelection() = runBlocking {
        val e = env()
        e.downloads.answer = { EnqueueResult.NotAllowed }
        val vm = loaded(e)

        vm.download()
        assertTrue(vm.await { it.notAllowed }.notAllowed)

        vm.select(q8.fileName)
        assertFalse(vm.state.value.notAllowed)
    }

    @Test
    fun pauseCancelAndRetryGoToTheQueue() = runBlocking {
        val e = env()
        val vm = loaded(e)

        vm.pause("d1")
        vm.cancel("d1")
        vm.retry("d1")
        until { e.downloads.calls.size == 3 }

        assertEquals(setOf("pause:d1", "cancel:d1", "retry:d1:false"), e.downloads.calls.toSet())
    }

    @Test
    fun startingTwiceLoadsOnce() = runBlocking {
        var calls = 0
        val e = env().apply { detail = { calls++; detail(HfGate.None, q4) } }
        val vm = viewModel(e)

        vm.start()
        vm.await { it.loaded != null }
        vm.start()
        delay(50)

        assertEquals(1, calls)
    }

    @Test
    fun anAlreadyOnDeviceAnswerBelongsToTheFileTappedNotTheCurrentSelection() = runBlocking {
        val e = env()
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        e.downloads.answer = { EnqueueResult.AlreadyOnDevice("m9") }
        val slow = object : RepoDownloads by e.downloads {
            override suspend fun enqueue(repo: String, quant: HfQuantFile, displayName: String, confirmMobileData: Boolean, commitSha: String?): EnqueueResult {
                gate.await()
                return e.downloads.enqueue(repo, quant, displayName, confirmMobileData, commitSha)
            }
        }
        val vm = RepoViewModel(
            scope = scope, repoId = "bartowski/Qwen3-GGUF", detail = { e.detail() }, readme = { e.readme() },
            profile = { phone }, downloads = slow, library = e.library, setAllowMobileData = {}, hasToken = e.hasToken, now = { 0 },
        )
        vm.start()
        vm.await { it.loaded != null }
        vm.select(q4.fileName)
        vm.download()
        vm.select(q8.fileName)
        gate.complete(Unit)

        val s = vm.await { it.knownOnDevice.isNotEmpty() }

        assertEquals(mapOf(q4.fileName to "m9"), s.knownOnDevice)
        assertEquals(q8.fileName, s.selected)
        assertEquals(RepoAction.Download(q8.sizeBytes), s.action)
    }
}
