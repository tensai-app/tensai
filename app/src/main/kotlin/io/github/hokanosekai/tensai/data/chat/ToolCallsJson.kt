package io.github.hokanosekai.tensai.data.chat

import io.github.hokanosekai.tensai.engine.ToolCall
import org.json.JSONArray
import org.json.JSONObject

/**
 * The format of [MessageEntity.toolCallsJson]: `[{"id":..,"name":..,"arguments":..}]`, where
 * `arguments` is the JSON object as a string, exactly as the model wrote it. Like [SamplingJson],
 * reading it back never throws: a column nobody can parse replays as no calls.
 */
object ToolCallsJson {

    fun encode(calls: List<ToolCall>): String {
        val arr = JSONArray()
        for (c in calls) {
            arr.put(JSONObject().put("id", c.id).put("name", c.name).put("arguments", c.argumentsJson))
        }
        return arr.toString()
    }

    /** The calls [json] holds, empty for `null`, blank or unreadable input. */
    fun decode(json: String?): List<ToolCall> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ToolCall(o.getString("id"), o.getString("name"), o.optString("arguments", "{}"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
