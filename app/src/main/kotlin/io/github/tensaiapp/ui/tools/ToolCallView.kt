package io.github.tensaiapp.ui.tools

import io.github.tensaiapp.agent.GateRefusal
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One argument of a call, as the detail view lists it. [mono] for a value that reads as code. */
data class ToolInput(val key: String, val value: String, val mono: Boolean)

/** The arguments of a call as text, for labels and the detail view. Never throws: unreadable arguments are no arguments. */
object ToolArgsView {

    /** Every argument as text, in the order the model wrote them. */
    fun values(argsJson: String): Map<String, String> {
        val obj = try {
            JSONObject(argsJson)
        } catch (_: JSONException) {
            return emptyMap()
        }
        val out = LinkedHashMap<String, String>()
        for (key in obj.keys()) {
            if (!obj.isNull(key)) out[key] = text(obj.get(key))
        }
        return out
    }

    /** An array of strings reads one item per line (the options of `ask_user`), anything else as JSON writes it. */
    private fun text(value: Any): String =
        if (value is JSONArray) (0 until value.length()).joinToString("\n") { value.get(it).toString() } else value.toString()

    fun inputs(argsJson: String): List<ToolInput> =
        values(argsJson).map { (key, value) -> ToolInput(key, value, isCodeLike(value)) }

    /** A value with an operator, a bracket, a path or a snake_case word in it reads better in the mono face. */
    internal fun isCodeLike(value: String): Boolean = codeShape.containsMatchIn(value)

    private val codeShape = Regex("""[{}\[\]()=<>;*/\\^%+_]|\n""")
}

/** What the output of a call looks like once read, see [readableOutput]. */
sealed interface ReadableOutput {
    /** A flat JSON object: one row per key. */
    data class Kv(val rows: List<Pair<String, String>>) : ReadableOutput

    /** A list of `{title, url}`. */
    data class Results(val items: List<ResultItem>) : ReadableOutput

    /** An intent that opened [app]: `{"opened": "Clock"}`. [set] when the app took the request as is (a timer or an alarm). */
    data class AppOpened(val app: String, val set: Boolean = false) : ReadableOutput

    data class Error(val message: String) : ReadableOutput

    /** The confirmation gate did not approve the call: shown as the app's own line, not the text the model read. */
    data object NotApproved : ReadableOutput

    data class Text(val text: String) : ReadableOutput
}

data class ResultItem(val title: String, val url: String?)

/**
 * The readable form of [output], the text a tool answered (and the model read):
 * - a call the confirmation gate did not approve: [ReadableOutput.NotApproved] (TEN-205), whatever its stored text;
 * - a failed, timed out, refused or stopped call: [ReadableOutput.Error], without the `Error:` the runtime
 *   puts in front for the model;
 * - a JSON object with an `opened` app name: [ReadableOutput.AppOpened] (the shape the phone actions answer with);
 * - a JSON array of `{title, url}`, or an object whose `results` is one: [ReadableOutput.Results];
 * - a flat JSON object: [ReadableOutput.Kv];
 * - anything else, including JSON that is none of those: [ReadableOutput.Text].
 */
fun readableOutput(output: String, state: ToolCallState): ReadableOutput {
    if (state == ToolCallState.Refused && GateRefusal.matches(output)) return ReadableOutput.NotApproved
    if (state != ToolCallState.Ok) return ReadableOutput.Error(errorMessage(output.removePrefix("Error: ").trim()))
    val json = parseJson(output) ?: return ReadableOutput.Text(output)
    when (json) {
        is JSONObject -> {
            json.optString("opened").takeIf { json.has("opened") && it.isNotBlank() }?.let { return ReadableOutput.AppOpened(it, json.optString("status").startsWith("set")) }
            resultsOf(json.optJSONArray("results"))?.let { return it }
            val keys = json.keys().asSequence().toList()
            if (keys.isNotEmpty() && keys.none { json.get(it) is JSONObject || json.get(it) is JSONArray }) {
                return ReadableOutput.Kv(keys.map { it to if (json.isNull(it)) "" else json.get(it).toString() })
            }
        }
        is JSONArray -> resultsOf(json)?.let { return it }
    }
    return ReadableOutput.Text(output)
}

/** A refusal that carries a marker (`{"error": "...", "blocked_permission": ...}`) reads as its message. */
private fun errorMessage(text: String): String =
    (parseJson(text) as? JSONObject)?.takeIf { it.has("blocked_permission") }?.optString("error")?.takeIf { it.isNotEmpty() } ?: text

private fun resultsOf(array: JSONArray?): ReadableOutput.Results? {
    if (array == null || array.length() == 0) return null
    val items = (0 until array.length()).map { i ->
        val item = array.optJSONObject(i) ?: return null
        if (!item.has("title")) return null
        ResultItem(item.getString("title"), item.optString("url").takeIf { item.has("url") && it.isNotBlank() })
    }
    return ReadableOutput.Results(items)
}

/** [output] pretty-printed when it is JSON, as it is otherwise: the "Show JSON" view. */
fun rawOutput(output: String): String = when (val json = parseJson(output)) {
    is JSONObject -> json.toString(2)
    is JSONArray -> json.toString(2)
    else -> output
}

/** Whether [output] is a JSON object or array, i.e. whether "Show JSON" has anything to show that the text does not. */
fun isJsonOutput(output: String): Boolean = parseJson(output) != null

private fun parseJson(text: String): Any? {
    val trimmed = text.trim()
    return try {
        when {
            trimmed.startsWith("{") -> JSONObject(trimmed)
            trimmed.startsWith("[") -> JSONArray(trimmed)
            else -> null
        }
    } catch (_: JSONException) {
        null
    }
}
