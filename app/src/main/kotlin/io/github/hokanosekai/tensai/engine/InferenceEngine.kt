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
 * @param maxTokens hard cap on the number of generated tokens.
 * @param enableThinking for thinking-capable models: whether to allow the
 *   model to reason before answering. Implementations that don't support
 *   this concept may ignore it.
 */
data class SamplingConfig(
    val temperature: Float = 0.8f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val minP: Float = 0.05f,
    val stopSequences: List<String> = emptyList(),
    val maxTokens: Int = 512,
    val enableThinking: Boolean = true,
)

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
 */
data class DecodeStats(
    val tokensGenerated: Int,
    val tokPerSec: Float,
    val ttftMs: Long,
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
 */
data class LoadedModelInfo(
    val name: String,
    val nCtxEffective: Int,
    val backend: String,
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
