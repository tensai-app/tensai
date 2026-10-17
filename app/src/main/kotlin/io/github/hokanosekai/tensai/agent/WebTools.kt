package io.github.hokanosekai.tensai.agent

import io.github.hokanosekai.websearch.FallbackSearch
import io.github.hokanosekai.websearch.PageReader
import io.github.hokanosekai.websearch.SearchError
import io.github.hokanosekai.websearch.SearchResult
import java.net.URI
import org.json.JSONException
import org.json.JSONObject

/** Sizes of what the web tools hand back, under [AgentRuntime]'s result budget (512 tokens, about 2,000 characters). */
object WebToolLimits {
    /** Room for a whole `web_search` answer; the runtime would cut anything longer. */
    const val ResultChars = 1_900
    const val DefaultLimit = 5
    const val MaxLimit = 8
    const val SnippetChars = 220
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
 * @param setup the chain for the current Settings and the name of the provider the user chose, read at each call
 *   so a change in Settings applies to the next one. A `null` chain means the chosen provider has no key yet.
 */
class WebSearchTool(
    private val setup: suspend () -> Setup,
) : Tool {
    /** [chain] is `null` when the chosen provider needs an API key that is not stored; [chosen] is its display name. */
    class Setup(val chain: FallbackSearch?, val chosen: String)

    override val name = "web_search"
    override val description = "Searches the web. Returns titles, links and short snippets. Use web_fetch to read one result."
    override val parametersSchema =
        """{"type":"object","properties":{"query":{"type":"string"},"limit":{"type":"integer","description":"max ${WebToolLimits.MaxLimit}, default ${WebToolLimits.DefaultLimit}"}},"required":["query"]}"""
    override val tier = ToolTier.Network

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
     * A numbered list, title then link then snippet. It stops at the first result that does not fit, so the
     * numbering has no gap and no result is cut in half.
     */
    internal fun format(results: List<SearchResult>, source: String): String {
        val footer = "source: $source"
        val out = StringBuilder()
        for ((index, result) in results.withIndex()) {
            val block = buildString {
                append(index + 1).append(". ").append(WebToolLimits.clip(result.title, 120)).append('\n')
                append("   ").append(WebToolLimits.clip(result.url, WebToolLimits.UrlChars)).append('\n')
                val snippet = WebToolLimits.clip(result.snippet, WebToolLimits.SnippetChars)
                if (snippet.isNotEmpty()) append("   ").append(snippet).append('\n')
            }
            if (index > 0 && out.length + block.length + footer.length > WebToolLimits.ResultChars) break
            out.append(block)
        }
        return out.append(footer).toString()
    }
}

/**
 * `web_fetch`: one page as readable text, through [PageReader] (public pages only, no bot-wall bypass).
 *
 * The app's network config blocks cleartext, so the tool is https-only: an `http://` URL is upgraded to
 * `https://` before anything else, a site that only speaks http fails with a message saying so, and a redirect to
 * plain http is refused. Build the reader with `httpsOnly = true`.
 */
class WebFetchTool(
    private val reader: PageReader,
    private val maxChars: Int = WebToolLimits.PageChars,
) : Tool {
    override val name = "web_fetch"
    override val description = "Reads one web page and returns its text. Give a full https URL, for example one from web_search."
    override val parametersSchema = """{"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}"""
    override val tier = ToolTier.Network

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
