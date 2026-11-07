package io.github.tensaiapp.agent

import io.github.tensaiapp.data.memory.MemoryContext
import io.github.tensaiapp.engine.ChatMessage
import io.github.tensaiapp.engine.DecodeEvent
import io.github.tensaiapp.engine.DecodeRequest
import io.github.tensaiapp.engine.DecodeStats
import io.github.tensaiapp.engine.MessageRole
import io.github.tensaiapp.engine.StopReason
import io.github.tensaiapp.engine.ToolCall
import io.github.tensaiapp.engine.ToolChoice
import io.github.tensaiapp.engine.ToolDefinition
import io.github.tensaiapp.runtime.QueueBusyException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException
import org.json.JSONObject

/** Starts one generation. [io.github.tensaiapp.runtime.InferenceQueue.generate] in the app, a script in tests. */
fun interface ReplyGenerator {
    fun generate(request: DecodeRequest): Flow<DecodeEvent>
}

/**
 * Which turns get the tools, and how strictly the base prompt holds the model back from them (TEN-197). Every chat
 * runs [Current]; the others are the debug agent bench's, to measure what the creative rule costs and saves.
 *
 * @param storageName the name a bench run logs and saves.
 * @param creativeWithoutTools a creative first turn ([CreativeRequest]) gets no tools, see [AgentRuntime.run].
 * @param strictPrompt the base prompt adds its stricter line ([BasePrompt.render]).
 */
enum class ToolPolicy(val storageName: String, val creativeWithoutTools: Boolean, val strictPrompt: Boolean) {
    Current("current", creativeWithoutTools = true, strictPrompt = false),

    /** The tools on every turn: one tools prefix for every first turn, a creative one included. */
    Always("always", creativeWithoutTools = false, strictPrompt = false),

    /** [Always], the base prompt telling the model to call a tool only when the answer cannot do without one. */
    AlwaysStrict("always-strict", creativeWithoutTools = false, strictPrompt = true),
    ;

    companion object {
        /** The policy saved as [name], `null` for one this build does not know. */
        fun of(name: String?): ToolPolicy? = entries.firstOrNull { it.storageName == name }
    }
}

/**
 * Asked before a call that [Tool.requiresConfirmation] runs. The chat's "tensai wants to ..." card is
 * [UiConfirmationGate] (TEN-174); the wait is not counted against the tool's timeout.
 *
 * A gate that asks the user calls [Tool.confirmTarget] before approving: `reply_notification` sends only to what its
 * card showed, and refuses a call approved without one (TEN-213).
 */
fun interface ConfirmationGate {
    /** `true` to run [call], `false` to refuse: the model is told so. */
    suspend fun confirm(call: ToolCall, tool: Tool): Boolean

    companion object {
        /** Approves everything: for tests and callers with no UI to ask. */
        val AutoApprove = ConfirmationGate { _, _ -> true }
    }
}

/**
 * The agent loop (TEN-172): generate with the active tools, run the calls the reply makes, hand the
 * results back, generate again, until a reply makes no call. Sits above
 * [io.github.tensaiapp.runtime.InferenceQueue] (through [ReplyGenerator]) and knows nothing
 * of the UI or the database, so the chat and the future API server share it.
 *
 * Guards:
 * - at most [MaxToolCallsPerTurn] calls per turn. Past it the model gets one more generation with
 *   [ToolChoice.None] (tools stay in the prompt, so the KV prefix is reused) and any call it still
 *   makes is dropped;
 * - after [MaxFailedCallsInARow] calls in a row that failed ([ToolCallStatus.Error], [ToolCallStatus.Refused] or
 *   [ToolCallStatus.Timeout]),
 *   the same: one generation with [ToolChoice.None], so a small model answers instead of chaining calls
 *   that go nowhere. An ok call starts the count again;
 * - a tool runs at most its tier's [ToolTier.timeoutMs] (none for a [Tool.waitsForUser]), a result at most [ResultTokenBudget] tokens;
 * - an answer that follows an ok call (the last one of the turn) and has no text (TEN-204: the model ended its turn inside or right after its
 *   think tags) is asked for once more, thinking off and [ToolChoice.None], with the empty reply left out of the history.
 *   The second answer ends the turn whatever it holds: never a third;
 * - cancelling the collector (Stop) cancels the running tool and ends the loop. The calls that did
 *   not finish get no event: whoever records the turn closes them with [ToolCallOutcome.stopped] (a
 *   flow cannot emit once its collector is cancelled), so every call of a reply has its result.
 *
 * Nothing a tool or the model does throws out of [run] except cancellation: unknown tool, bad
 * arguments, an exception, a timeout and a refusal all come back to the model as an error result.
 *
 * Between two generations the queue is free: a foreign generation (a warm-up aside, which is cancelled)
 * may take it, and the next generation is then rejected as busy. [waitForQueue] gets one bounded wait
 * per generation, then the turn ends with a [QueueBusyException] error. For the same reason cancelling
 * the queue's active generation does nothing while a tool runs: Stop, in the app or the notification,
 * cancels the whole turn (see `ChatTurns`).
 *
 * A tool runs on [Dispatchers.IO], detached from the turn: one that ignores cancellation is abandoned
 * at its timeout (reported [ToolCallStatus.Timeout]) and the loop moves on, see [Tool.execute].
 *
 * @param waitForQueue suspends until the queue can take a generation again, `false` when it did not
 *   within a bound. The default only pauses, for a generator that has no queue.
 * @param countTokens tokens of a text under the loaded model's tokenizer, `null` when it cannot be
 *   counted (no model, a generation holding the engine).
 * @param timeoutMs how long a call of [Tool] may run, a parameter for tests.
 * @param toolPolicy which turns get the tools: [ToolPolicy.Current] in a chat, another in the debug agent bench.
 */
class AgentRuntime(
    private val registry: ToolRegistry,
    private val gate: ConfirmationGate = ConfirmationGate.AutoApprove,
    private val waitForQueue: suspend () -> Boolean = { delay(QueueRetryPauseMs); true },
    private val countTokens: suspend (String) -> Int? = { null },
    private val timeoutMs: (Tool) -> Long = { it.tier.timeoutMs },
    private val clock: () -> Long = System::currentTimeMillis,
    private val toolPolicy: ToolPolicy = ToolPolicy.Current,
) {

    /** The tier of the registered tool called [toolName], granted or not, `null` when there is none of that name. */
    fun tierOf(toolName: String): ToolTier? = registry.find(toolName)?.tier

    /**
     * Whether a call of [toolName] with [argsJson], made with [trust] behind it, goes through the [ConfirmationGate],
     * read against every registered tool: a stored call is shown with or without its card whatever the grants are now.
     */
    fun requiresConfirmation(toolName: String, argsJson: String, trust: ConversationTrust = ConversationTrust()): Boolean =
        registry.find(toolName)?.let { runCatching { it.asksFirst(argsJson.ifBlank { "{}" }, trust) }.getOrDefault(false) } ?: false

    /**
     * [trust] once [message] is in the history, read against every registered tool. The one fold of [ConversationTrust]:
     * [run] applies it to each message of the prompt and of the turn, the chat to the stored rows, so its cards match
     * the calls that were asked about.
     */
    fun trustWith(trust: ConversationTrust, message: ChatMessage): ConversationTrust = trust.with(message, registry::find)

    /** [Tool.fallbackAnswer] of [outcome]'s tool, `null` for a call that was not ok or a tool with none. */
    fun fallbackAnswer(outcome: ToolCallOutcome): String? =
        if (outcome.status != ToolCallStatus.Ok) null else registry.find(outcome.call.name)?.fallbackAnswer(outcome.output)

    /** The active tools as the prompt describes them. */
    fun definitions(): List<ToolDefinition> =
        registry.active().map { ToolDefinition(it.name, it.description, it.parametersSchema) }

    /** Whether any tool is active right now: with none, a turn is just a plain generation. */
    fun hasTools(): Boolean = registry.active().isNotEmpty()

    /**
     * Runs a turn on [request] (the history, ending on the user's message). [request]'s own tools are
     * replaced by the registry's active ones, read once here: a grant changed mid-turn applies to the next.
     * Cold, runs when collected.
     *
     * [system], when given, builds the system message from the names of those same tools and replaces the
     * request's own: the base prompt names a tool only when the turn offers it, even when a grant changed
     * between the request being built and the turn starting.
     *
     * The first turn of a conversation (no assistant message yet, a regenerate of it included) whose user message
     * asks for creative writing ([CreativeRequest]) gets no tools at all, and [system] the empty set: the model writes,
     * as on the plain path, instead of searching first. Only the first: without tools the prompt differs from token 0,
     * so later in a chat the whole history would be prefilled again, for this turn and for the next. On the first turn
     * that prompt is short, and there is no earlier call in it for the model to copy. Unless [toolPolicy] offers the
     * tools on every turn.
     */
    fun run(request: DecodeRequest, generator: ReplyGenerator, system: ((Set<String>) -> String?)? = null): Flow<AgentEvent> = flow {
        emit(AgentEvent.TurnStarted)
        val firstTurn = request.messages.none { it.role == MessageRole.Assistant }
        val asked = request.messages.lastOrNull { it.role == MessageRole.User }?.let { MemoryContext.withoutBlock(it.content) }
        val creative = toolPolicy.creativeWithoutTools && firstTurn && asked != null && CreativeRequest.matches(asked)
        val tools = if (creative) emptyList() else registry.active()
        val definitions = tools.map { ToolDefinition(it.name, it.description, it.parametersSchema) }
        val turnId = UUID.randomUUID().toString().take(8)
        val usedIds = HashSet<String>()
        val messages = if (system == null) {
            request.messages
        } else {
            listOfNotNull(system(tools.mapTo(HashSet()) { it.name })?.let { ChatMessage(MessageRole.System, it) }) +
                request.messages.dropWhile { it.role == MessageRole.System }
        }
        val history = messages.toMutableList()
        var callsMade = 0
        var failedInARow = 0
        var index = 0
        // The last call ended ok, so the model has something to answer from; and whether the empty answer's one retry is spent.
        var okCall = false
        var askedAgain = false
        // What the gate reads (web_fetch, memory remember): the whole history, then each message the turn adds to it.
        var trust = request.messages.fold(ConversationTrust(), ::trustWith)
        while (true) {
            val forced = callsMade >= MaxToolCallsPerTurn || failedInARow >= MaxFailedCallsInARow
            emit(AgentEvent.ReplyStarted(index))
            val generation = request.copy(
                messages = history.toList(),
                tools = definitions,
                toolChoice = if (forced || askedAgain) ToolChoice.None else ToolChoice.Auto,
                sampling = if (askedAgain) request.sampling.copy(enableThinking = false, reasoningBudget = null) else request.sampling,
            )
            var reply = generateOnce(generator, generation)
            // Rejected before any token, so asking again is safe: once, after the queue frees.
            if (reply.error is QueueBusyException && waitForQueue()) reply = generateOnce(generator, generation)
            reply.error?.let {
                emit(AgentEvent.Error(if (it is QueueBusyException) QueueBusyException("The model was busy with another task, the turn stopped") else it))
                return@flow
            }
            val turn = reply.turn?.let { bareCall(it, tools) ?: it }
            val calls = if (forced || askedAgain || turn == null) emptyList() else uniqueIds(turn.toolCalls, turnId, index, usedIds)
            // Not a reply whose calls were dropped past the limit (that model wanted more tools, it did not end on nothing),
            // nor one the parser rejected: its prose may be in the stream, and the chat keeps it.
            val askAgain = calls.isEmpty() && okCall && !askedAgain && turn != null && turn.toolCalls.isEmpty() &&
                !turn.toolParseFailed && turn.content.isBlank() && reply.stats?.stopReason !in NoSecondAsk
            emit(
                AgentEvent.ReplyFinished(
                    index = index,
                    content = turn?.content,
                    calls = calls,
                    droppedCalls = forced && turn != null && turn.toolCalls.isNotEmpty(),
                    stats = reply.stats,
                    toolsIgnored = turn?.toolsIgnored ?: false,
                    toolParseFailed = turn?.toolParseFailed ?: false,
                    truncated = turn?.truncated ?: false,
                    retrying = askAgain,
                    raw = reply.raw.takeIf { askAgain },
                ),
            )
            if (askAgain) {
                askedAgain = true
                index++
                continue
            }
            if (calls.isEmpty()) {
                emit(AgentEvent.Done)
                return@flow
            }
            val replied = ChatMessage(MessageRole.Assistant, turn!!.content, toolCalls = calls.map { it.replayable() }, reasoning = turn!!.reasoning)
            history += replied
            trust = trustWith(trust, replied)
            for ((i, call) in calls.withIndex()) {
                val outcome = executeCall(call, tools, trust, overLimit = callsMade + i >= MaxToolCallsPerTurn)
                emit(AgentEvent.ToolCallFinished(outcome))
                okCall = outcome.status == ToolCallStatus.Ok
                when (outcome.status) {
                    ToolCallStatus.Error, ToolCallStatus.Refused, ToolCallStatus.Timeout -> failedInARow++
                    ToolCallStatus.Ok -> failedInARow = 0
                    else -> Unit
                }
                val result = ChatMessage(MessageRole.Tool, outcome.output, toolCallId = call.id)
                history += result
                // Every result counts, an error or a refusal as much as an ok: the stored history the chat rebuilds
                // this from keeps no status. A tool that puts third-party text in an error is covered by that.
                trust = trustWith(trust, result)
            }
            callsMade += calls.size
            index++
        }
    }

    private class Reply(val stats: DecodeStats?, val turn: DecodeEvent.ToolTurn?, val error: Throwable?, val raw: String)

    /** Runs one generation, forwarding its text and keeping the terminal facts. */
    private suspend fun FlowCollector<AgentEvent>.generateOnce(generator: ReplyGenerator, request: DecodeRequest): Reply {
        var stats: DecodeStats? = null
        var turn: DecodeEvent.ToolTurn? = null
        var error: Throwable? = null
        val raw = StringBuilder()
        generator.generate(request).collect { event ->
            when (event) {
                is DecodeEvent.Token -> {
                    raw.append(event.text)
                    emit(AgentEvent.Token(event.text))
                }
                is DecodeEvent.Parsed -> emit(AgentEvent.Parsed(event.content, event.reasoning, event.toolCalls))
                is DecodeEvent.ToolTurn -> turn = event
                is DecodeEvent.Done -> stats = event.stats
                is DecodeEvent.Error -> error = event.cause
            }
        }
        return Reply(stats, turn, error, raw.toString())
    }

    /**
     * [turn] as a call when its whole content is one written as bare JSON ([BareToolCall]), with no content
     * left: it then goes through the same validation, gate and history as a tagged call, and the reply is
     * stored and replayed as a call, not as the JSON. `null` when it is not one, or the engine's parse is not
     * one to second-guess (it found calls, failed, was cut short, or the template never showed the tools).
     */
    private fun bareCall(turn: DecodeEvent.ToolTurn, tools: List<Tool>): DecodeEvent.ToolTurn? {
        if (turn.toolCalls.isNotEmpty() || turn.toolParseFailed || turn.truncated || turn.toolsIgnored) return null
        val call = BareToolCall.parse(turn.content, tools.map { it.name }) ?: return null
        return turn.copy(content = "", toolCalls = listOf(call))
    }

    /**
     * Every call gets an id of the runtime's own: templates and small models reuse `call_1` from one turn to the next,
     * and the chat keys its rows and pairs results by id. The `tool` message
     * answers by it. Namespaced by a per-turn id so they never repeat across turns.
     */
    private fun uniqueIds(calls: List<ToolCall>, turnId: String, replyIndex: Int, seen: MutableSet<String>): List<ToolCall> {
        return calls.mapIndexed { i, call ->
            val id = "call_${turnId}_${replyIndex}_$i"
            seen += id
            call.copy(id = id)
        }
    }

    /** Validates, confirms and runs [call]. Throws only for cancellation. */
    private suspend fun FlowCollector<AgentEvent>.executeCall(
        call: ToolCall,
        tools: List<Tool>,
        trust: ConversationTrust,
        overLimit: Boolean,
    ): ToolCallOutcome {
        val tool = tools.firstOrNull { it.name == call.name }
        val startedAt = clock()
        emit(AgentEvent.ToolCallStarted(call, tool?.tier, startedAt))

        suspend fun outcome(
            status: ToolCallStatus,
            text: String,
            durationMs: Long = 0,
            destination: String? = null,
        ): ToolCallOutcome {
            val (output, tokens) = clip(text)
            return ToolCallOutcome(call, tool?.tier, status, startedAt, durationMs, output, tokens, destination)
        }

        if (overLimit) {
            return outcome(ToolCallStatus.Error, "Error: tool call limit reached ($MaxToolCallsPerTurn per turn). Answer with what you have.")
        }
        if (tool == null) {
            val available = tools.joinToString { it.name }.ifEmpty { "none" }
            return outcome(ToolCallStatus.Error, "Error: unknown tool '${call.name}'. Available tools: $available.")
        }
        val args = call.argumentsJson.ifBlank { "{}" }
        ToolArgs.check(tool.parametersSchema, args)?.let {
            return outcome(ToolCallStatus.Error, "Error: invalid arguments for '${tool.name}': $it. Expected: ${tool.parametersSchema}")
        }
        if (tool.asksFirst(args, trust)) {
            val approved = try {
                gate.confirm(call, tool)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return outcome(ToolCallStatus.Error, "Error: confirmation failed: ${e.message}")
            }
            if (!approved) return outcome(ToolCallStatus.Refused, GateRefusal.text(tool.name))
        }

        val ranFrom = clock()
        return try {
            val result = runDetached(tool, args, call.id)
            val took = clock() - ranFrom
            // Text other people wrote reaches the prompt, tokenized with special tokens parsed: a page or a message
            // holding `<|im_start|>system` would open a real turn (TEN-211).
            val text = result?.text?.let { if (tool.returnsUntrustedContent) MemoryContext.stripControl(it) else it }.orEmpty()
            when {
                result == null -> outcome(ToolCallStatus.Timeout, "Error: '${tool.name}' timed out after ${timeoutMs(tool) / 1000} s.", took)
                result.refused -> outcome(ToolCallStatus.Refused, text, took)
                result.isError -> outcome(ToolCallStatus.Error, text, took, result.destination)
                else -> outcome(ToolCallStatus.Ok, text, took, result.destination)
            }
        } catch (e: CancellationException) {
            // Ours (Stop) is rethrown; a cancellation the tool raised on its own is just its failure.
            if (!currentCoroutineContext().isActive) throw e
            outcome(ToolCallStatus.Error, "Error: '${tool.name}' was cancelled.", clock() - ranFrom)
        } catch (e: Exception) {
            outcome(ToolCallStatus.Error, "Error: '${tool.name}' failed: ${e.message ?: e::class.simpleName}", clock() - ranFrom)
        }
    }

    /**
     * Runs [tool] on [Dispatchers.IO] in a scope of its own, waiting at most its timeout. Not a child of
     * the turn: [kotlinx.coroutines.withContext] would wait for a tool that ignores cancellation, and the
     * loop with it. Past the timeout, or on Stop, the tool is cancelled and, if it does not notice, left
     * to finish on its own thread. `null` is the timeout. A [Tool.waitsForUser] has none: it ends with the user's answer or Stop.
     */
    private suspend fun runDetached(tool: Tool, args: String, callId: String): ToolResult? {
        val running = toolScope.async(ToolCallContext(callId)) { tool.execute(args) }
        return try {
            withTimeoutOrNull(if (tool.waitsForUser) Long.MAX_VALUE else timeoutMs(tool)) { running.await() }.also { if (it == null) running.cancel() }
        } catch (e: CancellationException) {
            running.cancel()
            throw e
        }
    }

    /**
     * Cuts [text] to [ResultTokenBudget] tokens. Counting a huge result is itself costly, so it is
     * first cut to [MaxResultChars] (the budget at ~4 chars per token). Then, when the engine can count,
     * the real figure decides and the text is cut in proportion; when it cannot, the char cut stands
     * and the token figure is the same 4 chars per token estimate.
     */
    private suspend fun clip(text: String): Pair<String, Int> {
        var body = text
        var cut = false
        if (body.length > MaxResultChars) {
            body = body.take(MaxResultChars)
            cut = true
        }
        val counted = try {
            countTokens(body)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (counted != null && counted > ResultTokenBudget) {
            body = body.take(body.length * ResultTokenBudget / counted)
            cut = true
        }
        val tokens = minOf(counted ?: ((body.length + CharsPerToken - 1) / CharsPerToken), ResultTokenBudget)
        return (if (cut) body + TruncationMark else body) to tokens
    }

    companion object {
        /** Calls per turn, then the model has to answer. */
        const val MaxToolCallsPerTurn = 5

        /** Failed calls in a row, then the model has to answer. */
        const val MaxFailedCallsInARow = 2

        /** Tokens of one tool result the model sees. */
        const val ResultTokenBudget = 512

        private const val QueueRetryPauseMs = 250L

        /** How an empty answer can end without being asked for again: stopped, failed, or no room left in the context. */
        private val NoSecondAsk: Set<StopReason?> =
            setOf(StopReason.Cancelled, StopReason.Failed, StopReason.ContextBudgetExhausted, StopReason.ContextBudgetClipped)

        private val toolScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private const val CharsPerToken = 4
        private const val MaxResultChars = ResultTokenBudget * CharsPerToken
        private const val TruncationMark = "\n[truncated]"
    }
}

/**
 * What the model reads when the [ConfirmationGate] did not approve a call: a Deny, a card left unanswered or the chat left
 * (see [UiConfirmationGate]). Plain words, not an error: a small model read "Error: the user refused this action." and
 * answered that it had done it anyway (TEN-205). [matches] lets the chat show its own line instead, for this text and for
 * [Legacy], which rows stored before still hold.
 */
internal object GateRefusal {
    private const val Prefix = "The user did not approve: '"
    private const val Legacy = "Error: the user refused this action."

    fun text(toolName: String) = "$Prefix$toolName' did not run and nothing changed. Tell the user it was not done; do not say it was."

    fun matches(output: String): Boolean = output.startsWith(Prefix) || output.trim() == Legacy
}

/** The cheap part of "arguments match the schema": a JSON object holding every `required` key. */
internal object ToolArgs {
    /** What is wrong with [argsJson] for [schema], `null` when nothing is. */
    fun check(schema: String, argsJson: String): String? {
        val args = try {
            JSONObject(argsJson)
        } catch (e: JSONException) {
            return "not a JSON object (${e.message?.lineSequence()?.first()})"
        }
        val required = try {
            JSONObject(schema).optJSONArray("required")
        } catch (e: JSONException) {
            null
        } ?: return null
        val missing = (0 until required.length()).map { required.getString(it) }.filterNot { args.has(it) }
        return if (missing.isEmpty()) null else "missing required: ${missing.joinToString()}"
    }
}
