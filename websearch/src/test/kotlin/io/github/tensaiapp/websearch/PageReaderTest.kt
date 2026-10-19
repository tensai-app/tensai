package io.github.tensaiapp.websearch

import java.net.InetAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageReaderTest {

    private val publicGuard = UrlGuard { listOf(InetAddress.getByName("93.184.216.34")) }
    private val html = mapOf("content-type" to "text/html; charset=utf-8")

    private fun reader(vararg responses: Any) = PageReader(FakeFetcher(*responses), publicGuard)

    private fun read(reader: PageReader, max: Int = 10_000, url: String = "https://example.com/a") =
        runBlocking { reader.read(url, max) }

    @Test fun articleIsExtractedWithoutNoise() {
        val page = read(reader(HttpResponse(200, fixture("article.html"), html)))

        assertEquals("Why small tools win", page.title)
        assertEquals(
            "Why small tools win\n\n" +
                "Small tools do one thing. They are easy to replace.\n\n" +
                "Second paragraph with a\nline break and a link.\n\n" +
                "first point\n\nsecond point",
            page.text,
        )
        for (noise in listOf("Subscribe", "Archive", "Related:", "Copyright", "tracking", "analytics", "comment")) {
            assertFalse("leaked: $noise", page.text.contains(noise))
        }
        assertFalse(page.truncated)
    }

    @Test fun fallsBackToBodyWithoutArticleOrMain() {
        val body = "<html><head><title>T</title></head><body><nav>menu</nav><div>Hello <b>world</b></div></body></html>"
        val page = read(reader(HttpResponse(200, body, html)))
        assertEquals("T\n\nHello world", page.text)
    }

    @Test fun truncatesToMaxCharsAndFlagsIt() {
        val page = read(reader(HttpResponse(200, fixture("article.html"), html)), max = 40)

        assertEquals(40, page.text.length)
        assertTrue(page.text.endsWith("…"))
        assertTrue(page.truncated)
    }

    @Test fun exactFitIsNotTruncated() {
        val body = "<html><head><title>T</title></head><body><p>abc</p></body></html>"
        val page = read(reader(HttpResponse(200, body, html)), max = "T\n\nabc".length)
        assertEquals("T\n\nabc", page.text)
        assertFalse(page.truncated)
    }

    @Test fun cloudflareChallengeIsBlocked() {
        assertFails<SearchError.Blocked> {
            read(reader(HttpResponse(200, fixture("cloudflare-challenge.html"), html)))
        }
        assertFails<SearchError.Blocked> {
            read(reader(HttpResponse(403, fixture("cloudflare-challenge.html"), html)))
        }
        assertFails<SearchError.Blocked> {
            read(reader(HttpResponse(503, fixture("cloudflare-challenge.html"), html)))
        }
    }

    @Test fun nonHtmlContentIsUnsupported() {
        assertFails<SearchError.Unsupported> {
            read(reader(HttpResponse(200, "%PDF-1.7", mapOf("content-type" to "application/pdf"))))
        }
    }

    @Test fun httpErrorsAreMapped() {
        assertFails<SearchError.Blocked> { read(reader(status(403, "forbidden"))) }
        assertFails<SearchError.RateLimited> { read(reader(status(429, "", "retry-after" to "9"))) }
        assertFails<SearchError.Network> { read(reader(status(500, "oops"))) }
    }

    @Test fun unsafeUrlIsRefusedBeforeAnyRequest() {
        val fetcher = FakeFetcher()
        val reader = PageReader(fetcher, publicGuard)
        assertFails<SearchError.UnsafeUrl> { runBlocking { reader.read("http://127.0.0.1:8080/", 100) } }
        assertFails<SearchError.UnsafeUrl> { runBlocking { reader.read("file:///etc/passwd", 100) } }
        assertTrue(fetcher.requests.isEmpty())
    }

    private fun redirect(location: String, code: Int = 302) = status(code, "", "location" to location)

    @Test fun redirectToPrivateAddressIsNeverRequested() {
        for (target in listOf("http://169.254.169.254/latest/meta-data", "http://192.168.1.1/", "http://localhost/")) {
            val fetcher = FakeFetcher(redirect(target), HttpResponse(200, fixture("article.html"), html))
            assertFails<SearchError.UnsafeUrl> { read(PageReader(fetcher, publicGuard)) }
            assertEquals(listOf("https://example.com/a"), fetcher.requests.map { it.url })
        }
    }

    @Test fun redirectsAreFollowedHopByHop() {
        val fetcher = FakeFetcher(
            redirect("/b", 301),
            redirect("https://other.example/c", 307),
            HttpResponse(200, fixture("article.html"), html),
        )
        val page = read(PageReader(fetcher, publicGuard))

        assertEquals(
            listOf("https://example.com/a", "https://example.com/b", "https://other.example/c"),
            fetcher.requests.map { it.url },
        )
        assertEquals("https://other.example/c", page.url)
    }

    @Test fun tooManyRedirectsIsNetworkError() {
        val fetcher = FakeFetcher(*Array(10) { redirect("/loop") })
        assertFails<SearchError.Network> { read(PageReader(fetcher, publicGuard)) }
        assertEquals(PageReader.MAX_REDIRECTS + 1, fetcher.requests.size)
    }

    @Test fun redirectWithoutLocationIsNetworkError() {
        assertFails<SearchError.Network> { read(reader(status(302))) }
    }

    @Test fun challengeMarkersOnTwoHundredNeedStrongEvidence() {
        val withCloudflareScript = "<html><head><title>Blog</title></head><body><p>Real article text.</p>" +
            "<script src=\"/cdn-cgi/challenge-platform/scripts/jsd/main.js\"></script></body></html>"
        assertEquals("Blog\n\nReal article text.", read(reader(HttpResponse(200, withCloudflareScript, html))).text)
        // same markup on a 403 is a wall
        assertFails<SearchError.Blocked> { read(reader(HttpResponse(403, withCloudflareScript, html))) }
        // header alone is enough on any status
        assertFails<SearchError.Blocked> {
            read(reader(HttpResponse(200, "<html><body>x</body></html>", html + ("cf-mitigated" to "challenge"))))
        }
    }

    @Test fun missingContentTypeIsSniffed() {
        assertFails<SearchError.Unsupported> { read(reader(HttpResponse(200, "%PDF-1.7 binary".toByteArray()))) }
        assertFails<SearchError.Unsupported> { read(reader(HttpResponse(200, byteArrayOf(1, 2, 0, 3, 4)))) }
        assertFails<SearchError.Unsupported> { read(reader(HttpResponse(200, ByteArray(200) { 1 }))) }
        val page = read(reader(HttpResponse(200, "<html><body><p>fine</p></body></html>")))
        assertEquals("fine", page.text)
    }

    @Test fun plainTextIsReturnedRaw() {
        val page = read(reader(HttpResponse(200, "line one\n\n\n\nline   two", mapOf("content-type" to "text/plain"))))
        assertEquals("line one\n\nline two", page.text)
        assertEquals("", page.title)
    }

    @Test fun charsetFromHeaderThenMetaThenUtf8() {
        val latin = "<html><head><title>Café</title></head><body><p>crème</p></body></html>"
        val header = HttpResponse(200, latin.toByteArray(Charsets.ISO_8859_1), mapOf("content-type" to "text/html; charset=ISO-8859-1"))
        assertEquals("Café\n\ncrème", read(reader(header)).text)

        val meta = "<html><head><meta charset=\"iso-8859-1\"><title>Café</title></head><body><p>crème</p></body></html>"
        assertEquals("Café\n\ncrème", read(reader(HttpResponse(200, meta.toByteArray(Charsets.ISO_8859_1), html + ("content-type" to "text/html")))).text)

        assertEquals("Café\n\ncrème", read(reader(HttpResponse(200, latin, html))).text)
    }

    @Test fun headerInsideArticleIsKept() {
        val body = "<html><head><title>T</title></head><body><header>Site menu</header>" +
            "<article><header><h1>Real title</h1></header><p>Text.</p></article></body></html>"
        val text = read(reader(HttpResponse(200, body, html))).text
        assertTrue(text.contains("Real title"))
        assertFalse(text.contains("Site menu"))
    }

    @Test fun truncationNeverSplitsASurrogatePair() {
        val body = "<html><body><p>" + "😀".repeat(50) + "</p></body></html>"
        for (max in 5..12) {
            val text = read(reader(HttpResponse(200, body, html)), max = max).text
            val beforeEllipsis = text.dropLast(1)
            assertFalse("max=$max", beforeEllipsis.isNotEmpty() && Character.isHighSurrogate(beforeEllipsis.last()))
            assertTrue(text.length <= max)
        }
    }

    @Test fun internationalizedHostIsPunycodedBeforeRequest() {
        val fetcher = FakeFetcher(HttpResponse(200, fixture("article.html"), html))
        read(PageReader(fetcher, publicGuard), url = "https://bücher.example/seite")
        assertEquals("https://xn--bcher-kva.example/seite", fetcher.requests.single().url)
    }

    @Test fun sendsMobileUserAgent() {
        val fetcher = FakeFetcher(HttpResponse(200, fixture("article.html"), html))
        read(PageReader(fetcher, publicGuard))
        assertTrue(fetcher.requests.single().headers.getValue("User-Agent").contains("Mobile"))
    }

    @Test fun emptyPageIsUnsupported() {
        assertFails<SearchError.Unsupported> {
            read(reader(HttpResponse(200, "<html><body><script>x()</script></body></html>", html)))
        }
    }

    @Test fun unsafeFirstUrlIsNotMarkedRequestedButARefusedHopIs() {
        val first = assertFails<SearchError.UnsafeUrl> { read(reader(), url = "http://127.0.0.1/") }
        assertFalse(first.requested)

        val hop = assertFails<SearchError.UnsafeUrl> { read(reader(redirect("http://192.168.1.1/"))) }
        assertTrue(hop.requested)
    }

    @Test fun httpsOnlyRefusesPlainHttpStartWithoutRequesting() {
        val fetcher = FakeFetcher()
        val reader = PageReader(fetcher, publicGuard, httpsOnly = true)
        val error = assertFails<SearchError.UnsafeUrl> { read(reader, url = "http://example.com/a") }

        assertFalse(error.requested)
        assertTrue(error.reason.contains("plain http"))
        assertTrue(fetcher.requests.isEmpty())
    }

    @Test fun httpsOnlyRefusesARedirectToPlainHttpAfterTheFirstRequest() {
        val fetcher = FakeFetcher(redirect("http://example.com/b"), HttpResponse(200, fixture("article.html"), html))
        val error = assertFails<SearchError.UnsafeUrl> { read(PageReader(fetcher, publicGuard, httpsOnly = true)) }

        assertTrue(error.requested)
        assertTrue(error.reason.contains("redirects to plain http"))
        assertEquals(1, fetcher.requests.size)
    }

    @Test fun httpsOnlyStillFollowsHttpsRedirects() {
        val fetcher = FakeFetcher(redirect("https://other.example/b"), HttpResponse(200, fixture("article.html"), html))
        assertEquals("https://other.example/b", read(PageReader(fetcher, publicGuard, httpsOnly = true)).url)
    }
}
