package io.github.tensaiapp.bench

import io.github.tensaiapp.agent.AgentEvent
import io.github.tensaiapp.agent.AgentRuntime
import io.github.tensaiapp.agent.ConfirmationGate
import io.github.tensaiapp.agent.ReplyGenerator
import io.github.tensaiapp.agent.SystemPrompt
import io.github.tensaiapp.agent.Tool
import io.github.tensaiapp.agent.ToolCallStatus
import io.github.tensaiapp.agent.ToolPolicy
import io.github.tensaiapp.agent.ConversationTrust
import io.github.tensaiapp.data.session.SaveResult
import io.github.tensaiapp.engine.ChatMessage
import io.github.tensaiapp.engine.DecodeRequest
import io.github.tensaiapp.engine.MessageRole
import io.github.tensaiapp.engine.ReasoningBudget
import io.github.tensaiapp.engine.SamplingConfig
import io.github.tensaiapp.engine.StopReason
import io.github.tensaiapp.engine.ToolCall
import io.github.tensaiapp.runtime.QueueBusyException
import java.math.BigDecimal
import java.text.Normalizer
import java.time.Clock
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * What a run asks for, from `am start ... --es bench agent [--ez thinking false] [--ez base_prompt false]
 * [--ez examples true] [--ez tool_grammar false] [--ez greedy true] [--ei reasoning_budget 256 | --ez adaptive_reasoning true]
 * [--es tool_policy current|always|always-strict]`, the debug panel of Settings > This device or Settings > Bench.
 *
 * @param basePrompt the harness's base prompt opens the system message, as in every chat.
 * @param examples its few-shot examples follow it (debug builds only, see [io.github.tensaiapp.agent.BasePrompt]). They
 *   are part of the base prompt, so never on without it.
 * @param toolGrammar a call the model opens is held to the tools' schemas, as in every chat
 *   ([io.github.tensaiapp.engine.SamplingConfig.constrainToolCalls]). Off to measure what it changes.
 * @param repetitions each prompt runs this many times in a row, 1 to [MaxRepetitions], so a verdict is a pass rate
 *   rather than one draw of the sampler. Every repetition samples as a chat does, unseeded: the engine takes no seed.
 * @param greedy temperature 0 for the whole run, so a single run is as close to reproducible as the engine allows. Off
 *   is the chat's own sampling, what the bench always measured.
 * @param reasoningBudget tokens of reasoning before the close tag is forced, the chat's own cap of a [ReasoningBudget]
 *   lowered, so the answer keeps the rest of the chat's total. `null` is the chat's split ([ReasoningBudget.splitting]).
 *   Only matters when the chat's sampling has a budget at all: thinking on, on a model that thinks.
 * @param adaptiveReasoning each prompt gets the budget a chat gives the same message
 *   ([io.github.tensaiapp.engine.ReasoningBudget.forMessage], see [BenchRunner]'s `samplingFor`), so the run measures the
 *   chat's rule. Never with a fixed [reasoningBudget].
 * @param toolPolicy which turns get the tools and whether the base prompt adds its stricter line, see [ToolPolicy].
 *   [ToolPolicy.Current] is the chat's. Its stricter line is part of the base prompt, so [ToolPolicy.AlwaysStrict] never
 *   runs without it.
 */
data class BenchTrigger(
    val thinking: Boolean,
    val basePrompt: Boolean = true,
    val examples: Boolean = false,
    val toolGrammar: Boolean = true,
    val repetitions: Int = 1,
    val greedy: Boolean = false,
    val reasoningBudget: Int? = null,
    val adaptiveReasoning: Boolean = false,
    val toolPolicy: ToolPolicy = ToolPolicy.Current,
) {
    init {
        require(repetitions in 1..MaxRepetitions) { "repetitions must be 1 to $MaxRepetitions, got $repetitions" }
        require(reasoningBudget == null || reasoningBudget > 0) { "reasoning budget must be positive, got $reasoningBudget" }
        require(!adaptiveReasoning || reasoningBudget == null) { "an adaptive budget takes no fixed one" }
        require(basePrompt || !toolPolicy.strictPrompt) { "the strict tool line rides in the base prompt" }
    }

    /** The system messages of the run: the base prompt as its toggles and [toolPolicy] say, no user instructions. */
    fun systemPrompt(clock: Clock = Clock.systemDefaultZone()): SystemPrompt =
        SystemPrompt(base = basePrompt, examples = examples, strict = toolPolicy.strictPrompt, clock = clock)

    /** How the run budgets reasoning, as logged and saved: "adaptive", "fixed" ([reasoningBudget]) or "default", the split. */
    val reasoningBudgetMode: String
        get() = when {
            adaptiveReasoning -> "adaptive"
            reasoningBudget != null -> "fixed"
            else -> "default"
        }

    /**
     * [chat], the sampling a new chat would use, as this run sends it: [toolGrammar], temperature 0 when [greedy], and
     * its reasoning cap set to [reasoningBudget] when it has one, the decode's total left as the chat's.
     */
    fun sampling(chat: SamplingConfig): SamplingConfig = chat.copy(
        constrainToolCalls = toolGrammar,
        temperature = if (greedy) 0f else chat.temperature,
        reasoningBudget = chat.reasoningBudget?.let { budget ->
            reasoningBudget?.let {
                require(it < chat.maxTokens) { "reasoning budget $it leaves no answer out of ${chat.maxTokens} tokens" }
                ReasoningBudget(it, chat.maxTokens - it, budget.endMarker)
            } ?: budget
        },
    )

    companion object {
        const val MaxRepetitions = 5

        /** The budgets the bench screen offers besides the chat's own. */
        val ReasoningBudgets = listOf(512, 256, 128)
    }
}

/**
 * One bench at a time, whichever it is (the agent bench, the debug decode curve) and wherever it is started from (the
 * launch intent, the panel): they share the engine and the log tag. A run enters before it launches and leaves when it ends.
 */
object BenchGate {
    private val _busy = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** Whether a run (or the decode tab's reload) holds the gate, for the screens to lock what would compete with it. */
    val busy: kotlinx.coroutines.flow.StateFlow<Boolean> = _busy

    /** `false` when another run holds the gate. */
    fun tryEnter(): Boolean = _busy.compareAndSet(false, true)

    fun leave() {
        _busy.value = false
    }
}

/** Where the last run is, for the debug panel. */
sealed interface BenchStatus {
    data object Idle : BenchStatus

    /** [done] prompts of [total], `null` until the list is read. */
    data class Running(val done: Int, val total: Int?) : BenchStatus

    /** [lines] from [BenchSummary.toDisplayLines]. [stoppedEarly]: the run stopped before the last prompt, the log says why. */
    data class Finished(val lines: List<String>, val stoppedEarly: Boolean) : BenchStatus

    /** Why the bench did not run. */
    data class Failed(val message: String) : BenchStatus
}

/**
 * The bench a launch intent asks for, `null` for none: only `bench=agent`, and only in a debug build ([debug]), so a
 * release build ignores the extra entirely. [thinking] absent is [defaultThinking], the app's own for a new chat;
 * [basePrompt] absent is on and [examples] absent off, what a chat sends. [examples] without the base prompt is off.
 * [toolGrammar] absent is on, what a chat sends. [greedy] absent is off, the chat's sampling. [reasoningBudget] absent,
 * not positive or without thinking is the chat's split. [adaptiveReasoning] needs thinking and wins over a fixed budget.
 * [toolPolicy] absent or unknown is [ToolPolicy.Current], the chat's; [ToolPolicy.AlwaysStrict] without the base prompt
 * is [ToolPolicy.Always], its line having nowhere to go.
 */
fun benchTrigger(
    debug: Boolean,
    bench: String?,
    thinking: Boolean?,
    defaultThinking: Boolean,
    basePrompt: Boolean? = null,
    examples: Boolean? = null,
    toolGrammar: Boolean? = null,
    greedy: Boolean? = null,
    reasoningBudget: Int? = null,
    adaptiveReasoning: Boolean? = null,
    toolPolicy: String? = null,
): BenchTrigger? {
    if (!debug || bench != "agent") return null
    val base = basePrompt ?: true
    val think = thinking ?: defaultThinking
    val policy = ToolPolicy.of(toolPolicy) ?: ToolPolicy.Current
    return BenchTrigger(
        think,
        base,
        base && examples == true,
        toolGrammar ?: true,
        greedy = greedy == true,
        reasoningBudget = reasoningBudget?.takeIf { think && it > 0 && adaptiveReasoning != true },
        adaptiveReasoning = think && adaptiveReasoning == true,
        toolPolicy = if (policy.strictPrompt && !base) ToolPolicy.Always else policy,
    )
}

/**
 * One prompt of the bench list.
 *
 * @param expected the tools any of which is a correct call, empty when the right answer calls none.
 * @param maxTokens each reply of the turn answers in at most this many tokens, reasoning left to the run's own cap,
 *   `null` for the run's own cap: a long-form ask is judged on its calls, not on the text, so it costs seconds instead
 *   of minutes.
 * @param answer what the final reply must hold to be right, `null` for a prompt with no answer to check.
 * @param tags the groups the prompt is counted in besides the whole list ("reasoning": an answer to work out without a
 *   tool), see [BenchSummary.tagAnswers].
 */
data class BenchPrompt(
    val id: String,
    val prompt: String,
    val expected: Set<String>,
    val maxTokens: Int? = null,
    val answer: AnswerCheck? = null,
    val tags: Set<String> = emptySet(),
)

/**
 * Reads the bench list: `{"version": 1, "prompts": [{"id", "prompt", "expected", "max_tokens"?, "answer"?, "tags"?}]}`,
 * `expected` being `"none"`, a tool name or an array of tool names, `max_tokens` a positive count, `answer`
 * `{"number": 37, "tolerance"?: 0.05}` or `{"text": "Tokyo"}`, see [AnswerCheck], `tags` an array of names. Throws on a
 * malformed file: a bench run on half a list would compare nothing.
 */
fun parseBenchPrompts(json: String): List<BenchPrompt> {
    val prompts = Json.parseToJsonElement(json).jsonObject.getValue("prompts").jsonArray.map { element ->
        val entry = element.jsonObject
        val expected = when (val value = entry.getValue("expected")) {
            is JsonArray -> value.map { it.jsonPrimitive.content }.toSet()
            else -> value.jsonPrimitive.content.let { if (it == NoTool) emptySet() else setOf(it) }
        }
        require(NoTool !in expected) { "'$NoTool' cannot be one of several expected tools" }
        val maxTokens = entry["max_tokens"]?.jsonPrimitive?.int
        require(maxTokens == null || maxTokens > 0) { "max_tokens must be positive, got $maxTokens" }
        val answer = entry["answer"]?.jsonObject?.let { check ->
            check["number"]?.let { AnswerCheck.Number(it.jsonPrimitive.double, check["tolerance"]?.jsonPrimitive?.double ?: 0.0) }
                ?: AnswerCheck.Text(check.getValue("text").jsonPrimitive.content)
        }
        val tags = entry["tags"]?.jsonArray?.mapTo(LinkedHashSet()) { it.jsonPrimitive.content }.orEmpty()
        BenchPrompt(entry.getValue("id").jsonPrimitive.content, entry.getValue("prompt").jsonPrimitive.content, expected, maxTokens, answer, tags)
    }
    val duplicate = prompts.groupBy { it.id }.filterValues { it.size > 1 }.keys
    require(duplicate.isEmpty()) { "duplicate prompt ids: $duplicate" }
    return prompts
}

private const val NoTool = "none"

/**
 * What a prompt's answer must hold to be right, checked on the text of every reply of the turn joined (the one before a
 * call and the one after it), reasoning and calls left out.
 */
sealed interface AnswerCheck {
    /** Whether [reply] holds the answer. */
    fun matches(reply: String): Boolean

    /** The expected answer as the log and the bench screen show it: "37 ± 0.05", "tokyo". */
    val shown: String

    /**
     * Any number of the reply that is [value] as written, rounded to its own decimals, or within [tolerance]: for an
     * exact 802.9674796..., "802.97" and "803" hold it, a truncated "802.9674" does not; "37 °C", "37C" and "37.0" all
     * hold 37, "56.4" does not hold 56. Digit groups by comma or thin space read as one number ("355,614,924"), so do
     * ".5" and "1.2e6"; a decimal comma does not, the replies are English.
     */
    data class Number(val value: Double, val tolerance: Double = 0.0) : AnswerCheck {
        init {
            require(tolerance >= 0) { "tolerance must not be negative, got $tolerance" }
        }

        override fun matches(reply: String): Boolean = numbersIn(reply).any { (number, halfUnit) ->
            abs(number - value) <= max(tolerance, halfUnit) + abs(value) * 1e-9 + 1e-12
        }

        override val shown: String get() = format(value) + if (tolerance > 0) " ± ${format(tolerance)}" else ""

        private fun format(x: Double): String = BigDecimal.valueOf(x).stripTrailingZeros().toPlainString()

        companion object {
            /**
             * A sign only where it cannot be a minus between two operands, else not inside a number; then digits, grouped
             * or not, with decimals, or decimals alone; then an exponent. Groups: the decimals, the exponent.
             */
            private val NumberPattern =
                Regex("""(?:(?<![\w.])[-\u2212]|(?<![\d.]))(?:(?:\d{1,3}(?:[,\u00A0\u2009\u202F]\d{3})+|\d+)(?:\.(\d+))?|\.(\d+))(?:[eE]([-+]?\d+))?""")

            /** Each number of [text] and half the unit of its last digit, how far off a rounding of it can be. */
            internal fun numbersIn(text: String): List<Pair<Double, Double>> = NumberPattern.findAll(text).mapNotNull { match ->
                val number = match.value.replace("\u2212", "-").filter { it.isDigit() || it in "-.eE+" }.toDoubleOrNull() ?: return@mapNotNull null
                val decimals = (match.groupValues[1].ifEmpty { match.groupValues[2] }).length
                val exponent = match.groupValues[3].toIntOrNull() ?: 0
                number to 0.5 * 10.0.pow(exponent - decimals)
            }.toList()
        }
    }

    /** [text] as words of the reply, case and accents aside: "Buenos días!" holds "buenos dias". */
    data class Text(val text: String) : AnswerCheck {
        override fun matches(reply: String): Boolean =
            Regex("""(?<!\w)${Regex.escape(normalize(text))}(?!\w)""").containsMatchIn(normalize(reply))

        override val shown: String get() = normalize(text)

        private fun normalize(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("""\p{Mn}+"""), "").lowercase(Locale.ROOT).replace(Regex("""\s+"""), " ").trim()
    }
}

/** How a prompt's final reply compares with its [AnswerCheck]. Logged and saved by [storageName]. */
enum class AnswerOutcome {
    Correct,
    Wrong,

    /** No check for the prompt and a reply to show, or the turn failed before it could answer. */
    Unchecked,

    /** A token cap ended the turn before its reply held the answer: the budget ran out, not the model's knowledge. */
    Cut,

    /**
     * No reply, the turn ended by the model itself: at a low budget, the forced close tag followed by end of turn, or an
     * empty answer after a tool call (TEN-204). A failure, not a cap, whether the prompt has a check or not.
     */
    Empty,
    ;

    val storageName: String get() = name.lowercase()

    companion object {
        /** The stop reasons of a decode a cap ended, by [StopReason] name. */
        private val CappedStops = setOf(
            StopReason.TokenBudget,
            StopReason.ReasoningBudgetExhausted,
            StopReason.ResponseBudgetExhausted,
            StopReason.ContextBudgetExhausted,
            StopReason.ContextBudgetClipped,
        ).mapTo(HashSet()) { it.name }

        /**
         * The outcome of [reply] (the turn's text, `null` for none) against [check], the last reply having stopped for
         * [stopReason], `null` when it reported no stats.
         */
        fun of(check: AnswerCheck?, reply: String?, stopReason: String?, failed: Boolean = false): AnswerOutcome = when {
            failed -> Unchecked
            reply.isNullOrBlank() && stopReason != null && stopReason !in CappedStops -> Empty
            check == null -> Unchecked
            !reply.isNullOrBlank() && check.matches(reply) -> Correct
            stopReason == null || stopReason in CappedStops -> Cut
            reply.isNullOrBlank() -> Empty
            else -> Wrong
        }
    }
}

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
 * @param maxReplyReasoningTokens the largest reasoning of one reply of the turn, counted the same way: the reasoning cap
 *   holds each decode, not the turn.
 * @param reasoningCap the reasoning cap the prompt's decodes got, `null` for none (thinking off): with an adaptive
 *   budget, which prompts got the split.
 * @param reasoningOverCap [maxReplyReasoningTokens] went past the run's reasoning cap by more than [ReasoningCapSlack]:
 *   nothing forced the close tag, the template does not delimit reasoning.
 * @param ttftMs the first reply's time to first token as the engine reports it (what the chat shows), `null` when
 *   it reported none. [totalMs] is wall-clock, the whole turn.
 * @param stopReason the last reply's [io.github.tensaiapp.engine.StopReason] name, `null` when it reported none.
 * @param prefixCold the tool prefix was not warmed before this prompt: it paid the prefill, left out of the medians.
 * @param stopsRun the queue refused the turn (busy, or the model gone): the prompts after it would only fail the same way.
 * @param namedInText offered tools a reply that made no call wrote out as if calling them ([toolsNamedInText]): a call
 *   the few-shot examples taught as text, which no verdict would count otherwise.
 * @param truncatedCalls offered tools a reply cut by the token cap had opened a call to: the engine drops a half-written
 *   call, the verdict counts it as made.
 * @param repetition which run of [prompt] this is, from 1, see [BenchTrigger.repetitions].
 * @param reply the text of the turn's replies, tool calls left out, `null` when none wrote any. Kept for the bench
 *   screen, never logged.
 * @param reasoning the reasoning the engine split out of the turn's replies, `null` for none. Same as [reply].
 * @param callErrors what the model was told for each of [calls] that ended in [ToolCallStatus.Error], by call id: a
 *   call the verdict counts as made, whose result gave the model nothing to answer from. Logged on its call.
 * @param answer [reply] against the prompt's [BenchPrompt.answer], whatever the [verdict]: a right call can still be
 *   answered wrong.
 * @param emptyRaw what the model wrote, think tags included, when an answer after a tool came out empty and the runtime
 *   asked again (TEN-204), `null` otherwise. Kept for the bench screen, never logged; the line logs `asked_again`.
 */
data class BenchResult(
    val prompt: BenchPrompt,
    val calls: List<ToolCall>,
    val refused: List<String>,
    val verdict: BenchVerdict,
    val tokens: Int,
    val reasoningTokens: Int?,
    val maxReplyReasoningTokens: Int? = reasoningTokens,
    val reasoningCap: Int? = null,
    val reasoningOverCap: Boolean = false,
    val ttftMs: Long?,
    val totalMs: Long,
    val stopReason: String?,
    val error: String? = null,
    val prefixCold: Boolean = false,
    val stopsRun: Boolean = false,
    val namedInText: List<String> = emptyList(),
    val truncatedCalls: List<String> = emptyList(),
    val repetition: Int = 1,
    val reply: String? = null,
    val reasoning: String? = null,
    val callErrors: Map<String, String> = emptyMap(),
    val answer: AnswerOutcome = AnswerOutcome.Unchecked,
    val emptyRaw: String? = null,
) {
    /** One JSON line for logcat, arguments cut to [MaxArgsChars]. */
    fun toLogLine(): String = buildJsonObject {
        put("event", "result")
        put("id", prompt.id)
        if (repetition > 1) put("rep", repetition)
        put("expected", prompt.expected.ifEmpty { setOf(NoTool) }.joinToString("|"))
        if (prompt.tags.isNotEmpty()) put("tags", JsonArray(prompt.tags.map { JsonPrimitive(it) }))
        put(
            "calls",
            buildJsonArray {
                calls.forEach { call ->
                    add(buildJsonObject {
                        put("name", call.name)
                        put("args", call.argumentsJson.let { if (it.length > MaxArgsChars) it.take(MaxArgsChars) + "..." else it })
                        callErrors[call.id]?.let { put("error", if (it.length > MaxArgsChars) it.take(MaxArgsChars) + "..." else it) }
                    })
                }
            },
        )
        put("refused", JsonArray(refused.map { JsonPrimitive(it) }))
        put("verdict", verdict.storageName)
        put("answer", answer.storageName)
        prompt.answer?.let { put("expected_answer", it.shown) }
        put("tokens", tokens)
        put("reasoning_tokens", reasoningTokens)
        put("max_reply_reasoning_tokens", maxReplyReasoningTokens)
        put("reasoning_cap", reasoningCap)
        if (reasoningOverCap) put("reasoning_over_cap", true)
        put("answer_tokens", reasoningTokens?.let { (tokens - it).coerceAtLeast(0) })
        put("ttft_ms", ttftMs)
        put("total_ms", totalMs)
        put("stop", stopReason)
        if (prefixCold) put("prefix_cold", true)
        if (emptyRaw != null) put("asked_again", true)
        if (namedInText.isNotEmpty()) put("named_in_text", JsonArray(namedInText.map { JsonPrimitive(it) }))
        if (truncatedCalls.isNotEmpty()) put("truncated_calls", JsonArray(truncatedCalls.map { JsonPrimitive(it) }))
        error?.let { put("error", it) }
    }.toString()

    companion object {
        const val MaxArgsChars = 120

        /** Tokens past the cap a reply's reasoning may count, the forced close tag and a tokenization that differs aside. */
        const val ReasoningCapSlack = 32
    }
}

/**
 * The bench's totals. Each rate is over the prompts it can happen to, errors and unavailable tools left out: false
 * calls over the prompts expecting no tool, missing calls over the ones expecting one. `null` when there is no such
 * prompt. The medians leave out errors and a [BenchResult.prefixCold] prompt. [namedInText]: prompts with a
 * [BenchResult.namedInText], [toolErrors]: prompts with a [BenchResult.callErrors], whatever their verdict.
 * [answers]: the [AnswerOutcome] counts. [medianReasoningTokens] and [medianMaxReplyReasoningTokens] leave out errors and
 * the prompts whose reasoning could not be counted; a cold prefix costs no reasoning, so it stays in. [reasoningOverCap]:
 * prompts with a [BenchResult.reasoningOverCap]. [tagAnswers]: per [BenchPrompt.tags] name, the answers right and the
 * answers checked of the prompts it tags.
 */
data class BenchSummary(
    val counts: Map<BenchVerdict, Int>,
    val falseCallRate: Double?,
    val missingRate: Double?,
    val medianTtftMs: Long?,
    val medianTotalMs: Long?,
    val namedInText: Int = 0,
    val toolErrors: Int = 0,
    val answers: Map<AnswerOutcome, Int> = emptyMap(),
    val medianReasoningTokens: Long? = null,
    val medianMaxReplyReasoningTokens: Long? = null,
    val reasoningOverCap: Int = 0,
    val tagAnswers: Map<String, Pair<Int, Int>> = emptyMap(),
) {
    fun toLogLine(trigger: BenchTrigger): String = buildJsonObject {
        put("event", "summary")
        put("thinking", trigger.thinking)
        put("base_prompt", trigger.basePrompt)
        put("examples", trigger.examples)
        put("tool_grammar", trigger.toolGrammar)
        if (trigger.repetitions > 1) put("repetitions", trigger.repetitions)
        if (trigger.greedy) put("greedy", true)
        put("reasoning_budget", trigger.reasoningBudget)
        put("reasoning_budget_mode", trigger.reasoningBudgetMode)
        put("tool_policy", trigger.toolPolicy.storageName)
        put("prompts", counts.values.sum())
        BenchVerdict.entries.forEach { put(it.storageName, counts[it] ?: 0) }
        put("false_call_rate", falseCallRate)
        put("missing_rate", missingRate)
        put("median_ttft_ms", medianTtftMs)
        put("median_total_ms", medianTotalMs)
        put("named_in_text", namedInText)
        put("tool_errors", toolErrors)
        put("answer_ok", answers[AnswerOutcome.Correct] ?: 0)
        put("answer_wrong", answers[AnswerOutcome.Wrong] ?: 0)
        put("answer_cut", answers[AnswerOutcome.Cut] ?: 0)
        put("answer_empty", answers[AnswerOutcome.Empty] ?: 0)
        put("median_reasoning_tokens", medianReasoningTokens)
        put("median_max_reply_reasoning_tokens", medianMaxReplyReasoningTokens)
        put("reasoning_over_cap", reasoningOverCap)
        tagAnswers.forEach { (tag, okOfChecked) ->
            put("${tag}_ok", okOfChecked.first)
            put("${tag}_checked", okOfChecked.second)
        }
    }.toString()

    /**
     * The summary as lines for the debug panel, to be read off a screenshot: the run's settings, the counts, the
     * rates and medians, then the ids of the prompts that went wrong, by verdict.
     */
    fun toDisplayLines(trigger: BenchTrigger, model: String, results: List<BenchResult>): List<String> = buildList {
        fun onOff(on: Boolean) = if (on) "on" else "off"
        add(model)
        add(
            "thinking ${onOff(trigger.thinking)} · base prompt ${onOff(trigger.basePrompt)} · examples ${onOff(trigger.examples)}" +
                " · tool grammar ${onOff(trigger.toolGrammar)}" + (if (trigger.greedy) " · greedy" else "") +
                (if (trigger.toolPolicy != ToolPolicy.Current) " · tools ${trigger.toolPolicy.storageName}" else "") +
                (if (trigger.repetitions > 1) " · repetitions ${trigger.repetitions}" else "") +
                when {
                    trigger.adaptiveReasoning -> " · reasoning adaptive"
                    trigger.reasoningBudget != null -> " · reasoning ${trigger.reasoningBudget}"
                    else -> ""
                },
        )
        add("prompts ${counts.values.sum()}: " + BenchVerdict.entries.joinToString(" ") { "${it.storageName} ${counts[it] ?: 0}" })
        add("false_call_rate ${falseCallRate ?: "-"} · missing_rate ${missingRate ?: "-"}")
        add("median ttft ${medianTtftMs ?: "-"} ms · median total ${medianTotalMs ?: "-"} ms")
        add("median reasoning ${medianReasoningTokens ?: "-"} tokens · one reply ${medianMaxReplyReasoningTokens ?: "-"}")
        add(
            "answers ok ${answers[AnswerOutcome.Correct] ?: 0} · wrong ${answers[AnswerOutcome.Wrong] ?: 0} · cut ${answers[AnswerOutcome.Cut] ?: 0}" +
                " · empty ${answers[AnswerOutcome.Empty] ?: 0}",
        )
        tagAnswers.forEach { (tag, okOfChecked) -> add("${tag}_ok ${okOfChecked.first}/${okOfChecked.second}") }
        if (reasoningOverCap > 0) add("reasoning_over_cap $reasoningOverCap: the cap was not enforced")
        results.filter { it.verdict != BenchVerdict.Correct }.groupBy { it.verdict }.forEach { (verdict, wrong) ->
            add("${verdict.storageName}: " + wrong.map { it.prompt.id }.distinct().joinToString(", "))
        }
        results.filter { it.namedInText.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { named ->
            add("named_in_text $namedInText: " + named.joinToString(", ") { "${it.prompt.id} (${it.namedInText.joinToString("|")})" })
        }
        results.filter { it.callErrors.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { failed ->
            add("tool_errors $toolErrors: " + failed.joinToString(", ") { r -> "${r.prompt.id} (${r.calls.filter { it.id in r.callErrors }.joinToString("|") { it.name }})" })
        }
        listOf(AnswerOutcome.Wrong, AnswerOutcome.Cut, AnswerOutcome.Empty).forEach { outcome ->
            results.filter { it.answer == outcome }.takeIf { it.isNotEmpty() }?.let { off ->
                add("answer_${outcome.storageName}: " + off.map { it.prompt.id }.distinct().joinToString(", "))
            }
        }
    }

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
                namedInText = results.count { it.namedInText.isNotEmpty() },
                toolErrors = results.count { it.callErrors.isNotEmpty() },
                answers = AnswerOutcome.entries.associateWith { a -> results.count { it.answer == a } },
                medianReasoningTokens = median(results.filter { it.verdict != BenchVerdict.Error }.mapNotNull { it.reasoningTokens?.toLong() }),
                medianMaxReplyReasoningTokens = median(results.filter { it.verdict != BenchVerdict.Error }.mapNotNull { it.maxReplyReasoningTokens?.toLong() }),
                reasoningOverCap = results.count { it.reasoningOverCap },
                tagAnswers = results.flatMap { it.prompt.tags }.distinct().sorted().associateWith { tag ->
                    val checked = results.filter { tag in it.prompt.tags && it.answer != AnswerOutcome.Unchecked }
                    checked.count { it.answer == AnswerOutcome.Correct } to checked.size
                },
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
 * and the user's instructions (so a run does not depend on what the user set).
 *
 * @param system the system message of a request offering the tools it is given (by name), handed to [AgentRuntime.run]
 *   as a chat does: a prompt the runtime runs with no tools gets the no-tools text. `null` for none.
 * @param countTokens tokens of a text under the loaded model's tokenizer, `null` when it cannot be counted.
 * @param samplingFor the sampling of a prompt by its text, in place of [sampling] (which still warms the prefix): the
 *   chat's per-message budget for [BenchTrigger.adaptiveReasoning]. `null`: every prompt on [sampling].
 */
class BenchRunner(
    private val runtime: AgentRuntime,
    private val gate: RefusingGate,
    private val generator: ReplyGenerator,
    private val sampling: SamplingConfig,
    private val countTokens: suspend (String) -> Int?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val system: ((Set<String>) -> String?)? = null,
    private val samplingFor: ((String) -> SamplingConfig)? = null,
) {
    /** The request the tool prefix is warmed from: the system message of the tools a turn gets, no history, those tools. */
    fun prefixRequest(): DecodeRequest = DecodeRequest(
        listOfNotNull(system?.invoke(availableTools())?.let { ChatMessage(MessageRole.System, it) }),
        sampling,
        tools = runtime.definitions(),
    )

    /** The names of the tools a prompt offers right now, read as [AgentRuntime.run] reads them. */
    fun availableTools(): Set<String> = runtime.definitions().mapTo(LinkedHashSet()) { it.name }

    /**
     * Runs [prompt] in a fresh one-message conversation, [prefixCold] when the tool prefix was not warmed before it.
     * [repetition] only labels the result.
     */
    suspend fun run(prompt: BenchPrompt, prefixCold: Boolean = false, repetition: Int = 1): BenchResult {
        gate.drain()
        val own = samplingFor?.invoke(prompt.prompt) ?: sampling
        val request = DecodeRequest(listOf(ChatMessage(MessageRole.User, prompt.prompt)), prompt.maxTokens?.let { capped(own, it) } ?: own)
        val available = availableTools()
        val startedAt = clock()
        var ttftMs: Long? = null
        var replies = 0
        var reasoning: String? = null
        var emptyRaw: String? = null
        val reasonings = mutableListOf<String>()
        val replyTexts = mutableListOf<String>()
        val calls = mutableListOf<ToolCall>()
        val named = LinkedHashSet<String>()
        var opened = emptyList<String>()
        val truncatedCalls = mutableListOf<String>()
        val callErrors = LinkedHashMap<String, String>()
        var tokens = 0
        var stopReason: String? = null
        var error: Throwable? = null
        runtime.run(request, generator, system).collect { event ->
            when (event) {
                is AgentEvent.ReplyStarted -> {
                    reasoning = null
                    opened = emptyList()
                }
                is AgentEvent.Parsed -> {
                    reasoning = event.reasoning
                    opened = event.toolCalls
                }
                is AgentEvent.ReplyFinished -> {
                    if (replies++ == 0) ttftMs = event.stats?.ttftMs
                    if (event.retrying) emptyRaw = event.raw
                    calls += event.calls
                    // The last name may be cut short: only a tool the prompt offered counts.
                    if (event.truncated && event.calls.isEmpty()) truncatedCalls += opened.filter { it in available }
                    if (event.calls.isEmpty()) event.content?.let { named += toolsNamedInText(it, available) }
                    reasoning?.takeIf { it.isNotBlank() }?.let { reasonings += it }
                    event.content?.takeIf { it.isNotBlank() }?.let { replyTexts += it }
                    tokens += event.stats?.tokensGenerated ?: 0
                    stopReason = event.stats?.stopReason?.name
                }
                is AgentEvent.ToolCallFinished -> if (event.outcome.status == ToolCallStatus.Error) callErrors[event.outcome.call.id] = event.outcome.output
                is AgentEvent.Error -> error = event.cause
                else -> Unit
            }
        }
        val totalMs = clock() - startedAt
        val reply = replyTexts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
        val counts = reasonings.map { countTokens(it) }
        val reasoningTokens = if (null in counts) null else counts.sumOf { it!! }
        val maxReplyReasoningTokens = if (null in counts) null else counts.maxOfOrNull { it!! } ?: 0
        val cap = request.sampling.reasoningBudget?.reasoningMaxTokens
        return BenchResult(
            prompt = prompt,
            calls = calls,
            refused = gate.drain().map { it.name },
            verdict = BenchVerdict.of(prompt.expected, calls.map { it.name } + truncatedCalls, available, failed = error != null),
            tokens = tokens,
            reasoningTokens = reasoningTokens,
            maxReplyReasoningTokens = maxReplyReasoningTokens,
            reasoningCap = cap,
            reasoningOverCap = cap != null && maxReplyReasoningTokens != null && maxReplyReasoningTokens > cap + BenchResult.ReasoningCapSlack,
            ttftMs = ttftMs,
            totalMs = totalMs,
            stopReason = stopReason,
            error = error?.let { it.message ?: it::class.simpleName },
            prefixCold = prefixCold,
            stopsRun = error is QueueBusyException,
            namedInText = named.toList(),
            truncatedCalls = truncatedCalls,
            repetition = repetition,
            reply = reply,
            reasoning = reasonings.takeIf { it.isNotEmpty() }?.joinToString("\n\n"),
            callErrors = callErrors,
            answer = AnswerOutcome.of(prompt.answer, reply, stopReason, failed = error != null),
            emptyRaw = emptyRaw,
        )
    }

    /**
     * [sampling] with its answer held to [maxTokens]. The reasoning keeps the prompt's own cap, so the model decides on a
     * call as it would in a chat: the total is then that cap plus [maxTokens], the sum the engine holds a decode to.
     */
    private fun capped(sampling: SamplingConfig, maxTokens: Int): SamplingConfig = when (val budget = sampling.reasoningBudget) {
        null -> sampling.copy(maxTokens = maxTokens)
        else -> sampling.copy(
            maxTokens = budget.reasoningMaxTokens + maxTokens,
            reasoningBudget = ReasoningBudget(budget.reasoningMaxTokens, maxTokens, budget.endMarker),
        )
    }
}

/**
 * Hands the KV cache over to the bench: the conversation [owner] names (`(id, key)` of what
 * [io.github.tensaiapp.runtime.ActiveSessionTracker] holds) has its session saved with [save], then dropped with
 * [forget], so no later save writes the bench's tokens as its session. `false` when the save did not put its
 * state on disk: the bench must not touch the cache then.
 */
suspend fun <K> handOverCache(owner: Pair<String, K>?, save: suspend (String, K) -> SaveResult, forget: (String) -> Unit): Boolean {
    val (conversationId, key) = owner ?: return true
    when (save(conversationId, key)) {
        is SaveResult.Saved, is SaveResult.Unchanged, SaveResult.NothingToSave -> Unit
        is SaveResult.Failed, SaveResult.Discarded, SaveResult.NotAttempted, SaveResult.OwnerChanged -> return false
    }
    forget(conversationId)
    return true
}

/**
 * Runs [prompts] in order on [runner], each [repetitions] times in a row, and returns their results, logging each line
 * with [log]. Before each run of a prompt, [beforeEach] gets the cache back from a chat that took it in between (see
 * [handOverCache]) and answers why the run must stop, `null` to go on. A turn the queue refused
 * ([BenchResult.stopsRun]) stops it too. Either way an `error` line says why, and the results so far are returned.
 * [firstCold]: the tool prefix was not warmed for the first prompt. [onStart] gets each prompt and repetition as it
 * starts and [onResult] each result as it comes, for a progress line.
 */
suspend fun runBench(
    prompts: List<BenchPrompt>,
    runner: BenchRunner,
    firstCold: Boolean,
    beforeEach: suspend () -> String?,
    log: (String) -> Unit,
    onResult: (BenchResult) -> Unit = {},
    repetitions: Int = 1,
    onStart: (BenchPrompt, Int) -> Unit = { _, _ -> },
): List<BenchResult> {
    val results = mutableListOf<BenchResult>()
    for (prompt in prompts) {
        for (rep in 1..repetitions) {
            beforeEach()?.let { why ->
                log(benchErrorLine("$why, run stopped before '${prompt.id}'"))
                return results
            }
            onStart(prompt, rep)
            val result = runner.run(prompt, prefixCold = firstCold && results.isEmpty(), repetition = rep)
            results += result
            log(result.toLogLine())
            onResult(result)
            if (result.stopsRun) {
                log(benchErrorLine("the queue refused '${prompt.id}' (${result.error}), run stopped"))
                return results
            }
        }
    }
    return results
}

/**
 * The tools of [tools] that [text] writes out as a call instead of making one: in backticks, after "call", before
 * a `(` or `{`, as a JSON `"name"`, or anywhere for a name no prose holds (one with an underscore). A plain word
 * ("convert these miles") is not one, so a reply answering in words is not counted.
 */
fun toolsNamedInText(text: String, tools: Set<String>): List<String> = tools.filter { name ->
    val n = Regex.escape(name)
    Regex("""`$n`|\bcall(?:s|ed|ing)?\s+(?:the\s+)?(?:`)?$n\b|\b$n\s*[({]|"name"\s*:\s*"$n"""", RegexOption.IGNORE_CASE).containsMatchIn(text) ||
        ('_' in name && Regex("""\b$n\b""").containsMatchIn(text))
}

/** An `error` line: why the bench does not run, or stopped. */
fun benchErrorLine(message: String): String = buildJsonObject {
    put("event", "error")
    put("message", message)
}.toString()
