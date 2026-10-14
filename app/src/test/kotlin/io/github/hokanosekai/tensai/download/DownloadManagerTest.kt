package io.github.hokanosekai.tensai.download

import io.github.hokanosekai.tensai.hf.HfQuantFile
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** [DownloadManager]: the enqueue decision matrix, storage, privacy, queue order, state changes. */
class DownloadManagerTest {

    private class RecordingScheduler : DownloadScheduler {
        var ensured = 0
        val rests = mutableListOf<Pair<Long?, Boolean>>()
        override fun ensureDrainer() {
            ensured++
        }

        override fun restDrainer(wakeAfterMs: Long?, wakeOnUnmetered: Boolean) {
            rests += wakeAfterMs to wakeOnUnmetered
        }
    }

    private lateinit var dir: File
    private lateinit var dao: FakeDownloadDao
    private val scheduler = RecordingScheduler()

    private var metered = false
    private var allowMobile = false
    private var free = 100L * GB
    private var now = 1_000L
    private var ids = 0

    private val file = HfQuantFile("model-Q4_K_M.gguf", "Q4_K_M", sizeBytes = 2L * GB, sha256 = "ab".repeat(32))

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("tensai-manager-test").toFile()
        dao = FakeDownloadDao()
    }

    @After
    fun tearDown() = dir.deleteRecursively().let { }

    private var installed: String? = null
    private val registry = DrainerRegistry()
    private val urls = mutableListOf<String?>()

    private fun manager(hostAllowed: (okhttp3.HttpUrl) -> Boolean = ::isHuggingFaceUrl) = DownloadManager(
        dao = dao,
        scheduler = scheduler,
        urlFor = { repo, sha, name ->
            urls += sha
            "https://huggingface.co/$repo/resolve/${sha ?: "main"}/$name"
        },
        partFor = { File(dir, "$it.part") },
        listParts = { dir.listFiles().orEmpty().toList() },
        freeBytes = { free },
        isMetered = { metered },
        allowMobileData = { allowMobile },
        findInstalled = { _, _ -> installed },
        registry = registry,
        hostAllowed = hostAllowed,
        clock = { now++ },
        newId = { "id${++ids}" },
    )

    private fun enqueue(m: DownloadManager, name: String = "model-Q4_K_M.gguf", confirm: Boolean = false, size: Long = 2L * GB) =
        runBlocking { m.enqueue("org/repo", file.copy(fileName = name, sizeBytes = size), "Model", confirm) }

    // ---- network decision matrix ----

    @Test
    fun enqueueDecisionMatrix() {
        data class Case(val metered: Boolean, val pref: Boolean, val confirm: Boolean, val expected: NetworkPolicy?)
        listOf(
            Case(metered = false, pref = false, confirm = false, expected = NetworkPolicy.Unmetered),
            Case(metered = false, pref = true, confirm = false, expected = NetworkPolicy.Connected),
            Case(metered = false, pref = false, confirm = true, expected = NetworkPolicy.Connected),
            Case(metered = true, pref = false, confirm = false, expected = null),
            Case(metered = true, pref = true, confirm = false, expected = NetworkPolicy.Connected),
            Case(metered = true, pref = false, confirm = true, expected = NetworkPolicy.Connected),
            Case(metered = true, pref = true, confirm = true, expected = NetworkPolicy.Connected),
        ).forEach { c ->
            assertEquals("$c", c.expected, networkPolicyFor(c.metered, c.pref, c.confirm))
        }
    }

    @Test
    fun meteredWithoutPrefOrConfirmationAsksAndStartsNothing() {
        metered = true

        val result = enqueue(manager())

        assertEquals(EnqueueResult.NeedsMobileDataConfirmation(2L * GB), result)
        assertTrue("no row, no work", dao.rows.isEmpty() && scheduler.ensured == 0)
    }

    @Test
    fun confirmingOnMeteredEnqueuesWithAnyNetwork() {
        metered = true

        val result = enqueue(manager(), confirm = true)

        assertEquals(EnqueueResult.Enqueued("id1"), result)
        assertEquals(1, scheduler.ensured)
        assertTrue("a mobile-data row", dao.row("id1")!!.allowMobile)
    }

    @Test
    fun unmeteredByDefaultWaitsForWifiAndThePrefLiftsIt() {
        enqueue(manager(), "a.gguf")
        allowMobile = true
        enqueue(manager(), "b.gguf")

        assertEquals(listOf(false, true), dao.rows.map { it.allowMobile })
    }

    // ---- storage ----

    @Test
    fun notEnoughStorageReportsNeededAndFreeAndStartsNothing() {
        free = 2L * GB + 100L * MB // the file fits, the 200 MB margin does not

        val result = enqueue(manager())

        assertEquals(EnqueueResult.NotEnoughStorage(neededBytes = 2L * GB + 200L * MB, freeBytes = free), result)
        assertTrue(dao.rows.isEmpty() && scheduler.ensured == 0)
    }

    @Test
    fun exactlyEnoughWithMarginIsAccepted() {
        free = 2L * GB + 200L * MB

        assertTrue(enqueue(manager()) is EnqueueResult.Enqueued)
    }

    @Test
    fun whatTheQueueAlreadyOwesCountsAgainstTheFreeSpace() {
        free = 3L * GB // room for one 2 GB file plus margin, not for two
        val m = manager()

        assertTrue(enqueue(m, "a.gguf") is EnqueueResult.Enqueued)
        val second = enqueue(m, "b.gguf")

        assertTrue("$second", second is EnqueueResult.NotEnoughStorage)
        assertEquals(1, dao.rows.size)
    }

    // ---- privacy ----

    @Test
    fun onlyHuggingFaceHostsAreAccepted() {
        val urlHosts = listOf(
            "https://huggingface.co/a/b/resolve/main/x.gguf" to true,
            "https://cdn-lfs.huggingface.co/x" to true,
            "http://huggingface.co/a/b" to false,
            "https://evilhuggingface.co/a/b" to false,
            "https://huggingface.co.evil.com/a/b" to false,
            "https://example.com/model.gguf" to false,
        )
        urlHosts.forEach { (url, ok) ->
            assertEquals(url, ok, isHuggingFaceUrl(url.toHttpUrl()))
        }
    }

    @Test
    fun aNonHuggingFaceFileIsRefusedAtEnqueue() {
        val m = DownloadManager(
            dao = dao, scheduler = scheduler,
            urlFor = { _, _, name -> "https://example.com/$name" },
            partFor = { File(dir, it) }, listParts = { emptyList() }, freeBytes = { free }, isMetered = { false }, allowMobileData = { false },
        )

        assertEquals(EnqueueResult.NotAllowed, runBlocking { m.enqueue("org/repo", file, "Model") })
        assertTrue(dao.rows.isEmpty() && scheduler.ensured == 0)
    }

    @Test
    fun anUnusableFileSizeIsRefused() {
        assertEquals(EnqueueResult.NotAllowed, enqueue(manager(), size = 0))
    }

    // ---- queue ----

    @Test
    fun theSameFileTwiceIsNotQueuedTwice() {
        val m = manager()
        val first = enqueue(m) as EnqueueResult.Enqueued

        assertEquals(EnqueueResult.AlreadyQueued(first.id), enqueue(m))
        assertEquals(1, scheduler.ensured)
    }

    @Test
    fun downloadsAreQueuedFifoAndResumeGoesToTheBack() = runBlocking {
        val m = manager()
        enqueue(m, "a.gguf")
        enqueue(m, "b.gguf")
        enqueue(m, "c.gguf")
        assertEquals(listOf("a.gguf", "b.gguf", "c.gguf"), m.downloads.first().map { it.fileName })

        m.pause("id1")
        m.resume("id1")

        assertEquals("every enqueue and the resume ensured a drainer", 4, scheduler.ensured)
        assertEquals(listOf("b.gguf", "c.gguf", "a.gguf"), m.downloads.first().map { it.fileName })
    }

    // ---- state changes ----

    private suspend fun status(id: String) = dao.row(id)!!.status

    @Test
    fun pauseKeepsThePartialAndResumeRequeuesIt() = runBlocking {
        val m = manager()
        enqueue(m)
        File(dir, "id1.part").writeBytes(ByteArray(1000))
        dao.transition("id1", listOf("queued"), "downloading", null, null)

        m.pause("id1")
        assertEquals("paused", status("id1"))
        assertTrue(File(dir, "id1.part").exists())

        assertEquals(EnqueueResult.Enqueued("id1"), m.resume("id1"))
        assertEquals("queued", status("id1"))
    }

    @Test
    fun cancelDeletesTheRowAndThePartialWhateverTheState() = runBlocking {
        val m = manager()
        enqueue(m)
        File(dir, "id1.part").writeBytes(ByteArray(1000))
        dao.transition("id1", listOf("queued"), "downloading", null, null)

        m.cancel("id1")

        assertTrue(dao.rows.isEmpty())
        assertFalse(File(dir, "id1.part").exists())
    }

    @Test
    fun retryOnlyAppliesToFailedAndResumeOnlyToPaused() = runBlocking {
        val m = manager()
        enqueue(m)

        assertEquals("queued rows are neither", EnqueueResult.NotFound, m.resume("id1"))
        assertEquals(EnqueueResult.NotFound, m.retry("id1"))
        assertEquals(EnqueueResult.NotFound, m.retry("ghost"))

        dao.transition("id1", listOf("queued"), "failed", "disk_full", null)
        assertEquals(EnqueueResult.NotFound, m.resume("id1"))
        assertEquals(EnqueueResult.Enqueued("id1"), m.retry("id1"))
        assertEquals("queued", status("id1"))
        assertEquals(null, dao.row("id1")!!.error)
    }

    @Test
    fun retryChecksStorageForWhatIsLeftNotTheWholeFile() = runBlocking {
        val m = manager()
        enqueue(m)
        File(dir, "id1.part").also { f -> f.outputStream().use { it.channel.truncate(0) } }
        dao.transition("id1", listOf("queued"), "failed", "disk_full", null)
        free = 1L * GB // nowhere near 2 GB + margin

        val result = m.retry("id1")

        assertTrue("$result", result is EnqueueResult.NotEnoughStorage)
        assertEquals("a refused retry leaves it failed", "failed", status("id1"))
    }

    @Test
    fun resumeOnMeteredAsksForConfirmationToo() = runBlocking {
        val m = manager()
        enqueue(m)
        m.pause("id1")
        metered = true

        assertTrue(m.resume("id1") is EnqueueResult.NeedsMobileDataConfirmation)
        assertEquals("paused", status("id1"))
        assertEquals(EnqueueResult.Enqueued("id1"), m.resume("id1", confirmMobileData = true))
    }

    @Test
    fun aFailedAttemptWithADifferentSizeIsReplacedNotStacked() = runBlocking {
        val m = manager()
        enqueue(m)
        File(dir, "id1.part").writeBytes(ByteArray(10))
        dao.transition("id1", listOf("queued"), "failed", "checksum", null)

        assertEquals(EnqueueResult.Enqueued("id2"), enqueue(m, size = 3L * GB))

        assertEquals(listOf("id2"), dao.rows.map { it.id })
        assertFalse("the dead attempt's bytes went with it", File(dir, "id1.part").exists())
    }

    // ---- dedup, pinned URL, concurrency ----

    @Test
    fun aFileAlreadyInTheLibraryIsRefused() {
        installed = "model-7"

        assertEquals(EnqueueResult.AlreadyOnDevice("model-7"), enqueue(manager()))
        assertTrue(dao.rows.isEmpty() && scheduler.ensured == 0)
    }

    @Test
    fun twoSimultaneousTapsQueueOnlyOne() = runBlocking {
        val m = manager()

        val results = (1..8).map { async(kotlinx.coroutines.Dispatchers.Default) { m.enqueue("org/repo", file, "Model") } }
            .map { it.await() }

        assertEquals(1, results.count { it is EnqueueResult.Enqueued })
        assertEquals(7, results.count { it is EnqueueResult.AlreadyQueued })
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun theCommitIsPinnedInTheRowAndInTheUrlOnlyWhenItIsAValidSha() = runBlocking {
        val m = manager()
        val sha = "0123456789abcdef0123456789abcdef01234567"

        m.enqueue("org/repo", file.copy(fileName = "a.gguf"), "A", commitSha = sha)
        m.enqueue("org/repo", file.copy(fileName = "b.gguf"), "B", commitSha = "main")
        m.enqueue("org/repo", file.copy(fileName = "c.gguf"), "C")

        assertEquals(listOf<String?>(sha, null, null), dao.rows.map { it.commitSha })
        assertEquals(sha, urls.first())
    }

    @Test
    fun publicCallsRunOffTheCallersThread() = runBlocking {
        var thread: String? = null
        val m = DownloadManager(
            dao = dao, scheduler = scheduler,
            urlFor = { repo, sha, name ->
                thread = Thread.currentThread().name
                "https://huggingface.co/$repo/resolve/main/$name"
            },
            partFor = { File(dir, it) }, listParts = { emptyList() }, freeBytes = { free },
            isMetered = { false }, allowMobileData = { false },
        )

        m.enqueue("org/repo", file, "Model")

        assertTrue("ran on $thread", thread!!.contains("DefaultDispatcher"))
    }

    // ---- reconcile ----

    @Test
    fun reconcileRequeuesStuckRowsDeletesOrphanPartsAndEnsuresADrainer() = runBlocking {
        val m = manager()
        enqueue(m, "a.gguf")
        enqueue(m, "b.gguf")
        dao.transition("id1", listOf("queued"), "downloading", null, null)
        dao.transition("id2", listOf("queued"), "paused", null, null)
        File(dir, "id1.part").writeBytes(ByteArray(5))
        File(dir, "id2.part").writeBytes(ByteArray(5))
        File(dir, "ghost.part").writeBytes(ByteArray(5))
        File(dir, "model.gguf").writeBytes(ByteArray(5))
        val before = scheduler.ensured

        m.reconcile()

        assertEquals("queued", status("id1"))
        assertEquals("paused", status("id2"))
        assertTrue(File(dir, "id1.part").exists() && File(dir, "id2.part").exists())
        assertFalse("no row references it", File(dir, "ghost.part").exists())
        assertTrue("only .part files are ever touched", File(dir, "model.gguf").exists())
        assertEquals(before + 1, scheduler.ensured)
    }

    @Test
    fun reconcileLeavesRowsOfARunningDrainerAlone() = runBlocking {
        val m = manager()
        enqueue(m)
        dao.transition("id1", listOf("queued"), "downloading", null, null)
        registry.begin()

        m.reconcile()

        assertEquals("downloading", status("id1"))
    }

    @Test
    fun reconcileWithNothingWaitingStartsNothing() = runBlocking {
        val m = manager()
        enqueue(m)
        m.pause("id1")
        val before = scheduler.ensured

        m.reconcile()

        assertEquals(before, scheduler.ensured)
    }

    @Test
    fun reconcileWithOnlyBackoffOrWifiRowsWakesInsteadOfStartingADrainer() = runBlocking {
        val m = manager()
        enqueue(m, "a.gguf")
        enqueue(m, "b.gguf")
        val before = scheduler.ensured
        dao.scheduleRetry("id1", 2, now + 60_000)
        metered = true // id2 is a Wi-Fi-only row

        m.reconcile()

        assertEquals("nothing could run, so no drainer and no foreground service", before, scheduler.ensured)
        assertEquals(listOf<Pair<Long?, Boolean>>(DownloadQueue.MIN_REST_MS to true).size, scheduler.rests.size)
        assertTrue("wake on Wi-Fi", scheduler.rests.single().second)
    }

    @Test
    fun reconcileNeverResetsRowsWhileAnyDrainGenerationIsActive() = runBlocking {
        val m = manager()
        enqueue(m)
        dao.transition("id1", listOf("queued"), "downloading", null, null)
        val old = registry.begin()
        val newer = registry.begin()
        registry.end(old) // the old drainer finishing must not release the newer one

        m.reconcile()
        assertEquals("downloading", status("id1"))

        registry.end(newer)
        m.reconcile()
        assertEquals("queued", status("id1"))
    }

    @Test
    fun enqueueingAFailedFileResumesItsPartInsteadOfDeletingIt() = runBlocking {
        val m = manager()
        enqueue(m)
        File(dir, "id1.part").writeBytes(ByteArray(1234))
        dao.transition("id1", listOf("queued"), "failed", "disk_full", null)
        dao.scheduleRetry("id1", 3, now + 99)
        dao.transition("id1", listOf("queued"), "failed", "disk_full", null)

        val result = enqueue(m)

        assertEquals("the same row, not a new one", EnqueueResult.Enqueued("id1"), result)
        assertEquals(1234L, File(dir, "id1.part").length())
        val row = dao.row("id1")!!
        assertEquals("queued", row.status)
        assertEquals(0, row.ioRetries)
        assertEquals(0L, row.notBefore)
        assertEquals(null, row.error)
    }

    @Test
    fun aFailedPartForAnotherCommitOrSizeIsNotReused() = runBlocking {
        val m = manager()
        val sha = "0123456789abcdef0123456789abcdef01234567"
        m.enqueue("org/repo", file, "Model", commitSha = sha)
        File(dir, "id1.part").writeBytes(ByteArray(10))
        dao.transition("id1", listOf("queued"), "failed", "network", null)

        val other = m.enqueue("org/repo", file, "Model", commitSha = null)

        assertEquals(EnqueueResult.Enqueued("id2"), other)
        assertFalse(File(dir, "id1.part").exists())
        assertEquals(listOf("id2"), dao.rows.map { it.id })
    }

    @Test
    fun resumeAndRetryChangeEverythingInOneWrite() = runBlocking {
        val m = manager()
        enqueue(m)
        dao.scheduleRetry("id1", 5, now + 10_000)
        dao.transition("id1", listOf("queued"), "failed", "network", null)
        allowMobile = true

        assertEquals(EnqueueResult.Enqueued("id1"), m.retry("id1"))

        val row = dao.row("id1")!!
        assertEquals(listOf("queued", 0, 0L, true, null), listOf(row.status, row.ioRetries, row.notBefore, row.allowMobile, row.error))
    }

    private companion object {
        const val MB = 1024L * 1024
        const val GB = 1024L * MB
    }
}
