package io.github.tensaiapp.agent

import io.github.tensaiapp.data.memory.MemoryContext
import io.github.tensaiapp.engine.ChatMessage
import io.github.tensaiapp.engine.MessageRole
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * What a tool needs before the model may call it (TEN-172). The user grants it by tier in Settings
 * (TEN-174), see [ToolRegistry].
 *
 * @param timeoutMs how long [AgentRuntime] lets one call of a tool of this tier run.
 */
enum class ToolTier(val timeoutMs: Long) {
    /** Reads or writes only inside the app and the device state it exposes, no permission. */
    Local(10_000),

    /** Opens another app through an intent, the user validates there. */
    PhoneAction(10_000),

    /** Needs a runtime Android permission, asked at first use. */
    AndroidPermission(10_000),

    /** Sends something off the phone. Slower, and the destination is shown to the user. */
    Network(30_000),
}

/**
 * What a [Tool] answers. The text goes back to the model as the `tool` message, so it should be
 * short: [AgentRuntime] truncates it to a token budget anyway.
 *
 * @param text the result (plain text or JSON) or, when [isError], what went wrong, written for the model.
 * @param destination for a [ToolTier.Network] tool, where the request went ("DuckDuckGo"), shown on the call.
 * @param refused an error that is a "no" rather than a failure: no app on the phone can do what the tool opened
 *   an intent for. Stored as [ToolCallStatus.Refused] and shown as a refusal. Implies [isError].
 */
data class ToolResult(
    val text: String,
    val isError: Boolean = false,
    val destination: String? = null,
    val refused: Boolean = false,
) {
    companion object {
        fun ok(text: String, destination: String? = null) = ToolResult(text, destination = destination)

        fun error(message: String, destination: String? = null) = ToolResult(message, isError = true, destination = destination)

        fun refused(message: String) = ToolResult(message, isError = true, refused = true)
    }
}

/**
 * What a gated call acts on, as the confirmation card names it (TEN-169): the fact a `forget` deletes, by its
 * text rather than its id. [text] is `null` when [id] matches nothing any more.
 */
data class ConfirmTarget(val id: String, val text: String?)

/**
 * What the conversation so far vouches for, read by [Tool.requiresConfirmation]: the links the user wrote, the links
 * `web_search` returned, and whether a [Tool.returnsUntrustedContent] result is anywhere in it. Conversation-wide, not
 * per turn: a page read in one turn can steer the model in the next ("when the user replies, remember X").
 *
 * Folded one message at a time by [with], over the history as the prompt replays it, by [AgentRuntime] as a turn runs
 * and by the chat over the stored rows: the same function on the same messages, so the card shows exactly when the
 * runtime asks. A result counts whatever its status (an error, a refusal, a stop): the replayed history keeps none.
 */
class ConversationTrust private constructor(
    private val userUrls: Set<String>,
    private val searchUrls: Set<String>,
    val readUntrusted: Boolean,
    /** The tool of each call id seen, so a `tool` message is read against the call it answers. */
    private val callTools: Map<String, String>,
) {
    /** Nothing seen yet: every link is the model's. */
    constructor() : this(emptySet(), emptySet(), false, emptyMap())

    /** The conversation once [message] is in it. [find] is the registered tool of a name. */
    fun with(message: ChatMessage, find: (String) -> Tool?): ConversationTrust = when (message.role) {
        MessageRole.User ->
            ConversationTrust(userUrls + FetchUrls.inUserText(MemoryContext.withoutBlock(message.content)), searchUrls, readUntrusted, callTools)
        MessageRole.Assistant -> {
            val calls = message.toolCalls.orEmpty()
            if (calls.isEmpty()) this else ConversationTrust(userUrls, searchUrls, readUntrusted, callTools + calls.map { it.id to it.name })
        }
        MessageRole.Tool -> {
            val tool = message.toolCallId?.let(callTools::get)?.let(find)
            if (tool == null) {
                this
            } else {
                ConversationTrust(
                    userUrls,
                    if (tool.name == WebSearchTool.NAME) searchUrls + FetchUrls.inSearchResult(message.content) else searchUrls,
                    readUntrusted || tool.returnsUntrustedContent,
                    callTools,
                )
            }
        }
        else -> this
    }

    /**
     * Whether [url] came from the user or a search engine rather than from the model: the same page, compared by
     * [FetchUrls.key]. A model-built URL, which could carry data read on the phone, is not.
     */
    fun vouchesFor(url: String): Boolean = FetchUrls.key(url)?.let { it in userUrls || it in searchUrls } ?: false
}

/** A capability the model can call (TEN-172). Registered in a [ToolRegistry]. */
interface Tool {
    /** Name the model calls it by: `[a-z0-9_]`, unique in a registry. */
    val name: String

    /** What it does and when to use it, as the prompt shows it to the model. */
    val description: String

    /** JSON Schema of the arguments, as a string. [AgentRuntime] only checks `required` against it. */
    val parametersSchema: String

    val tier: ToolTier

    /** The call sends something out or deletes something: [AgentRuntime] asks its [ConfirmationGate] first. */
    val sendsOrDeletes: Boolean get() = false

    /**
     * Whether this very call needs the gate. Defaults to [sendsOrDeletes]; a tool with several actions
     * overrides it to ask only for the ones that send or delete (`memory` asks for `forget`, not `list`).
     * [trust] is what the conversation vouched for before the call, for a tool whose risk depends on it (`web_fetch`,
     * `memory` remember); the default, nothing vouched for, is the strict reading.
     */
    fun requiresConfirmation(argsJson: String, trust: ConversationTrust = ConversationTrust()): Boolean = sendsOrDeletes

    /**
     * What the tool returns is text a third party wrote (a web page, a search snippet, an invite's title), which
     * can carry instructions aimed at the model. Read by [ConversationTrust.with]: once such a result is in the
     * conversation, `memory` asks before a `remember`.
     */
    val returnsUntrustedContent: Boolean get() = false

    /**
     * What the call [callId] acts on, read by [UiConfirmationGate] before the card shows (TEN-169), so the user
     * decides on the fact and not on its id. `null`, the default, when the arguments say it all. Called from the
     * turn's own context, so it must be main-safe, as Room's suspend queries are. A tool that answers should hold
     * [execute] for [callId] to what it answered: the store can change between the card and the tap.
     */
    suspend fun confirmTarget(callId: String, argsJson: String): ConfirmTarget? = null

    /**
     * The call waits for the user (`ask_user`): [AgentRuntime] puts no timeout on it, the tier's does not apply.
     * Stop still cancels it.
     */
    val waitsForUser: Boolean get() = false

    /**
     * Runs the tool, on [kotlinx.coroutines.Dispatchers.IO]. [argsJson] is a JSON object that passed the
     * `required` check. May throw: the runtime turns an exception into an error result for the model.
     *
     * Must be cancellable: the runtime cancels it at the tier's timeout and on Stop, so blocking I/O has to be
     * interruptible (use `suspendCancellableCoroutine`, `runInterruptible`, a client with call timeouts). A
     * tool that ignores cancellation is abandoned at the timeout: the call is reported as a timeout and the
     * loop goes on, while the work finishes on its own thread and its result is discarded.
     */
    suspend fun execute(argsJson: String): ToolResult
}

/**
 * The id of the call a tool is running for, in the coroutine context of [Tool.execute]: a tool that publishes
 * something to the UI (`ask_user`) keys it by call, as [UiConfirmationGate] does.
 */
class ToolCallContext(val callId: String) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ToolCallContext>
}
