package io.github.hokanosekai.tensai.hf

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** One page of search results; [nextCursor] feeds [HfClient.searchNext], null on the last page. */
data class HfPage(val items: List<HfRepoSummary>, val nextCursor: String?)

/**
 * Hugging Face Hub client: search, repo details, README, file URLs. Data layer only.
 *
 * **Privacy: the only host this talks to is huggingface.co** (see the contract in `HfModels.kt`):
 * [baseUrl] is the single origin every request is built from, and a pagination cursor handed back
 * in is refused unless it points at that same origin, so a `Link` header cannot steer the client
 * elsewhere. Nothing is logged or sent beyond the request itself.
 *
 * Every function suspends on [Dispatchers.IO] (network, body read and parsing) and throws
 * [HfException] with a typed [HfError]. Cancelling the coroutine cancels the HTTP call, including
 * while the body is being read.
 *
 * API calls per use case (anonymous limit is 3000 per 5 minutes, see the spec):
 * - Discover, one page of results: 1 [search] / [searchNext], then 1 [detail] per visible card,
 *   lazily, cached ([cacheSize] repos, in memory, per process). Concurrent [detail] calls for the
 *   same repo share one request.
 * - Repo screen: 1 [detail] (cache hit when the card was already shown), 1 [readme] on expand.
 * - Download: [fileUrl] builds the URL, no call here.
 *
 * **Invalid token.** An invalid token makes the Hub answer 401 even on public endpoints. On a 401
 * sent with a token the call is retried once without it: if that works the error is
 * [HfError.InvalidToken] (the token is the problem), otherwise the anonymous 401/403 stands and is
 * [HfError.Gated] (the repo is). That costs one extra request only in the failure case.
 *
 * @param tokenProvider the user's Hugging Face token or null, read on every call so a change in
 *   Settings applies at once. Sent as `Authorization: Bearer` to [baseUrl] only.
 */
class HfClient(
    private val http: OkHttpClient = defaultHttp(),
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val tokenProvider: () -> String? = { null },
    private val cacheSize: Int = 32,
) {
    private val origin: HttpUrl = baseUrl.toHttpUrl()

    // Access-ordered LinkedHashMap = LRU. Guarded by its own monitor, never held over I/O.
    private val cache = object : LinkedHashMap<String, HfRepoDetail>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, HfRepoDetail>?) = size > cacheSize
    }

    // In-flight detail loads, so two cards asking for the same repo share one request. They run in
    // their own scope: one caller cancelling only stops waiting, the others still get the result.
    private val inFlight = HashMap<String, Deferred<HfRepoDetail>>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** First page of repos with GGUF files matching [query] (blank lists everything), [limit] per page. */
    suspend fun search(query: String, sort: HfSort = HfSort.Downloads, limit: Int = PAGE_SIZE): HfPage {
        val url = origin.newBuilder().addPathSegments("api/models").apply {
            if (query.isNotBlank()) addQueryParameter("search", query.trim())
            addQueryParameter("filter", "gguf")
            // Chat models only: no audio, embedding or image GGUF repos.
            addQueryParameter("pipeline_tag", "text-generation")
            addQueryParameter("sort", sort.apiValue)
            addQueryParameter("direction", "-1")
            addQueryParameter("limit", limit.toString())
            // The default listing carries neither lastModified nor gated.
            listOf("downloads", "likes", "lastModified", "gated", "tags").forEach { addQueryParameter("expand[]", it) }
        }.build()
        return page(url)
    }

    /** The page [HfPage.nextCursor] pointed at. Refused with [HfError.Parse] if it is not on this client's origin. */
    suspend fun searchNext(cursor: String): HfPage {
        val url = cursor.toHttpUrlOrNull()?.takeIf { it.host == origin.host && it.scheme == origin.scheme && it.port == origin.port }
            ?: throw HfException(HfError.Parse("cursor is not on ${origin.host}"))
        return page(url)
    }

    /**
     * Details of [repo] (`author/name`) in a single call: file list with sizes and sha256
     * (`blobs=true`) and the GGUF header summary, licence, counters (`expand[]`). Cached, and
     * concurrent calls for the same repo are merged. A [repo] that is not `author/name` is
     * [HfError.NotFound].
     */
    suspend fun detail(repo: String, forceRefresh: Boolean = false): HfRepoDetail {
        val url = repoUrl(repo, "api/models").newBuilder().apply {
            addQueryParameter("blobs", "true")
            listOf("gguf", "cardData", "downloads", "likes", "lastModified", "gated", "tags", "siblings", "sha")
                .forEach { addQueryParameter("expand[]", it) }
        }.build()
        if (!forceRefresh) synchronized(cache) { cache[repo] }?.let { return it }
        val load = synchronized(inFlight) {
            inFlight.getOrPut(repo) {
                scope.async {
                    try {
                        val parsed = fetch(url) { parse { HfParser.parseDetail(readBody(it, MAX_JSON_BYTES)) } }
                        synchronized(cache) { cache[repo] = parsed }
                        parsed
                    } finally {
                        // Blocks until getOrPut above has inserted, so this always removes it.
                        synchronized(inFlight) { inFlight.remove(repo) }
                    }
                }
            }
        }
        return load.await()
    }

    /** `README.md` of [repo] as text, capped at [README_MAX_BYTES]. */
    suspend fun readme(repo: String): String =
        fetch(repoUrl(repo, "raw/main/README.md", api = false)) { readBody(it, README_MAX_BYTES) }

    /** Direct download URL of [fileName] in [repo] at [revision] (a commit sha to pin it, `main` otherwise; honours `Range`). Same origin, no call made. */
    fun fileUrl(repo: String, fileName: String, revision: String = "main"): String =
        repoUrl(repo, "resolve/$revision", api = false).newBuilder().addPathSegments(fileName).build().toString()

    /** Page of the repo on the website, for "Open on Hugging Face". */
    fun repoPageUrl(repo: String): String = repoUrl(repo, "", api = false).toString()

    fun clearCache() = synchronized(cache) { cache.clear() }

    private suspend fun page(url: HttpUrl): HfPage = fetch(url) {
        val next = it.headers("Link").firstNotNullOfOrNull { h -> NEXT_LINK.find(h)?.groupValues?.get(1) }
        val body = readBody(it, MAX_JSON_BYTES)
        HfPage(parse { HfParser.parseSearch(body) }, next)
    }

    private fun repoUrl(repo: String, prefix: String, api: Boolean = true): HttpUrl {
        if (!REPO.matches(repo)) throw HfException(HfError.NotFound)
        val b = origin.newBuilder()
        if (prefix.isNotEmpty() && api) b.addPathSegments(prefix).addPathSegments(repo)
        else {
            b.addPathSegments(repo)
            if (prefix.isNotEmpty()) b.addPathSegments(prefix)
        }
        return b.build()
    }

    private fun readBody(response: Response, maxBytes: Long): String {
        val source = (response.body ?: throw HfException(HfError.Parse("empty body"))).source()
        source.request(maxBytes + 1)
        return source.buffer.readUtf8(minOf(source.buffer.size, maxBytes))
    }

    /**
     * GET [url] and run [read] on the response, all on IO. Handles the invalid-token retry (see the
     * class KDoc), maps status codes and I/O failures, and cancels the live call if the coroutine
     * is cancelled at any point: while connecting (via the continuation) or while [read] blocks on
     * the body (via a watcher child that fires when the scope is cancelled).
     */
    private suspend fun <T> fetch(url: HttpUrl, read: (Response) -> T): T = withContext(Dispatchers.IO) {
        coroutineScope {
            val token = tokenProvider()?.takeIf { it.isNotBlank() }
            val live = AtomicReference<Call?>(null)
            val watcher = launch {
                try {
                    awaitCancellation()
                } finally {
                    live.get()?.cancel()
                }
            }
            try {
                var response = send(url, token, live)
                if (response.code == 401 && token != null) {
                    response.close()
                    val anonymous = send(url, null, live)
                    if (anonymous.isSuccessful) {
                        anonymous.close()
                        throw HfException(HfError.InvalidToken)
                    }
                    response = anonymous
                }
                response.use {
                    if (!it.isSuccessful) {
                        throw HfException(errorFor(it.code, it.header("Retry-After")?.toLongOrNull()))
                    }
                    read(it)
                }
            } catch (e: IOException) {
                // A cancelled call surfaces as an IOException: that is a cancellation, not "offline".
                coroutineContext.ensureActive()
                throw HfException(HfError.Offline, e)
            } finally {
                watcher.cancel()
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun send(url: HttpUrl, token: String?, live: AtomicReference<Call?>): Response {
        val request = Request.Builder().url(url).header("Accept", "application/json, text/plain")
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .build()
        return suspendCancellableCoroutine { cont ->
            val call = http.newCall(request)
            live.set(call)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)

                // Cancelled between the response arriving and the resume: nobody will close it.
                override fun onResponse(call: Call, response: Response) = cont.resume(response) { _, _, _ -> response.close() }
            })
        }
    }

    private inline fun <T> parse(block: () -> T): T = try {
        block()
    } catch (e: HfException) {
        throw e
    } catch (e: kotlinx.serialization.SerializationException) {
        throw HfException(HfError.Parse(e.message.orEmpty().take(200)), e)
    } catch (e: IllegalArgumentException) {
        throw HfException(HfError.Parse(e.message.orEmpty().take(200)), e)
    }

    private fun String.toHttpUrlOrNull(): HttpUrl? = runCatching { toHttpUrl() }.getOrNull()

    companion object {
        const val DEFAULT_BASE_URL = "https://huggingface.co"
        const val PAGE_SIZE = 20
        const val README_MAX_BYTES = 1_000_000L
        private const val MAX_JSON_BYTES = 8_000_000L

        private val NEXT_LINK = Regex("<([^>]+)>\\s*;\\s*rel=\"next\"")
        private val REPO = Regex("[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*")

        internal fun errorFor(code: Int, retryAfterSeconds: Long? = null): HfError = when (code) {
            404 -> HfError.NotFound
            401, 403 -> HfError.Gated(code)
            429 -> HfError.RateLimited(retryAfterSeconds)
            else -> HfError.Http(code)
        }

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
