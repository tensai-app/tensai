package io.github.tensaiapp.agent

import io.github.tensaiapp.data.chat.ChatSearchDao
import java.time.Instant
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Query building and snippets for `search_chats`, apart from the tool so they test without a database. */
object ChatSearch {
    const val DEFAULT_LIMIT = 5
    const val MAX_LIMIT = 10
    const val SNIPPET_RADIUS = 60

    /** [query] as a LIKE pattern matching it anywhere, `%` `_` and the escape itself taken literally. */
    fun likePattern(query: String): String =
        "%" + query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    /** About [radius] characters either side of the first match of [query] (the start of [content] when only the title matched). */
    fun snippet(content: String, query: String, radius: Int = SNIPPET_RADIUS): String {
        val flat = content.replace(Regex("\\s+"), " ").trim()
        val at = flat.indexOf(query.trim(), ignoreCase = true).coerceAtLeast(0)
        val start = (at - radius).coerceAtLeast(0)
        val end = (at + query.trim().length + radius).coerceAtMost(flat.length)
        return (if (start > 0) "..." else "") + flat.substring(start, end) + (if (end < flat.length) "..." else "")
    }
}

/**
 * Looks a word or phrase up in the user's past conversations, so the model can answer "what did we say about X".
 * The conversation being held is left out of the results: the query is in it, so it would always be the first hit.
 * A snippet can repeat the text of a page read in that other conversation: [returnsUntrustedContent]. Or a code or a
 * message a reply quoted from a notification or an SMS: [returnsPersonalContent] (TEN-215).
 */
class SearchChatsTool(
    private val dao: ChatSearchDao,
    private val conversationId: () -> String? = { null },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) : Tool {
    override val name = "search_chats"
    override val description = "Searches the user's past chats for a word or phrase."
    override val parametersSchema =
        """{"type":"object","properties":{"query":{"type":"string"},"limit":{"type":"integer","description":"max 10"}},"required":["query"]}"""
    override val tier = ToolTier.Local
    override val returnsUntrustedContent = true
    override fun returnsPersonalContent(argsJson: String) = true

    override suspend fun execute(argsJson: String): ToolResult {
        val args = try {
            JSONObject(argsJson)
        } catch (e: JSONException) {
            return ToolResult.error("arguments are not a JSON object")
        }
        val query = args.stringArg("query").orEmpty().trim()
        if (query.isEmpty()) return ToolResult.error("query is empty")
        val limit = args.optInt("limit", ChatSearch.DEFAULT_LIMIT).coerceIn(1, ChatSearch.MAX_LIMIT)

        val hits = dao.search(ChatSearch.likePattern(query), limit, conversationId())
        if (hits.isEmpty()) return ToolResult.ok("no match")
        val out = JSONArray()
        hits.forEach { hit ->
            out.put(
                JSONObject()
                    .put("chat", hit.title.ifBlank { "(untitled)" })
                    .put("date", Instant.ofEpochMilli(hit.createdAt).atZone(zone()).toLocalDate().toString())
                    .put("snippet", ChatSearch.snippet(hit.content, query)),
            )
        }
        return ToolResult.ok(out.toString())
    }
}
