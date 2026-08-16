package io.github.hokanosekai.tensai.engine

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
 * @param nCtx requested context window size, in tokens.
 * @param backend see [BackendHint].
 * @param kvCacheType see [KvCacheType].
 * @param onProgress optional loading-progress callback, receiving a value
 *   in 0.0–1.0. Called from whatever thread the engine performs loading
 *   on — implementations must not assume the caller's dispatcher.
 */
data class ModelLoadConfig(
    val path: String,
    val nCtx: Int = 4096,
    val backend: BackendHint = BackendHint.Auto,
    val kvCacheType: KvCacheType = KvCacheType.F16,
    val onProgress: ((Float) -> Unit)? = null,
)

/** Role of a [ChatMessage] in a conversation. */
enum class MessageRole {
    System,
    User,
    Assistant,
    Tool,
}

/**
 * Placeholder for a single tool invocation requested by the model.
 *
 * Reserved for future tool-calling support — no current engine
 * implementation produces or interprets this yet.
 *
 * @param id engine/model-assigned call identifier.
 * @param name name of the tool being invoked.
 * @param argumentsJson tool arguments, as a raw JSON string.
 */
data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String,
)

/**
 * A single message in a chat conversation.
 *
 * @param toolCalls reserved placeholder for tool-calling support — see
 *   [ToolCall]. Always `null` until an engine implementation supports it.
 */
data class ChatMessage(
    val role: MessageRole,
    val content: String,
    val toolCalls: List<ToolCall>? = null,
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
 *   default) keeps the single, undivided [maxTokens] behaviour.
 */
data class SamplingConfig(
    val temperature: Float = 0.8f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val minP: Float = 0.05f,
    val stopSequences: List<String> = emptyList(),
    val maxTokens: Int = 2048,
    val enableThinking: Boolean = true,
    val reasoningBudget: ReasoningBudget? = null,
)

/**
 * Splits a decode's token budget into a reasoning cap and a response cap (TEN-61), so that a
 * thinking model spending [SamplingConfig.maxTokens] entirely on its `<think>` block — TEN-60's
 * problem — can no longer starve the actual answer.
 *
 * The engine treats [endMarker] as an opaque string, not a parsed tag: it has no notion that
 * `<think>` exists (that's [io.github.hokanosekai.tensai.data.chat.ThinkingStreamParser]'s job, on
 * the app side) — only that seeing [endMarker] in the stream means "reasoning is over, count
 * against [responseMaxTokens] from here on". Carrying the marker as data rather than baking the
 * format into the engine is what lets a model whose chat template marks reasoning differently
 * (e.g. Gemma's `<|channel|>`, TEN-47) reuse this same mechanism with no engine change.
 *
 * Known limitation, left as-is rather than worked around: if the model never emits [endMarker],
 * generation is cut when [reasoningMaxTokens] runs out and [StopReason.ReasoningBudgetExhausted]
 * is reported — there is no way to *force* an answer out of a model stuck reasoning. Doing that for
 * real would mean re-injecting a synthetic [endMarker] and re-running the completion from there
 * (a full re-prefill of the reasoning so far), which is out of scope here.
 *
 * @param reasoningMaxTokens cap on tokens sampled before [endMarker] is seen in the stream.
 * @param responseMaxTokens cap on tokens sampled from [endMarker] onward.
 * @param endMarker string that ends the reasoning phase. Matched across token boundaries — a model
 *   rarely emits it as a single token — by whoever tracks it (see
 *   `io.github.hokanosekai.tensai.engine.ReasoningBudgetTracker`).
 */
data class ReasoningBudget(
    val reasoningMaxTokens: Int,
    val responseMaxTokens: Int,
    val endMarker: String,
)

/**
 * Why a decode stopped, reported in [DecodeStats.stopReason].
 *
 * EOS and a matched stop sequence are indistinguishable from here (llama.kt's `completion` returns
 * `Unit` and tells us nothing beyond the token count), and are deliberately merged into [Complete]
 * rather than guessed at. The budget cases are told apart from each other because, unlike
 * EOS/stop-sequence, they're each driven by a distinct counter this layer already keeps (see
 * `io.github.hokanosekai.tensai.engine.ReasoningBudgetTracker`).
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
     * [ReasoningBudget.reasoningMaxTokens] was reached before the model's `<think>` block closed
     * (TEN-61). There may be no answer at all yet — see [ReasoningBudget]'s KDoc for why this
     * can't be forced past.
     */
    ReasoningBudgetExhausted,

    /** [ReasoningBudget.responseMaxTokens] was reached after reasoning ended (TEN-61). The answer is cut off mid-thought, same as [TokenBudget] but counted from the reasoning/response split. */
    ResponseBudgetExhausted,

    /**
     * The prompt alone already left no meaningful room in [LoadedModelInfo.nCtxEffective] to
     * generate a reply into (TEN-65, see [io.github.hokanosekai.tensai.engine.ContextBudget]) — no
     * completion was even attempted, so [DecodeStats.tokensGenerated] is `0` and
     * [DecodeStats.ttftMs] is `0`. Reported instead of [Complete] on purpose: [Complete] reads as
     * "the model chose to say nothing", when in fact it was never given a chance to say anything.
     */
    ContextBudgetExhausted,
}

/**
 * A decode (completion) request against a [LoadedModel].
 *
 * @param messages the conversation so far, in order. Implementations are
 *   expected to apply the model's own chat template.
 * @param sampling see [SamplingConfig].
 */
data class DecodeRequest(
    val messages: List<ChatMessage>,
    val sampling: SamplingConfig = SamplingConfig(),
)

/**
 * Statistics reported when a decode completes — see [DecodeEvent.Done].
 *
 * @param tokensGenerated number of tokens streamed to the caller.
 * @param tokPerSec average generation throughput over the whole decode.
 * @param ttftMs time to first token, in milliseconds.
 * @param stopReason why generation ended — see [StopReason]. Lets the UI tell a finished reply
 *   apart from one cut off at the budget, which otherwise look identical (TEN-60).
 */
data class DecodeStats(
    val tokensGenerated: Int,
    val tokPerSec: Float,
    val ttftMs: Long,
    val stopReason: StopReason = StopReason.Complete,
)

/** An event emitted while streaming a [DecodeRequest]. */
sealed class DecodeEvent {
    /** A single piece of generated text. May be a partial token or a few characters. */
    data class Token(val text: String) : DecodeEvent()

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
 */
data class LoadedModelInfo(
    val name: String,
    val nCtxEffective: Int,
    val backend: String,
    val kvCacheType: KvCacheType,
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
