package io.github.hokanosekai.tensai.ui.tools

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.ContactSupport
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.ManageSearch
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.WbCloudy
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.hokanosekai.tensai.R

/** What a tool call reads like while it runs and once it ran. */
class ToolActions(@StringRes val done: Int, @StringRes val running: Int)

/**
 * How one tool shows in the UI (TEN-173, TEN-174). The model's own description stays on
 * [io.github.hokanosekai.tensai.agent.Tool]; this is what the user reads.
 *
 * @param actions the labels of a call, "Checked the phone" / "Checking the phone...".
 * @param variantArg an argument whose value picks a more precise label (`memory` has an `action`), with the
 *   labels in [variants] by value. A value with no entry gets [actions].
 * @param confirmLabel the "tensai wants to ..." line of the confirmation card, built from the call's arguments.
 * @param refused the "Timer refused" line of a phone action no app handled, [ToolUiCatalog.refusedLabel] falls back to "<name> refused".
 * @param dismissed the "Question dismissed" line of a call the user closed, [ToolUiCatalog.dismissedLabel] falls back to [actions].
 */
class ToolUiMeta(
    val id: String,
    val icon: ImageVector,
    @StringRes val name: Int,
    @StringRes val description: Int,
    val actions: ToolActions,
    val variantArg: String? = null,
    val variants: Map<String, ToolActions> = emptyMap(),
    val confirmLabel: ((Map<String, String>) -> UiText)? = null,
    @StringRes val refused: Int? = null,
    @StringRes val dismissed: Int? = null,
)

/**
 * The UI metadata of every tool, by id: display name, icon, description and action labels. Holds the planned
 * tools of v0.3 too, so the ticket that registers one adds no UI work. Icons are Material Symbols Rounded as
 * far as material-icons-extended has them: `play_pause` is [Icons.Rounded.PlayArrow] and `partly_cloudy_day`
 * [Icons.Rounded.WbCloudy], the closest ones it ships.
 */
object ToolUiCatalog {

    private val entries = listOf(
        ToolUiMeta("datetime", Icons.Rounded.Schedule, R.string.tool_name_datetime, R.string.tool_desc_datetime,
            ToolActions(R.string.tool_done_datetime, R.string.tool_run_datetime)),
        ToolUiMeta("calculate", Icons.Rounded.Calculate, R.string.tool_name_calculate, R.string.tool_desc_calculate,
            ToolActions(R.string.tool_done_calculate, R.string.tool_run_calculate)),
        ToolUiMeta("convert", Icons.Rounded.SwapHoriz, R.string.tool_name_convert, R.string.tool_desc_convert,
            ToolActions(R.string.tool_done_convert, R.string.tool_run_convert)),
        ToolUiMeta("convert_time", Icons.Rounded.Schedule, R.string.tool_name_convert_time, R.string.tool_desc_convert_time,
            ToolActions(R.string.tool_done_convert_time, R.string.tool_run_convert_time)),
        ToolUiMeta("device_info", Icons.Rounded.Smartphone, R.string.tool_name_device_info, R.string.tool_desc_device_info,
            ToolActions(R.string.tool_done_device_info, R.string.tool_run_device_info)),
        ToolUiMeta("search_chats", Icons.Rounded.ManageSearch, R.string.tool_name_search_chats, R.string.tool_desc_search_chats,
            ToolActions(R.string.tool_done_search_chats, R.string.tool_run_search_chats)),
        ToolUiMeta("memory", Icons.Rounded.Bookmark, R.string.tool_name_memory, R.string.tool_desc_memory,
            ToolActions(R.string.tool_done_memory, R.string.tool_run_memory),
            variantArg = "action",
            variants = mapOf(
                "remember" to ToolActions(R.string.tool_done_memory_remember, R.string.tool_run_memory_remember),
                "list" to ToolActions(R.string.tool_done_memory_list, R.string.tool_run_memory_list),
                "forget" to ToolActions(R.string.tool_done_memory_forget, R.string.tool_run_memory_forget),
            ),
            confirmLabel = { args ->
                val id = args["id"]
                if (id != null) UiText.of(R.string.tool_confirm_memory_forget_id, id) else UiText.of(R.string.tool_confirm_memory_forget)
            }),
        ToolUiMeta("ask_user", Icons.Rounded.ContactSupport, R.string.tool_name_ask_user, R.string.tool_desc_ask_user,
            ToolActions(R.string.tool_done_ask_user, R.string.tool_run_ask_user),
            dismissed = R.string.tool_dismissed_ask_user),
        ToolUiMeta("clipboard", Icons.Rounded.ContentPaste, R.string.tool_name_clipboard, R.string.tool_desc_clipboard,
            ToolActions(R.string.tool_done_clipboard, R.string.tool_run_clipboard),
            variantArg = "action",
            variants = mapOf(
                "get" to ToolActions(R.string.tool_done_clipboard_get, R.string.tool_run_clipboard_get),
                "set" to ToolActions(R.string.tool_done_clipboard_set, R.string.tool_run_clipboard_set),
            )),
        ToolUiMeta("torch", Icons.Rounded.FlashlightOn, R.string.tool_name_torch, R.string.tool_desc_torch,
            ToolActions(R.string.tool_done_torch, R.string.tool_run_torch),
            variantArg = "state",
            variants = mapOf(
                "on" to ToolActions(R.string.tool_done_torch_on, R.string.tool_run_torch_on),
                "off" to ToolActions(R.string.tool_done_torch_off, R.string.tool_run_torch_off),
            )),
        ToolUiMeta("set_timer", Icons.Rounded.Timer, R.string.tool_name_set_timer, R.string.tool_desc_set_timer,
            ToolActions(R.string.tool_done_set_timer, R.string.tool_run_set_timer),
            refused = R.string.tool_refused_timer),
        ToolUiMeta("set_alarm", Icons.Rounded.Alarm, R.string.tool_name_set_alarm, R.string.tool_desc_set_alarm,
            ToolActions(R.string.tool_done_set_alarm, R.string.tool_run_set_alarm),
            refused = R.string.tool_refused_alarm),
        ToolUiMeta("create_event", Icons.Rounded.Event, R.string.tool_name_create_event, R.string.tool_desc_create_event,
            ToolActions(R.string.tool_done_create_event, R.string.tool_run_create_event),
            refused = R.string.tool_refused_event),
        ToolUiMeta("draft_sms", Icons.Rounded.Sms, R.string.tool_name_draft_sms, R.string.tool_desc_draft_sms,
            ToolActions(R.string.tool_done_draft_sms, R.string.tool_run_draft_sms),
            refused = R.string.tool_refused_sms),
        ToolUiMeta("draft_email", Icons.Rounded.Mail, R.string.tool_name_draft_email, R.string.tool_desc_draft_email,
            ToolActions(R.string.tool_done_draft_email, R.string.tool_run_draft_email),
            refused = R.string.tool_refused_email),
        ToolUiMeta("find_contact", Icons.Rounded.Contacts, R.string.tool_name_find_contact, R.string.tool_desc_find_contact,
            ToolActions(R.string.tool_done_find_contact, R.string.tool_run_find_contact),
            refused = R.string.tool_refused_contacts),
        ToolUiMeta("open_app", Icons.Rounded.Apps, R.string.tool_name_open_app, R.string.tool_desc_open_app,
            ToolActions(R.string.tool_done_open_app, R.string.tool_run_open_app)),
        ToolUiMeta("open_url", Icons.Rounded.OpenInNew, R.string.tool_name_open_url, R.string.tool_desc_open_url,
            ToolActions(R.string.tool_done_open_url, R.string.tool_run_open_url),
            refused = R.string.tool_refused_url,
            confirmLabel = { UiText.of(R.string.tool_confirm_open_url) }),
        ToolUiMeta("media_control", Icons.Rounded.PlayArrow, R.string.tool_name_media_control, R.string.tool_desc_media_control,
            ToolActions(R.string.tool_done_media_control, R.string.tool_run_media_control)),
        ToolUiMeta("web_search", Icons.Rounded.TravelExplore, R.string.tool_name_web_search, R.string.tool_desc_web_search,
            ToolActions(R.string.tool_done_web_search, R.string.tool_run_web_search)),
        ToolUiMeta("web_fetch", Icons.Rounded.Article, R.string.tool_name_web_fetch, R.string.tool_desc_web_fetch,
            ToolActions(R.string.tool_done_web_fetch, R.string.tool_run_web_fetch)),
        ToolUiMeta("weather", Icons.Rounded.WbCloudy, R.string.tool_name_weather, R.string.tool_desc_weather,
            ToolActions(R.string.tool_done_weather, R.string.tool_run_weather)),
        ToolUiMeta("wikipedia", Icons.Rounded.MenuBook, R.string.tool_name_wikipedia, R.string.tool_desc_wikipedia,
            ToolActions(R.string.tool_done_wikipedia, R.string.tool_run_wikipedia)),
    )

    private val byId = entries.associateBy { it.id }

    /** Every tool id with metadata, in display order. */
    val ids: List<String> = entries.map { it.id }

    /** The metadata of [tool], `null` for an id this build has none for (a model can call any name). */
    fun meta(tool: String): ToolUiMeta? = byId[tool]

    fun icon(tool: String): ImageVector = byId[tool]?.icon ?: Icons.Rounded.Build

    /** "Date and time"; the raw id for an unknown tool. */
    fun name(tool: String): UiText = if (tool.isEmpty()) UiText.of(R.string.tool_name_unnamed) else byId[tool]?.let { UiText.of(it.name) } ?: UiText.Raw(tool)

    /** The user-facing description, `null` for an unknown tool. */
    fun description(tool: String): UiText? = byId[tool]?.let { UiText.of(it.description) }

    /** "Checked the phone": what the call did, past tense. */
    fun doneLabel(tool: String, argsJson: String): UiText {
        if (tool.isEmpty()) return UiText.of(R.string.tool_done_unnamed)
        val meta = byId[tool] ?: return UiText.of(R.string.tool_done_generic, name(tool))
        return UiText.of(actions(meta, argsJson).done)
    }

    /** "Checking the phone...": what the call is doing. */
    fun runningLabel(tool: String, argsJson: String): UiText {
        // A call whose name is not written yet (TEN-185).
        if (tool.isEmpty()) return UiText.of(R.string.tool_run_unnamed)
        val meta = byId[tool] ?: return UiText.of(R.string.tool_run_generic, name(tool))
        return UiText.of(actions(meta, argsJson).running)
    }

    /** "Timer refused": a call that was refused, by the user or because no app handled it. */
    fun refusedLabel(tool: String): UiText =
        byId[tool]?.refused?.let { UiText.of(it) } ?: UiText.of(R.string.tool_line_refused, name(tool))

    /** "Question dismissed": a call the user closed without answering. */
    fun dismissedLabel(tool: String): UiText =
        byId[tool]?.dismissed?.let { UiText.of(it) } ?: doneLabel(tool, "{}")

    /**
     * The rows of a call in the detail sheet: the arguments as they are, except `ask_user`, whose question and options
     * are prose and only `multiple` reads as code.
     */
    fun detailInputs(tool: String, argsJson: String): List<ToolInput> {
        val inputs = ToolArgsView.inputs(argsJson)
        return if (tool == "ask_user") inputs.map { it.copy(mono = it.key == "multiple") } else inputs
    }

    /**
     * What a network call sent out, in one flat line for "Sent to <host> · <this>" in the detail sheet: the query
     * of a search or Wikipedia lookup, the place of a forecast, the URL of a page. Makes it obvious at a glance when
     * the model puts something unexpected in a request. `null` for a tool that sends nothing of the kind.
     */
    fun sentDetail(tool: String, argsJson: String): String? {
        val key = SENT_ARGS[tool] ?: return null
        return ToolArgsView.values(argsJson)[key]?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
    }

    private val SENT_ARGS = mapOf("web_search" to "query", "wikipedia" to "query", "weather" to "place", "web_fetch" to "url")

    /** The "tensai wants to ..." line of the confirmation card. */
    fun confirmLabel(tool: String, argsJson: String): UiText =
        byId[tool]?.confirmLabel?.invoke(ToolArgsView.values(argsJson)) ?: UiText.of(R.string.tool_confirm_generic, name(tool))

    /**
     * The rows of the confirmation card. The arguments as they are, except `open_url`: the host first, in the mono
     * face, because the host is what the user decides on, then the whole URL.
     */
    fun confirmInputs(tool: String, argsJson: String): List<ToolInput> {
        val url = ToolArgsView.values(argsJson)["url"]
        val host = if (tool == "open_url" && url != null) runCatching { java.net.URI(url.trim()).host }.getOrNull() else null
        if (host == null) return ToolArgsView.inputs(argsJson)
        return listOf(ToolInput("host", host, mono = true), ToolInput("url", url!!, mono = false))
    }

    private fun actions(meta: ToolUiMeta, argsJson: String): ToolActions {
        val value = meta.variantArg?.let { ToolArgsView.values(argsJson)[it] }
        return value?.let { meta.variants[it] } ?: meta.actions
    }
}
