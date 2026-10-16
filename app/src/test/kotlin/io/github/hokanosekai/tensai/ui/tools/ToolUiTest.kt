package io.github.hokanosekai.tensai.ui.tools

import io.github.hokanosekai.tensai.R
import io.github.hokanosekai.tensai.agent.CalculateTool
import io.github.hokanosekai.tensai.agent.DateTimeTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The UI metadata of the tools, the tool line's labels and the detail view's reading of inputs and output (TEN-173). */
class ToolUiTest {

    private fun call(
        tool: String,
        state: ToolCallState = ToolCallState.Ok,
        args: String = "{}",
        output: String? = "ok",
        id: String = "c-$tool-${state.name}-${args.hashCode()}",
    ) = ToolCallUi(id, "m1", tool, args, state, durationMs = 5, output = output, destination = null, confirmed = false)

    // ---- metadata

    /** Every tool the app registers (RuntimeGraph.toolRegistry) has an entry, and so do the ones the next tickets add. */
    @Test
    fun everyRegisteredToolHasMetadata() {
        val registered = listOf("datetime", "calculate", "device_info", "search_chats", "memory", "clipboard", "torch")
        val planned = listOf(
            "set_timer", "set_alarm", "create_event", "draft_sms", "draft_email", "open_app", "open_url",
            "media_control", "web_search", "web_fetch", "weather", "wikipedia",
        )

        assertEquals(emptyList<String>(), (registered + planned).filter { ToolUiCatalog.meta(it) == null })
        assertEquals((registered + planned).sorted(), ToolUiCatalog.ids.sorted())
        // The two tools the registry builds without a platform are checked against the real objects.
        assertNotNull(ToolUiCatalog.meta(DateTimeTool().name))
        assertNotNull(ToolUiCatalog.meta(CalculateTool().name))
    }

    @Test
    fun anUnknownToolGetsAGenericLabelAndItsRawName() {
        assertNull(ToolUiCatalog.meta("mystery"))
        assertEquals(UiText.Raw("mystery"), ToolUiCatalog.name("mystery"))
        assertEquals(UiText.of(R.string.tool_done_generic, UiText.Raw("mystery")), ToolUiCatalog.doneLabel("mystery", "{}"))
        assertEquals(UiText.of(R.string.tool_run_generic, UiText.Raw("mystery")), ToolUiCatalog.runningLabel("mystery", "{}"))
        assertNull(ToolUiCatalog.description("mystery"))
    }

    @Test
    fun anArgumentPicksTheMorePreciseLabel() {
        assertEquals(UiText.of(R.string.tool_done_memory_forget), ToolUiCatalog.doneLabel("memory", """{"action":"forget","id":1}"""))
        assertEquals(UiText.of(R.string.tool_run_memory_list), ToolUiCatalog.runningLabel("memory", """{"action":"list"}"""))
        assertEquals(UiText.of(R.string.tool_done_torch_off), ToolUiCatalog.doneLabel("torch", """{"state":"off"}"""))
        // A value the table has no entry for, or no argument at all, falls back to the tool's own label.
        assertEquals(UiText.of(R.string.tool_done_memory), ToolUiCatalog.doneLabel("memory", """{"action":"wipe"}"""))
        assertEquals(UiText.of(R.string.tool_done_memory), ToolUiCatalog.doneLabel("memory", "not json"))
    }

    @Test
    fun theConfirmationLineIsBuiltFromTheArguments() {
        assertEquals(UiText.of(R.string.tool_confirm_memory_forget_id, "3"), ToolUiCatalog.confirmLabel("memory", """{"action":"forget","id":3}"""))
        assertEquals(UiText.of(R.string.tool_confirm_memory_forget), ToolUiCatalog.confirmLabel("memory", """{"action":"forget"}"""))
        assertEquals(UiText.of(R.string.tool_confirm_generic, UiText.of(R.string.tool_name_torch)), ToolUiCatalog.confirmLabel("torch", "{}"))
    }

    // ---- the tool line

    @Test
    fun oneCallReadsAsItsAction() {
        val summary = toolLineSummary(listOf(call("set_timer")))

        assertEquals(ToolLineSummary(UiText.of(R.string.tool_done_set_timer), LineStatus.Ok), summary)
    }

    @Test
    fun severalCallsOfTheSameActionAreCounted() {
        val summary = toolLineSummary(listOf(call("web_search", id = "a"), call("web_search", id = "b"), call("web_search", id = "c")))

        assertEquals(UiText.of(R.string.tool_line_repeated, UiText.of(R.string.tool_done_web_search), 3), summary.label)
        assertEquals(LineStatus.Ok, summary.status)
    }

    @Test
    fun callsOfDifferentToolsAreCountedAsTools() {
        val summary = toolLineSummary(listOf(call("datetime"), call("torch", args = """{"state":"on"}"""), call("datetime", id = "d2")))

        assertEquals(UiText.Plural(R.plurals.tool_line_used_tools, listOf(3)), summary.label)
    }

    @Test
    fun theSameToolWithDifferentActionsIsNotPassedOffAsRepeats() {
        val summary = toolLineSummary(
            listOf(call("memory", args = """{"action":"list"}"""), call("memory", args = """{"action":"remember","text":"x"}""")),
        )

        assertEquals(UiText.Plural(R.plurals.tool_line_used_tools, listOf(2)), summary.label)
    }

    @Test
    fun aRunningCallShowsItsRunningLabelWhateverCameBefore() {
        val summary = toolLineSummary(
            listOf(call("datetime", ToolCallState.Error), call("web_search", ToolCallState.Running, output = null)),
        )

        assertEquals(ToolLineSummary(UiText.of(R.string.tool_run_web_search), LineStatus.Running), summary)
    }

    @Test
    fun aFailureTintsTheLineAndNamesTheToolThatFailed() {
        val failed = toolLineSummary(listOf(call("datetime"), call("web_search", ToolCallState.Timeout)))
        val unknown = toolLineSummary(listOf(call("mystery", ToolCallState.Error)))

        assertEquals(
            ToolLineSummary(UiText.of(R.string.tool_line_failed, UiText.of(R.string.tool_name_web_search)), LineStatus.Error),
            failed,
        )
        assertEquals(UiText.of(R.string.tool_line_failed, UiText.Raw("mystery")), unknown.label)
    }

    @Test
    fun aRefusalIsTintedAsAWarningAndAFailureWinsOverIt() {
        val refused = toolLineSummary(listOf(call("memory", ToolCallState.Refused, args = """{"action":"forget","id":1}""")))
        val both = toolLineSummary(listOf(call("memory", ToolCallState.Refused), call("datetime", ToolCallState.Error)))

        assertEquals(ToolLineSummary(UiText.of(R.string.tool_line_refused, UiText.of(R.string.tool_name_memory)), LineStatus.Refused), refused)
        assertEquals(LineStatus.Error, both.status)
    }

    @Test
    fun aStoppedCallShowsStopped() {
        val summary = toolLineSummary(listOf(call("datetime"), call("torch", ToolCallState.Stopped)))

        assertEquals(ToolLineSummary(UiText.of(R.string.tool_line_stopped), LineStatus.Stopped), summary)
    }

    @Test
    fun theTimelineLabelsEachCallByItsOwnState() {
        assertEquals(UiText.of(R.string.tool_done_datetime), toolCallLabel(call("datetime")))
        assertEquals(UiText.of(R.string.tool_run_datetime), toolCallLabel(call("datetime", ToolCallState.Running)))
        assertEquals(UiText.of(R.string.tool_line_stopped), toolCallLabel(call("datetime", ToolCallState.Stopped)))
    }

    @Test
    fun storedStatusesMapToStates() {
        assertEquals(
            listOf(ToolCallState.Ok, ToolCallState.Error, ToolCallState.Refused, ToolCallState.Timeout, ToolCallState.Stopped),
            listOf("ok", "error", "refused", "timeout", "stopped").map(ToolCallState::fromStorage),
        )
        assertEquals(ToolCallState.Error, ToolCallState.fromStorage("something new"))
    }

    @Test
    fun durationsReadInMillisecondsThenSeconds() {
        assertEquals("120 ms", formatToolDuration(120))
        assertEquals("1.4 s", formatToolDuration(1_432))
    }

    // ---- inputs and output

    @Test
    fun inputsMarkCodeLikeValues() {
        val inputs = ToolArgsView.inputs("""{"query":"best pizza in Rennes","expression":"12 * (3 + 4)","limit":5}""")

        // Order is the JSON parser's: Android's keeps the model's, the JVM stand-in used here does not.
        val byKey = inputs.associateBy { it.key }
        assertEquals(setOf("query", "expression", "limit"), byKey.keys)
        assertEquals(listOf(false, true, false), listOf("query", "expression", "limit").map { byKey.getValue(it).mono })
        assertEquals("5", byKey.getValue("limit").value)
    }

    @Test
    fun unreadableOrNullArgumentsAreNoInputs() {
        assertTrue(ToolArgsView.inputs("nope").isEmpty())
        assertEquals(listOf("a"), ToolArgsView.inputs("""{"a":"x","b":null}""").map { it.key })
    }

    @Test
    fun aFlatObjectReadsAsRows() {
        val out = readableOutput("""{"battery":"82%","charging":false}""", ToolCallState.Ok)

        assertEquals(mapOf("battery" to "82%", "charging" to "false"), (out as ReadableOutput.Kv).rows.toMap())
    }

    @Test
    fun aListOfTitlesAndUrlsReadsAsResults() {
        val direct = readableOutput("""[{"title":"One","url":"https://a.example"},{"title":"Two"}]""", ToolCallState.Ok)
        val wrapped = readableOutput("""{"results":[{"title":"One","url":"https://a.example"}]}""", ToolCallState.Ok)

        assertEquals(
            ReadableOutput.Results(listOf(ResultItem("One", "https://a.example"), ResultItem("Two", null))),
            direct,
        )
        assertEquals(ReadableOutput.Results(listOf(ResultItem("One", "https://a.example"))), wrapped)
    }

    @Test
    fun anOpenedAppReadsAsAnApp() {
        assertEquals(ReadableOutput.AppOpened("Clock"), readableOutput("""{"opened":"Clock"}""", ToolCallState.Ok))
        assertEquals(ReadableOutput.AppOpened("Clock", set = true), readableOutput("""{"opened":"Clock","status":"set, shown in Clock"}""", ToolCallState.Ok))
    }

    @Test
    fun aNestedObjectOrPlainTextFallsBackToText() {
        val nested = """{"a":{"b":1}}"""
        assertEquals(ReadableOutput.Text(nested), readableOutput(nested, ToolCallState.Ok))
        assertEquals(ReadableOutput.Text("It is 14:02."), readableOutput("It is 14:02.", ToolCallState.Ok))
        assertEquals(ReadableOutput.Text("{broken"), readableOutput("{broken", ToolCallState.Ok))
        assertEquals(ReadableOutput.Text("[1,2]"), readableOutput("[1,2]", ToolCallState.Ok))
        assertEquals(ReadableOutput.Text("{}"), readableOutput("{}", ToolCallState.Ok))
    }

    @Test
    fun aFailedCallReadsAsAnErrorWithoutTheRuntimesPrefix() {
        assertEquals(
            ReadableOutput.Error("unknown tool 'x'."),
            readableOutput("Error: unknown tool 'x'.", ToolCallState.Error),
        )
        assertTrue(readableOutput("Error: the user refused this action.", ToolCallState.Refused) is ReadableOutput.Error)
        assertTrue(readableOutput("""{"ok":true}""", ToolCallState.Timeout) is ReadableOutput.Error)
    }

    @Test
    fun theRawViewPrettyPrintsJsonAndLeavesTextAlone() {
        assertTrue(rawOutput("""{"a":1,"b":2}""").contains("\n"))
        assertEquals("plain", rawOutput("plain"))
        assertTrue(isJsonOutput("""[{"title":"x"}]"""))
        assertFalse(isJsonOutput("It is 14:02."))
        assertFalse(isJsonOutput("{broken"))
    }
}
