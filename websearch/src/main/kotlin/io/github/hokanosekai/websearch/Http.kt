package io.github.hokanosekai.websearch

import java.nio.charset.Charset

/**
 * Minimal HTTP seam. `java.net.http` does not exist on Android and this module must not pull
 * an HTTP stack, so the host supplies the implementation (OkHttp in the app, a fake in tests).
 *
 * Security contract, mandatory for implementations because the model picks the URLs:
 * - **Never follow redirects.** Return the 3xx response with its `Location` header: [PageReader]
 *   follows them itself and runs [UrlGuard] on every hop before it is requested (max 5 hops).
 * - **Pin DNS.** The name checked by [UrlGuard] and the address connected to must be the same
 *   lookup. With OkHttp, install a `Dns` that resolves normally and throws `UnknownHostException`
 *   when any returned address satisfies [UrlGuard.isPrivate]. Otherwise a hostile resolver can
 *   answer public at check time and private at connect time (DNS rebinding).
 * - **No proxy** (`Proxy.NO_PROXY`): a proxy resolves names on its own side and bypasses the Dns hook.
 *
 * Other rules:
 * - Main-safe: [get] and [post] are called from any dispatcher and must not block the caller's thread.
 * - Read at most `maxBytes` of the body (set [HttpResponse.truncated] when cut).
 * - Enforce `timeoutMs` as an overall call budget.
 * - Return non-2xx statuses as normal responses, and throw [java.io.IOException] only when no
 *   response was obtained (DNS, connect, TLS, timeout). Providers map those to [SearchError.Network].
 */
interface HttpFetcher {

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        maxBytes: Int = DEFAULT_MAX_BYTES,
    ): HttpResponse

    suspend fun post(
        url: String,
        body: String,
        contentType: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        maxBytes: Int = DEFAULT_MAX_BYTES,
    ): HttpResponse

    companion object {
        const val DEFAULT_TIMEOUT_MS = 10_000L
        const val DEFAULT_MAX_BYTES = 1_000_000
    }
}

/**
 * Response of an [HttpFetcher] call. The raw [bytes] are kept so the charset can be decided
 * by the header, then the page's own `<meta>`, then UTF-8 (see [PageReader]).
 */
class HttpResponse(
    val status: Int,
    val bytes: ByteArray,
    /** Header names lowercased. */
    val headers: Map<String, String> = emptyMap(),
    val truncated: Boolean = false,
) {
    /** Convenience for UTF-8 text bodies (JSON fixtures, tests). */
    constructor(
        status: Int,
        body: String,
        headers: Map<String, String> = emptyMap(),
        truncated: Boolean = false,
    ) : this(status, body.toByteArray(Charsets.UTF_8), headers, truncated)

    val contentType: String? get() = headers["content-type"]

    val isSuccess: Boolean get() = status in 200..299

    /** Charset named by the `Content-Type` header, null when absent or unknown. */
    val headerCharset: Charset?
        get() {
            val name = contentType?.let { CHARSET.find(it)?.groupValues?.get(1) } ?: return null
            return try {
                Charset.forName(name)
            } catch (e: Exception) {
                null
            }
        }

    /** Body decoded with the header charset, UTF-8 otherwise. */
    val body: String get() = String(bytes, headerCharset ?: Charsets.UTF_8)

    /** Seconds from a numeric `Retry-After` header, null when absent or an HTTP date. */
    val retryAfterSeconds: Long? get() = headers["retry-after"]?.trim()?.toLongOrNull()

    private companion object {
        val CHARSET = Regex("charset=\"?([^;\"\\s]+)", RegexOption.IGNORE_CASE)
    }
}
