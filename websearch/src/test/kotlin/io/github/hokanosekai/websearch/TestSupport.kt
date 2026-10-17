package io.github.hokanosekai.websearch

import java.io.IOException

fun fixture(name: String): String =
    object {}.javaClass.getResourceAsStream("/fixtures/$name")?.bufferedReader()?.readText()
        ?: error("missing fixture $name")

data class RecordedRequest(
    val method: String,
    val url: String,
    val body: String?,
    val headers: Map<String, String>,
)

/** Replays queued responses (or throws queued IO failures) and records every request. */
class FakeFetcher(vararg responses: Any) : HttpFetcher {
    private val queue = ArrayDeque(responses.toList())
    val requests = mutableListOf<RecordedRequest>()

    private fun next(): HttpResponse = when (val item = queue.removeFirst()) {
        is HttpResponse -> item
        is IOException -> throw item
        else -> error("bad queue item $item")
    }

    override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Long, maxBytes: Int): HttpResponse {
        requests += RecordedRequest("GET", url, null, headers)
        return next()
    }

    override suspend fun post(
        url: String,
        body: String,
        contentType: String,
        headers: Map<String, String>,
        timeoutMs: Long,
        maxBytes: Int,
    ): HttpResponse {
        requests += RecordedRequest("POST", url, body, headers + ("content-type" to contentType))
        return next()
    }
}

fun ok(body: String, vararg headers: Pair<String, String>) = HttpResponse(200, body, headers.toMap())

fun status(code: Int, body: String = "", vararg headers: Pair<String, String>) =
    HttpResponse(code, body, headers.toMap())

/** Provider that returns a fixed list or throws a fixed error, counting calls. */
class StubProvider(
    override val name: String,
    private val results: List<SearchResult> = emptyList(),
    private val error: SearchError? = null,
) : SearchProvider {
    var calls = 0

    override suspend fun search(query: String, limit: Int): List<SearchResult> {
        calls++
        error?.let { throw it }
        return results
    }
}

inline fun <reified E : SearchError> assertFails(block: () -> Unit): E {
    try {
        block()
    } catch (e: SearchError) {
        if (e is E) return e
        throw AssertionError("expected ${E::class.simpleName} but got $e", e)
    }
    throw AssertionError("expected ${E::class.simpleName} but nothing was thrown")
}
