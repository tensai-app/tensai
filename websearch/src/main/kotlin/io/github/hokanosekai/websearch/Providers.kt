package io.github.hokanosekai.websearch

import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.jsoup.Jsoup

/** Browser-like mobile UA for the scraped endpoints. */
const val MOBILE_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/124.0.0.0 Mobile Safari/537.36"

private val lenientJson = Json { ignoreUnknownKeys = true }

internal fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

/** Runs [block], turning transport failures into [SearchError.Network]. */
internal suspend fun <T> network(block: suspend () -> T): T = try {
    block()
} catch (e: IOException) {
    throw SearchError.Network(e.message ?: e.javaClass.simpleName, e)
}

/** Parses a JSON body, any structural problem is [SearchError.FormatChanged]. */
internal fun parseJsonObject(body: String, provider: String): JsonObject = try {
    lenientJson.parseToJsonElement(body).jsonObject
} catch (e: Exception) {
    throw SearchError.FormatChanged("$provider returned non-JSON or a non-object body")
}

internal fun JsonElement?.str(): String = (this as? JsonPrimitive)?.contentOrNull.orEmpty()

internal fun JsonElement?.array(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())

/** Strips markup such as `<span class="searchmatch">` from API snippets. */
internal fun stripHtml(html: String): String = Jsoup.parse(html).text().trim()

/** Shared HTTP status mapping, providers override the codes that mean something specific. */
internal fun HttpResponse.failOnStatus(
    provider: String,
    unauthorized: Set<Int> = setOf(401),
    rateLimited: Set<Int> = setOf(429),
    quota: Set<Int> = emptySet(),
) {
    if (isSuccess) return
    when (status) {
        in unauthorized -> throw SearchError.Unauthorized("$provider rejected the API key (HTTP $status)")
        in rateLimited -> throw SearchError.RateLimited(retryAfterSeconds)
        in quota -> throw SearchError.QuotaExceeded("$provider plan limit reached (HTTP $status)")
        else -> throw SearchError.Network("$provider answered HTTP $status")
    }
}

/**
 * DuckDuckGo's HTML endpoint. No official API and no scraping agreement: expect it to break or
 * throttle, always pair it with a fallback ([FallbackSearch]).
 */
class DuckDuckGoHtml(
    private val http: HttpFetcher,
    private val userAgent: String = MOBILE_USER_AGENT,
    private val endpoint: String = "https://html.duckduckgo.com/html/",
) : SearchProvider {
    override val name = "duckduckgo"

    override suspend fun search(query: String, limit: Int): List<SearchResult> {
        val response = network {
            http.post(
                url = endpoint,
                body = "q=${urlEncode(query)}&l=wt-wt&s=0",
                contentType = "application/x-www-form-urlencoded",
                headers = mapOf("User-Agent" to userAgent, "Accept" to "text/html"),
            )
        }
        // 202 is DDG's "come back later" with an empty page.
        if (response.status == 202) throw SearchError.RateLimited(response.retryAfterSeconds)
        if (response.status == 403) throw SearchError.Blocked("DuckDuckGo refused the request (HTTP 403)")
        response.failOnStatus(name)
        return parse(response.body, limit)
    }

    internal fun parse(html: String, limit: Int): List<SearchResult> {
        val doc = Jsoup.parse(html, "https://html.duckduckgo.com/")
        val blocks = doc.select("div.result__body").ifEmpty { doc.select("div.web-result") }
        if (blocks.isEmpty()) {
            // The bot challenge is detected by its markup, not by the word "anomaly": real
            // results can mention it ("anomaly detection").
            if (doc.select("div.anomaly-modal__modal, form#challenge-form, form[action*=anomaly]").isNotEmpty()) {
                throw SearchError.Blocked("DuckDuckGo anomaly page (bot detection)")
            }
            // A real "no results" page says so, anything else means the layout moved.
            if (doc.select("div.no-results").isNotEmpty()) return emptyList()
            throw SearchError.FormatChanged("no result blocks in DuckDuckGo page")
        }
        val results = ArrayList<SearchResult>()
        for (block in blocks) {
            if (results.size >= limit) break
            if (block.closest("div.result--ad") != null) continue
            val link = block.selectFirst("h2 a[href]") ?: continue
            val href = link.attr("href")
            if (href.contains("duckduckgo.com/y.js")) continue
            val url = unwrapRedirect(href) ?: continue
            val title = link.text().trim()
            if (title.isEmpty()) continue
            val snippet = block.selectFirst(".result__snippet")?.text()?.trim().orEmpty()
            results += SearchResult(title, url, snippet, name)
        }
        return results
    }

    /** Result links are `//duckduckgo.com/l/?uddg=<encoded target>&rut=...` unless already direct. */
    private fun unwrapRedirect(href: String): String? {
        val absolute = if (href.startsWith("//")) "https:$href" else href
        val target = if (absolute.contains("duckduckgo.com/l/")) {
            val uddg = absolute.substringAfter('?', "").split('&').firstOrNull { it.startsWith("uddg=") }
                ?: return null
            try {
                URLDecoder.decode(uddg.removePrefix("uddg="), "UTF-8")
            } catch (e: IllegalArgumentException) {
                // Malformed % escape: skip this result rather than fail the page.
                return null
            }
        } else {
            absolute
        }
        return target.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }
}

/**
 * Wikipedia REST search, no key. [userAgent] must be descriptive
 * (`tensai/<version> (<repo>; <contact>)`), Wikimedia answers 403 otherwise.
 */
class Wikipedia(
    private val http: HttpFetcher,
    private val userAgent: String,
    private val language: String = "en",
) : SearchProvider {
    override val name = "wikipedia"

    init {
        // The language becomes part of the host name.
        require(Regex("[a-z]{2,3}(-[a-z0-9]{1,8})*").matches(language)) { "invalid Wikipedia language: $language" }
    }

    override suspend fun search(query: String, limit: Int): List<SearchResult> {
        val host = "https://$language.wikipedia.org"
        val response = network {
            http.get(
                url = "$host/w/rest.php/v1/search/page?q=${urlEncode(query)}&limit=$limit",
                headers = mapOf("User-Agent" to userAgent, "Accept" to "application/json"),
            )
        }
        if (response.status == 403) throw SearchError.Blocked("Wikimedia refused the User-Agent (HTTP 403)")
        response.failOnStatus(name)
        val pages = parseJsonObject(response.body, name)["pages"]
            ?: throw SearchError.FormatChanged("Wikipedia response has no 'pages'")
        return pages.array().take(limit).mapNotNull { page ->
            val obj = page as? JsonObject ?: return@mapNotNull null
            val key = obj["key"].str().ifEmpty { return@mapNotNull null }
            val excerpt = stripHtml(obj["excerpt"].str()).ifEmpty { obj["description"].str() }
            SearchResult(
                title = obj["title"].str().ifEmpty { key },
                url = "$host/wiki/${urlEncode(key).replace("+", "_")}",
                snippet = excerpt,
                source = name,
            )
        }
    }
}

/** Tavily search (key required, free tier without card). One credit per basic request. */
class Tavily(
    private val http: HttpFetcher,
    private val apiKey: String,
    private val endpoint: String = "https://api.tavily.com/search",
) : SearchProvider {
    override val name = "tavily"

    override suspend fun search(query: String, limit: Int): List<SearchResult> {
        val body = JsonObject(
            mapOf(
                "query" to JsonPrimitive(query),
                // Explicit: "advanced" costs 2 credits.
                "search_depth" to JsonPrimitive("basic"),
                "max_results" to JsonPrimitive(limit.coerceIn(1, 20)),
            ),
        ).toString()
        val response = network {
            http.post(
                url = endpoint,
                body = body,
                contentType = "application/json",
                headers = mapOf("Authorization" to "Bearer $apiKey", "Accept" to "application/json"),
            )
        }
        // 432/433: plan or pay-as-you-go limit.
        response.failOnStatus(name, quota = setOf(432, 433))
        val results = parseJsonObject(response.body, name)["results"]
            ?: throw SearchError.FormatChanged("Tavily response has no 'results'")
        return results.array().take(limit).mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val url = obj["url"].str().ifEmpty { return@mapNotNull null }
            SearchResult(obj["title"].str().ifEmpty { url }, url, obj["content"].str(), name)
        }
    }
}

/** Brave Search API (key required, card required since early 2026). */
class Brave(
    private val http: HttpFetcher,
    private val apiKey: String,
    private val endpoint: String = "https://api.search.brave.com/res/v1/web/search",
) : SearchProvider {
    override val name = "brave"

    override suspend fun search(query: String, limit: Int): List<SearchResult> {
        val response = network {
            http.get(
                url = "$endpoint?q=${urlEncode(query)}&count=${limit.coerceAtMost(MAX_COUNT)}",
                headers = mapOf("X-Subscription-Token" to apiKey, "Accept" to "application/json"),
            )
        }
        // 422 is how Brave answers a missing or invalid subscription token.
        response.failOnStatus(name, unauthorized = setOf(401, 422), quota = setOf(402))
        val root = parseJsonObject(response.body, name)
        val results = (root["web"] as? JsonObject)?.get("results")
        if (results == null) {
            // A query with no hit omits the "web" block but still says type=search.
            if (root["type"].str() == "search") return emptyList()
            throw SearchError.FormatChanged("Brave response has no 'web.results'")
        }
        return results.array().take(limit).mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val url = obj["url"].str().ifEmpty { return@mapNotNull null }
            SearchResult(obj["title"].str().ifEmpty { url }, url, stripHtml(obj["description"].str()), name)
        }
    }

    private companion object {
        const val MAX_COUNT = 20
    }
}

/**
 * Marginalia, independent small-web index. The shared `public` key is often throttled (503)
 * and the data is CC-BY-NC-SA: last-resort fallback only.
 */
class Marginalia(
    private val http: HttpFetcher,
    private val apiKey: String = "public",
    private val endpoint: String = "https://api2.marginalia-search.com/search",
) : SearchProvider {
    override val name = "marginalia"

    override suspend fun search(query: String, limit: Int): List<SearchResult> {
        val response = network {
            http.get(
                url = "$endpoint?query=${urlEncode(query)}&count=$limit",
                headers = mapOf("API-Key" to apiKey, "Accept" to "application/json"),
            )
        }
        // 503 is how the shared key says "too busy".
        response.failOnStatus(name, unauthorized = setOf(401, 403), rateLimited = setOf(429, 503))
        val results = parseJsonObject(response.body, name)["results"]
            ?: throw SearchError.FormatChanged("Marginalia response has no 'results'")
        return results.array().take(limit).mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            val url = obj["url"].str().ifEmpty { return@mapNotNull null }
            SearchResult(obj["title"].str().ifEmpty { url }, url, obj["description"].str(), name)
        }
    }
}
