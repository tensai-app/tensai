package io.github.hokanosekai.tensai.download

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** [DownloadEngine] against a MockWebServer and a temp dir: resume, checksum, disk, error mapping. */
class DownloadEngineTest {

    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private lateinit var part: File
    private var token: String? = null
    private var free: () -> Long = { Long.MAX_VALUE / 2 }

    private val content = ByteArray(1_000_000) { (it * 31 % 251).toByte() }
    private val contentSha = sha256(content)

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        dir = Files.createTempDirectory("tensai-download-test").toFile()
        part = File(dir, "x.part")
    }

    @After
    fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun engine() = DownloadEngine(
        http = OkHttpClient(),
        freeBytes = { free() },
        tokenProvider = { token },
        progressIntervalMs = 0,
    )

    private fun url() = server.url("/org/repo/resolve/main/model.gguf").toString()

    private fun run(
        total: Long = content.size.toLong(),
        sha: String? = contentSha,
        progress: MutableList<Long> = mutableListOf(),
    ): Ran = runBlocking {
        val e = engine()
        val bytes = e.download(url(), part, total) { progress += it }
        e.verifySha256(part, sha)
        Ran(bytes, sha)
    }

    /** [sha256] is what was verified against, null when nothing was expected. */
    private data class Ran(val bytes: Long, val sha256: String?)

    private fun failure(block: () -> Unit): Throwable {
        try {
            block()
        } catch (e: Throwable) {
            return e
        }
        fail("expected a failure")
        error("unreachable")
    }

    /** Serves [content] honouring `Range` like the Hub does (206 + Content-Range), recording requests. */
    private inner class RangeServer(var honorRange: Boolean = true) : Dispatcher() {
        val requests = mutableListOf<RecordedRequest>()

        override fun dispatch(request: RecordedRequest): MockResponse {
            requests += request
            val range = request.getHeader("Range")?.let { Regex("bytes=(\\d+)-").find(it)?.groupValues?.get(1)?.toInt() }
            if (range == null || !honorRange) return MockResponse().setResponseCode(200).setBody(Buffer().write(content))
            if (range >= content.size) return MockResponse().setResponseCode(416)
            return MockResponse().setResponseCode(206)
                .addHeader("Content-Range", "bytes $range-${content.size - 1}/${content.size}")
                .setBody(Buffer().write(content, range, content.size - range))
        }
    }

    // ---- the happy path and the checksum ----

    @Test
    fun downloadsHashesAndReportsProgress() {
        server.dispatcher = RangeServer()
        val progress = mutableListOf<Long>()

        val result = run(progress = progress)

        assertArrayEquals(content, part.readBytes())
        assertEquals(contentSha, result.sha256)
        assertEquals(content.size.toLong(), progress.last())
        assertEquals("starts from the resume point", 0L, progress.first())
        assertNull("no Range on a fresh download", (server.dispatcher as RangeServer).requests.single().getHeader("Range"))
    }

    @Test
    fun checksumMismatchDeletesTheFileAndSaysSo() {
        server.dispatcher = RangeServer()

        val e = failure { run(sha = "0".repeat(64)) }

        assertEquals(DownloadError.ChecksumMismatch, (e as DownloadException).error)
        assertFalse("the bad file must not stay", part.exists())
    }

    @Test
    fun noExpectedHashSkipsVerification() {
        server.dispatcher = RangeServer()

        val result = run(sha = null)

        assertNull(result.sha256)
        assertEquals(content.size.toLong(), part.length())
    }

    // ---- one-pass verification ----

    @Test
    fun verifyPassesOnTheRightHashInAnyCaseAndSkipsWhenNoneIsExpected() = runBlocking {
        part.writeBytes(content)

        engine().verifySha256(part, contentSha)
        engine().verifySha256(part, contentSha.uppercase())
        engine().verifySha256(part, null)

        assertTrue(part.exists())
    }

    @Test
    fun verifyDeletesAFileWithTheWrongHash() {
        part.writeBytes(content)

        val e = failure { runBlocking { engine().verifySha256(part, "0".repeat(64)) } }

        assertEquals(DownloadError.ChecksumMismatch, (e as DownloadException).error)
        assertFalse(part.exists())
    }

    @Test
    fun downloadingHashesNothingOnItsOwn() {
        // A wrong expected hash is not noticed by download() itself: only verifySha256 looks.
        server.dispatcher = RangeServer()

        runBlocking { engine().download(url(), part, content.size.toLong()) { } }

        assertEquals(content.size.toLong(), part.length())
    }

    // ---- resume ----

    @Test
    fun resumeSendsRangeAndAppendsOn206AndTheHashCoversBothHalves() {
        val half = 400_000
        part.writeBytes(content.copyOf(half))
        val range = RangeServer().also { server.dispatcher = it }

        val result = run()

        assertEquals("bytes=$half-", range.requests.single().getHeader("Range"))
        assertArrayEquals(content, part.readBytes())
        assertEquals("the sha256 spans the bytes already on disk and the new ones", contentSha, result.sha256)
    }

    @Test
    fun a200ToARangedRequestRestartsFromZero() {
        part.writeBytes(content.copyOf(300_000))
        val range = RangeServer(honorRange = false).also { server.dispatcher = it }

        val result = run()

        assertEquals("one ranged attempt, its whole-file answer used as is", "bytes=300000-", range.requests.single().getHeader("Range"))
        assertArrayEquals("not 300000 bytes of old data followed by the whole file", content, part.readBytes())
        assertEquals(contentSha, result.sha256)
    }

    @Test
    fun a206StartingElsewhereThanAskedRestartsFromZero() {
        part.writeBytes(content.copyOf(300_000))
        var calls = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = if (calls++ == 0) {
                MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 0-${content.size - 1}/${content.size}")
                    .setBody(Buffer().write(content))
            } else {
                MockResponse().setResponseCode(200).setBody(Buffer().write(content))
            }
        }

        val result = run()

        assertEquals(2, calls)
        assertArrayEquals(content, part.readBytes())
        assertEquals(contentSha, result.sha256)
    }

    @Test
    fun a416OnACompletePartIsAlreadyDone() {
        part.writeBytes(content)
        val range = RangeServer().also { server.dispatcher = it }

        val result = run()

        assertEquals(1, range.requests.size)
        assertEquals(contentSha, result.sha256)
        assertArrayEquals(content, part.readBytes())
    }

    @Test
    fun aPartLargerThanTheAnnouncedSizeIsDiscarded() {
        part.writeBytes(ByteArray(content.size + 10))
        val range = RangeServer().also { server.dispatcher = it }

        run()

        assertNull(range.requests.single().getHeader("Range"))
        assertArrayEquals(content, part.readBytes())
    }

    @Test
    fun aBodyCutShortIsARetryableIoErrorAndKeepsTheBytes() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(200)
                .setBody(Buffer().write(content, 0, 100_000))
                .setHeader("Content-Length", content.size)
        }

        val e = failure { run() }

        assertTrue("a network failure, not a final one: $e", e is IOException)
        assertTrue("the partial file stays for the resume", part.length() in 1..100_000)
    }

    @Test
    fun moreBytesThanAnnouncedIsASizeMismatchAndDeletesTheFile() {
        server.dispatcher = RangeServer()

        val e = failure { run(total = content.size - 5L) }

        assertEquals(DownloadError.SizeMismatch, (e as DownloadException).error)
        assertFalse(part.exists())
    }

    // ---- error mapping ----

    private fun answer(code: Int, vararg headers: Pair<String, String>) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setResponseCode(code).apply { headers.forEach { (k, v) -> addHeader(k, v) } }
        }
    }

    private fun errorFor(@Suppress("UNUSED_PARAMETER") code: Int): DownloadError? =
        (failure { run() } as? DownloadException)?.error

    @Test
    fun errorMapping() {
        answer(404)
        assertEquals(DownloadError.NotFound, errorFor(404))
        answer(403)
        assertEquals(DownloadError.Gated, errorFor(403))
        answer(401)
        assertEquals("401 without a token is the repo", DownloadError.Gated, errorFor(401))
        answer(418)
        assertEquals(DownloadError.Http(418), errorFor(418))
        for (code in listOf(429, 500, 503)) {
            answer(code)
            assertTrue("$code is retried, not final", failure { run() } is IOException)
        }
        assertFalse("nothing was written for any of them", part.exists() && part.length() > 0)
    }

    @Test
    fun a401WithATokenThatWorksAnonymouslyIsInvalidToken() {
        token = "hf_bad"
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                if (request.getHeader("Authorization") != null) MockResponse().setResponseCode(401)
                else MockResponse().setResponseCode(206).addHeader("Content-Range", "bytes 0-0/1").setBody("x")
        }

        assertEquals(DownloadError.InvalidToken, (failure { run() } as DownloadException).error)
    }

    @Test
    fun a401WithATokenThatAlsoFailsAnonymouslyIsGated() {
        token = "hf_ok_but_no_licence"
        answer(401)

        assertEquals(DownloadError.Gated, (failure { run() } as DownloadException).error)
    }

    @Test
    fun theTokenIsSentAsBearer() {
        token = "hf_abc"
        val range = RangeServer().also { server.dispatcher = it }

        run()

        assertEquals("Bearer hf_abc", range.requests.single().getHeader("Authorization"))
    }

    // ---- disk ----

    @Test
    fun notEnoughRoomForWhatIsLeftStopsBeforeWriting() {
        server.dispatcher = RangeServer()
        free = { content.size.toLong() } // fits the file, not the safety margin on top of it

        val e = failure { run() }

        assertEquals(DownloadError.DiskFull, (e as DownloadException).error)
        assertTrue(!part.exists() || part.length() == 0L)
    }

    @Test
    fun aDiskThatFillsMidDownloadAbortsAndKeepsThePartial() {
        val big = ByteArray(40 * 1024 * 1024) { 1 }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(200).setBody(Buffer().write(big))
        }
        var checks = 0
        // Plenty on the first check, then another app eats the disk.
        free = { if (checks++ == 0) Long.MAX_VALUE / 2 else 1_000L }

        val e = failure { runBlocking { engine().download(url(), part, big.size.toLong()) { } } }

        assertEquals(DownloadError.DiskFull, (e as DownloadException).error)
        assertTrue("some bytes were written before it noticed", part.length() > 0)
        assertTrue("and not all of them", part.length() < big.size)
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
