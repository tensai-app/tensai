package io.github.tensaiapp.agent

import io.github.tensaiapp.websearch.FallbackSearch
import io.github.tensaiapp.websearch.PageReader
import io.github.tensaiapp.websearch.SearchError
import io.github.tensaiapp.websearch.SearchResult
import java.net.URI
import org.json.JSONException
import org.json.JSONObject

/** Sizes of what the web tools hand back, under [AgentRuntime]'s result budget (512 tokens, about 2,000 characters). */
object WebToolLimits {
    /** Room for a whole result; the runtime would cut anything longer. */
    const val ResultChars = 1_900

    /**
     * Room for a whole `web_search` answer, below [ResultChars]. At about 3 characters a token, what URLs and Latin titles
     * take, a full answer stays under the 512-token result budget; CJK titles or percent-encoded URLs take more tokens a
     * character and can pass it, and then [AgentRuntime]'s token cut drops the tail. Ten typical results take about 1,100.
     */
    const val SearchChars = 1_500
    const val DefaultLimit = 8
    const val MaxLimit = 10
    const val UrlChars = 300

    /** Page text kept by `web_fetch`, the rest of [ResultChars] being the URL header and the cut notice. */
    const val PageChars = 1_700

    /** Cuts [text] to [max] characters with an ellipsis, on a word boundary when one is close, never inside a surrogate pair. */
    fun clip(text: String, max: Int): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        if (flat.length <= max) return flat
        var end = max - 1
        if (end > 0 && Character.isHighSurrogate(flat[end - 1])) end--
        val cut = flat.take(end)
        val space = cut.lastIndexOf(' ')
        return (if (space > max / 2) cut.substring(0, space) else cut).trimEnd() + "…"
    }
}

/**
 * `web_search`: the search provider chosen in Settings, with the free fallbacks of [SearchProviders].
 *
 * A list to pick from, not an answer (TEN-183): titles and links, no snippet. A snippet read as the answer, so the model
 * answered from it instead of reading a page; without them more results fit, and `web_fetch` reads the one it picks.
 *
 * Never asks: the query goes to the search engine the user chose, not to a host the model picks, so it cannot hand
 * data to a third party of the model's choosing. The links it returns are what lets `web_fetch` read a result
 * without asking, see [ConversationTrust].
 *
 * @param setup the chain for the current Settings and the name of the provider the user chose, read at each call
 *   so a change in Settings applies to the next one. A `null` chain means the chosen provider has no key yet.
 */
class WebSearchTool(
    private val setup: suspend () -> Setup,
) : Tool {
    /** [chain] is `null` when the chosen provider needs an API key that is not stored; [chosen] is its display name. */
    class Setup(val chain: FallbackSearch?, val chosen: String)

    override val name = NAME
    override val description = "Finds web pages: titles and links only. Read one or more with web_fetch before giving a precise fact."
    override val parametersSchema =
        """{"type":"object","properties":{"query":{"type":"string"},"limit":{"type":"integer","description":"max ${WebToolLimits.MaxLimit}"}},"required":["query"]}"""
    override val tier = ToolTier.Network
    override val returnsUntrustedContent = true

    override suspend fun execute(argsJson: String): ToolResult {
        val args = try {
            JSONObject(argsJson)
        } catch (e: JSONException) {
            return ToolResult.error("arguments are not a JSON object")
        }
        val query = args.stringArg("query").orEmpty().trim()
        if (query.isEmpty()) return ToolResult.error("query is empty")
        val limit = args.optInt("limit", WebToolLimits.DefaultLimit).coerceIn(1, WebToolLimits.MaxLimit)

        val configured = setup()
        // Nothing is sent without a key, so there is no destination to show.
        val chain = configured.chain
            ?: return ToolResult.error(
                "${configured.chosen} needs an API key and none is set. Ask the user to add it in Settings > Tools > Internet.",
            )
        // Every provider the query went to, in order: a fallback means the query reached several.
        val tried = mutableListOf<String>()
        fun destination() = tried.map(::providerDisplayName).distinct().joinToString(", ").ifEmpty { configured.chosen }
        return try {
            val results = chain.search(query, limit) { tried += it }
            if (results.isEmpty()) {
                ToolResult.ok("no results", destination = destination())
            } else {
                ToolResult.ok(format(results, providerDisplayName(results.first().source)), destination = destination())
            }
        } catch (e: SearchError) {
            ToolResult.error(e.toModelMessage(configured.chosen), destination = destination())
        }
    }

    /**
     * A numbered list, each title with its link on the line under it, the shape [FetchUrls.inSearchResult] reads. A
     * result whose link is longer than [WebToolLimits.UrlChars] is left out: clipped, it would be a dead link the search
     * vouches for. It stops at the first result that does not fit [WebToolLimits.SearchChars], so the numbering has no
     * gap and no result is cut in half.
     */
    internal fun format(results: List<SearchResult>, source: String): String {
        val footer = "source: $source"
        val out = StringBuilder()
        var listed = 0
        for (result in results) {
            val url = result.url.trim()
            if (url.length > WebToolLimits.UrlChars) continue
            val block = buildString {
                append(listed + 1).append(". ").append(WebToolLimits.clip(result.title, 120)).append('\n')
                append("   ").append(url).append('\n')
            }
            if (listed > 0 && out.length + block.length + footer.length > WebToolLimits.SearchChars) break
            out.append(block)
            listed++
        }
        return if (listed == 0) "no results" else out.append(footer).toString()
    }

    companion object {
        const val NAME = "web_search"
    }
}

/**
 * `web_fetch`: one page as readable text, through [PageReader] (public pages only, no bot-wall bypass).
 *
 * The app's network config blocks cleartext, so the tool is https-only: an `http://` URL is upgraded to
 * `https://` before anything else, a site that only speaks http fails with a message saying so, and a redirect to
 * plain http is refused. Build the reader with `httpsOnly = true`.
 *
 * Asks first unless the conversation vouches for the URL ([ConversationTrust.vouchesFor]): a link the user wrote or
 * `web_search` returned, in any turn, is read at once, any other is one the model built, and its path or query could carry what a tool read on
 * the phone (the clipboard, a contact) to a host of an injection's choosing. The card shows the whole URL.
 */
class WebFetchTool(
    private val reader: PageReader,
    private val maxChars: Int = WebToolLimits.PageChars,
) : Tool {
    override val name = "web_fetch"
    override val description = "Reads one web page, given its full https URL."
    override val parametersSchema = """{"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}"""
    override val tier = ToolTier.Network
    override val returnsUntrustedContent = true

    override fun requiresConfirmation(argsJson: String, trust: ConversationTrust): Boolean {
        val url = runCatching { JSONObject(argsJson).stringArg("url") }.getOrNull() ?: return true
        return !trust.vouchesFor(url)
    }

    override suspend fun execute(argsJson: String): ToolResult {
        val args = try {
            JSONObject(argsJson)
        } catch (e: JSONException) {
            return ToolResult.error("arguments are not a JSON object")
        }
        val given = args.stringArg("url").orEmpty().trim()
        if (given.isEmpty()) return ToolResult.error("url is empty")
        val upgraded = given.startsWith("http://", ignoreCase = true)
        val url = if (upgraded) "https://" + given.substring("http://".length) else given
        val startHost = hostOf(url)
        return try {
            val page = reader.read(url, maxChars)
            val body = buildString {
                append("url: ").append(page.url).append("\n\n").append(page.text)
                if (page.truncated) append("\n[page cut]")
            }
            // The page may have moved: the host that answered is the one the request ended at.
            ToolResult.ok(body, destination = hostOf(page.url) ?: startHost)
        } catch (e: SearchError.UnsafeUrl) {
            // Nothing left the phone unless an earlier hop of this very read was already sent.
            ToolResult.error(e.toModelMessage(startHost ?: url), destination = if (e.requested) startHost else null)
        } catch (e: SearchError.Network) {
            val message = if (upgraded) {
                "this page may only be served over http, which is not supported (the https request failed: ${e.detail})"
            } else {
                e.toModelMessage(startHost ?: "the site")
            }
            ToolResult.error(message, destination = startHost)
        } catch (e: SearchError) {
            ToolResult.error(e.toModelMessage(startHost ?: "the site"), destination = startHost)
        }
    }

    /** The host of [url] without `www.`; for an address `java.net.URI` has no host for (underscores, IDN), the raw authority. */
    internal fun hostOf(url: String): String? {
        val parsed = try {
            URI(url).host
        } catch (e: Exception) {
            null
        }
        val host = parsed ?: url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringAfterLast('@').substringBefore(':')
        return host.removePrefix("www.").ifEmpty { null }
    }
}

/**
 * The URLs a conversation vouches for, see [ConversationTrust]. Two URLs are the same page when their [key]s are: scheme, host,
 * port, path and query as written. Only the case of the host, the fragment (never sent) and http against https
 * (`web_fetch` upgrades it) are let go, so a model cannot add a parameter to a vouched link.
 */
internal object FetchUrls {
    private val numbered = Regex("^\\d+\\. ")

    /** The page [url] reads, `null` for one that is not an http(s) URL with a host and no credentials. */
    fun key(url: String): String? {
        val uri = try {
            URI(url.trim())
        } catch (e: Exception) {
            return null
        }
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.rawUserInfo != null) return null
        val host = uri.host?.lowercase() ?: return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        val path = uri.rawPath.orEmpty().ifEmpty { "/" }
        return "https://$host$port$path" + uri.rawQuery?.let { "?$it" }.orEmpty()
    }

    /**
     * The link of each result of a `web_search` answer: the line under each numbered title, as [WebSearchTool.format]
     * writes it. A URL inside a title or a snippet is page text, not a result, and stays the model's.
     */
    fun inSearchResult(text: String): Set<String> {
        val lines = text.lines()
        return lines.indices.filter { numbered.containsMatchIn(lines[it]) }
            .mapNotNullTo(HashSet()) { i -> lines.getOrNull(i + 1)?.takeIf { it.startsWith("   ") }?.let(::key) }
    }

    /**
     * The links the user wrote, with or without a scheme ("lemonde.fr/sport" reads as https), without the
     * punctuation around them.
     */
    fun inUserText(text: String): Set<String> = text.split(Regex("\\s+")).mapNotNullTo(HashSet()) { word ->
        val token = word.trimStart('(', '<', '[', '"', '\'', '«').trimEnd('.', ',', ';', ':', '!', '?', ')', '>', ']', '"', '\'', '»')
        when {
            token.contains("://") -> key(token)
            '.' in token && '@' !in token -> key("https://$token")
            else -> null
        }
    }
}
