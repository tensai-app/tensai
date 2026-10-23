package io.github.tensaiapp.bench

import io.github.tensaiapp.agent.AgentEvent
import io.github.tensaiapp.agent.AgentRuntime
import io.github.tensaiapp.agent.ConfirmationGate
import io.github.tensaiapp.agent.ReplyGenerator
import io.github.tensaiapp.agent.Tool
import io.github.tensaiapp.agent.ConversationTrust
import io.github.tensaiapp.data.session.SaveResult
import io.github.tensaiapp.engine.ChatMessage
import io.github.tensaiapp.engine.DecodeRequest
import io.github.tensaiapp.engine.MessageRole
import io.github.tensaiapp.engine.SamplingConfig
import io.github.tensaiapp.engine.ToolCall
import io.github.tensaiapp.runtime.QueueBusyException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** What `am start ... --es bench agent [--ez thinking false]` asks for. */
data class BenchTrigger(val thinking: Boolean)

/**
 * The bench a launch intent asks for, `null` for none: only `bench=agent`, and only in a debug build ([debug]), so a
 * release build ignores the extra entirely. [thinking] absent is [defaultThinking], the app's own for a new chat.
 */
fun benchTrigger(debug: Boolean, bench: String?, thinking: Boolean?, defaultThinking: Boolean): BenchTrigger? {
    if (!debug || bench != "agent") return null
    return BenchTrigger(thinking ?: defaultThinking)
}

/**
 * One prompt of the bench list.
 *
 * @param expected the tools any of which is a correct call, empty when the right answer calls none.
 */
data class BenchPrompt(val id: String, val prompt: String, val expected: Set<String>)

/**
 * Reads the bench list: `{"version": 1, "prompts": [{"id", "prompt", "expected"}]}`, `expected` being `"none"`, a tool
 * name or an array of tool names. Throws on a malformed file: a bench run on half a list would compare nothing.
 */
fun parseBenchPrompts(json: String): List<BenchPrompt> {
    val prompts = Json.parseToJsonElement(json).jsonObject.getValue("prompts").jsonArray.map { element ->
        val entry = element.jsonObject
        val expected = when (val value = entry.getValue("expected")) {
            is JsonArray -> value.map { it.jsonPrimitive.content }.toSet()
            else -> value.jsonPrimitive.content.let { if (it == NoTool) emptySet() else setOf(it) }
        }
        require(NoTool !in expected) { "'$NoTool' cannot be one of several expected tools" }
        BenchPrompt(entry.getValue("id").jsonPrimitive.content, entry.getValue("prompt").jsonPrimitive.content, expected)
    }
    val duplicate = prompts.groupBy { it.id }.filterValues { it.size > 1 }.keys
    require(duplicate.isEmpty()) { "duplicate prompt ids: $duplicate" }
    return prompts
}

private const val NoTool = "none"

/** How a prompt's tool calls compare with what it expected. Logged by [storageName]. */
enum class BenchVerdict {
    /** No call when none was expected, or a call to one of the expected tools. */
    Correct,

    /** A call when none was expected. */
    FalseCall,

    /** No call when one was expected. */
    Missing,

    /** Calls, none of them to an expected tool. */
    WrongTool,

    /** None of the expected tools was in the prompt (its tier is not granted): nothing to say about the model. */
    Unavailable,

    /** The turn failed (the engine, a busy queue) before it could be judged. */
    Error,
    ;

    val storageName: String get() = name.replace(Regex("(?<=[a-z])(?=[A-Z])"), "_").lowercase()

    companion object {
        /**
         * The verdict of a turn that called [called], in order, for a prompt expecting [expected], the prompt
         * offering the tools named [available].
         */
        fun of(expected: Set<String>, called: List<String>, available: Set<String>, failed: Boolean = false): BenchVerdict = when {
            failed -> Error
            expected.isNotEmpty() && expected.none { it in available } -> Unavailable
            expected.isEmpty() -> if (called.isEmpty()) Correct else FalseCall
            called.isEmpty() -> Missing
            called.any { it in expected } -> Correct
            else -> WrongTool
        }
    }
}

/**
 * The calls the bench lets run: the ones with no effect outside the reply, the user's data read and left as it was.
 * `memory` only for `list`. Anything else (network, a phone action, a permission, `ask_user`, a write) is refused by
 * [RefusingGate].
 */
private val RunnableTools = setOf("datetime", "calculate", "convert", "convert_time", "device_info")

internal fun benchRuns(name: String, argsJson: String): Boolean =
    name in RunnableTools ||
        (name == "memory" && runCatching { Json.parseToJsonElement(argsJson).jsonObject["action"]?.jsonPrimitive?.content == "list" }.getOrDefault(false))

/**
 * [inner] as the model sees it (name, description, schema, tier: the prompt is the app's, byte for byte), except that
 * every call [benchRuns] does not allow goes through the [ConfirmationGate], where [RefusingGate] says no.
 */
class BenchTool(private val inner: Tool) : Tool by inner {
    override fun requiresConfirmation(argsJson: String, trust: ConversationTrust): Boolean = !benchRuns(inner.name, argsJson) || inner.requiresConfirmation(argsJson, trust)
}

/** Refuses every call it is asked about and keeps it: the bench's record of what the model tried and was not run. */
class RefusingGate : ConfirmationGate {
    private val refused = mutableListOf<ToolCall>()

    override suspend fun confirm(call: ToolCall, tool: Tool): Boolean {
        synchronized(refused) { refused += call }
        return false
    }

    /** The calls refused since the last drain. */
    fun drain(): List<ToolCall> = synchronized(refused) { refused.toList().also { refused.clear() } }
}

/**
 * One prompt's run.
 *
 * @param calls every call the turn's replies made, in order, run or refused.
 * @param refused the names of the calls [RefusingGate] stopped.
 * @param tokens llama.cpp's count over every reply of the turn, reasoning included.
 * @param reasoningTokens the reasoning the engine split out (think tags excluded), tokenized again after the turn,
 *   `null` when it could not be counted. The logged `answer_tokens` is [tokens] minus this, tool-call tokens included.
 * @param ttftMs the first reply's time to first token as the engine reports it (what the chat shows), `null` when
 *   it reported none. [totalMs] is wall-clock, the whole turn.
 * @param stopReason the last reply's [io.github.tensaiapp.engine.StopReason] name, `null` when it reported none.
 * @param prefixCold the tool prefix was not warmed before this prompt: it paid the prefill, left out of the medians.
 * @param stopsRun the queue refused the turn (busy, or the model gone): the prompts after it would only fail the same way.
 */
data class BenchResult(
    val prompt: BenchPrompt,
    val calls: List<ToolCall>,
    val refused: List<String>,
    val verdict: BenchVerdict,
    val tokens: Int,
    val reasoningTokens: Int?,
    val ttftMs: Long?,
    val totalMs: Long,
    val stopReason: String?,
    val error: String? = null,
    val prefixCold: Boolean = false,
    val stopsRun: Boolean = false,
) {
    /** One JSON line for logcat, arguments cut to [MaxArgsChars]. */
    fun toLogLine(): String = buildJsonObject {
        put("event", "result")
        put("id", prompt.id)
        put("expected", prompt.expected.ifEmpty { setOf(NoTool) }.joinToString("|"))
        put(
            "calls",
            buildJsonArray {
                calls.forEach { call ->
                    add(buildJsonObject {
                        put("name", call.name)
                        put("args", call.argumentsJson.let { if (it.length > MaxArgsChars) it.take(MaxArgsChars) + "..." else it })
                    })
                }
            },
        )
        put("refused", JsonArray(refused.map { JsonPrimitive(it) }))
        put("verdict", verdict.storageName)
        put("tokens", tokens)
        put("reasoning_tokens", reasoningTokens)
        put("answer_tokens", reasoningTokens?.let { (tokens - it).coerceAtLeast(0) })
        put("ttft_ms", ttftMs)
        put("total_ms", totalMs)
        put("stop", stopReason)
        if (prefixCold) put("prefix_cold", true)
        error?.let { put("error", it) }
    }.toString()

    companion object {
        const val MaxArgsChars = 120
    }
}

/**
 * The bench's totals. Each rate is over the prompts it can happen to, errors and unavailable tools left out: false
 * calls over the prompts expecting no tool, missing calls over the ones expecting one. `null` when there is no such
 * prompt. The medians leave out errors and a [BenchResult.prefixCold] prompt.
 */
data class BenchSummary(
    val counts: Map<BenchVerdict, Int>,
    val falseCallRate: Double?,
    val missingRate: Double?,
    val medianTtftMs: Long?,
    val medianTotalMs: Long?,
) {
    fun toLogLine(thinking: Boolean): String = buildJsonObject {
        put("event", "summary")
        put("thinking", thinking)
        put("prompts", counts.values.sum())
        BenchVerdict.entries.forEach { put(it.storageName, counts[it] ?: 0) }
        put("false_call_rate", falseCallRate)
        put("missing_rate", missingRate)
        put("median_ttft_ms", medianTtftMs)
        put("median_total_ms", medianTotalMs)
    }.toString()

    companion object {
        fun of(results: List<BenchResult>): BenchSummary {
            val judged = results.filter { it.verdict != BenchVerdict.Error && it.verdict != BenchVerdict.Unavailable }
            val noTool = judged.filter { it.prompt.expected.isEmpty() }
            val withTool = judged.filter { it.prompt.expected.isNotEmpty() }
            val timed = results.filter { it.verdict != BenchVerdict.Error && !it.prefixCold }
            fun rate(part: Int, of: Int): Double? = if (of == 0) null else Math.round(part * 1000.0 / of) / 1000.0
            return BenchSummary(
                counts = BenchVerdict.entries.associateWith { v -> results.count { it.verdict == v } },
                falseCallRate = rate(noTool.count { it.verdict == BenchVerdict.FalseCall }, noTool.size),
                missingRate = rate(withTool.count { it.verdict == BenchVerdict.Missing }, withTool.size),
                medianTtftMs = median(timed.mapNotNull { it.ttftMs }),
                medianTotalMs = median(timed.map { it.totalMs }),
            )
        }

        /** The middle value, the mean of the two middle ones for an even count, `null` for none. */
        internal fun median(values: List<Long>): Long? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            val mid = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
        }
    }
}

/**
 * Runs bench prompts through [runtime], the app's agent loop, on [generator]. Each prompt is a conversation of one
 * user message on [sampling], nothing stored: the request a first send of a new chat builds, minus the saved facts
 * (so a run does not depend on what the user's memory holds).
 *
 * @param countTokens tokens of a text under the loaded model's tokenizer, `null` when it cannot be counted.
 */
class BenchRunner(
    private val runtime: AgentRuntime,
    private val gate: RefusingGate,
    private val generator: ReplyGenerator,
    private val sampling: SamplingConfig,
    private val countTokens: suspend (String) -> Int?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The request the tool prefix is warmed from: no history, the tools a turn gets. */
    fun prefixRequest(): DecodeRequest = DecodeRequest(emptyList(), sampling, tools = runtime.definitions())

    /** The names of the tools a prompt offers right now, read as [AgentRuntime.run] reads them. */
    fun availableTools(): Set<String> = runtime.definitions().mapTo(LinkedHashSet()) { it.name }

    /** Runs [prompt] in a fresh one-message conversation, [prefixCold] when the tool prefix was not warmed before it. */
    suspend fun run(prompt: BenchPrompt, prefixCold: Boolean = false): BenchResult {
        gate.drain()
        val request = DecodeRequest(listOf(ChatMessage(MessageRole.User, prompt.prompt)), sampling)
        val available = availableTools()
        val startedAt = clock()
        var ttftMs: Long? = null
        var replies = 0
        var reasoning: String? = null
        val reasonings = mutableListOf<String>()
        val calls = mutableListOf<ToolCall>()
        var tokens = 0
        var stopReason: String? = null
        var error: Throwable? = null
        runtime.run(request, generator).collect { event ->
            when (event) {
                is AgentEvent.ReplyStarted -> reasoning = null
                is AgentEvent.Parsed -> reasoning = event.reasoning
                is AgentEvent.ReplyFinished -> {
                    if (replies++ == 0) ttftMs = event.stats?.ttftMs
                    calls += event.calls
                    reasoning?.takeIf { it.isNotBlank() }?.let { reasonings += it }
                    tokens += event.stats?.tokensGenerated ?: 0
                    stopReason = event.stats?.stopReason?.name
                }
                is AgentEvent.Error -> error = event.cause
                else -> Unit
            }
        }
        val totalMs = clock() - startedAt
        var reasoningTokens: Int? = 0
        for (text in reasonings) reasoningTokens = reasoningTokens?.let { sum -> countTokens(text)?.let { sum + it } }
        return BenchResult(
            prompt = prompt,
            calls = calls,
            refused = gate.drain().map { it.name },
            verdict = BenchVerdict.of(prompt.expected, calls.map { it.name }, available, failed = error != null),
            tokens = tokens,
            reasoningTokens = reasoningTokens,
            ttftMs = ttftMs,
            totalMs = totalMs,
            stopReason = stopReason,
            error = error?.let { it.message ?: it::class.simpleName },
            prefixCold = prefixCold,
            stopsRun = error is QueueBusyException,
        )
    }
}

/**
 * Hands the KV cache over to the bench: the conversation [owner] names (`(id, key)`, as
 * [io.github.tensaiapp.runtime.ActiveSessionTracker] holds it) has its session saved with [save], then dropped with
 * [forget], so no later save writes the bench's tokens as its session. `false` when the save did not put its
 * state on disk: the bench must not touch the cache then.
 */
suspend fun <K> handOverCache(owner: Pair<String, K>?, save: suspend (String, K) -> SaveResult, forget: (String) -> Unit): Boolean {
    val (conversationId, key) = owner ?: return true
    when (save(conversationId, key)) {
        is SaveResult.Saved, is SaveResult.Unchanged, SaveResult.NothingToSave -> Unit
        is SaveResult.Failed, SaveResult.Discarded, SaveResult.NotAttempted -> return false
    }
    forget(conversationId)
    return true
}

/**
 * Runs [prompts] in order on [runner] and returns their results, logging each line with [log]. Before each prompt,
 * [beforeEach] gets the cache back from a chat that took it in between (see [handOverCache]) and answers why the run
 * must stop, `null` to go on. A turn the queue refused ([BenchResult.stopsRun]) stops it too. Either way an `error`
 * line says why, and the results so far are returned. [firstCold]: the tool prefix was not warmed for the first prompt.
 */
suspend fun runBench(
    prompts: List<BenchPrompt>,
    runner: BenchRunner,
    firstCold: Boolean,
    beforeEach: suspend () -> String?,
    log: (String) -> Unit,
): List<BenchResult> {
    val results = mutableListOf<BenchResult>()
    for ((i, prompt) in prompts.withIndex()) {
        beforeEach()?.let { why ->
            log(benchErrorLine("$why, run stopped before '${prompt.id}'"))
            return results
        }
        val result = runner.run(prompt, prefixCold = firstCold && i == 0)
        results += result
        log(result.toLogLine())
        if (result.stopsRun) {
            log(benchErrorLine("the queue refused '${prompt.id}' (${result.error}), run stopped"))
            return results
        }
    }
    return results
}

/** An `error` line: why the bench does not run, or stopped. */
fun benchErrorLine(message: String): String = buildJsonObject {
    put("event", "error")
    put("message", message)
}.toString()
