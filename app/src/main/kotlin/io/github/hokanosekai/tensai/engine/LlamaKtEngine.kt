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

    /** Persist the current KV-cache + prompt state to [path]. Throws [InferenceException] on failure. */
    suspend fun saveSession(path: String)

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
