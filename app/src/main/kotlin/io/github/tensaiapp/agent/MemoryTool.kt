package io.github.tensaiapp.agent

import io.github.tensaiapp.data.memory.MemoryContext
import io.github.tensaiapp.data.memory.MemoryDao
import io.github.tensaiapp.data.memory.MemoryFactEntity
import java.time.Instant
import java.time.ZoneId
import kotlin.coroutines.coroutineContext
import org.json.JSONException
import org.json.JSONObject

/**
 * Facts about the user that outlive a conversation (TEN-176): `remember`, `list`, `forget`.
 *
 * The newest facts are already in every conversation (TEN-189, [io.github.tensaiapp.data.memory.MemoryContext]),
 * `list` is for the ones past that cap and for the ids `forget` needs. One tool with an `action` so the prompt carries one schema, not three.
 *
 * `forget` deletes user data, so it goes through the confirmation gate; `list` does not, and `remember` only once the
 * conversation holds a [Tool.returnsUntrustedContent] result, in any turn ([requiresConfirmation], [ConversationTrust]):
 * text from a web page could get the model to store something the user never said, and a fact is in every later
 * conversation. A `remember` the user asks for in a conversation with nothing of the kind runs at once. [sendsOrDeletes] stays true so the registry and Settings list the
 * tool as one that can delete.
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
        "Long-term memory about the user: remember (text), list, forget (id). Recent facts are already in context. Remember only what the user wants kept."
    override val parametersSchema =
        """{"type":"object","properties":{"action":{"type":"string","enum":["remember","list","forget"]},""" +
            """"text":{"type":"string"},"id":{"type":"integer"}},"required":["action"]}"""
    override val tier = ToolTier.Local
    override val sendsOrDeletes = true

    /** What each `forget` card showed, by call id, see [confirmTarget]. Bounded: a denied call never comes to take its own. */
    private val shown = object : LinkedHashMap<String, ConfirmTarget>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ConfirmTarget>) = size > MAX_SHOWN
    }

    override fun requiresConfirmation(argsJson: String, trust: ConversationTrust): Boolean =
        when (runCatching { JSONObject(argsJson).stringArg("action") }.getOrNull()) {
            "forget" -> true
            "remember" -> trust.readUntrusted || trust.readPersonal
            else -> false
        }

    /**
     * The fact a `forget` deletes, looked up by id: its text, or `null` when the id is not in the store. What the card
     * shows is kept for [callId], and [forget] deletes the row only if it still reads that: SQLite hands a deleted top
     * id to the next fact, so "id 7" on Allow may no longer be the fact the card named.
     */
    override suspend fun confirmTarget(callId: String, argsJson: String): ConfirmTarget? {
        val args = runCatching { JSONObject(argsJson) }.getOrNull() ?: return null
        val id = forgetId(args)
        if (args.stringArg("action") != "forget" || id < 0) return null
        val target = ConfirmTarget(id.toString(), dao.textOf(id))
        synchronized(shown) { shown[callId] = target }
        return target
    }

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
                val id = forgetId(args)
                val confirmed = coroutineContext[ToolCallContext]?.callId?.let { synchronized(shown) { shown.remove(it) } }
                if (id < 0) ToolResult.error("forget needs the id of a fact, see list") else forget(id, confirmed)
            }
            else -> ToolResult.error("action must be remember, list or forget, got '$action'")
        }
    }

    private suspend fun remember(raw: String): ToolResult {
        val text = factText(raw)
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

    /** The `id` of a `forget`, -1 when missing: one reading for the card and the delete. */
    private fun forgetId(args: JSONObject): Long = if (args.isNull("id")) -1 else args.optLong("id", -1)

    /**
     * Deletes fact [id]. When a card showed it, [confirmed] is what the user saw, and the row goes only while it
     * still reads that: a card that said the fact no longer exists deletes nothing.
     */
    private suspend fun forget(id: Long, confirmed: ConfirmTarget?): ToolResult {
        val deleted = when {
            confirmed == null -> dao.delete(id)
            confirmed.text == null -> 0
            else -> dao.deleteIfText(id, confirmed.text)
        }
        return when {
            deleted > 0 -> {
                onForgotten()
                ToolResult.ok("forgotten (id $id)")
            }
            confirmed != null && dao.textOf(id) != null ->
                ToolResult.error("fact $id changed since the user confirmed it, nothing deleted, see list")
            else -> ToolResult.error("no fact with id $id, see list")
        }
    }

    companion object {
        const val MAX_FACT_CHARS = 300
        /** Rows read for a `list`: more than fit in [MAX_LIST_CHARS] for short facts, a bound for the query. */
        const val MAX_LISTED = 50
        const val MAX_LIST_CHARS = 1800
        private const val MAX_SHOWN = 32

        /** The fact a `remember` of [raw] stores, on one line: what its confirmation card shows. */
        fun factText(raw: String): String = raw.trim().replace(Regex("\\s+"), " ")
    }
}
