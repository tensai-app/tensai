package io.github.tensaiapp.agent

import io.github.tensaiapp.engine.ToolCall
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/**
 * A call the model wrote as a bare JSON object, without its template's call markers: small models
 * (Qwen3 1.7B) sometimes answer `{"name": "ask_user", "arguments": {...}}` as plain text, and the
 * engine's parser, rightly, reads that as content. Shown and replayed as text, it teaches the model the
 * bad format for the next turns.
 *
 * A real answer can hold JSON, so the shape is narrow: the whole reply, trimmed and at most inside one
 * ``` or ```json fence, must be one object with exactly `name` and `arguments`, the name one of the
 * offered tools and the arguments an object. Prose around it, JSON inside a longer answer, an unknown
 * name or anything that does not parse stay text. The syntax is org.json's, which is lenient (unquoted
 * keys, single quotes): the offered name and the two-key shape are what keep an answer from passing.
 */
internal object BareToolCall {
    private val Fence = Regex("""^```(?:json)?\s*\n(.*)\n\s*```$""", RegexOption.DOT_MATCHES_ALL)

    /** The call [content] is, `null` when it is not exactly one call of a tool in [toolNames]. The id is left empty. */
    fun parse(content: String, toolNames: Collection<String>): ToolCall? {
        val trimmed = content.trim()
        val body = (Fence.matchEntire(trimmed)?.groupValues?.get(1) ?: trimmed).trim()
        if (!body.startsWith("{") || !body.endsWith("}")) return null
        val obj = try {
            val tokener = JSONTokener(body)
            val value = tokener.nextValue()
            // nextValue() stops after the first value: anything left means more than one.
            if (value !is JSONObject || tokener.nextClean() != 0.toChar()) return null
            value
        } catch (_: JSONException) {
            return null
        }
        if (obj.length() != 2) return null
        val name = obj.opt("name") as? String ?: return null
        val arguments = obj.opt("arguments") as? JSONObject ?: return null
        if (name !in toolNames) return null
        return ToolCall("", name, arguments.toString())
    }
}
