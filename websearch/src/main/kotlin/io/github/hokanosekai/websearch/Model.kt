package io.github.hokanosekai.websearch

/** One hit of a [SearchProvider]. [source] is the [SearchProvider.name] that produced it. */
data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    val source: String,
)

/** Web search backend. Failures are thrown as [SearchError], never as raw IO exceptions. */
interface SearchProvider {
    val name: String

    /** At most [limit] results (limit >= 1), possibly empty. */
    suspend fun search(query: String, limit: Int): List<SearchResult>
}

/** Readable text of a page, see [PageReader]. */
data class PageContent(
    val url: String,
    val title: String,
    val text: String,
    val truncated: Boolean,
)

/** Typed failures, so the caller can pick a fallback or tell the user something precise. */
sealed class SearchError(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Anti-bot wall, captcha, challenge page or plain refusal. */
    class Blocked(val reason: String) : SearchError("Blocked: $reason")

    class RateLimited(val retryAfterSeconds: Long? = null) :
        SearchError("Rate limited" + (retryAfterSeconds?.let { " (retry after ${it}s)" } ?: ""))

    /** Plan or monthly quota exhausted, retrying later in the day will not help. */
    class QuotaExceeded(val detail: String) : SearchError("Quota exceeded: $detail")

    /** Missing or rejected API key. */
    class Unauthorized(val detail: String) : SearchError("Unauthorized: $detail")

    /** The page or JSON no longer looks like what the parser expects. */
    class FormatChanged(val detail: String) : SearchError("Format changed: $detail")

    class Network(val detail: String, cause: Throwable? = null) : SearchError("Network: $detail", cause)

    /** Content that is not readable text (binary, PDF, ...). */
    class Unsupported(val detail: String) : SearchError("Unsupported content: $detail")

    /**
     * URL refused by [UrlGuard] (scheme, private or loopback target) or by [PageReader] (plain http when it is
     * https-only). [requested] is true when an earlier hop of the same read was already sent, so something did
     * leave the device and the caller should still show where it went.
     */
    class UnsafeUrl(val reason: String, val requested: Boolean = false) : SearchError("Unsafe URL: $reason")
}
