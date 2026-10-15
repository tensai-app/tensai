package io.github.hokanosekai.tensai.engine

/**
 * GGUF header metadata, read without loading the model weights — mirrors
 * the useful fields of llama.kt's `GgufMetadata` for the engine's public
 * contract (kept separate so callers never depend on the llama.kt type
 * directly).
 *
 * @param architecture model architecture (e.g. "qwen2", "llama").
 * @param name model name as recorded in the GGUF header (may be blank).
 * @param quantLabel human-readable quantization label (e.g. "Q4_K_M").
 * @param contextLength context length the model was trained with.
 * @param embeddingLength embedding (hidden) dimension.
 * @param blockCount number of transformer layers.
 * @param paramCount total parameter count, summed from tensor infos.
 * @param vocabSize tokenizer vocabulary size.
 * @param fileSizeBytes size of the GGUF file on disk, in bytes.
 * @param supportsThinking whether the model's chat template supports a
 *   thinking/reasoning mode (e.g. Qwen3).
 * @param chatTemplate raw Jinja chat template from the header, empty when it has none. The app
 *   derives its capability flags from it with
 *   [io.github.hokanosekai.tensai.data.models.ChatTemplateCapabilities].
 * @param hasToolUseTemplate the GGUF also carries a separate `tool_use` chat template, which counts as tool support.
 * @param kvFullElementsPerToken K+V cache elements one context token occupies
 *   across the layers whose cache is sized by `n_ctx`, summed the way
 *   llama.cpp allocates it (per-layer KV head count × head dimension) rather
 *   than assumed from [embeddingLength], with the V half charged at the
 *   largest any layer needs because that is what llama.cpp allocates when the
 *   V cache is transposed, i.e. whenever flash attention is off. **0 means the
 *   GGUF header didn't yield a usable attention shape** — see
 *   [io.github.hokanosekai.tensai.data.models.KvCacheSize] for the fallback.
 * @param kvSwaElementsPerToken same, for sliding-window layers, whose cache is
 *   capped at [slidingWindow] rather than growing with `n_ctx`. 0 when the
 *   model has none *or* when they couldn't be identified — those layers are
 *   then already counted in [kvFullElementsPerToken].
 * @param slidingWindow window size in tokens, 0 unless
 *   [kvSwaElementsPerToken] is non-zero.
 * @param kvShapeVersion which revision of the native derivation produced the
 *   three fields above — see [LlamaKtEngineImpl.kvShapeVersion]. Recorded on
 *   the row so
 *   [io.github.hokanosekai.tensai.data.models.KvCacheShapeBackfill] can tell a
 *   shape derived by an older formula from one derived by the current
 *   one. 0 only ever comes from a native library too old to report one.
 */
data class GgufInfo(
    val architecture: String,
    val name: String,
    val quantLabel: String,
    val contextLength: Long,
    val embeddingLength: Long,
    val blockCount: Long,
    val paramCount: Long,
    val vocabSize: Long,
    val fileSizeBytes: Long,
    val supportsThinking: Boolean,
    val chatTemplate: String = "",
    val hasToolUseTemplate: Boolean = false,
    val kvFullElementsPerToken: Long = 0,
    val kvSwaElementsPerToken: Long = 0,
    val slidingWindow: Long = 0,
    val kvShapeVersion: Long = 0,
)

/**
 * [InferenceEngine] sub-interface exposing llama.cpp/llama.kt-specific
 * capabilities that don't belong in the generic contract.
 *
 * Note: llama.kt's underlying `LlamaEngine` holds a single native handle
 * per instance — the operations below act on whatever model is currently
 * loaded on this engine instance, not on an arbitrary [LoadedModel]
 * obtained from it.
 */
interface LlamaKtEngine : InferenceEngine {
    /** Number of KV-cache cells currently in use (sequence 0). Compare against `nCtx` for a fill ratio. */
    fun kvCacheUsedCells(): Int

    /**
     * Persist the current KV-cache + prompt state to [path]. Returns the number of tokens
     * included in the saved state (always `>= 0`). Throws [InferenceException] on failure.
     */
    suspend fun saveSession(path: String): Int

    /**
     * Restore a previously saved session from [path]. Requires the same
     * model and context parameters as when it was saved.
     * Returns `true` on success, `false` if the session could not be restored.
     */
    suspend fun loadSession(path: String): Boolean

    /**
     * Read the GGUF header of [path] without loading the weights —
     * fast (milliseconds even for multi-GB files), safe to call before
     * [InferenceEngine.loadModel].
     * Throws [ModelLoadException] if [path] is not a readable GGUF file.
     */
    suspend fun readMetadata(path: String): GgufInfo
}
