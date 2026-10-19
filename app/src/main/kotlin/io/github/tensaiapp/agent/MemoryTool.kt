package io.github.tensaiapp.agent

import io.github.tensaiapp.data.memory.MemoryContext
import io.github.tensaiapp.data.memory.MemoryDao
import io.github.tensaiapp.data.memory.MemoryFactEntity
import java.time.Instant
import java.time.ZoneId
import org.json.JSONException
import org.json.JSONObject

/**
 * Facts about the user that outlive a conversation (TEN-176): `remember`, `list`, `forget`.
 *
 * The newest facts are already in every conversation (TEN-189, [io.github.tensaiapp.data.memory.MemoryContext]),
 * `list` is for the ones past that cap and for the ids `forget` needs. One tool with an `action` so the prompt carries one schema, not three.
 *
 * `forget` deletes user data, so it goes through the confirmation gate; `remember` and `list` do not
 * ([requiresConfirmation]). [sendsOrDeletes] stays true so the registry and Settings list the tool as one that can delete.
 *
 * Known risk, not addressed here: `remember` runs without asking, so text the model reads from a web page or a
 * file could get it to store something the user never said. The call cards of TEN-173 make every call visible,
 * and Settings > Memory lets the user delete a fact; confirming `remember` is a later decision.
 *
 * @param conversationId the active conversation, stored with a fact as its source.
 */
class MemoryTool(
    private val dao: MemoryDao,
    private val now: () -> Long = System::currentTimeMillis,
    private val conversationId: () -> String? = { null },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    /** Called after a delete, so the blocks held by [io.github.tensaiapp.data.memory.MemoryPrompt] drop the fact. */
    private val onForgotten: () -> Unit = {},
) : Tool {
    override val name = "memory"
    override val description =
        "Long-term memory about the user. action remember (text), list, or forget (id). Recent facts are already in context, list is for older ones. Remember only what the user asks or clearly wants kept."
    override val parametersSchema =
        """{"type":"object","properties":{"action":{"type":"string","enum":["remember","list","forget"]},""" +
            """"text":{"type":"string"},"id":{"type":"integer"}},"required":["action"]}"""
    override val tier = ToolTier.Local
    override val sendsOrDeletes = true

    override fun requiresConfirmation(argsJson: String): Boolean =
        runCatching { JSONObject(argsJson).stringArg("action") == "forget" }.getOrDefault(false)

    override suspend fun execute(argsJson: String): ToolResult {
        val args = try {
            JSONObject(argsJson)
        } catch (e: JSONException) {
            return ToolResult.error("arguments are not a JSON object")
        }
        return when (val action = args.stringArg("action").orEmpty()) {
            "remember" -> remember(args.stringArg("text").orEmpty())
            "list" -> list()
            "forget" -> {
                val id = if (args.isNull("id")) -1 else args.optLong("id", -1)
                if (id < 0) ToolResult.error("forget needs the id of a fact, see list") else forget(id)
            }
            else -> ToolResult.error("action must be remember, list or forget, got '$action'")
        }
    }

    private suspend fun remember(raw: String): ToolResult {
        val text = raw.trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty()) return ToolResult.error("remember needs text")
        if (text.length > MAX_FACT_CHARS) {
            return ToolResult.error("fact too long (${text.length} characters, max $MAX_FACT_CHARS), say it shorter")
        }
        if (MemoryContext.hasControl(text)) {
            return ToolResult.error("fact contains special tokens or tags (<|...|>, <think>, <tool_call>), say it in plain text")
        }
        dao.findIdByText(text)?.let { return ToolResult.ok("already remembered (id $it)") }
        val id = dao.insert(MemoryFactEntity(text = text, createdAt = now(), sourceConversationId = conversationId()))
        return ToolResult.ok("remembered (id $id)")
    }

    /**
     * Newest first, one `id | date | text` line per fact, cut by length rather than count: the runtime shortens a
     * result to a token budget (512), and a cut in the middle of a line could lose the id `forget` needs.
     */
    private suspend fun list(): ToolResult {
        val facts = dao.recent(MAX_LISTED)
        if (facts.isEmpty()) return ToolResult.ok("nothing remembered yet")
        val out = StringBuilder()
        var shown = 0
        for (fact in facts) {
            val line = line(fact)
            if (shown > 0 && out.length + line.length + 1 > MAX_LIST_CHARS) break
            if (shown > 0) out.append('\n')
            out.append(line)
            shown++
        }
        val total = dao.count()
        if (shown < total) out.append("\n($shown of $total facts shown)")
        return ToolResult.ok(out.toString())
    }

    private fun line(fact: MemoryFactEntity): String {
        val date = Instant.ofEpochMilli(fact.createdAt).atZone(zone()).toLocalDate()
        return "${fact.id} | $date | ${fact.text}"
    }

    private suspend fun forget(id: Long): ToolResult =
        if (dao.delete(id) > 0) {
            onForgotten()
            ToolResult.ok("forgotten (id $id)")
        } else {
            ToolResult.error("no fact with id $id, see list")
        }

    companion object {
        const val MAX_FACT_CHARS = 300
        /** Rows read for a `list`: more than fit in [MAX_LIST_CHARS] for short facts, a bound for the query. */
        const val MAX_LISTED = 50
        const val MAX_LIST_CHARS = 1800
    }
}
