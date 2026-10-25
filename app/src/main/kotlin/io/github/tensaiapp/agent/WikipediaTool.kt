package io.github.tensaiapp.agent

import io.github.tensaiapp.websearch.HttpFetcher
import io.github.tensaiapp.websearch.SearchError
import io.github.tensaiapp.websearch.Wikipedia
import org.json.JSONException
import org.json.JSONObject

/**
 * `wikipedia`: the summary of the best matching article, with its link. Searches with the module's [Wikipedia]
 * provider, then reads `/api/rest_v1/page/summary/{title}` for the top hit. Text is CC BY-SA: the answer carries
 * the article URL. Anyone can edit an article: [returnsUntrustedContent].
 *
 * @param userAgent the descriptive one Wikimedia requires (`tensai/<version> (+<repo>)`), 403 otherwise.
 */
class WikipediaTool(
    private val http: HttpFetcher,
    private val userAgent: String,
    private val defaultLanguage: () -> String = { "en" },
) : Tool {
    override val name = "wikipedia"
    override val description = "Summary and link of the best Wikipedia article on a topic."
    override val parametersSchema =
        """{"type":"object","properties":{"query":{"type":"string"},"lang":{"type":"string","description":"language code like en or fr, default en"}},"required":["query"]}"""
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
        val lang = (args.stringArg("lang")?.trim()?.lowercase()).takeUnless { it.isNullOrEmpty() } ?: defaultLanguage()
        if (!LANGUAGE.matches(lang)) return ToolResult.error("lang must be a language code like en or fr, got '$lang'")

        val destination = "Wikipedia ($lang)"
        return try {
            val hit = Wikipedia(http, userAgent, lang).search(query, 1).firstOrNull()
                ?: return ToolResult.ok("no article found", destination = destination)
            // The search URL ends with the encoded title key, which is what the summary endpoint takes.
            val key = hit.url.substringAfter("/wiki/")
            val summary = try {
                http.getJson("https://$lang.wikipedia.org/api/rest_v1/page/summary/$key")
            } catch (e: SearchError) {
                // Any failure of the summary (missing, rate limited, odd JSON) still leaves the search hit.
                null
            }
            ToolResult.ok(format(hit.title, hit.url, hit.snippet, summary), destination = destination)
        } catch (e: SearchError) {
            ToolResult.error(e.toModelMessage("Wikipedia"), destination = destination)
        }
    }

    private fun format(title: String, url: String, snippet: String, summary: JSONObject?): String {
        val heading = summary?.optString("title")?.takeIf { it.isNotBlank() } ?: title
        val description = summary?.optString("description").orEmpty()
        val extract = summary?.optString("extract")?.takeIf { it.isNotBlank() } ?: snippet
        val link = summary?.optJSONObject("content_urls")?.optJSONObject("desktop")?.optString("page")?.takeIf { it.isNotBlank() } ?: url
        return buildString {
            append(heading)
            if (description.isNotBlank()) append(" (").append(description).append(')')
            append('\n').append(WebToolLimits.clip(extract, EXTRACT_CHARS)).append('\n')
            append(link).append("\nsource: Wikipedia, CC BY-SA")
        }
    }

    private companion object {
        const val EXTRACT_CHARS = 1_400
        val LANGUAGE = Regex("[a-z]{2,3}(-[a-z0-9]{1,8})*")
    }
}
