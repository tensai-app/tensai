package io.github.hokanosekai.tensai.hf

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class HfClientTest {

    private lateinit var server: MockWebServer
    private var token: String? = null
    private lateinit var client: HfClient

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        client = HfClient(baseUrl = server.url("/").toString().trimEnd('/'), tokenProvider = { token })
    }

    @After
    fun tearDown() = server.shutdown()

    private fun ok(body: String, vararg headers: Pair<String, String>) = MockResponse().setResponseCode(200).setBody(body).apply { headers.forEach { (k, v) -> addHeader(k, v) } }

    private fun status(code: Int, vararg headers: Pair<String, String>) = MockResponse().setResponseCode(code).setBody("{}").apply { headers.forEach { (k, v) -> addHeader(k, v) } }

    private fun errorOf(block: suspend () -> Unit): HfError {
        try {
            runBlocking { block() }
        } catch (e: HfException) {
            return e.error
        }
        fail("expected an HfException")
        error("unreachable")
    }

    @Test
    fun `search builds the documented query and follows the Link cursor`() = runBlocking {
        val next = server.url("/api/models?cursor=abc").toString()
        server.enqueue(ok(fixture("search_page.json"), "Link" to "<$next>; rel=\"next\""))
        val page = client.search("qwen3", HfSort.Likes)
        assertEquals(20, page.items.size)
        assertEquals(next, page.nextCursor)

        val req = server.takeRequest().requestUrl!!
        assertEquals("/api/models", req.encodedPath)
        assertEquals("qwen3", req.queryParameter("search"))
        assertEquals("gguf", req.queryParameter("filter"))
        assertEquals("likes", req.queryParameter("sort"))
        assertEquals("-1", req.queryParameter("direction"))
        assertEquals("20", req.queryParameter("limit"))
        assertTrue(req.queryParameterValues("expand[]").containsAll(listOf("gated", "lastModified")))

        server.enqueue(ok("[]"))
        val last = client.searchNext(page.nextCursor!!)
        assertEquals(emptyList<HfRepoSummary>(), last.items)
        assertNull(last.nextCursor)
        assertEquals("abc", server.takeRequest().requestUrl!!.queryParameter("cursor"))
    }

    @Test
    fun `a blank query sends no search parameter`() = runBlocking {
        server.enqueue(ok("[]"))
        client.search("  ")
        assertNull(server.takeRequest().requestUrl!!.queryParameter("search"))
    }

    @Test
    fun `a cursor on another host is refused without any request`() {
        val e = errorOf { client.searchNext("https://evil.example/api/models?cursor=x") }
        assertTrue(e is HfError.Parse)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `detail is one call with blobs and gguf, then served from the cache`() = runBlocking {
        server.enqueue(ok(fixture("detail_qwen3_1_7b.json")))
        val a = client.detail("bartowski/Qwen_Qwen3-1.7B-GGUF")
        val b = client.detail("bartowski/Qwen_Qwen3-1.7B-GGUF")
        assertEquals(a, b)
        assertEquals(1, server.requestCount)
        val url = server.takeRequest().requestUrl!!
        assertEquals("/api/models/bartowski/Qwen_Qwen3-1.7B-GGUF", url.encodedPath)
        assertEquals("true", url.queryParameter("blobs"))
        assertTrue(url.queryParameterValues("expand[]").containsAll(listOf("gguf", "siblings", "cardData")))

        server.enqueue(ok(fixture("detail_qwen3_1_7b.json")))
        client.detail("bartowski/Qwen_Qwen3-1.7B-GGUF", forceRefresh = true)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `cache evicts the least recently used repo`() = runBlocking {
        val small = HfClient(baseUrl = server.url("/").toString().trimEnd('/'), cacheSize = 2)
        repeat(3) { server.enqueue(ok("""{"id":"a/r$it"}""")) }
        small.detail("a/r0")
        small.detail("a/r1")
        small.detail("a/r2") // evicts r0
        assertEquals(3, server.requestCount)
        server.enqueue(ok("""{"id":"a/r0"}"""))
        small.detail("a/r1") // still cached
        assertEquals(3, server.requestCount)
        small.detail("a/r0") // refetched
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `readme and token header`() = runBlocking {
        token = "hf_secret"
        server.enqueue(ok("# Hello"))
        assertEquals("# Hello", client.readme("a/b"))
        val req = server.takeRequest()
        assertEquals("/a/b/raw/main/README.md", req.requestUrl!!.encodedPath)
        assertEquals("Bearer hf_secret", req.getHeader("Authorization"))

        token = null
        server.enqueue(ok("# Hello"))
        client.readme("a/b")
        assertNull(server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `url builders stay on the origin`() {
        val base = server.url("/").toString().trimEnd('/')
        assertEquals("$base/a/b/resolve/main/Q8_0/m-Q8_0.gguf", client.fileUrl("a/b", "Q8_0/m-Q8_0.gguf"))
        assertEquals("$base/a/b", client.repoPageUrl("a/b"))
        assertEquals("https://huggingface.co/a/b/resolve/main/m.gguf", HfClient().fileUrl("a/b", "m.gguf"))
    }

    @Test
    fun `status codes map to typed errors`() {
        server.enqueue(status(404))
        assertEquals(HfError.NotFound, errorOf { client.detail("a/b") })
        server.enqueue(status(401))
        assertEquals(HfError.Gated(401), errorOf { client.detail("a/b") })
        server.enqueue(status(403))
        assertEquals(HfError.Gated(403), errorOf { client.readme("a/b") })
        server.enqueue(status(429, "Retry-After" to "12"))
        assertEquals(HfError.RateLimited(12), errorOf { client.search("x") })
        server.enqueue(status(429))
        assertEquals(HfError.RateLimited(null), errorOf { client.search("x") })
        server.enqueue(status(503))
        assertEquals(HfError.Http(503), errorOf { client.search("x") })
    }

    @Test
    fun `a malformed body is a parse error and is not cached`() {
        server.enqueue(ok("not json"))
        assertTrue(errorOf { client.detail("a/b") } is HfError.Parse)
        server.enqueue(ok("""{"no":"id"}"""))
        assertTrue(errorOf { client.detail("a/b") } is HfError.Parse)
        server.enqueue(ok("""{"id":"a/b"}"""))
        runBlocking { client.detail("a/b") }
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `an unreachable server is Offline`() {
        server.shutdown()
        assertEquals(HfError.Offline, errorOf { client.search("x") })
    }

    @Test
    fun `a repo path that is not author-name is NotFound before any request`() {
        assertEquals(HfError.NotFound, errorOf { client.detail("../etc/passwd") })
        assertEquals(HfError.NotFound, errorOf { client.detail("gpt2") })
        assertEquals(HfError.NotFound, errorOf { client.readme("gpt2") })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a refused token on a public call is InvalidToken, a real gated repo stays Gated`() {
        token = "hf_bad"
        // Token refused, anonymous retry works: the token is the problem.
        server.enqueue(status(401))
        server.enqueue(ok("[]"))
        assertEquals(HfError.InvalidToken, errorOf { client.search("x") })
        assertEquals("Bearer hf_bad", server.takeRequest().getHeader("Authorization"))
        assertNull(server.takeRequest().getHeader("Authorization"))
        // Both refused: the repo is gated.
        server.enqueue(status(401))
        server.enqueue(status(403))
        assertEquals(HfError.Gated(403), errorOf { client.readme("a/b") })
        // Without a token there is no retry.
        token = null
        server.enqueue(status(401))
        assertEquals(HfError.Gated(401), errorOf { client.readme("a/b") })
        assertEquals(5, server.requestCount)
    }

    @Test
    fun `concurrent detail calls for one repo share a request`() = runBlocking {
        server.enqueue(ok(fixture("detail_qwen3_1_7b.json")).setBodyDelay(300, java.util.concurrent.TimeUnit.MILLISECONDS))
        val all = (1..4).map { async(Dispatchers.Default) { client.detail("a/b") } }
        val results = all.map { it.await() }
        assertEquals(1, server.requestCount)
        assertTrue(results.all { it == results[0] })
    }

    @Test
    fun `cancelling while the body is being read cancels the call`() = runBlocking {
        server.enqueue(ok("[]").setBodyDelay(5, java.util.concurrent.TimeUnit.SECONDS))
        val started = System.nanoTime()
        val job = async(Dispatchers.Default) { client.search("x") }
        delay(300)
        job.cancel()
        try {
            job.await()
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertTrue("cancel must not wait for the slow body", System.nanoTime() - started < 3_000_000_000L)
    }
}
