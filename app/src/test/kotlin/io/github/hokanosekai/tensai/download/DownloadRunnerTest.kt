package io.github.hokanosekai.tensai.download

import io.github.hokanosekai.tensai.data.models.DuplicateModelException
import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.data.models.RegisteredModel
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** [DownloadRunner]: row states around the engine, pause/cancel detection, finalisation. */
class DownloadRunnerTest {

    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private lateinit var dao: FakeDownloadDao

    private val content = ByteArray(2_000_000) { (it % 253).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
    private var finalized = mutableListOf<Pair<File, DownloadEntity>>()
    private var finalizeFailure: Throwable? = null

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        dir = Files.createTempDirectory("tensai-runner-test").toFile()
        dao = FakeDownloadDao()
        serve(200)
    }

    @After
    fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun serve(code: Int, body: ByteArray = content, throttle: Boolean = false) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(code).apply {
                if (code == 200) {
                    setBody(Buffer().write(body))
                    if (throttle) throttleBody(256 * 1024, 50, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
            }
        }
    }

    private fun part(id: String) = File(dir, "$id.part")

    private var now = 10_000L
    private val urls = mutableListOf<String?>()
    private val unregistered = mutableListOf<RegisteredModel>()
    private var previous: ModelEntity? = null
    private var duringFinalize: suspend () -> Unit = {}

    private fun runner(maxIoRetries: Int = 8) = DownloadRunner(
        dao = dao,
        engine = DownloadEngine(OkHttpClient(), freeBytes = { Long.MAX_VALUE / 2 }, progressIntervalMs = 0),
        urlFor = { _, sha, _ ->
            urls += sha
            server.url("/f.gguf").toString()
        },
        partFor = ::part,
        finalize = { file, row ->
            finalizeFailure?.let { throw it }
            finalized += file to row
            duringFinalize()
            RegisteredModel(model("model-of-${row.id}"), previous)
        },
        unregister = { unregistered += it },
        clock = { now },
        persistEveryMs = 0,
        maxIoRetries = maxIoRetries,
    )

    private fun model(id: String) = ModelEntity(
        id = id, displayName = "m", fileName = "m.gguf", filePath = "/x", sizeBytes = 1, architecture = "a",
        quantLabel = "Q4_K_M", paramCount = 1, contextLength = 1, embeddingLength = 1, blockCount = 1,
        supportsThinking = false, importedAt = 0,
    )

    private suspend fun queue(
        id: String = "d1",
        status: String = "queued",
        sha256: String? = sha,
        at: Long = 1,
        ioRetries: Int = 0,
        commitSha: String? = null,
    ) {
        dao.insert(
            DownloadEntity(
                id = id, repo = "org/repo", fileName = "f.gguf", quant = "Q4_K_M", displayName = "Model",
                sizeBytes = content.size.toLong(), sha256 = sha256, status = status, bytesDone = 0, error = null,
                queuedAt = at, modelId = null, ioRetries = ioRetries, commitSha = commitSha,
            ),
        )
    }

    @Test
    fun aGoodDownloadIsFinalizedAndMarkedDone() = runBlocking {
        queue()

        val outcome = runner().run("d1")

        assertTrue(outcome is RunOutcome.Done)
        val row = dao.row("d1")!!
        assertEquals("done", row.status)
        assertEquals("model-of-d1", row.modelId)
        assertEquals(content.size.toLong(), row.bytesDone)
        assertEquals("the pipeline got the finished part", part("d1"), finalized.single().first)
    }

    @Test
    fun aChecksumMismatchFailsTheRowAndDeletesTheFile() = runBlocking {
        queue(sha256 = "f".repeat(64))

        val outcome = runner().run("d1")

        assertEquals(RunOutcome.Failed(DownloadError.ChecksumMismatch), outcome)
        assertEquals("failed", dao.row("d1")!!.status)
        assertEquals("checksum", dao.row("d1")!!.error)
        assertFalse(part("d1").exists())
        assertTrue("never registered", finalized.isEmpty())
    }

    @Test
    fun aNetworkFailureRequeuesAndKeepsThePartial() = runBlocking {
        queue()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(200)
                .setBody(Buffer().write(content, 0, 600_000)).setHeader("Content-Length", content.size)
        }

        val outcome = runner().run("d1")

        assertEquals("backoff 30 s from now", 40_000L, (outcome as RunOutcome.Retry).notBefore)
        assertEquals("queued", dao.row("d1")!!.status)
        assertEquals(40_000L, dao.row("d1")!!.notBefore)
        assertEquals(1, dao.row("d1")!!.ioRetries)
        assertEquals("the real bytes on disk", part("d1").length(), dao.row("d1")!!.bytesDone)
        assertTrue(part("d1").length() > 0)
    }

    @Test
    fun theLastAttemptTurnsANetworkFailureIntoAFailedRow() = runBlocking {
        queue(ioRetries = 2)
        serve(503)

        val outcome = runner(maxIoRetries = 3).run("d1")

        assertEquals(RunOutcome.Failed(DownloadError.Network), outcome)
        assertEquals("failed", dao.row("d1")!!.status)
    }

    @Test
    fun errorsMapToFinalRows() = runBlocking {
        queue("a")
        queue("b", at = 2)
        queue("c", at = 3)
        serve(404)
        assertEquals(RunOutcome.Failed(DownloadError.NotFound), runner().run("a"))
        serve(403)
        assertEquals(RunOutcome.Failed(DownloadError.Gated), runner().run("b"))
        serve(401)
        assertEquals(RunOutcome.Failed(DownloadError.Gated), runner().run("c"))
        assertEquals(listOf("failed", "failed", "failed"), dao.rows.map { it.status })
        assertEquals(listOf("not_found", "gated", "gated"), dao.rows.map { it.error })
    }

    @Test
    fun aDuplicateModelIsAFailureAndTheFileIsDropped() = runBlocking {
        queue()
        finalizeFailure = DuplicateModelException("f.gguf")

        val outcome = runner().run("d1")

        assertEquals(RunOutcome.Failed(DownloadError.AlreadyImported), outcome)
        assertFalse(part("d1").exists())
    }

    @Test
    fun aStaleRequestForADoneOrPausedOrMissingRowIsSkipped() = runBlocking {
        queue("done", status = "done")
        queue("paused", status = "paused", at = 2)

        assertEquals(RunOutcome.Skipped, runner().run("done"))
        assertEquals(RunOutcome.Skipped, runner().run("paused"))
        assertEquals(RunOutcome.Skipped, runner().run("ghost"))
        assertEquals("paused", dao.row("paused")!!.status)
        assertEquals("no request was made", 0, server.requestCount)
    }

    @Test
    fun pausingWhileItRunsStopsItAndKeepsThePartial() = runBlocking {
        queue()
        serve(200, throttle = true)
        var first = true

        val outcome = runner().run("d1") { _, _ ->
            if (first) {
                first = false
                dao.transition("d1", listOf("downloading"), "paused", null, null)
            }
        }

        assertEquals(RunOutcome.Paused, outcome)
        assertEquals("paused", dao.row("d1")!!.status)
        assertTrue("partial kept", part("d1").exists())
        assertTrue("and stopped early", part("d1").length() < content.size)
        assertEquals(part("d1").length(), dao.row("d1")!!.bytesDone)
    }

    @Test
    fun cancellingWhileItRunsDeletesThePartialAndTheRow() = runBlocking {
        queue()
        serve(200, throttle = true)
        var first = true

        val outcome = runner().run("d1") { _, _ ->
            if (first) {
                first = false
                dao.delete("d1")
            }
        }

        assertEquals(RunOutcome.Cancelled, outcome)
        assertNull(dao.row("d1"))
        assertFalse(part("d1").exists())
    }

    @Test
    fun stoppingTheWorkerRequeuesInsteadOfLeavingItDownloading() {
        runBlocking {
            queue()
            serve(200, throttle = true)

            try {
                runner().run("d1") { _, _ -> throw CancellationException("worker stopped") }
                fail("the cancellation must propagate")
            } catch (_: CancellationException) {
            }

            assertEquals("queued", dao.row("d1")!!.status)
        }
    }

    @Test
    fun resumingContinuesFromThePartAlreadyOnDisk() = runBlocking {
        queue()
        part("d1").writeBytes(content.copyOf(500_000))
        var ranged: String? = null
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                ranged = request.getHeader("Range")
                return MockResponse().setResponseCode(206)
                    .addHeader("Content-Range", "bytes 500000-${content.size - 1}/${content.size}")
                    .setBody(Buffer().write(content, 500_000, content.size - 500_000))
            }
        }

        val outcome = runner().run("d1")

        assertTrue(outcome is RunOutcome.Done)
        assertEquals("bytes=500000-", ranged)
        assertNotNull(dao.row("d1")!!.modelId)
    }

    @Test
    fun anAttemptThatMovedBytesResetsTheRetryCount() = runBlocking {
        queue(ioRetries = 5)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(200)
                .setBody(Buffer().write(content, 0, 600_000)).setHeader("Content-Length", content.size)
        }

        runner().run("d1")

        assertEquals("progress means the link works: this is failure number one, not six", 1, dao.row("d1")!!.ioRetries)
    }

    @Test
    fun anAttemptThatGetsNowhereCountsUp() = runBlocking {
        queue(ioRetries = 2)
        serve(503)

        val outcome = runner().run("d1") as RunOutcome.Retry

        assertEquals(3, dao.row("d1")!!.ioRetries)
        assertEquals("30 s * 2^2 after the third failure", now + 120_000, outcome.notBefore)
    }

    @Test
    fun theUrlIsPinnedToTheStoredCommit() = runBlocking {
        val commit = "a".repeat(40)
        queue(commitSha = commit)

        runner().run("d1")

        assertEquals(listOf<String?>(commit), urls)
    }

    @Test
    fun aRowWithoutACommitAsksForNull() = runBlocking {
        queue()

        runner().run("d1")

        assertEquals(listOf<String?>(null), urls)
    }

    @Test
    fun withNoExpectedHashTheExactSizeIsWhatCounts() = runBlocking {
        queue(sha256 = null)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(200)
                .setBody(Buffer().write(content, 0, content.size - 1)).setHeader("Content-Length", content.size)
        }

        val outcome = runner().run("d1")

        assertTrue("one byte short is a transfer error, never a finished model", outcome is RunOutcome.Retry)
        assertTrue(finalized.isEmpty())
    }

    @Test
    fun aCancelRacingTheEndRollsTheRegisteredModelBack() = runBlocking {
        queue()
        duringFinalize = { dao.delete("d1") }

        val outcome = runner().run("d1")

        assertEquals("nobody is told it is ready", RunOutcome.Cancelled, outcome)
        assertEquals(listOf("model-of-d1"), unregistered.map { it.model.id })
    }

    @Test
    fun aFinalizeCrashEndsFailedUnknownAndKeepsTheFileToRetry() = runBlocking {
        queue()
        finalizeFailure = IllegalStateException("insert failed")

        val outcome = runner().run("d1")

        assertEquals(RunOutcome.Failed(DownloadError.Unknown), outcome)
        assertEquals("failed", dao.row("d1")!!.status)
        assertTrue("the finished bytes are kept for the retry", part("d1").length() == content.size.toLong())
    }

    @Test
    fun aCancelRacingTheEndOfARelinkedModelKeepsThePreviousRow() = runBlocking {
        queue()
        previous = model("missing-one")
        duringFinalize = { dao.delete("d1") }

        runner().run("d1")

        assertEquals("the caller gets the old row to put back, not a delete", previous, unregistered.single().previous)
    }

    @Test
    fun aPauseThatLandsAtTheEndOfTheTransferStopsBeforeRegistering() = runBlocking {
        queue()
        val outcome = runner().run("d1") { _, p ->
            if (p.bytesDone == content.size.toLong()) dao.transition("d1", listOf("downloading"), "paused", null, null)
        }

        assertEquals("paused during the hash pass or just after it: never registered", RunOutcome.Paused, outcome)
        assertTrue(finalized.isEmpty())
        assertTrue("the finished bytes are kept for the resume", part("d1").exists())
    }

    @Test
    fun aFailureWhileTheUserCancelsIsACancelNotAFailure() = runBlocking {
        queue()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                runBlocking { dao.delete("d1") }
                return MockResponse().setResponseCode(404)
            }
        }

        // The row is gone by the time the 404 is recorded: no Failed outcome to announce.
        val outcome = runner().run("d1")

        assertEquals(RunOutcome.Cancelled, outcome)
    }

    @Test
    fun aPauseDuringAFailureWinsOverTheFailure() = runBlocking {
        queue()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                runBlocking { dao.transition("d1", listOf("downloading"), "paused", null, null) }
                return MockResponse().setResponseCode(404)
            }
        }

        val outcome = runner().run("d1")

        assertTrue("$outcome", outcome is RunOutcome.Paused)
        assertEquals("paused", dao.row("d1")!!.status)
    }

    @Test
    fun theSameRowIsNeverRunTwiceAtOnce() = runBlocking {
        queue()
        serve(200, throttle = true)
        val r = runner()
        val first = async(kotlinx.coroutines.Dispatchers.Default) { r.run("d1") }
        while (server.requestCount == 0) kotlinx.coroutines.delay(5)

        val second = r.run("d1")

        assertEquals("an overlapping drainer must not start it again", RunOutcome.Skipped, second)
        dao.delete("d1")
        first.await()
        Unit
    }

    @Test
    fun aStopBecauseTheRowIsAlreadyDoneDeletesTheLeftoverPart() = runBlocking {
        queue()
        val outcome = runner().run("d1") { _, p ->
            if (p.bytesDone == content.size.toLong()) dao.markDone("d1", content.size.toLong(), "someone-elses")
        }

        assertEquals(RunOutcome.Skipped, outcome)
        assertFalse("no .part left behind a done row", part("d1").exists())
        assertTrue(finalized.isEmpty())
    }

    // ---- SpeedMeter ----

    @Test
    fun speedMeterSmoothsAndEstimates() {
        var now = 0L
        val meter = SpeedMeter({ now })

        assertEquals("no speed from one sample", 0L, meter.sample(0, 10_000).bytesPerSecond)
        now = 1_000
        val first = meter.sample(1_000, 10_000)
        assertEquals(1_000L, first.bytesPerSecond)
        assertEquals(9L, first.etaSeconds)
        now = 2_000
        val faster = meter.sample(4_000, 10_000) // 3000 B/s instant
        assertEquals("EMA: 0.3 * 3000 + 0.7 * 1000", 1_600L, faster.bytesPerSecond)
        assertTrue(faster.etaSeconds!! in 3..4)
    }

    @Test
    fun noEtaWhileStalled() {
        var now = 0L
        val meter = SpeedMeter({ now })
        meter.sample(0, 100)
        now = 1_000

        assertNull(meter.sample(0, 100).etaSeconds)
    }

    @Test
    fun errorCodesRoundTrip() {
        val all = listOf(
            DownloadError.Gated, DownloadError.InvalidToken, DownloadError.NotFound, DownloadError.ChecksumMismatch,
            DownloadError.SizeMismatch, DownloadError.DiskFull, DownloadError.Network, DownloadError.AlreadyImported,
            DownloadError.InvalidModel, DownloadError.Unknown, DownloadError.Http(418),
        )
        all.forEach { assertEquals(it, DownloadError.fromCode(it.code)) }
        assertNull(DownloadError.fromCode(null))
        assertEquals("an unknown stored code never crashes", DownloadError.Network, DownloadError.fromCode("from_the_future"))
        assertEquals(DownloadStatus.Failed, DownloadStatus.fromCode("???"))
        assertTrue(IOException("x") is Exception)
    }
}
