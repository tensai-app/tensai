package io.github.tensaiapp.ui.screens.models

import io.github.tensaiapp.hf.QuantLabel
import java.net.URLDecoder

/** What the Discover search field holds, once read (TEN-139). */
internal sealed interface DiscoverQuery {
    /** Blank: the Discover home. */
    data object Empty : DiscoverQuery

    /** Plain words to search for. */
    data class Text(val query: String) : DiscoverQuery

    /**
     * A Hugging Face repo link: [id] is `author/name`, [file] the GGUF the link points at (path inside
     * the repo) when it is a file link, null for a repo link.
     */
    data class Repo(val id: String, val file: String?) : DiscoverQuery

    /** A URL that is not a Hugging Face repo or file: refused with an inline error, nothing is requested. */
    data object Foreign : DiscoverQuery
}

/**
 * Reads the search field. Anything URL-shaped is either a huggingface.co / hf.co repo or file link
 * ([DiscoverQuery.Repo]) or [DiscoverQuery.Foreign]: a link never becomes a search and never a
 * request. Pure, so it runs on the JVM.
 */
internal object DiscoverQueryParser {

    private val SCHEME = Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE)
    private val WWW = Regex("^www\\.", RegexOption.IGNORE_CASE)

    // `hf.co/foo`, `huggingface.co/a/b`: a dotted host followed by a slash, no scheme.
    private val BARE_URL = Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+/\\S*", RegexOption.IGNORE_CASE)
    private val SEGMENT = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
    private val HOSTS = setOf("huggingface.co", "hf.co")
    private val FILE_KINDS = setOf("blob", "resolve", "raw")

    // First path segments of the site that are not an author.
    private val RESERVED = setOf(
        "spaces", "datasets", "docs", "models", "papers", "collections", "blog", "settings", "api",
        "organizations", "join", "login", "tasks", "posts", "learn", "new", "enterprise", "pricing", "chat", "buckets",
    )

    fun parse(raw: String): DiscoverQuery {
        val s = raw.trim()
        if (s.isEmpty()) return DiscoverQuery.Empty
        val looksUrl = SCHEME.containsMatchIn(s) || WWW.containsMatchIn(s) || BARE_URL.matches(s)
        return if (looksUrl) parseLink(s) else DiscoverQuery.Text(s)
    }

    private fun parseLink(s: String): DiscoverQuery {
        // Only http(s): `ftp://huggingface.co/...` is not a link to the site.
        if (SCHEME.containsMatchIn(s) && !s.startsWith("http://", ignoreCase = true) && !s.startsWith("https://", ignoreCase = true)) {
            return DiscoverQuery.Foreign
        }
        val rest = s.replace(SCHEME, "")
        val hostEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        val host = rest.substring(0, hostEnd).lowercase().replace(WWW, "")
        if (host !in HOSTS) return DiscoverQuery.Foreign
        val path = rest.substring(hostEnd).substringBefore('#').substringBefore('?')
        val segments = path.split('/').filter { it.isNotEmpty() }.map(::decode)
        if (segments.size < 2 || segments[0].lowercase() in RESERVED) return DiscoverQuery.Foreign
        val author = segments[0]
        // `hf.co/author/name:Q4_K_M` (the llama.cpp spelling): the tag is the quant, not part of the name.
        val name = segments[1].substringBefore(':')
        if (!SEGMENT.matches(author) || !SEGMENT.matches(name)) return DiscoverQuery.Foreign
        val file = if (segments.size >= 5 && segments[2] in FILE_KINDS) {
            segments.drop(4).joinToString("/").takeIf { QuantLabel.isGguf(it) && ".." !in it.split('/') }
        } else {
            null
        }
        return DiscoverQuery.Repo("$author/$name", file)
    }

    // URLDecoder reads `+` as a space: file names keep their plus signs.
    private fun decode(segment: String): String =
        runCatching { URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8") }.getOrDefault(segment)
}
