package io.github.hokanosekai.tensai.agent

import io.github.hokanosekai.tensai.engine.ChatMessage
import io.github.hokanosekai.tensai.engine.DecodeEvent
import io.github.hokanosekai.tensai.engine.DecodeRequest
import io.github.hokanosekai.tensai.engine.DecodeStats
import io.github.hokanosekai.tensai.engine.MessageRole
import io.github.hokanosekai.tensai.engine.ToolCall
import io.github.hokanosekai.tensai.engine.ToolChoice
import io.github.hokanosekai.tensai.engine.ToolDefinition
import io.github.hokanosekai.tensai.runtime.QueueBusyException
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

/** Starts one generation. [io.github.hokanosekai.tensai.runtime.InferenceQueue.generate] in the app, a script in tests. */
fun interface ReplyGenerator {
    fun generate(request: DecodeRequest): Flow<DecodeEvent>
}

/**
 * Asked before a call that [Tool.requiresConfirmation] runs. The chat's "tensai wants to ..." card is
 * [UiConfirmationGate] (TEN-174); the wait is not counted against the tool's timeout.
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
 * [io.github.hokanosekai.tensai.runtime.InferenceQueue] (through [ReplyGenerator]) and knows nothing
 * of the UI or the database, so the chat and the future API server share it.
 *
 * Guards:
 * - at most [MaxToolCallsPerTurn] calls per turn. Past it the model gets one more generation with
 *   [ToolChoice.None] (tools stay in the prompt, so the KV prefix is reused) and any call it still
 *   makes is dropped;
 * - a tool runs at most its tier's [ToolTier.timeoutMs] (none for a [Tool.waitsForUser]), a result at most [ResultTokenBudget] tokens;
 * - cancelling the collector (Stop) cancels the running tool and ends the loop. The calls that did
 *   not finish get no event: whoever records the turn closes them with [ToolCallOutcome.stopped] (a
 *   flow cannot emit once its collector is cancelled), so every call of a reply has its result.
 *
 * Nothing a tool or the model does throws out of [run] except cancellation: unknown tool, bad
 * arguments, an exception, a timeout and a refusal all come back to the model as an error result.
 *
 * Between two generations the queue is free: a foreign generation (a warm-up aside, which is cancelled)
 * may take it, and the next generation is then rejected as busy. [waitForQueue] gets one bounded wait
 * per generation, then the turn ends with a [QueueBusyException] error. For the same reason the
 * notification's Stop, which only cancels the queue's active generation, does nothing while a tool
 * runs; the in-app Stop cancels the whole turn.
 *
 * A tool runs on [Dispatchers.IO], detached from the turn: one that ignores cancellation is abandoned
 * at its timeout (reported [ToolCallStatus.Timeout]) and the loop moves on, see [Tool.execute].
 *
 * @param waitForQueue suspends until the queue can take a generation again, `false` when it did not
 *   within a bound. The default only pauses, for a generator that has no queue.
 * @param countTokens tokens of a text under the loaded model's tokenizer, `null` when it cannot be
 *   counted (no model, a generation holding the engine).
 * @param timeoutMs how long a call of [Tool] may run, a parameter for tests.
 */
class AgentRuntime(
    private val registry: ToolRegistry,
    private val gate: ConfirmationGate = ConfirmationGate.AutoApprove,
    private val waitForQueue: suspend () -> Boolean = { delay(QueueRetryPauseMs); true },
    private val countTokens: suspend (String) -> Int? = { null },
    private val timeoutMs: (Tool) -> Long = { it.tier.timeoutMs },
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** The tier of the registered tool called [toolName], granted or not, `null` when there is none of that name. */
    fun tierOf(toolName: String): ToolTier? = registry.find(toolName)?.tier

    /**
     * Whether a call of [toolName] with [argsJson] goes through the [ConfirmationGate], read against every
     * registered tool: a stored call is shown with or without its card whatever the grants are now.
     */
    fun requiresConfirmation(toolName: String, argsJson: String): Boolean =
        registry.find(toolName)?.let { runCatching { it.requiresConfirmation(argsJson.ifBlank { "{}" }) }.getOrDefault(false) } ?: false

    /** The active tools as the prompt describes them. */
    fun definitions(): List<ToolDefinition> =
        registry.active().map { ToolDefinition(it.name, it.description, it.parametersSchema) }

    /** Whether any tool is active right now: with none, a turn is just a plain generation. */
    fun hasTools(): Boolean = registry.active().isNotEmpty()

    /**
     * Runs a turn on [request] (the history, ending on the user's message). [request]'s own tools are
     * replaced by the registry's active ones, read once here: a grant changed mid-turn applies to the next.
     * Cold, runs when collected.
     */
    fun run(request: DecodeRequest, generator: ReplyGenerator): Flow<AgentEvent> = flow {
        emit(AgentEvent.TurnStarted)
        val tools = registry.active()
        val definitions = definitions()
        val turnId = UUID.randomUUID().toString().take(8)
        val usedIds = HashSet<String>()
        val history = request.messages.toMutableList()
        var callsMade = 0
        var index = 0
        while (true) {
            val forced = callsMade >= MaxToolCallsPerTurn
            emit(AgentEvent.ReplyStarted(index))
            val generation = request.copy(
                messages = history.toList(),
                tools = definitions,
                toolChoice = if (forced) ToolChoice.None else ToolChoice.Auto,
            )
            var reply = generateOnce(generator, generation)
            // Rejected before any token, so asking again is safe: once, after the queue frees.
            if (reply.error is QueueBusyException && waitForQueue()) reply = generateOnce(generator, generation)
            reply.error?.let {
                emit(AgentEvent.Error(if (it is QueueBusyException) QueueBusyException("The model was busy with another task, the turn stopped") else it))
                return@flow
            }
            val turn = reply.turn
            val calls = if (forced || turn == null) emptyList() else uniqueIds(turn.toolCalls, turnId, index, usedIds)
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
                ),
            )
            if (calls.isEmpty()) {
                emit(AgentEvent.Done)
                return@flow
            }
            history += ChatMessage(MessageRole.Assistant, turn!!.content, toolCalls = calls.map { it.replayable() }, reasoning = turn!!.reasoning)
            for ((i, call) in calls.withIndex()) {
                val outcome = executeCall(call, tools, overLimit = callsMade + i >= MaxToolCallsPerTurn)
                emit(AgentEvent.ToolCallFinished(outcome))
                history += ChatMessage(MessageRole.Tool, outcome.output, toolCallId = call.id)
            }
            callsMade += calls.size
            index++
        }
    }

    private class Reply(val stats: DecodeStats?, val turn: DecodeEvent.ToolTurn?, val error: Throwable?)

    /** Runs one generation, forwarding its text and keeping the terminal facts. */
    private suspend fun FlowCollector<AgentEvent>.generateOnce(generator: ReplyGenerator, request: DecodeRequest): Reply {
        var stats: DecodeStats? = null
        var turn: DecodeEvent.ToolTurn? = null
        var error: Throwable? = null
        generator.generate(request).collect { event ->
            when (event) {
                is DecodeEvent.Token -> emit(AgentEvent.Token(event.text))
                is DecodeEvent.Parsed -> emit(AgentEvent.Parsed(event.content, event.reasoning, event.toolCalls))
                is DecodeEvent.ToolTurn -> turn = event
                is DecodeEvent.Done -> stats = event.stats
                is DecodeEvent.Error -> error = event.cause
            }
        }
        return Reply(stats, turn, error)
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
        if (tool.requiresConfirmation(args)) {
            val approved = try {
                gate.confirm(call, tool)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return outcome(ToolCallStatus.Error, "Error: confirmation failed: ${e.message}")
            }
            if (!approved) return outcome(ToolCallStatus.Refused, "Error: the user refused this action.")
        }

        val ranFrom = clock()
        return try {
            val result = runDetached(tool, args, call.id)
            val took = clock() - ranFrom
            when {
                result == null -> outcome(ToolCallStatus.Timeout, "Error: '${tool.name}' timed out after ${timeoutMs(tool) / 1000} s.", took)
                result.refused -> outcome(ToolCallStatus.Refused, result.text, took)
                result.isError -> outcome(ToolCallStatus.Error, result.text, took, result.destination)
                else -> outcome(ToolCallStatus.Ok, result.text, took, result.destination)
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

        /** Tokens of one tool result the model sees. */
        const val ResultTokenBudget = 512

        private const val QueueRetryPauseMs = 250L

        private val toolScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private const val CharsPerToken = 4
        private const val MaxResultChars = ResultTokenBudget * CharsPerToken
        private const val TruncationMark = "\n[truncated]"
    }
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
