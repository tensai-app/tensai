package io.github.tensaiapp.agent

import io.github.tensaiapp.engine.ChatMessage
import io.github.tensaiapp.engine.MessageRole
import io.github.tensaiapp.engine.ToolCall
import io.github.tensaiapp.websearch.HttpFetcher
import io.github.tensaiapp.websearch.HttpResponse
import io.github.tensaiapp.websearch.SearchError
import io.github.tensaiapp.websearch.SearchProvider
import io.github.tensaiapp.websearch.SearchResult
import io.github.tensaiapp.websearch.UrlGuard
import java.io.IOException
import java.net.InetAddress

/** Replays queued [HttpResponse]s (or throws queued [IOException]s) and records the URL of every request. */
class FakeHttp(vararg responses: Any) : HttpFetcher {
    private val queue = ArrayDeque(responses.toList())
    val urls = mutableListOf<String>()
    val posts = mutableListOf<String>()

    private fun next(): HttpResponse = when (val item = queue.removeFirstOrNull() ?: error("no response queued")) {
        is HttpResponse -> item
        is IOException -> throw item
        else -> error("bad queue item $item")
    }

    override suspend fun get(url: String, headers: Map<String, String>, timeoutMs: Long, maxBytes: Int): HttpResponse {
        urls += url
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
        urls += url
        posts += body
        return next()
    }
}

fun json(body: String) = HttpResponse(200, body, mapOf("content-type" to "application/json"))

fun html(body: String) = HttpResponse(200, body, mapOf("content-type" to "text/html; charset=utf-8"))

fun status(code: Int, vararg headers: Pair<String, String>) = HttpResponse(code, "", headers.toMap())

/** Everything resolves to a public address: the tests of the tools should not depend on DNS. */
val PublicGuard = UrlGuard { listOf(InetAddress.getByName("93.184.216.34")) }

/** A provider that answers a fixed list, or throws, remembering what it was asked. */
class FakeProvider(
    override val name: String = "duckduckgo",
    private val results: List<SearchResult> = emptyList(),
    private val error: SearchError? = null,
) : SearchProvider {
    var lastQuery: String? = null
    var lastLimit: Int? = null

    override suspend fun search(query: String, limit: Int): List<SearchResult> {
        lastQuery = query
        lastLimit = limit
        error?.let { throw it }
        return results
    }
}

fun hit(n: Int, source: String = "duckduckgo", snippet: String = "snippet $n") =
    SearchResult("Title $n", "https://example.com/$n", snippet, source)

/** This trust once the user said [text]. */
fun ConversationTrust.said(text: String): ConversationTrust = with(ChatMessage(MessageRole.User, text)) { null }

/** This trust once a call of [tool] (id [id]) was answered with [output], as the prompt replays it. */
fun ConversationTrust.read(tool: Tool, output: String, id: String = "c-${tool.name}"): ConversationTrust {
    val find = { name: String -> tool.takeIf { it.name == name } }
    return with(ChatMessage(MessageRole.Assistant, "", toolCalls = listOf(ToolCall(id, tool.name, "{}"))), find)
        .with(ChatMessage(MessageRole.Tool, output, toolCallId = id), find)
}
