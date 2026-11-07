package io.github.tensaiapp.download

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
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

    /** The Hub answers `resolve/` with a redirect to its CDN: the followed request must still ask for the rest only. */
    @Test
    fun resumeKeepsItsRangeAcrossTheHubsRedirect() {
        val half = 400_000
        part.writeBytes(content.copyOf(half))
        val cdn = RangeServer()
        val seen = mutableListOf<RecordedRequest>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                seen += request
                return if (request.path!!.startsWith("/org/")) {
                    MockResponse().setResponseCode(302).addHeader("Location", "/cdn/model.gguf")
                } else {
                    cdn.dispatch(request)
                }
            }
        }

        run()

        assertEquals(listOf("/org/repo/resolve/main/model.gguf", "/cdn/model.gguf"), seen.map { it.path })
        assertEquals(listOf("bytes=$half-", "bytes=$half-"), seen.map { it.getHeader("Range") })
        assertArrayEquals(content, part.readBytes())
    }

    @Test
    fun a200ToARangedRequestReadsPastThePartAndAppendsTheRest() {
        part.writeBytes(content.copyOf(300_000))
        val range = RangeServer(honorRange = false).also { server.dispatcher = it }

        val result = run()

        assertEquals("one ranged attempt, its whole-file answer used", "bytes=300000-", range.requests.single().getHeader("Range"))
        assertArrayEquals("the part kept, the body appended after its first 300000 bytes", content, part.readBytes())
        assertEquals(contentSha, result.sha256)
    }

    /** The bar follows the re-read from 0 back to the resume point, then on: it climbs, never freezes nor drops. */
    @Test
    fun readingPastThePartReportsTheBytesReRead() {
        part.writeBytes(content.copyOf(600_000))
        server.dispatcher = RangeServer(honorRange = false)
        val progress = mutableListOf<Long>()

        run(progress = progress)

        assertEquals(0L, progress.first())
        assertTrue("re-read bytes reported: $progress", progress.any { it in 1 until 600_000 })
        assertEquals(progress.sorted(), progress)
        assertEquals(content.size.toLong(), progress.last())
    }

    /** A part that is not the start of the body (another revision, junk), which a 200 used to heal, still is. */
    @Test
    fun aStalePartIsRewrittenFromA200() {
        val junk = ByteArray(300_000) { (it * 7 % 13).toByte() }
        // The body in reads of at most 4096 bytes, so where a mismatch falls against the reads is known.
        val throttled = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                MockResponse().setResponseCode(200).setBody(Buffer().write(content)).throttleBody(4096, 0, TimeUnit.MILLISECONDS)
        }
        // Matches the body for its first 10 000 bytes, then not: caught past the first read.
        val late = content.copyOf(10_000) + junk.copyOf(290_000)
        // Mismatch exactly at 4096, the first byte of the second read.
        val boundary = content.copyOf(4096) + junk.copyOf(295_904)
        assertTrue(boundary[4096] != content[4096])
        for ((stale, sha) in listOf(junk to contentSha, late to contentSha, boundary to contentSha, junk to null)) {
            part.writeBytes(stale)
            server.dispatcher = throttled

            run(sha = sha)

            assertArrayEquals(content, part.readBytes())
        }
    }

    /** A 200 to a ranged request whose body is the whole file, cut after [bytes] (0: empty), announced whole. */
    private fun cut200(bytes: Int) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = if (bytes == 0) {
                MockResponse().setResponseCode(200).setBody(Buffer())
            } else {
                MockResponse().setResponseCode(200).setBody(Buffer().write(content, 0, bytes)).setHeader("Content-Length", content.size)
            }
        }
    }

    /** TEN-210: an empty 200 to a resume (a broken CDN reply) is a retryable error, and the part is untouched. */
    @Test
    fun anEmpty200ToAResumeKeepsThePartByteForByte() {
        val before = content.copyOf(300_000)
        part.writeBytes(before)
        cut200(0)

        val e = failure { run() }

        assertTrue("retryable, not final: $e", e is IOException && e !is DownloadException)
        assertArrayEquals(before, part.readBytes())
    }

    /** Cut before the old size: nothing is lost. Cut after it: what came past the old size is kept for the next resume. */
    @Test
    fun aCut200ToAResumeNeverLosesThePart() {
        val before = content.copyOf(300_000)
        part.writeBytes(before)
        cut200(100_000)

        assertTrue(failure { run() } is IOException)
        assertArrayEquals("cut before the old size", before, part.readBytes())

        cut200(500_000)

        assertTrue(failure { run() } is IOException)
        assertArrayEquals("cut after it: a longer valid prefix", content.copyOf(500_000), part.readBytes())

        server.dispatcher = RangeServer()
        assertEquals(contentSha, run().sha256)
        assertArrayEquals(content, part.readBytes())
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

    /**
     * A gated file: anonymous requests get [anonymous], requests with [goodToken] get the file,
     * any other bearer gets [other]. Records every request.
     */
    private fun gatedServer(anonymous: Int, other: Int, goodToken: String? = null): RangeServer {
        val range = RangeServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val auth = request.getHeader("Authorization")
                return when {
                    auth == null -> {
                        range.requests += request
                        MockResponse().setResponseCode(anonymous)
                    }
                    goodToken != null && auth == "Bearer $goodToken" -> range.dispatch(request)
                    else -> {
                        range.requests += request
                        MockResponse().setResponseCode(other)
                    }
                }
            }
        }
        return range
    }

    private fun gatedError(): DownloadError = (failure { run() } as DownloadException).error

    @Test
    fun gatedWithoutATokenIsGatedOnEither401Or403AndMakesOneRequest() {
        for (code in listOf(401, 403)) {
            val range = gatedServer(anonymous = code, other = 500)
            assertEquals(DownloadError.Gated, gatedError())
            assertEquals(1, range.requests.size)
            assertNull(range.requests.single().getHeader("Authorization"))
        }
    }

    @Test
    fun a401ToAValidTokenIsInvalidTokenNeverTheLicense() {
        token = "hf_badbadbad"
        val range = gatedServer(anonymous = 401, other = 401)

        assertEquals(DownloadError.InvalidToken, gatedError())
        assertEquals(listOf(null, "Bearer hf_badbadbad"), range.requests.map { it.getHeader("Authorization") })
    }

    @Test
    fun a403ToATokenIsTheLicenseNotAcceptedAndAnonymous403AlsoTriesTheToken() {
        token = "hf_validvalid"
        for (anonymous in listOf(401, 403)) {
            val range = gatedServer(anonymous = anonymous, other = 403)
            assertEquals(DownloadError.LicenseNotAccepted, gatedError())
            assertEquals(2, range.requests.size)
        }
    }

    @Test
    fun aGatedFileWithAGoodTokenIsFetchedAfterAnAnonymousRefusal() {
        token = "hf_goodgood1"
        val range = gatedServer(anonymous = 401, other = 401, goodToken = "hf_goodgood1")

        run()

        assertEquals(listOf(null, "Bearer hf_goodgood1"), range.requests.map { it.getHeader("Authorization") })
        assertArrayEquals(content, part.readBytes())
    }

    /**
     * The gated path on the Hub's real shape: anonymous 401, the token retry answered with a redirect to the CDN on
     * another host. The token must not follow it there (OkHttp drops `Authorization` across hosts, see
     * [DownloadEngine]'s KDoc), the resume `Range` must.
     */
    @Test
    fun theTokenStaysOnTheHubAndTheRangeFollowsTheRedirectToTheCdn() {
        token = "hf_goodgood1"
        val half = 400_000
        part.writeBytes(content.copyOf(half))
        val cdn = MockWebServer().also { it.start() }
        try {
            val cdnRange = RangeServer().also { cdn.dispatcher = it }
            val cdnUrl = cdn.url("/cdn/model.gguf").newBuilder().host("127.0.0.1").build()
            val hub = mutableListOf<RecordedRequest>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    hub += request
                    return if (request.getHeader("Authorization") == null) {
                        MockResponse().setResponseCode(401)
                    } else {
                        MockResponse().setResponseCode(302).addHeader("Location", cdnUrl.toString())
                    }
                }
            }

            run()

            assertEquals(listOf(null, "Bearer hf_goodgood1"), hub.map { it.getHeader("Authorization") })
            val atCdn = cdnRange.requests.single()
            assertNull("the token reached the CDN", atCdn.getHeader("Authorization"))
            assertEquals("bytes=$half-", atCdn.getHeader("Range"))
            assertArrayEquals(content, part.readBytes())
        } finally {
            cdn.shutdown()
        }
    }

    @Test
    fun aPublicFileNeverCarriesTheToken() {
        token = "hf_publicpub"
        val range = RangeServer().also { server.dispatcher = it }

        run()

        assertNull(range.requests.single().getHeader("Authorization"))
    }

    @Test
    fun aMalformedStoredTokenIsIgnoredWithoutCrashing() {
        for (bad in listOf("hf bad token", "hf_éééééé", "hf_line\nbreak1", "short")) {
            token = bad
            val range = gatedServer(anonymous = 401, other = 401)
            // No usable token: the same outcome as having none, one request, no header, no exception text with the value.
            val error = failure { run() }
            assertEquals(DownloadError.Gated, (error as DownloadException).error)
            assertFalse((error.message ?: "").contains(bad))
            assertEquals(1, range.requests.size)
        }
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
