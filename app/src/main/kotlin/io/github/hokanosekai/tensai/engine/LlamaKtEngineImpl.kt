package io.github.hokanosekai.tensai.engine

import com.tensai.llamakt.LlamaEngine
import com.tensai.llamakt.LoadProgressCallback
import com.tensai.llamakt.TokenCallback
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.tensai.llamakt.ChatMessage as LlamaChatMessage
import com.tensai.llamakt.GgufMetadata as LlamaGgufMetadata
import com.tensai.llamakt.SamplingParams as LlamaSamplingParams

/**
 * [LlamaKtEngine] implementation wrapping llama.kt's `LlamaEngine`.
 *
 * llama.kt's `LlamaEngine` holds a single native handle per instance and
 * all its calls (`load`, `completion`, `tokenize`, …) are blocking. Every
 * such call in this class is routed through [dispatcher], a dedicated
 * single-thread dispatcher, so they never block a caller's own dispatcher
 * and are naturally serialized against each other *for this engine
 * instance*. This is a stopgap: it does not serialize across multiple
 * engine instances or coordinate with other work — strict, app-wide
 * inference serialization arrives with the `InferenceQueue` (TEN-26).
 */
class LlamaKtEngineImpl : LlamaKtEngine {

    private val llamaEngine = LlamaEngine()

    private val dispatcher = Executors
        .newSingleThreadExecutor { runnable -> Thread(runnable, "llama-kt-engine") }
        .asCoroutineDispatcher()

    /**
     * Last KV-cache fill read from the native context, published for [kvCacheUsedCells].
     *
     * Written only from [dispatcher] — after a load, after each token, and once a completion
     * ends — so the native handle is never touched off that thread (TEN-51). Callers poll this
     * instead of reaching into llama.cpp themselves.
     *
     * Refreshing it per token rather than on demand is what keeps it readable *during* a
     * generation: `completion()` is a single blocking call that owns [dispatcher] from prefill to
     * last token, so an on-demand read routed through the dispatcher could only ever answer once
     * the generation it was meant to observe had already finished.
     */
    private val kvUsedCells = AtomicInteger(0)

    /** Must only be called from [dispatcher]. */
    private fun refreshKvUsedCells() {
        kvUsedCells.set(runCatching { llamaEngine.kvUsed() }.getOrDefault(0))
    }

    override suspend fun loadModel(config: ModelLoadConfig): LoadedModel = withContext(dispatcher) {
        val nGpuLayers = resolveNGpuLayers(config.backend)
        val kvCacheTypeArg = config.kvCacheType.toLibArg()
        val libProgress = config.onProgress?.let { onProgress ->
            LoadProgressCallback { progress -> onProgress(progress); true }
        }

        try {
            llamaEngine.load(
                path = config.path,
                nGpuLayers = nGpuLayers,
                nCtx = config.nCtx,
                onProgress = libProgress,
                kvCacheType = kvCacheTypeArg,
            )
        } catch (e: OutOfMemoryError) {
            throw OutOfMemoryException("Out of memory while loading model at ${config.path}", e)
        } catch (e: IllegalStateException) {
            throw ModelLoadException("Failed to load model at ${config.path}: ${e.message}", e)
        } catch (e: Exception) {
            throw ModelLoadException("Unexpected error while loading model at ${config.path}: ${e.message}", e)
        }

        val backend = try {
            llamaEngine.activeBackend()
        } catch (_: Exception) {
            "unknown"
        }
        val meta = try {
            LlamaEngine.readMetadata(config.path)
        } catch (_: Exception) {
            null
        }

        val info = LoadedModelInfo(
            name = meta?.name?.takeIf { it.isNotBlank() } ?: File(config.path).name,
            nCtxEffective = config.nCtx,
            backend = backend,
        )

        refreshKvUsedCells()
        LoadedModelImpl(info)
    }

    override fun kvCacheUsedCells(): Int = kvUsedCells.get()

    override suspend fun saveSession(path: String) = withContext(dispatcher) {
        val savedTokens = try {
            llamaEngine.saveSession(path)
        } catch (e: Exception) {
            throw InferenceException("Failed to save session to $path: ${e.message}", e)
        }
        if (savedTokens < 0) {
            throw InferenceException("Failed to save session to $path")
        }
    }

    override suspend fun loadSession(path: String): Boolean = withContext(dispatcher) {
        try {
            llamaEngine.loadSession(path) >= 0
        } catch (e: Exception) {
            throw InferenceException("Failed to load session from $path: ${e.message}", e)
        }
    }

    override suspend fun readMetadata(path: String): GgufInfo = withContext(dispatcher) {
        val meta = try {
            LlamaEngine.readMetadata(path)
        } catch (e: Exception) {
            throw ModelLoadException("Failed to read GGUF metadata for $path: ${e.message}", e)
        }
        (meta ?: throw ModelLoadException("Not a readable GGUF file: $path")).toGgufInfo()
    }

    private fun resolveNGpuLayers(hint: BackendHint): Int = when (hint) {
        BackendHint.Cpu -> 0
        BackendHint.Gpu -> FULL_GPU_OFFLOAD
        BackendHint.Auto -> if (hasGpuBackend()) FULL_GPU_OFFLOAD else 0
    }

    private fun hasGpuBackend(): Boolean = try {
        llamaEngine.availableBackends().any { it.type == "gpu" || it.type == "igpu" }
    } catch (_: Exception) {
        false
    }

    private fun KvCacheType.toLibArg(): String? = when (this) {
        KvCacheType.F16 -> null
        KvCacheType.Q8_0 -> "q8_0"
        KvCacheType.Q4_0 -> "q4_0"
    }

    private fun MessageRole.toLibRole(): String = when (this) {
        MessageRole.System -> "system"
        MessageRole.User -> "user"
        MessageRole.Assistant -> "assistant"
        MessageRole.Tool -> "tool"
    }

    private fun LlamaGgufMetadata.toGgufInfo(): GgufInfo = GgufInfo(
        architecture = architecture,
        name = name,
        quantLabel = quantLabel,
        contextLength = contextLength,
        embeddingLength = embeddingLength,
        blockCount = blockCount,
        paramCount = paramCount,
        vocabSize = vocabSize,
        fileSizeBytes = fileSizeBytes,
        supportsThinking = supportsThinking,
    )

    /**
     * A model loaded on the enclosing [LlamaKtEngineImpl]'s single native
     * handle. Because llama.kt's `LlamaEngine` has exactly one handle per
     * instance, calling [LlamaKtEngineImpl.loadModel] again (or [free])
     * invalidates every [LoadedModel] previously returned by this engine.
     */
    private inner class LoadedModelImpl(override val metadata: LoadedModelInfo) : LoadedModel {

        override suspend fun decode(request: DecodeRequest): Flow<DecodeEvent> = callbackFlow {
            val libMessages = request.messages.map { LlamaChatMessage(it.role.toLibRole(), it.content) }
            val libParams = LlamaSamplingParams(
                nPredict = request.sampling.maxTokens,
                temperature = request.sampling.temperature,
                topK = request.sampling.topK,
                topP = request.sampling.topP,
                minP = request.sampling.minP,
                stopSequences = request.sampling.stopSequences,
            )

            // The blocking completion runs in its own coroutine so this body reaches
            // awaitClose immediately — that's what makes cancellation work: cancelling
            // the flow fires awaitClose, and interrupt() (safe from any thread) unblocks
            // an in-flight completion, prefill included.
            launch(dispatcher) {
                var tokenCount = 0
                var firstTokenAtMs = 0L
                val startMs = System.currentTimeMillis()

                try {
                    val prompt = llamaEngine.formatChat(libMessages, request.sampling.enableThinking)
                    val callback = TokenCallback { token ->
                        if (firstTokenAtMs == 0L) firstTokenAtMs = System.currentTimeMillis()
                        tokenCount++
                        // Runs on `dispatcher`, inside the blocking completion — the one place the
                        // KV fill can be sampled while a generation is under way (TEN-51).
                        refreshKvUsedCells()
                        trySend(DecodeEvent.Token(token))
                    }
                    llamaEngine.completion(prompt, libParams, callback)

                    val elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1)
                    val ttftMs = if (firstTokenAtMs > 0L) firstTokenAtMs - startMs else 0L
                    val tokPerSec = tokenCount * 1000f / elapsedMs
                    trySend(DecodeEvent.Done(DecodeStats(tokenCount, tokPerSec, ttftMs)))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: OutOfMemoryError) {
                    trySend(DecodeEvent.Error(OutOfMemoryException("Out of memory during decode: ${e.message}", e)))
                } catch (e: Exception) {
                    trySend(DecodeEvent.Error(InferenceException("Decode failed: ${e.message}", e)))
                }

                // The interrupt path skips the callback for the token it cut short, so settle on
                // the real figure before anyone reads it.
                refreshKvUsedCells()
                close()
            }

            awaitClose { llamaEngine.interrupt() }
        }.buffer(Channel.UNLIMITED)

        override suspend fun tokenize(text: String): Int = withContext(dispatcher) {
            try {
                llamaEngine.tokenize(text).size
            } catch (e: Exception) {
                throw InferenceException("Tokenize failed: ${e.message}", e)
            }
        }

        override suspend fun free() = withContext(dispatcher) {
            llamaEngine.free()
            // Nothing is resident any more; leaving the last fill behind would have the next
            // reader attribute it to whatever gets loaded next.
            kvUsedCells.set(0)
        }
    }

    private companion object {
        /** `nGpuLayers` value that offloads every layer llama.cpp reports (safe over-count). */
        const val FULL_GPU_OFFLOAD = 99
    }
}
