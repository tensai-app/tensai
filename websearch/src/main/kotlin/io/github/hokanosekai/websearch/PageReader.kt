package io.github.hokanosekai.websearch

import java.io.ByteArrayInputStream
import java.net.URI
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

/**
 * Fetches a page and reduces it to readable text: no scripts, menus or footers, `article` or
 * `main` preferred, paragraph breaks kept, whitespace collapsed, title first.
 *
 * Redirects are followed here, not by the [HttpFetcher]: every hop goes through [UrlGuard]
 * before it is requested, up to [MAX_REDIRECTS]. Anti-bot walls raise [SearchError.Blocked],
 * there is no attempt to get around them.
 */
class PageReader(
    private val http: HttpFetcher,
    private val guard: UrlGuard = UrlGuard(),
    private val userAgent: String = MOBILE_USER_AGENT,
    private val maxBytes: Int = HttpFetcher.DEFAULT_MAX_BYTES,
) {

    suspend fun read(url: String, maxChars: Int): PageContent {
        require(maxChars > 0) { "maxChars must be positive" }
        val (response, finalUrl) = fetch(url)
        val head = response.body.take(BOT_SCAN_CHARS).lowercase()
        if (!response.isSuccess) {
            if (isBotWall(response, head, strict = false)) {
                throw SearchError.Blocked("anti-bot challenge (HTTP ${response.status})")
            }
            when (response.status) {
                429 -> throw SearchError.RateLimited(response.retryAfterSeconds)
                401, 403 -> throw SearchError.Blocked("HTTP ${response.status}")
                else -> throw SearchError.Network("HTTP ${response.status}")
            }
        }
        val type = response.contentType?.substringBefore(';')?.trim()?.lowercase()
        when (type) {
            null, "" -> sniffUnreadable(response.bytes)?.let { throw SearchError.Unsupported(it) }
            "text/html", "application/xhtml+xml", "text/plain" -> Unit
            else -> throw SearchError.Unsupported("content type $type")
        }
        if (isBotWall(response, head, strict = true)) throw SearchError.Blocked("anti-bot challenge page")

        if (type == "text/plain") {
            val text = normalize(response.body)
            if (text.isEmpty()) throw SearchError.Unsupported("page has no readable text")
            return truncate(finalUrl, "", text, maxChars, response.truncated)
        }

        // Charset: Content-Type header, then <meta>, then UTF-8 (jsoup order when given null).
        val doc = Jsoup.parse(ByteArrayInputStream(response.bytes), response.headerCharset?.name(), finalUrl)
        val title = doc.title().trim()
        doc.select("script, style, noscript, template, nav, footer, aside, form, iframe, svg").remove()
        // A header inside an article is its title block, only page-level headers are chrome.
        doc.select("header").filter { it.closest("article") == null }.forEach { it.remove() }
        val root = listOf("article", "main")
            .firstNotNullOfOrNull { tag -> doc.selectFirst(tag)?.takeIf { it.text().isNotBlank() } }
            ?: doc.body()
        val body = root?.let(::extractText).orEmpty()
        if (body.isBlank() && title.isEmpty()) throw SearchError.Unsupported("page has no readable text")
        return truncate(finalUrl, title, body, maxChars, response.truncated)
    }

    /** GET with manual redirects, each hop checked by the guard before the request. */
    private suspend fun fetch(startUrl: String): Pair<HttpResponse, String> {
        var current = guard.verify(startUrl)
        for (hop in 0..MAX_REDIRECTS) {
            val response = network {
                http.get(
                    url = current,
                    headers = mapOf("User-Agent" to userAgent, "Accept" to "text/html,application/xhtml+xml"),
                    maxBytes = maxBytes,
                )
            }
            if (response.status !in REDIRECTS) return response to current
            if (hop == MAX_REDIRECTS) throw SearchError.Network("too many redirects")
            val location = response.headers["location"]?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw SearchError.Network("redirect without Location")
            val next = try {
                URI(current).resolve(location).toString()
            } catch (e: Exception) {
                throw SearchError.UnsafeUrl("malformed redirect target")
            }
            current = guard.verify(next)
        }
        error("unreachable")
    }

    private fun truncate(url: String, title: String, body: String, maxChars: Int, cut: Boolean): PageContent {
        // The body usually repeats the title as its h1, don't print it twice.
        val full = when {
            title.isEmpty() -> body
            body.isEmpty() -> title
            body.startsWith(title) -> body
            else -> "$title\n\n$body"
        }
        if (full.length <= maxChars) return PageContent(url, title, full, cut)
        var end = maxChars - 1
        // Never leave half of a surrogate pair before the ellipsis.
        if (end > 0 && Character.isHighSurrogate(full[end - 1])) end--
        return PageContent(url, title, full.take(end).trimEnd() + ELLIPSIS, true)
    }

    /**
     * Cloudflare-style walls. A 2xx page is only called a wall on strong evidence (the
     * `cf-mitigated: challenge` header, the "Just a moment" title, the challenge form), since
     * ordinary sites embed Cloudflare scripts too. On 403/503 the broad markers count as well.
     */
    private fun isBotWall(response: HttpResponse, head: String, strict: Boolean): Boolean {
        if (response.headers["cf-mitigated"]?.contains("challenge", ignoreCase = true) == true) return true
        if (STRONG_MARKERS.any { it.containsMatchIn(head) }) return true
        val challengeStatus = response.status == 403 || response.status == 503
        return !strict && challengeStatus && BROAD_MARKERS.any { head.contains(it) }
    }

    /** Null when the bytes look like text, otherwise why they do not (no Content-Type to trust). */
    private fun sniffUnreadable(bytes: ByteArray): String? {
        val sample = bytes.take(SNIFF_BYTES)
        if (String(bytes, 0, minOf(4, bytes.size), Charsets.ISO_8859_1) == "%PDF") return "PDF document"
        if (sample.any { it.toInt() == 0 }) return "binary content"
        val control = sample.count { val c = it.toInt() and 0xFF; c < 0x20 && c != 9 && c != 10 && c != 13 }
        return if (sample.isNotEmpty() && control * 10 > sample.size) "binary content" else null
    }

    /** Block elements become paragraph breaks, `br` a line break, other text is whitespace-collapsed. */
    private fun extractText(root: Element): String {
        val out = StringBuilder()
        NodeTraversor.traverse(
            object : NodeVisitor {
                override fun head(node: Node, depth: Int) {
                    when {
                        node is TextNode -> out.append(node.text())
                        node is Element && node.nameIs("br") -> out.append('\n')
                        node is Element && node.tagName() in BLOCK_TAGS -> out.append("\n\n")
                    }
                }

                override fun tail(node: Node, depth: Int) {
                    if (node is Element && node.tagName() in BLOCK_TAGS) out.append("\n\n")
                }
            },
            root,
        )
        return normalize(out.toString())
    }

    private fun normalize(raw: String): String = raw
        .lines()
        .joinToString("\n") { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    companion object {
        const val MAX_REDIRECTS = 5
        private const val ELLIPSIS = "…"
        private const val BOT_SCAN_CHARS = 20_000
        private const val SNIFF_BYTES = 1024
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private val BLOCK_TAGS = setOf(
            "p", "div", "section", "article", "main", "h1", "h2", "h3", "h4", "h5", "h6",
            "ul", "ol", "li", "pre", "blockquote", "table", "tr", "hr", "figure", "figcaption",
        )
        private val STRONG_MARKERS = listOf(
            Regex("<title>\\s*(just a moment|attention required)"),
            Regex("id=\"challenge-form\""),
            Regex("cf-browser-verification"),
        )
        private val BROAD_MARKERS = listOf(
            "challenge-platform",
            "cf-chl-",
            "enable javascript and cookies to continue",
            "checking your browser before accessing",
        )
    }
}
