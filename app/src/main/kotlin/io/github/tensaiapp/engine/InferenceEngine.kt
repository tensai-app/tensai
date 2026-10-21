package io.github.tensaiapp.engine

import kotlinx.coroutines.flow.Flow

/**
 * Hint for which compute backend a model should run on.
 *
 * This is a hint, not a guarantee — an engine implementation is free to
 * fall back (e.g. no GPU device available) and report the backend it
 * actually used via [LoadedModelInfo.backend].
 */
enum class BackendHint {
    /** Let the engine decide (typically: GPU if a device is available, CPU otherwise). */
    Auto,

    /** Force CPU-only inference. */
    Cpu,

    /** Prefer GPU offload if a device is available. */
    Gpu,
}

/**
 * KV-cache quantization. Lower precision roughly halves/quarters cache
 * memory (more context in the same RAM) at the cost of some generation
 * quality. Engines that don't support quantized KV caches should treat
 * anything other than [F16] as a best-effort hint.
 */
enum class KvCacheType {
    /** Full precision (default, safest). */
    F16,

    /** Half the KV memory of [F16]. */
    Q8_0,

    /** Quarter the KV memory of [F16] — quality risk. */
    Q4_0,
}

/**
 * Configuration to load a model with [InferenceEngine.loadModel].
 *
 * @param path path to the model file on disk, in whatever format the
 *   target engine expects (GGUF, MLC bundle, LiteRT model, …).
 * @param modelId opaque caller-supplied identity for the file at [path], echoed back on
 *   [LoadedModelInfo.modelId] and never interpreted by the engine. The app passes the loaded
 *   row's [io.github.tensaiapp.data.models.ModelEntity.id]; `null` (the default) is for
 *   callers loading a bare path with no persisted row behind it, and costs them nothing but the
 *   exact identity that field buys — see [LoadedModelInfo.modelId].
 * @param nCtx requested context window size, in tokens.
 * @param backend see [BackendHint].
 * @param kvCacheType see [KvCacheType].
 * @param onProgress optional loading-progress callback, receiving a value
 *   in 0.0–1.0. Called from whatever thread the engine performs loading
 *   on — implementations must not assume the caller's dispatcher.
 * @param onStage optional callback fired when the load enters a new [LoadStage]. Same threading as
 *   [onProgress]. An engine that has no stages simply never calls it.
 * @param name name to report on [LoadedModelInfo.name] in place of the GGUF header's own, which is
 *   often a hash or a placeholder (TEN-147). The app passes [io.github.tensaiapp.data.models.ModelEntity.shownName];
 *   `null` (the default) keeps the header name.
 * @param chatTemplate the chat format to force on the model ([io.github.tensaiapp.data.models.ChatTemplateChoice.id]),
 *   `null` (the default) keeps the template of the file (TEN-159). Part of the config, so a change reloads the model.
 */
data class ModelLoadConfig(
    val path: String,
    val modelId: String? = null,
    val nCtx: Int = 4096,
    val backend: BackendHint = BackendHint.Auto,
    val kvCacheType: KvCacheType = KvCacheType.F16,
    val onProgress: ((Float) -> Unit)? = null,
    val onStage: ((LoadStage) -> Unit)? = null,
    val name: String? = null,
    val chatTemplate: String? = null,
)

/**
 * Coarse phases of a model load, in the order an engine reports them through
 * [ModelLoadConfig.onStage]. Only [Weights] has an inner progress ([ModelLoadConfig.onProgress]);
 * the others are boundaries the engine really crossed, never estimates.
 */
enum class LoadStage {
    /**
     * Before the engine has reported anything: a previous model being saved and released, or
     * the engine probing the file. Set by the queue, never by an engine; no progress.
     */
    Preparing,

    /** Reading the weights into memory. */
    Weights,

    /** Creating the inference context (KV cache allocation). */
    Context,

    /** First pass on the new context, warming the backend up. */
    Warmup,

    /** The model is usable. */
    Ready,
}

/** Role of a [ChatMessage] in a conversation. */
enum class MessageRole {
    System,
    User,
    Assistant,
    Tool,
}

/**
 * A single tool invocation requested by the model (TEN-172).
 *
 * @param id call identifier, echoed by the `tool` message that answers it ([ChatMessage.toolCallId]).
 * @param name name of the tool being invoked.
 * @param argumentsJson tool arguments, as the model wrote them: a JSON object string that may not
 *   match the tool's schema.
 */
data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
) {
    /**
     * This call as it can be replayed into a prompt: llama.kt refuses a call whose arguments are not
     * a JSON object, and a model that wrote garbage must not break every later turn. Valid arguments
     * are kept as they are, anything else becomes `{}`. The stored call keeps what the model wrote.
     */
    fun replayable(): ToolCall = try {
        org.json.JSONObject(argumentsJson)
        this
    } catch (_: org.json.JSONException) {
        copy(argumentsJson = "{}")
    }
}

/**
 * A tool the model may call, as the prompt describes it.
 *
 * @param parametersJson JSON Schema of the arguments, as a string.
 */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parametersJson: String,
)

/** Whether the model may call the tools of a [DecodeRequest]. */
enum class ToolChoice {
    /** The model decides. */
    Auto,

    /** The tools stay described in the prompt (same prefix, same KV cache) but no call is allowed. */
    None,
}

/**
 * A single message in a chat conversation.
 *
 * @param toolCalls calls an assistant message made, replayed next to their results. `null` or empty for none.
 * @param toolCallId for [MessageRole.Tool], the [ToolCall.id] the message answers.
 * @param reasoning for an assistant message of the turn in progress, the reasoning that led to its calls (TEN-191).
 *   The template renders it or not (Qwen3: after the last user message only). `null` or empty for none.
 */
data class ChatMessage(
    val role: MessageRole,
    val content: String,
    val toolCalls: List<ToolCall>? = null,
    val toolCallId: String? = null,
    val reasoning: String? = null,
)

/**
 * Sampling parameters for a single [DecodeRequest].
 *
 * @param temperature softmax temperature. Lower = more deterministic,
 *   higher = more random. 0 disables randomness (greedy).
 * @param topP nucleus sampling: keep the smallest set of tokens whose
 *   cumulative probability reaches [topP]. 1.0 disables the filter.
 * @param topK keep only the K most probable tokens before sampling.
 *   0 disables the filter.
 * @param minP drop tokens whose probability is below `minP` times the
 *   top token's probability. 0 disables the filter.
 * @param repeatPenalty discourages tokens already generated in this decode (llama.cpp `penalty_repeat`,
 *   window 64, generated tokens only: the prompt is not counted, reasoning is). 1.0 disables it.
 *   Default 1.10 (TEN-114: a thinking model looped on one sentence with no penalty at all).
 * @param stopSequences strings that end generation when they appear in
 *   the output. Implementations must not emit the matched sequence itself.
 * @param maxTokens hard cap on the number of generated tokens when [reasoningBudget] is `null`.
 *   Counts *every* sampled token, reasoning included: a thinking model spends this budget on its
 *   `<think>` block before it writes a word of the answer, and llama.cpp has no notion of that
 *   boundary (TEN-60). Ignored — superseded by [reasoningBudget]'s two caps — when it is non-null
 *   (TEN-61).
 * @param enableThinking for thinking-capable models: whether to allow the
 *   model to reason before answering. Implementations that don't support
 *   this concept may ignore it.
 * @param reasoningBudget splits [maxTokens] into a reasoning cap and a response cap, so a verbose
 *   `<think>` block can no longer eat into the answer's share — see [ReasoningBudget]. `null` (the
 *   default) keeps the single, undivided [maxTokens] behaviour. Built with
 *   [ReasoningBudget.splitting] its two caps sum to exactly [maxTokens], so "ignored" above means
 *   "read through the budget instead", not "disagrees with it" (TEN-35).
 */
data class SamplingConfig(
    val temperature: Float = 0.8f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.10f,
    val stopSequences: List<String> = emptyList(),
    val maxTokens: Int = 2048,
    val enableThinking: Boolean = true,
    val reasoningBudget: ReasoningBudget? = null,
)

/**
 * Caps how much of a decode's token budget the model's reasoning may spend (TEN-61), so that a
 * thinking model spending [SamplingConfig.maxTokens] entirely on its `<think>` block — TEN-60's
 * problem — can no longer starve the actual answer.
 *
 * The two figures are a **cap on reasoning** and a **floor under the answer**, not two separate
 * allowances: reasoning stops at [reasoningMaxTokens], and the answer may then run to the whole
 * `reasoningMaxTokens + responseMaxTokens`, keeping whatever reasoning left unspent. So
 * [responseMaxTokens] is what the answer is *guaranteed* even against the most verbose reasoning,
 * and a model that reasons briefly or not at all answers into the full budget (TEN-69 — before it
 * the answer was also *held to* [responseMaxTokens], so an empty `<think>` block cost 1021
 * generated tokens that nothing ever used).
 *
 * Where the reasoning ends is decided by the engine's own parse of the reply when it offers one
 * ([DecodeEvent.Parsed], TEN-47) — that parse comes from the model's own chat template, so it holds
 * for `<think>`, for harmony's `<|channel|>`, and for a model that emits no reasoning at all.
 * [endMarker] is the fallback for when no parse arrives, and the engine treats it as an opaque
 * string, not a parsed tag: it has no notion that `<think>` exists (that's
 * [io.github.tensaiapp.data.chat.ThinkingStreamParser]'s job, on the app side) — only that
 * seeing it in the stream means "reasoning is over".
 *
 * A model that really does reason past [reasoningMaxTokens] is **made to stop reasoning**, not made
 * to stop generating (TEN-85): llama.cpp's own reasoning-budget sampler forces the template's
 * closing tag out token by token and then steps aside, and the model answers into what is left of
 * the two caps' sum. Until TEN-85 the decode was interrupted at the cap instead, which is how a
 * Gemma-4 reply reached the user as a reasoning block with no answer under it. The forcing needs
 * the model's chat template to delimit reasoning; where it doesn't, nothing enforces
 * [reasoningMaxTokens] at all and only the sum still holds.
 *
 * @param reasoningMaxTokens cap on tokens sampled while the model is still reasoning.
 * @param responseMaxTokens tokens the answer is guaranteed. It may use more — up to the sum of the
 *   two caps — when reasoning came in under [reasoningMaxTokens].
 * @param endMarker string that ends the reasoning phase when the engine offers no parse of its own.
 *   Matched across token boundaries — a model rarely emits it as a single token — by whoever tracks
 *   it (see `io.github.tensaiapp.engine.ReasoningBudgetTracker`).
 */
data class ReasoningBudget(
    val reasoningMaxTokens: Int,
    val responseMaxTokens: Int,
    val endMarker: String,
) {
    companion object {
        /**
         * Smallest total [splitting] will divide. Below 2 the reasoning half rounds down to 0, and
         * a reasoning cap of 0 stops the decode on its very first token
         * ([ReasoningBudgetTracker.onToken] compares `>=`) — a generation that can never produce
         * anything. [io.github.tensaiapp.data.chat.SamplingRanges.MaxTokens]'s own floor is far above this; this is the backstop
         * for a caller that doesn't go through it.
         */
        private const val MinSplittableTokens = 2

        /**
         * Divides [maxTokens] into the two caps, so that
         * `reasoningMaxTokens + responseMaxTokens == maxTokens` **by construction**.
         *
         * That equality is load-bearing and used to be a coincidence. [LlamaKtEngineImpl] derives
         * llama.cpp's `nPredict` from the sum of the two caps, and [ReasoningBudgetTracker] holds
         * the whole decode to that same sum — so before TEN-35 introduced a user-settable
         * [SamplingConfig.maxTokens], the two caps were a pair of hardcoded 1024s that happened to
         * add up to the hardcoded 2048 default. Keeping the caps fixed while letting the user move
         * [SamplingConfig.maxTokens] would have severed that: asking for 512 tokens would still
         * have generated up to 2048, and [SamplingConfig.maxTokens] would have been a number the
         * UI displayed and nothing obeyed.
         *
         * So the split is *derived* from the figure the user set, and there is no way to build a
         * budget that disagrees with it. Half to reasoning (rounded down, so the answer takes the
         * odd token — the answer is what the user reads), half to the response, which is a floor
         * and not a ceiling: whatever the reasoning leaves unspent goes to the answer, see this
         * class's KDoc and TEN-69. At the 2048 default this yields 1024/1024, byte for byte what
         * TEN-61 and TEN-69 shipped.
         */
        fun splitting(maxTokens: Int, endMarker: String): ReasoningBudget {
            val total = maxTokens.coerceAtLeast(MinSplittableTokens)
            val reasoning = total / 2
            return ReasoningBudget(
                reasoningMaxTokens = reasoning,
                responseMaxTokens = total - reasoning,
                endMarker = endMarker,
            )
        }
    }
}

/**
 * Why a decode stopped, reported in [DecodeStats.stopReason].
 *
 * EOS and a matched stop sequence are indistinguishable from here (llama.kt's `completion` returns
 * `Unit` and tells us nothing beyond the token count), and are deliberately merged into [Complete]
 * rather than guessed at. The budget cases are told apart from each other because, unlike
 * EOS/stop-sequence, they're each driven by a distinct counter this layer already keeps (see
 * `io.github.tensaiapp.engine.ReasoningBudgetTracker`).
 */
enum class StopReason {
    /** The model ended the turn itself — end-of-sequence, or a stop sequence matched. */
    Complete,

    /**
     * [SamplingConfig.maxTokens] was reached with [SamplingConfig.reasoningBudget] `null` — the
     * single, undivided budget from before TEN-61. Whatever was being written is cut off
     * mid-thought.
     */
    TokenBudget,

    /**
     * The decode spent everything [ReasoningBudget] allows — `reasoningMaxTokens +
     * responseMaxTokens` — while the model was **still reasoning**. There is no answer, and there
     * was never going to be one: nothing of the budget was left to write it with.
     *
     * Not "the reasoning cap was reached", which is what this meant up to TEN-85 and what made it
     * the common outcome for a verbose thinking model. That cap no longer ends a decode — see
     * [ReasoningBudget]'s KDoc — so reaching this now means the model reasoned for the *whole*
     * budget without the reasoning-budget sampler ever forcing it out: a template that delimits no
     * reasoning, a raw prompt, or a model that ignored its own closing tag. Rare, and a real
     * failure when it happens, which is why it keeps its own name instead of folding into
     * [ResponseBudgetExhausted].
     */
    ReasoningBudgetExhausted,

    /**
     * The decode spent everything [ReasoningBudget] allows — `reasoningMaxTokens +
     * responseMaxTokens` — while writing its answer (TEN-61, TEN-69). The answer is cut off
     * mid-thought, same as [TokenBudget]. Not "the answer reached
     * [ReasoningBudget.responseMaxTokens]": the answer also gets whatever reasoning left unspent,
     * so how many tokens of answer this stands for depends on how long the model reasoned.
     */
    ResponseBudgetExhausted,

    /**
     * The prompt alone already left no meaningful room in [LoadedModelInfo.nCtxEffective] to
     * generate a reply into (TEN-65, see [io.github.tensaiapp.engine.ContextBudget]) — no
     * completion was even attempted, so [DecodeStats.tokensGenerated] is `0` and
     * [DecodeStats.ttftMs] is `0`. Reported instead of [Complete] on purpose: [Complete] reads as
     * "the model chose to say nothing", when in fact it was never given a chance to say anything.
     */
    ContextBudgetExhausted,

    /**
     * The decode filled every token [LoadedModelInfo.nCtxEffective] had left after the prompt —
     * the reply is cut off, and raising [SamplingConfig.maxTokens] would not have made it any
     * longer (TEN-79).
     *
     * Distinct from [TokenBudget]/[ResponseBudgetExhausted] because the two call for opposite
     * reactions. Those mean "the cap you set was reached", and the cap is a setting the user can
     * move. This one means the cap was never in play: TEN-65 had already clipped it down to the
     * room the context window actually left, so the number in the settings sheet is not what
     * stopped the reply and moving it changes nothing. The way forward is a new conversation, or
     * reloading the model with a larger context — which is what the chat's own
     * [io.github.tensaiapp.ui.screens.chat.ChatError.ContextWindowFull] banner says.
     *
     * Sibling of [ContextBudgetExhausted], one step earlier: there the prompt left no usable room
     * at all and nothing was generated, here it left some and the reply spent all of it.
     */
    ContextBudgetClipped,

    /**
     * The decode ended on a [DecodeEvent.Error] (TEN-52). Never reported in [DecodeStats]: a failed
     * decode produces no stats at all, which is precisely the problem this value exists to solve.
     * The app layer needs a name for the third way a generation can end when it records one (see
     * [io.github.tensaiapp.data.chat.MessageEntity.stopReason]), because a generation the
     * user stopped records no stop reason either, so a genuine failure and a deliberate stop used
     * to look identical once persisted. Engines must not emit this: they report a failure by
     * emitting [DecodeEvent.Error], not by finishing with a stop reason that says they didn't
     * finish.
     */
    Failed,

    /**
     * The user tapped stop (TEN-39). Never reported in [DecodeStats], and never emitted by an
     * engine, for the same reason as [Failed]: a cancelled decode produces no terminal event at
     * all, so there is nothing for an engine to put a reason on. This exists because "stopped" was
     * previously recorded as the *absence* of both other outcomes — no stats, no stop reason — and
     * absence is also what a reply still streaming looks like, and what a row written before this
     * value existed looks like. Recording it makes a stopped reply a fact the chat can read back a
     * week later instead of a guess it has to make from three nulls.
     *
     * The app layer sets it, off the
     * [io.github.tensaiapp.runtime.GenerationStopSignal] the cancelled generation was
     * started with — see that class for why the fact belongs to the generation rather than to
     * whoever pressed the button, and for what it cannot be confused with.
     */
    Cancelled,
}

/**
 * A decode (completion) request against a [LoadedModel].
 *
 * @param messages the conversation so far, in order. Implementations are
 *   expected to apply the model's own chat template.
 * @param sampling see [SamplingConfig].
 * @param tools tools rendered into the prompt. Empty (the default) is the plain path, untouched by
 *   tool calling: same formatting, no parse of the reply, no [DecodeEvent.ToolTurn].
 * @param toolChoice see [ToolChoice]. Ignored when [tools] is empty.
 */
data class DecodeRequest(
    val messages: List<ChatMessage>,
    val sampling: SamplingConfig = SamplingConfig(),
    val tools: List<ToolDefinition> = emptyList(),
    val toolChoice: ToolChoice = ToolChoice.Auto,
)

/**
 * Statistics reported when a decode completes — see [DecodeEvent.Done].
 *
 * @param tokensGenerated llama.cpp's count of sampled tokens for the whole reply, reasoning
 *   tokens included (TEN-79). Unchanged by TEN-109.
 * @param tokPerSec generation throughput: [tokensGenerated] over the time spent *after* the first
 *   token, i.e. `(total - ttftMs)`, so prefill is excluded and the figure is comparable with
 *   llama.kt's tg numbers (TEN-109, see [generationTokPerSec]). `0` when it can't be measured
 *   (fewer than 2 tokens, or no time after TTFT): the UI then shows no tok/s. Rows stored before
 *   TEN-109 hold the old definition, `tokensGenerated / total` with prefill included, and are not
 *   migrated, so they read lower after a long prefill.
 * @param ttftMs time to first token, in milliseconds (prefill time, roughly).
 * @param stopReason why generation ended — see [StopReason]. Lets the UI tell a finished reply
 *   apart from one cut off at the budget, which otherwise look identical (TEN-60).
 */
data class DecodeStats(
    val tokensGenerated: Int,
    val tokPerSec: Float,
    val ttftMs: Long,
    val stopReason: StopReason = StopReason.Complete,
)

/**
 * Generation speed in tokens/s, prefill excluded (TEN-109): `tokens / (totalMs - ttftMs)`.
 *
 * Returns `0f` when there is no meaningful decode window: fewer than 2 tokens (the single token
 * lands at TTFT, so what is left of the total is noise) or `ttftMs >= totalMs`. Callers treat `0`
 * as "no figure" rather than dividing by ~0 or falling back to a prefill-inclusive number.
 */
fun generationTokPerSec(tokens: Int, totalMs: Long, ttftMs: Long): Float {
    val decodeMs = totalMs - ttftMs
    if (tokens < 2 || decodeMs <= 0L) return 0f
    return tokens * 1000f / decodeMs
}

/** An event emitted while streaming a [DecodeRequest]. */
sealed class DecodeEvent {
    /** A single piece of generated text. May be a partial token or a few characters. */
    data class Token(val text: String) : DecodeEvent()

    /**
     * The reply so far, as the *engine* splits it into answer and reasoning (TEN-47) — best-effort,
     * and strictly extra: the [Token] stream is unaffected and still carries the raw text.
     *
     * Whether reasoning is marked `<think>…</think>`, with gpt-oss/harmony `<|channel|>` markers or
     * however else, is a property of the model's chat template, and an engine that applies that
     * template is the only layer that can know. Only
     * [io.github.tensaiapp.data.chat.ThinkingStreamParser]'s hardcoded `<think>` guess was
     * available before, which is why Gemma-4 and gpt-oss reasoning used to render raw in the chat.
     *
     * **Consumers must cope with this never arriving**: an engine with no chat parser, a template
     * it could not render, or a reply that doesn't match the format its own template advertised all
     * produce a decode with zero of these. That's what the `ThinkingStreamParser` fallback is for —
     * see [io.github.tensaiapp.data.chat.ReplyStreamSplitter], which arbitrates between
     * the two.
     *
     * @param content the whole answer resolved so far, markers stripped — *cumulative*, not a
     *   delta, and not necessarily monotonic (a parse can retract text a later token disproves).
     * @param reasoning the reasoning that preceded it, markers stripped, or `null` when the engine
     *   found none.
     * @param toolCalls the names of the calls the reply has opened so far, the last one possibly still
     *   being written (empty or cut short: only trust a name you offered). Always empty for a request
     *   without [DecodeRequest.tools]. A request with tools also gets one of these before it has
     *   anything to report, with empty [content]: the engine is parsing, so a reply that opens on a
     *   call marker is held back instead of shown raw (TEN-185).
     */
    data class Parsed(val content: String, val reasoning: String?, val toolCalls: List<String> = emptyList()) : DecodeEvent()

    /**
     * The finished reply of a request with [DecodeRequest.tools], as the engine's chat parser split
     * it (TEN-172). Emitted once, before [Done], and only for a decode that generated something:
     * an interrupted or failed one reports nothing. Never emitted for a request without tools.
     *
     * @param content the answer, call markup stripped (the raw reply when [toolParseFailed]).
     * @param reasoning the reasoning, or `null`.
     * @param toolCalls the calls found. Empty when there are none, when the reply was [truncated]
     *   (its last call would be half-written) or when the parse failed.
     * @param toolsIgnored the model's template could not render the tools: the model never heard of them.
     * @param toolParseFailed the parser rejected the reply (a malformed call, typically). Nothing is thrown for it.
     * @param truncated the reply stopped on the token cap or a full context window, not on its own end.
     */
    data class ToolTurn(
        val content: String,
        val reasoning: String?,
        val toolCalls: List<ToolCall>,
        val toolsIgnored: Boolean,
        val toolParseFailed: Boolean,
        val truncated: Boolean,
    ) : DecodeEvent()

    /** Generation finished normally (EOS, stop sequence, or [SamplingConfig.maxTokens] reached). */
    data class Done(val stats: DecodeStats) : DecodeEvent()

    /** Generation failed. No further events follow. */
    data class Error(val cause: Throwable) : DecodeEvent()
}

/**
 * Metadata describing a model as loaded by an [InferenceEngine], exposed
 * through [LoadedModel.metadata].
 *
 * @param name human-readable model name.
 * @param nCtxEffective context window actually in effect for this load.
 * @param backend compute backend actually used (implementation-defined
 *   label, e.g. "CPU", "Vulkan").
 * @param kvCacheType KV-cache precision actually requested for this load — see
 *   [ModelLoadConfig.kvCacheType]. Carried here (rather than only on the request) so a saved
 *   session's metadata can record exactly what produced it: TEN-34's restore path refuses to load
 *   a session back unless the model, [nCtxEffective] and this all still match, and this is the one
 *   place that triple is available together after a load completes.
 * @param modelId whatever [ModelLoadConfig.modelId] the load was requested with, echoed back
 *   untouched — the app's [io.github.tensaiapp.data.models.ModelEntity.id], or `null` for
 *   a load with no row behind it. [name] cannot stand in for it: it comes from the GGUF header, so
 *   two quantisations of the same model — the ordinary case of importing a Q4 and a Q8 to compare
 *   them — are two files with two rows and one single name. Everything that has to say *which
 *   file* is loaded reads this: TEN-34's session key (a session restored onto the other
 *   quantisation is one model's KV state fed to another), the delete guard's "is this file
 *   mmapped", and this type's own equality, which
 *   [io.github.tensaiapp.runtime.InferenceQueue.restoreSession] re-checks a model against
 *   under its lock (TEN-80).
 * @param chatTemplate whatever [ModelLoadConfig.chatTemplate] the load was requested with, echoed back: part of the
 *   session key (TEN-159), as a saved state was prefilled from a prompt rendered by that template.
 */
data class LoadedModelInfo(
    val name: String,
    val nCtxEffective: Int,
    val backend: String,
    val kvCacheType: KvCacheType,
    val modelId: String? = null,
    val chatTemplate: String? = null,
)

/**
 * A model loaded and ready for inference. Obtained from
 * [InferenceEngine.loadModel]; must be released with [free] once no
 * longer needed.
 */
interface LoadedModel {
    /** See [LoadedModelInfo]. */
    val metadata: LoadedModelInfo

    /**
     * Stream a completion for [request].
     *
     * The returned [Flow] is cold and cancellable: cancelling collection
     * must stop the underlying generation. Implementations must terminate
     * the flow with exactly one terminal event — either [DecodeEvent.Done]
     * or [DecodeEvent.Error] — after zero or more [DecodeEvent.Token]s.
     */
    suspend fun decode(request: DecodeRequest): Flow<DecodeEvent>

    /**
     * Prefill the KV cache with the start of the prompt the *next* turn of [messages] will send,
     * without generating anything (TEN-88), see [WarmUpPrompt] for what is prefilled and why.
     * Best-effort and purely an optimisation: an engine with no way to prefill keeps this default
     * and the next turn simply pays for it itself.
     *
     * Cancellable like [decode]: cancelling the caller interrupts the prefill, suspends until the
     * engine is actually back, and keeps what was decoded, so the next turn resumes from there.
     *
     * @param messages the conversation ending on the assistant reply that just finished.
     * @param enableThinking the setting the next turn will render its prompt with.
     * @return `true` if the cache now holds the whole warm-up prompt, `false` if nothing was done
     *   or it could not be completed (it does not fit the context window, nothing to cut at).
     */
    suspend fun warmUp(messages: List<ChatMessage>, enableThinking: Boolean): Boolean = false

    /**
     * Puts the fixed start of [request]'s prompt (system messages and tool block, cut where the user's
     * words begin, TEN-182) in the KV cache, from a file when one exists, without generating anything
     * (TEN-205). For the first prefill after a load: the decode that follows finds it resident.
     * Cancellable like [warmUp]. An engine with no prefix cache keeps this default.
     *
     * @param replaceResident what the cache holds is an earlier prefix and nothing else, or a conversation
     *   already saved, so it may be replaced instead of left alone (the tool set changed since).
     * @return `true` if the cache now holds the whole prefix, `false` if nothing was done.
     */
    suspend fun warmToolPrefix(request: DecodeRequest, replaceResident: Boolean = false): Boolean = false

    /** Tokenize [text] with this model's tokenizer and return the token count. */
    suspend fun tokenize(text: String): Int

    /** Release any resources (native memory, file handles, …) held by this model. */
    suspend fun free()
}

/**
 * Generic contract for an on-device LLM inference backend.
 *
 * Implementations wrap a specific runtime (llama.cpp via llama.kt, MLC,
 * LiteRT, …) behind engine-agnostic types — nothing in this contract
 * leaks runtime-specific concepts (no GGUF, no native sessions, no KV
 * cache cells). Runtime-specific capabilities belong in a sub-interface,
 * see e.g. `LlamaKtEngine`.
 */
interface InferenceEngine {
    /** Load a model per [config]. Throws on failure — see runtime-specific exception hierarchies. */
    suspend fun loadModel(config: ModelLoadConfig): LoadedModel
}
