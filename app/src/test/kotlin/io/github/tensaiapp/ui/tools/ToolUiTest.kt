package io.github.tensaiapp.ui.tools

import io.github.tensaiapp.R
import io.github.tensaiapp.agent.CalculateTool
import io.github.tensaiapp.agent.ConfirmTarget
import io.github.tensaiapp.agent.ConvertTimeTool
import io.github.tensaiapp.agent.ConvertTool
import io.github.tensaiapp.agent.DateTimeTool
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
        val registered = listOf("datetime", "calculate", "convert", "convert_time", "device_info", "search_chats", "memory", "ask_user", "clipboard", "torch")
        val planned = listOf(
            "set_timer", "set_alarm", "create_event", "draft_sms", "draft_email", "find_contact", "open_app", "open_url",
            "read_calendar", "location", "media_control", "web_search", "web_fetch", "weather", "wikipedia", "read_notifications",
            "read_sms", "reply_notification",
        )

        assertEquals(emptyList<String>(), (registered + planned).filter { ToolUiCatalog.meta(it) == null })
        assertEquals((registered + planned).sorted(), ToolUiCatalog.ids.sorted())
        // The tools the registry builds without a platform are checked against the real objects.
        assertNotNull(ToolUiCatalog.meta(DateTimeTool().name))
        assertNotNull(ToolUiCatalog.meta(ConvertTool().name))
        assertNotNull(ToolUiCatalog.meta(ConvertTimeTool().name))
        assertNotNull(ToolUiCatalog.meta(CalculateTool().name))
    }

    @Test
    fun anUnknownToolGetsAGenericLabelAndItsRawName() {
        assertNull(ToolUiCatalog.meta("mystery"))
        assertEquals(UiText.Raw("mystery"), ToolUiCatalog.name("mystery"))
        assertEquals(UiText.of(R.string.tool_done_generic, UiText.Raw("mystery")), ToolUiCatalog.doneLabel("mystery", "{}"))
        assertEquals(UiText.of(R.string.tool_run_generic, UiText.Raw("mystery")), ToolUiCatalog.runningLabel("mystery", "{}"))
        assertEquals(UiText.of(R.string.tool_run_unnamed), ToolUiCatalog.runningLabel("", "{}"))
        assertEquals(UiText.of(R.string.tool_done_unnamed), ToolUiCatalog.doneLabel("", "{}"))
        assertEquals(UiText.of(R.string.tool_name_unnamed), ToolUiCatalog.name(""))
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

    @Test
    fun aForgetCardNamesTheFactNotItsId() {
        val args = """{"action":"forget","id":1}"""
        val tea = ConfirmTarget("1", "My favourite tea is genmaicha")

        assertEquals(UiText.of(R.string.tool_confirm_memory_forget_fact, "My favourite tea is genmaicha"), ToolUiCatalog.confirmLabel("memory", args, tea))
        // The title says it all: no `action` / `id` rows under it.
        assertEquals(emptyList<UiText>(), ToolUiCatalog.confirmTargetLines("memory", tea))
    }

    @Test
    fun aLongFactIsShortenedInTheTitleAndShownWholeUnderIt() {
        val long = "The user keeps a sourdough starter called Clint and feeds it every morning at seven"
        val target = ConfirmTarget("2", long)

        val label = ToolUiCatalog.confirmLabel("memory", """{"action":"forget","id":2}""", target) as UiText.Res
        val shown = label.args.single() as String

        assertTrue(shown.endsWith("…"))
        assertEquals(60, shown.codePointCount(0, shown.length))
        assertTrue(long.startsWith(shown.removeSuffix("…")))
        assertEquals(listOf(UiText.Raw(long)), ToolUiCatalog.confirmTargetLines("memory", target))
    }

    @Test
    fun theTitleNeverCutsAnEmojiInHalf() {
        // 58 letters then emoji: a cut at 59 UTF-16 units would keep half of the first one.
        val fact = "x".repeat(58) + "\uD83C\uDF75\uD83C\uDF75 every morning"

        val shown = (ToolUiCatalog.confirmLabel("memory", "{}", ConfirmTarget("1", fact)) as UiText.Res).args.single() as String

        assertEquals("x".repeat(58) + "\uD83C\uDF75…", shown)
    }

    @Test
    fun aFactThatNoLongerExistsIsSaidSo() {
        val gone = ConfirmTarget("7", null)

        assertEquals(UiText.of(R.string.tool_confirm_memory_forget), ToolUiCatalog.confirmLabel("memory", """{"action":"forget","id":7}""", gone))
        assertEquals(listOf(UiText.of(R.string.tool_confirm_memory_fact_missing, "7")), ToolUiCatalog.confirmTargetLines("memory", gone))
    }

    @Test
    fun aWebFetchCardShowsTheHostThenTheWholeUrl() {
        val args = """{"url":"https://collect.example.net/p?d=hunter2"}"""

        assertEquals(UiText.of(R.string.tool_confirm_web_fetch), ToolUiCatalog.confirmLabel("web_fetch", args))
        assertEquals(
            listOf(ToolInput("host", "collect.example.net", mono = true), ToolInput("url", "https://collect.example.net/p?d=hunter2", mono = false)),
            ToolUiCatalog.confirmInputs("web_fetch", args),
        )
    }

    /** TEN-213: a reply card names the app and the conversation, then the exact text, never the id. */
    @Test
    fun aReplyCardNamesWhereItGoesAndShowsTheExactText() {
        val args = """{"id":"a1b2c3","text":" On my way, 10 min \n"}"""

        assertEquals(UiText.of(R.string.tool_confirm_reply_notification_to, "WhatsApp · Alice"), ToolUiCatalog.confirmLabel("reply_notification", args, ConfirmTarget("a1b2c3", "WhatsApp · Alice")))
        assertEquals(UiText.of(R.string.tool_confirm_reply_notification), ToolUiCatalog.confirmLabel("reply_notification", args))
        assertEquals(
            UiText.of(R.string.tool_confirm_reply_notification_group_from, "WhatsApp · Mom", "Alice"),
            ToolUiCatalog.confirmLabel("reply_notification", args, ConfirmTarget("a1b2c3", "WhatsApp · Mom", group = true, sender = "Alice")),
        )
        assertEquals(
            UiText.of(R.string.tool_confirm_reply_notification_group, "WhatsApp · Mom"),
            ToolUiCatalog.confirmLabel("reply_notification", args, ConfirmTarget("a1b2c3", "WhatsApp · Mom", group = true)),
        )
        assertEquals(listOf(ToolInput("text", "On my way, 10 min", mono = false)), ToolUiCatalog.confirmInputs("reply_notification", args))
    }

    @Test
    fun aRememberCardShowsTheFactAsItWillBeStored() {
        val args = """{"action":"remember","text":"  The user   likes\ntea "}"""

        assertEquals(UiText.of(R.string.tool_confirm_memory_remember), ToolUiCatalog.confirmLabel("memory", args))
        assertEquals(listOf(ToolInput("text", "The user likes tea", mono = false)), ToolUiCatalog.confirmInputs("memory", args))
    }

    @Test
    fun withoutATargetTheCardListsTheArguments() {
        assertNull(ToolUiCatalog.confirmTargetLines("memory", null))
        assertNull(ToolUiCatalog.confirmTargetLines("torch", ConfirmTarget("1", "x")))
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

    // ---- ask_user (TEN-187)

    private val askArgs = """{"question":"Which Jade?","options":["Jade Martin","Jade Leroy"],"multiple":false}"""

    @Test
    fun aQuestionReadsAskingThenAskedThenDismissed() {
        val open = toolLineSummary(listOf(call("ask_user", ToolCallState.Running, askArgs, output = null)))
        val answered = toolLineSummary(listOf(call("ask_user", args = askArgs)))
        val dismissed = toolLineSummary(listOf(call("ask_user", ToolCallState.Dismissed, askArgs)))

        assertEquals(ToolLineSummary(UiText.of(R.string.tool_run_ask_user), LineStatus.Running), open)
        assertEquals(ToolLineSummary(UiText.of(R.string.tool_done_ask_user), LineStatus.Ok), answered)
        // Untinted like any ok line, only the words differ.
        assertEquals(ToolLineSummary(UiText.of(R.string.tool_dismissed_ask_user), LineStatus.Ok), dismissed)
        assertEquals(UiText.of(R.string.tool_dismissed_ask_user), toolCallLabel(call("ask_user", ToolCallState.Dismissed, askArgs)))
    }

    @Test
    fun theTimelineShowsTheQuestionNotTheRawArguments() {
        assertEquals("Which Jade?", toolCallSummary(call("ask_user", args = askArgs)))
    }

    @Test
    fun theDetailListsQuestionOptionsOnePerLineAndMultipleInMono() {
        val inputs = ToolUiCatalog.detailInputs("ask_user", """{"question":"Pick (one)?","options":["Terraform","Ansible"],"multiple":true}""")

        assertEquals(
            listOf(
                ToolInput("question", "Pick (one)?", mono = false),
                ToolInput("options", "Terraform\nAnsible", mono = false),
                ToolInput("multiple", "true", mono = true),
            ),
            inputs,
        )
    }

    @Test
    fun theMetadataOfAskUserIsInTheLocalListWithItsDescription() {
        val meta = ToolUiCatalog.meta("ask_user")!!

        assertEquals(R.string.tool_name_ask_user, meta.name)
        assertEquals(R.string.tool_desc_ask_user, meta.description)
        assertEquals(R.string.tool_dismissed_ask_user, meta.dismissed)
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
        val inputs = ToolArgsView.inputs("""{"query":"best pizza in Lisbon","expression":"12 * (3 + 4)","limit":5}""")

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
        assertTrue(readableOutput("""{"ok":true}""", ToolCallState.Timeout) is ReadableOutput.Error)
    }

    /**
     * TEN-205: what the model reads after a Deny is an order to it, in English, with the tool's internal name. The detail
     * shows the app's own line instead, for that text and for the one rows stored before still hold. A tool's own refusal
     * (no app for it, a permission) is useful to the user and stays as written.
     */
    @Test
    fun aCallTheGateDidNotApproveReadsAsTheAppsLineNotTheModelsText() {
        val now = "The user did not approve: 'memory' did not run and nothing changed. Tell the user it was not done; do not say it was."
        assertEquals(ReadableOutput.NotApproved, readableOutput(now, ToolCallState.Refused))
        assertEquals(ReadableOutput.NotApproved, readableOutput("Error: the user refused this action.", ToolCallState.Refused))
        assertEquals(
            ReadableOutput.Error("no app on this phone can open maps."),
            readableOutput("Error: no app on this phone can open maps.", ToolCallState.Refused),
        )
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
