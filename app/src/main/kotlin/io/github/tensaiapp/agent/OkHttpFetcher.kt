package io.github.tensaiapp.agent

import io.github.tensaiapp.websearch.HttpFetcher
import io.github.tensaiapp.websearch.HttpResponse
import io.github.tensaiapp.websearch.UrlGuard
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer

/**
 * The [HttpFetcher] of the `:websearch` module on OkHttp. It honors the module's security contract, which
 * exists because the model chooses the URLs:
 * - redirects are not followed (`followRedirects(false)`, `followSslRedirects(false)`): the 3xx comes back
 *   with its `Location` and `PageReader` re-checks every hop;
 * - DNS is pinned: [GuardedDns] refuses a name that resolves to a private address, and a network interceptor
 *   checks the address the socket actually connected to, which also covers IP literals (OkHttp skips Dns for those);
 * - no proxy, a proxy would resolve names on its own side;
 * - the body is read through a cap, and the whole call is bounded by a call timeout.
 *
 * Main-safe: the work runs on [Dispatchers.IO] and the call is cancelled with the coroutine.
 *
 * @param userAgent sent when a request carries none: the descriptive one (`tensai/<version> (+<repo>)`) that
 *   Wikimedia and Open-Meteo ask for. DuckDuckGo and page fetches pass a mobile browser UA of their own, since
 *   they refuse or throttle anything that does not look like a browser.
 */
class OkHttpFetcher(
    private val client: OkHttpClient = guardedClient(),
    private val userAgent: String,
) : HttpFetcher {

    override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Long, maxBytes: Int): HttpResponse =
        execute(buildRequest { Request.Builder().url(url).applyHeaders(headers).build() }, timeoutMs, maxBytes)

    override suspend fun post(
        url: String,
        body: String,
        contentType: String,
        headers: Map<String, String>,
        timeoutMs: Long,
        maxBytes: Int,
    ): HttpResponse {
        val request = buildRequest {
            Request.Builder()
                .url(url)
                .applyHeaders(headers)
                .post(body.toRequestBody(contentType.toMediaType()))
                .build()
        }
        return execute(request, timeoutMs, maxBytes)
    }

    /** A malformed URL or header is a transport failure for the module (`IOException`), not an `IllegalArgumentException`. */
    private inline fun buildRequest(build: () -> Request): Request = try {
        build()
    } catch (e: IllegalArgumentException) {
        throw IOException("invalid request", e)
    }

    private fun Request.Builder.applyHeaders(headers: Map<String, String>): Request.Builder {
        header("User-Agent", userAgent)
        headers.forEach { (name, value) -> header(name, value) }
        return this
    }

    private suspend fun execute(request: Request, timeoutMs: Long, maxBytes: Int): HttpResponse =
        withContext(Dispatchers.IO) {
            // OkHttp's default 10 s read timeout would cut a slow server before the call budget: align it.
            val call = client.newBuilder()
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
                .newCall(request)
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        // Whatever goes wrong while reading must end the call: a throwable left here would hang
                        // the coroutine or crash the dispatcher thread. Non-IO ones become IOException, which
                        // the module maps to SearchError.Network.
                        val result = try {
                            Result.success(response.use { read(it, maxBytes) })
                        } catch (t: Throwable) {
                            Result.failure(if (t is IOException) t else IOException(t.message ?: t.javaClass.simpleName, t))
                        }
                        continuation.resumeWith(result)
                    }
                })
            }
        }

    private fun read(response: Response, maxBytes: Int): HttpResponse {
        val headers = response.headers.names().associate { it.lowercase() to response.header(it).orEmpty() }
        val source = response.body?.source()
        val buffer = Buffer()
        var truncated = false
        if (source != null) {
            while (buffer.size < maxBytes) {
                if (source.read(buffer, minOf(CHUNK, maxBytes - buffer.size)) == -1L) break
            }
            truncated = buffer.size >= maxBytes && !source.exhausted()
        }
        return HttpResponse(response.code, buffer.readByteArray(), headers, truncated)
    }

    companion object {
        private const val CHUNK = 8192L
        private const val CONNECT_TIMEOUT_MS = 8_000L

        /**
         * The client the app uses. [dns] and [blockPrivateConnect] are only turned off by tests that talk to a
         * local server.
         */
        fun guardedClient(dns: Dns = GuardedDns(), blockPrivateConnect: Boolean = true): OkHttpClient =
            OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .proxy(Proxy.NO_PROXY)
                .dns(dns)
                .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .apply { if (blockPrivateConnect) addNetworkInterceptor(PrivateConnectInterceptor) }
                .build()
    }
}

/**
 * The system resolver, except that a name resolving to any private, loopback or link-local address fails:
 * the address OkHttp connects to is the one that was checked, so DNS rebinding cannot swap it afterwards.
 */
class GuardedDns(private val delegate: Dns = Dns.SYSTEM) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        addresses.firstOrNull { UrlGuard.isPrivate(it) }?.let {
            throw UnknownHostException("$hostname resolves to a private address")
        }
        return addresses
    }
}

/** Refuses a connection whose peer is private, whatever the URL said: the case [GuardedDns] never sees (IP literals). */
private object PrivateConnectInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val address = chain.connection()?.route()?.socketAddress?.address
        if (address != null && UrlGuard.isPrivate(address)) {
            throw IOException("refusing to talk to a private address")
        }
        return chain.proceed(chain.request())
    }
}
