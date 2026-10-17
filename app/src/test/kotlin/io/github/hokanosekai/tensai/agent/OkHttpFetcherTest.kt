package io.github.hokanosekai.tensai.agent

import io.github.hokanosekai.websearch.PageReader
import io.github.hokanosekai.websearch.SearchError
import io.github.hokanosekai.websearch.UrlGuard
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The OkHttp side of the `HttpFetcher` contract (TEN-181): redirects, DNS pinning, no proxy, body cap. */
class OkHttpFetcherTest {

    private val server = MockWebServer().apply { start() }

    @After fun stop() = server.shutdown()

    /** A client that may reach the local mock server: guards off, the other settings as in the app. */
    private fun local(userAgent: String = "tensai/test") =
        OkHttpFetcher(OkHttpFetcher.guardedClient(dns = Dns.SYSTEM, blockPrivateConnect = false), userAgent)

    private fun url(path: String = "/") = server.url(path).toString()

    @Test fun aRedirectIsReturnedNotFollowed() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/elsewhere"))
        server.enqueue(MockResponse().setBody("should not be fetched"))

        val response = local().get(url("/start"))

        assertEquals(302, response.status)
        assertEquals("/elsewhere", response.headers["location"])
        assertEquals(1, server.requestCount)
    }

    @Test fun theClientIsConfiguredForTheContract() {
        val client = OkHttpFetcher.guardedClient()
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertEquals(Proxy.NO_PROXY, client.proxy)
    }

    @Test fun theBodyIsCappedAndFlagged() = runBlocking {
        server.enqueue(MockResponse().setBody("a".repeat(100_000)))
        val response = local().get(url(), maxBytes = 1000)

        assertEquals(1000, response.bytes.size)
        assertTrue(response.truncated)
    }

    @Test fun aSmallBodyIsNotFlagged() = runBlocking {
        server.enqueue(MockResponse().setBody("hello").addHeader("Content-Type", "text/plain; charset=utf-8"))
        val response = local().get(url())

        assertEquals("hello", response.body)
        assertFalse(response.truncated)
        assertEquals("text/plain; charset=utf-8", response.contentType)
    }

    @Test fun nonSuccessStatusesComeBackAsResponses() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "7"))
        val response = local().get(url())

        assertEquals(429, response.status)
        assertEquals(7L, response.retryAfterSeconds)
    }

    @Test fun theDescriptiveUserAgentIsTheDefaultAndACallerCanOverrideIt() = runBlocking {
        server.enqueue(MockResponse())
        server.enqueue(MockResponse())
        local("tensai/1.2 (+repo)").get(url())
        local("tensai/1.2 (+repo)").get(url(), headers = mapOf("User-Agent" to "Mozilla/5.0 Mobile"))

        assertEquals("tensai/1.2 (+repo)", server.takeRequest().getHeader("User-Agent"))
        assertEquals("Mozilla/5.0 Mobile", server.takeRequest().getHeader("User-Agent"))
    }

    @Test fun postSendsTheBodyAndContentType() = runBlocking {
        server.enqueue(MockResponse().setBody("ok"))
        local().post(url(), "q=kotlin", "application/x-www-form-urlencoded", mapOf("Accept" to "text/html"))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("q=kotlin", request.body.readUtf8())
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/x-www-form-urlencoded"))
        assertEquals("text/html", request.getHeader("Accept"))
    }

    @Test fun aConnectionFailureIsAnIoException() {
        val dead = url()
        server.shutdown()
        try {
            runBlocking { local().get(dead) }
            fail("expected IOException")
        } catch (e: IOException) {
            // expected: the module maps it to SearchError.Network
        }
    }

    // DNS pinning

    @Test fun guardedDnsRejectsAnyPrivateAnswer() {
        fun dns(vararg addresses: String) = GuardedDns(object : Dns {
            override fun lookup(hostname: String) = addresses.map { InetAddress.getByName(it) }
        })
        for (private in listOf("10.0.0.5", "127.0.0.1", "192.168.1.1", "169.254.169.254", "::1", "fd12::1")) {
            try {
                dns(private).lookup("evil.example")
                fail("accepted $private")
            } catch (e: UnknownHostException) {
                // expected
            }
        }
        // one private address among public ones is enough to refuse the name
        try {
            dns("93.184.216.34", "10.0.0.5").lookup("rebind.example")
            fail("accepted a mixed answer")
        } catch (e: UnknownHostException) {
            // expected
        }
        assertEquals(listOf(InetAddress.getByName("93.184.216.34")), dns("93.184.216.34").lookup("example.com"))
    }

    @Test fun theGuardedClientNeverConnectsToALocalServer() {
        server.enqueue(MockResponse().setBody("secret"))
        val fetcher = OkHttpFetcher(userAgent = "tensai/test")
        // by name (resolved to loopback, refused by the Dns) and by literal (refused once connected)
        for (target in listOf(url(), "http://127.0.0.1:${server.port}/", "http://[::1]:${server.port}/")) {
            try {
                runBlocking { fetcher.get(target) }
                fail("reached $target")
            } catch (e: IOException) {
                // expected
            }
        }
        assertEquals("no HTTP request ever reached the server", 0, server.requestCount)
    }

    // through PageReader

    @Test fun pageReaderNeverRequestsARedirectToAPrivateAddress() {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "http://169.254.169.254/latest/meta-data"))
        server.enqueue(MockResponse().setBody("<html><body>metadata</body></html>").addHeader("Content-Type", "text/html"))
        // The guard refuses localhost, so the reader talks to a public name that this wrapper points at the mock server.
        val inner = local()
        val toMock = object : io.github.hokanosekai.websearch.HttpFetcher by inner {
            override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Long, maxBytes: Int) =
                inner.get(url.replace("http://public.example", server.url("").toString().trimEnd('/')), headers, timeoutMs, maxBytes)
        }
        val reader = PageReader(toMock, UrlGuard { listOf(InetAddress.getByName("93.184.216.34")) })

        try {
            runBlocking { reader.read("http://public.example/start", 500) }
            fail("expected UnsafeUrl")
        } catch (e: SearchError.UnsafeUrl) {
            // expected
        }
        assertEquals(1, server.requestCount)
    }

    @Test fun aMalformedUrlOrHeaderIsAnIoExceptionNotACrash() {
        for (call in listOf<suspend () -> Unit>(
            { local().get("not a url") },
            { local().get("http://") },
            { local().post("::::", "x", "text/plain") },
            { local().get(url(), headers = mapOf("Bad Header" to "x")) },
        )) {
            try {
                runBlocking { call() }
                fail("expected IOException")
            } catch (e: IOException) {
                // expected: the module maps it to SearchError.Network
            }
        }
    }
}
