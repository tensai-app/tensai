package io.github.tensaiapp.bench

import io.github.tensaiapp.engine.SamplingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

/**
 * A saved agent bench run (TEN-187), debug builds only: what it ran on and with, and every result, one JSON file in
 * `filesDir/bench/` ([BenchRunStore]). Read back leniently ([BenchRunJson]): a field a later build added is skipped,
 * one an older build lacked takes its default, so runs saved weeks apart still compare.
 *
 * @param results in run order, each prompt's repetitions in a row.
 * @param plannedTrials what a run to the end grades: prompts times repetitions.
 * @param partial the run ended before [plannedTrials]: [stopReason] says why.
 * @param stopReason [StoppedByUser] for the Stop button, else the reason the log gave, `null` for a full run.
 */
@Serializable
data class BenchRun(
    val kind: String = Kind,
    val version: Int = Version,
    val id: String,
    @SerialName("started_at_ms") val startedAtMs: Long,
    @SerialName("finished_at_ms") val finishedAtMs: Long,
    val config: BenchRunConfig,
    @SerialName("planned_trials") val plannedTrials: Int,
    val partial: Boolean = false,
    @SerialName("stop_reason") val stopReason: String? = null,
    val results: List<BenchTrial>,
) {
    companion object {
        const val Kind = "tensai.bench.agent_run"
        const val Version = 1
        const val StoppedByUser = "stopped"

        /** How a [stopReason] for a run that threw starts: "threw: " and the message. */
        const val ThrewPrefix = "threw: "

        /** The id, and file name, of a run started at [startedAtMs]: one run at a time, so the millisecond is unique. */
        fun idFor(startedAtMs: Long): String = "run-$startedAtMs"

        /**
         * The run that graded [results] of [planned] trials. Fewer is a partial run, [stopReason] saying why; a full
         * run keeps no reason, whatever ended it after the last trial. [BenchRunConfig.reasoningCapExceeded] is read
         * off the results.
         */
        fun of(startedAtMs: Long, finishedAtMs: Long, config: BenchRunConfig, planned: Int, results: List<BenchResult>, stopReason: String?): BenchRun {
            val partial = results.size < planned
            return BenchRun(
                id = idFor(startedAtMs),
                startedAtMs = startedAtMs,
                finishedAtMs = finishedAtMs,
                config = config.copy(reasoningCapExceeded = results.any { it.reasoningOverCap }.takeIf { config.sampling.reasoningMaxTokens != null }),
                plannedTrials = planned,
                partial = partial,
                stopReason = stopReason.takeIf { partial },
                results = results.map(BenchTrial::of),
            )
        }
    }
}

/**
 * What a run ran on and with: the loaded model, the toggles of its [BenchTrigger], the sampling, the build.
 *
 * @param quant the model's quantization label as the library shows it, `null` when unknown.
 * @param kvType the KV cache type the model was loaded with ([io.github.tensaiapp.engine.KvCacheType]), lowercase.
 * @param tools the tools a prompt offered, by name.
 * @param reasoningBudget the run's [BenchTrigger.reasoningBudget], `null` for the chat's split (and for every run saved
 *   before the setting, and when the sampling had no cap to lower): [BenchSampling.reasoningMaxTokens] is the cap the
 *   decodes got either way.
 * @param reasoningBudgetMode [BenchTrigger.reasoningBudgetMode], `null` for a run saved before it: "default" or "fixed",
 *   [reasoningBudget] says which. "adaptive": each prompt had its own cap, see [BenchTrial.reasoningCap].
 * @param reasoningCapExceeded a reply reasoned past that cap ([BenchResult.reasoningOverCap]): the template does not
 *   delimit reasoning, so nothing enforced it. `null` with no cap, and for a run saved before the check.
 * @param prefixWarmed the tool prefix was warmed before the first prompt (its timings are left out of the medians either way).
 */
@Serializable
data class BenchRunConfig(
    val model: String,
    val quant: String? = null,
    val backend: String? = null,
    @SerialName("kv_type") val kvType: String? = null,
    val thinking: Boolean,
    @SerialName("thinking_supported") val thinkingSupported: Boolean = true,
    @SerialName("base_prompt") val basePrompt: Boolean,
    @SerialName("base_prompt_version") val basePromptVersion: Int,
    val examples: Boolean,
    @SerialName("tool_grammar") val toolGrammar: Boolean,
    val repetitions: Int = 1,
    @SerialName("reasoning_budget") val reasoningBudget: Int? = null,
    @SerialName("reasoning_budget_mode") val reasoningBudgetMode: String? = null,
    @SerialName("reasoning_cap_exceeded") val reasoningCapExceeded: Boolean? = null,
    val sampling: BenchSampling,
    val prompts: Int,
    val tools: List<String> = emptyList(),
    @SerialName("prefix_warmed") val prefixWarmed: Boolean? = null,
    @SerialName("app_version_name") val appVersionName: String? = null,
    @SerialName("app_version_code") val appVersionCode: Int? = null,
    @SerialName("llama_kt_commit") val llamaKtCommit: String? = null,
)

/**
 * The sampling every prompt of a run decoded with (a capped long-form prompt lowers [maxTokens] for its own answer).
 *
 * @param reasoningMaxTokens the reasoning cap when thinking is on, `null` otherwise.
 * @param seeds the seed of each repetition, in order. `null`: the engine takes no seed, so each decode draws its own
 *   and a repetition cannot be replayed, only counted.
 * @param seeded whether [seeds] were passed down: `false` until the engine takes a seed.
 * @param greedy the run asked for temperature 0 ([BenchTrigger.greedy]), which [temperature] then is.
 */
@Serializable
data class BenchSampling(
    val temperature: Float,
    @SerialName("top_p") val topP: Float,
    @SerialName("top_k") val topK: Int,
    @SerialName("min_p") val minP: Float,
    @SerialName("repeat_penalty") val repeatPenalty: Float,
    @SerialName("max_tokens") val maxTokens: Int,
    @SerialName("reasoning_max_tokens") val reasoningMaxTokens: Int? = null,
    val seeds: List<Long>? = null,
    val seeded: Boolean = false,
    val greedy: Boolean = false,
) {
    companion object {
        /** The record of [config]. No seed: [SamplingConfig] has none to pass down, llama.kt draws a random one per decode. */
        fun of(config: SamplingConfig, greedy: Boolean = false): BenchSampling = BenchSampling(
            temperature = config.temperature,
            topP = config.topP,
            topK = config.topK,
            minP = config.minP,
            repeatPenalty = config.repeatPenalty,
            maxTokens = config.maxTokens,
            reasoningMaxTokens = config.reasoningBudget?.reasoningMaxTokens,
            seeds = null,
            seeded = false,
            greedy = greedy,
        )
    }
}

/**
 * One graded run of one prompt: a [BenchResult] as saved. [reply] and [reasoning] are cut to [MaxTextChars], [replyCut]
 * and [reasoningCut] saying so; tool call arguments are kept whole.
 *
 * @param expected the tools any of which is a correct call, empty when the right answer calls none.
 * @param verdict a [BenchVerdict.storageName], see [benchVerdict].
 * @param answer an [AnswerOutcome.storageName], see [answerOutcome], `null` in a run saved before answers were checked.
 * @param expectedAnswer the prompt's [AnswerCheck.shown], `null` for a prompt with no check.
 * @param tags the prompt's [BenchPrompt.tags].
 * @param reasoningCap [BenchResult.reasoningCap], `null` too in a run saved before it.
 */
@Serializable
data class BenchTrial(
    val id: String,
    val prompt: String,
    val expected: List<String> = emptyList(),
    val rep: Int = 1,
    val verdict: String,
    val calls: List<BenchCall> = emptyList(),
    val refused: List<String> = emptyList(),
    @SerialName("truncated_calls") val truncatedCalls: List<String> = emptyList(),
    @SerialName("named_in_text") val namedInText: List<String> = emptyList(),
    val tokens: Int = 0,
    @SerialName("reasoning_tokens") val reasoningTokens: Int? = null,
    @SerialName("max_reply_reasoning_tokens") val maxReplyReasoningTokens: Int? = null,
    @SerialName("reasoning_over_cap") val reasoningOverCap: Boolean = false,
    @SerialName("ttft_ms") val ttftMs: Long? = null,
    @SerialName("total_ms") val totalMs: Long = 0,
    val stop: String? = null,
    val error: String? = null,
    @SerialName("prefix_cold") val prefixCold: Boolean = false,
    val reply: String? = null,
    @SerialName("reply_cut") val replyCut: Boolean = false,
    val reasoning: String? = null,
    @SerialName("reasoning_cut") val reasoningCut: Boolean = false,
    val answer: String? = null,
    @SerialName("expected_answer") val expectedAnswer: String? = null,
    val tags: List<String> = emptyList(),
    @SerialName("reasoning_cap") val reasoningCap: Int? = null,
) {
    /** [verdict] read back, [BenchVerdict.Error] for a name this build does not know. */
    val benchVerdict: BenchVerdict get() = BenchVerdict.entries.firstOrNull { it.storageName == verdict } ?: BenchVerdict.Error

    /** [answer] read back, `null` when it was not saved or this build does not know it. */
    val answerOutcome: AnswerOutcome? get() = AnswerOutcome.entries.firstOrNull { it.storageName == answer }

    companion object {
        const val MaxTextChars = 4000

        fun of(result: BenchResult): BenchTrial {
            val (reply, replyCut) = capText(result.reply)
            val (reasoning, reasoningCut) = capText(result.reasoning)
            return BenchTrial(
                id = result.prompt.id,
                prompt = result.prompt.prompt,
                expected = result.prompt.expected.toList(),
                rep = result.repetition,
                verdict = result.verdict.storageName,
                calls = result.calls.map { BenchCall(it.name, it.argumentsJson, result.callErrors[it.id]) },
                refused = result.refused,
                truncatedCalls = result.truncatedCalls,
                namedInText = result.namedInText,
                tokens = result.tokens,
                reasoningTokens = result.reasoningTokens,
                maxReplyReasoningTokens = result.maxReplyReasoningTokens,
                reasoningOverCap = result.reasoningOverCap,
                ttftMs = result.ttftMs,
                totalMs = result.totalMs,
                stop = result.stopReason,
                error = result.error,
                prefixCold = result.prefixCold,
                reply = reply,
                replyCut = replyCut,
                reasoning = reasoning,
                reasoningCut = reasoningCut,
                answer = result.answer.storageName,
                expectedAnswer = result.prompt.answer?.shown,
                tags = result.prompt.tags.toList(),
                reasoningCap = result.reasoningCap,
            )
        }
    }
}

/**
 * A tool call as the model wrote it: [args] is its arguments' text, kept even when it is not valid JSON. [error] is what
 * the model was told when the call ended in an error ([BenchResult.callErrors]), `null` otherwise.
 */
@Serializable
data class BenchCall(val name: String, val args: String, val error: String? = null)

/**
 * Runs [run] (a [runBench], or the decode curve, that adds each result to [results] as it comes and returns why it
 * stopped early, `null` for none), then hands what it graded and why it ended to [finish], under [NonCancellable], however it ended: at the
 * end, on Stop ([stopRequested]: [BenchRun.StoppedByUser]), or on a throw ([BenchRun.ThrewPrefix] and the message,
 * not rethrown: the trials before it are worth keeping). Any other cancellation is still finished, as "cancelled",
 * then rethrown. Returns the reason [finish] got.
 */
internal suspend fun <T> runThenSave(
    results: List<T>,
    stopRequested: () -> Boolean,
    run: suspend () -> String?,
    finish: (List<T>, String?) -> Unit,
): String? {
    var reason: String?
    var cancelled: CancellationException? = null
    try {
        reason = run()
    } catch (e: CancellationException) {
        reason = if (stopRequested()) BenchRun.StoppedByUser else "cancelled"
        if (!stopRequested()) cancelled = e
    } catch (e: Exception) {
        reason = BenchRun.ThrewPrefix + (e.message ?: e::class.simpleName)
    }
    withContext(NonCancellable) { finish(results.toList(), reason) }
    cancelled?.let { throw it }
    return reason
}

/**
 * The backend of an engine label as the bench shows and saves it: "CPU", "Vulkan". The engine reports
 * "<backend>: <device>" ("CPU: CPU", "Vulkan: Mali-G68"); a label with no colon is kept whole.
 */
fun backendName(label: String): String = label.substringBefore(':').trim().ifBlank { label }

/** [text] cut to [max] chars, never inside a surrogate pair, and whether it was cut. */
internal fun capText(text: String?, max: Int = BenchTrial.MaxTextChars): Pair<String?, Boolean> {
    if (text == null || text.length <= max) return text to false
    val end = if (Character.isHighSurrogate(text[max - 1])) max - 1 else max
    return text.substring(0, end) to true
}

/** Reads and writes [BenchRun]s, and builds the JSON a comparison exports. */
object BenchRunJson {
    const val CompareKind = "tensai.bench.agent_compare"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(run: BenchRun): String = json.encodeToString(BenchRun.serializer(), run)

    /** Throws on a file that is not a run: the store skips it. */
    fun decode(text: String): BenchRun = json.decodeFromString(BenchRun.serializer(), text)

    /** One result as JSON, for the prompt detail's copy icon. */
    fun encode(trial: BenchTrial): String = json.encodeToString(BenchTrial.serializer(), trial)

    /**
     * [runs] side by side, keyed `a` to `d` in the order the screen shows them: each run whole, as saved, and its
     * [RunTotals], so the export reads without the app.
     */
    fun encodeCompare(runs: List<BenchRun>): String = buildJsonObject {
        put("kind", CompareKind)
        put("version", BenchRun.Version)
        put(
            "runs",
            buildJsonArray {
                runs.forEachIndexed { i, run ->
                    val totals = RunTotals.of(run.results)
                    add(
                        buildJsonObject {
                            put("key", runKey(i))
                            put("summary", totals.toJson())
                            put("run", json.encodeToJsonElement(run))
                        },
                    )
                }
            },
        )
    }.toString()

    private fun RunTotals.toJson(): JsonObject = buildJsonObject {
        put("trials", trials)
        BenchVerdict.entries.forEach { put(it.storageName, counts[it] ?: 0) }
        put("named_in_text", namedInText)
        put("tool_errors", toolErrors)
        put("ttft_p50_ms", ttftP50Ms)
        put("total_p50_ms", totalP50Ms)
        put("answer_ok", answerOk)
        put("answer_wrong", answerWrong)
        put("answer_cut", answerCut)
        put("answer_empty", answerEmpty)
        put("reasoning_tokens_p50", reasoningP50)
        put("max_reply_reasoning_tokens_p50", maxReplyReasoningP50)
    }
}

/** The letter a run goes by in a comparison: `a` for the first. */
fun runKey(index: Int): String = ('a' + index).toString()
