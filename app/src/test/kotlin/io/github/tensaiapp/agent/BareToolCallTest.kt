package io.github.tensaiapp.agent

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BareToolCallTest {
    private val tools = listOf("ask_user", "calculate")

    @Test
    fun aBareObjectNamingAnOfferedToolIsACall() {
        val call = BareToolCall.parse(
            """  {"name": "ask_user", "arguments": {"question": "Which one?", "options": ["a", "b"], "multiple": false}}
            """,
            tools,
        )!!
        assertEquals("ask_user", call.name)
        val args = JSONObject(call.argumentsJson)
        assertEquals("Which one?", args.getString("question"))
        assertEquals(2, args.getJSONArray("options").length())
    }

    @Test
    fun aFencedObjectIsACallToo() {
        val json = "```json\n{\"name\": \"calculate\", \"arguments\": {\"expression\": \"2+2\"}}\n```"
        assertEquals("calculate", BareToolCall.parse(json, tools)?.name)
        val plain = "```\n{\"name\": \"calculate\", \"arguments\": {}}\n```"
        assertEquals("{}", BareToolCall.parse(plain, tools)?.argumentsJson)
    }

    @Test
    fun orgJsonLeniencyStillNeedsAnOfferedName() {
        assertEquals("calculate", BareToolCall.parse("{name: 'calculate', arguments: {expression: '2+2'}}", tools)?.name)
        assertNull(BareToolCall.parse("{name: 'greeting', arguments: {text: 'hello'}}", tools))
    }

    @Test
    fun anUnknownNameStaysText() {
        assertNull(BareToolCall.parse("""{"name": "web_fetch", "arguments": {"url": "x"}}""", tools))
    }

    @Test
    fun proseAroundTheObjectStaysText() {
        assertNull(BareToolCall.parse("""Sure: {"name": "calculate", "arguments": {}}""", tools))
        assertNull(BareToolCall.parse("""{"name": "calculate", "arguments": {}} Done.""", tools))
        assertNull(BareToolCall.parse("""{"name": "calculate", "arguments": {}} {"name": "calculate", "arguments": {}}""", tools))
    }

    @Test
    fun jsonInsideALongerAnswerStaysText() {
        val answer = "Here is an example payload:\n```json\n{\"name\": \"calculate\", \"arguments\": {}}\n```\nSend it to the API."
        assertNull(BareToolCall.parse(answer, tools))
    }

    @Test
    fun invalidOrOtherShapesStayText() {
        assertNull(BareToolCall.parse("""{"name": "calculate", "arguments": {""", tools))
        assertNull(BareToolCall.parse("""{"name": "calculate", "arguments": "{}"}""", tools))
        assertNull(BareToolCall.parse("""{"name": "calculate"}""", tools))
        assertNull(BareToolCall.parse("""{"name": "calculate", "arguments": {}, "note": "x"}""", tools))
        assertNull(BareToolCall.parse("""[{"name": "calculate", "arguments": {}}]""", tools))
        assertNull(BareToolCall.parse("Hello there, how are you today?", tools))
    }
}
