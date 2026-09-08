package io.github.hokanosekai.tensai.engine

import com.tensai.llamakt.ChatParseCallback
import com.tensai.llamakt.LlamaEngine
import com.tensai.llamakt.LoadProgressCallback
import com.tensai.llamakt.TokenCallback
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
 *
 * **Lifecycle: whoever constructs an engine owns it and must [close] it**
 * (TEN-66). [dispatcher]'s thread belongs to this instance, not to the
 * process: nothing else ever reclaims it, so an engine that is never closed
 * leaks a live thread for as long as the process runs. `RuntimeGraph` builds
 * exactly one and deliberately never closes it — a process-lifetime singleton
 * has nothing to close *to* — but anywhere an engine is built per unit of
 * work, and instrumented tests build one per test, [close] is mandatory.
 *
 * Ownership stays at the construction site and is not passed on:
 * [io.github.hokanosekai.tensai.runtime.InferenceQueue] is *handed* an engine
 * and must never close it, because in the app that same instance also backs
 * `ModelRepository`'s GGUF metadata reads and the save-before-swap hook (see
 * `RuntimeGraph`) — a queue closing what it merely borrows would break two
 * consumers that never asked.
 *
 * Free the model *before* closing the engine. [LoadedModel.free] releases the
 * native handle and has to run on [dispatcher], which [close] shuts down, so a
 * `free()` after a `close()` throws rather than freeing and the weights stay
 * resident until the process dies.
 */
class LlamaKtEngineImpl : LlamaKtEngine, Closeable {

    private val llamaEngine = LlamaEngine()

    private val dispatcher = Executors
        .newSingleThreadExecutor { runnable -> Thread(runnable, "llama-kt-engine") }
        .asCoroutineDispatcher()

    /** Set once by [close], never cleared — an engine does not reopen. */
    private val closed = AtomicBoolean(false)

    /**
     * Shuts [dispatcher] down, ending this engine's thread. Idempotent, safe from any thread, and
     * non-blocking — it does not wait for the thread to actually die.
     *
     * Work already running on [dispatcher] is not aborted: the executor is shut down gracefully,
     * so an in-flight call runs to completion and the thread dies once it returns. A blocking
     * `completion()` is the one that could hold it for minutes, hence the [LlamaEngine.interrupt]
     * first — a flag llama.cpp checks between tokens, safe from any thread (same mechanism the
     * decode's `awaitClose` uses), which ends the generation early instead of leaving the very
     * thread this call exists to reclaim alive until it finishes on its own. Best-effort by
     * nature: it does nothing when no completion is running, and nothing at all once the model has
     * been freed (the native side no-ops on a zero handle).
     *
     * That interrupt is why "free the model first" means *sequenced before*, not merely "at some
     * point": it reads the native handle from the calling thread, and a [LoadedModel.free] running
     * on [dispatcher] at that same moment is busy invalidating it. Awaiting the free before
     * closing is what keeps the two apart — the same hazard the decode's `awaitClose` already
     * lives with.
     *
     * Afterwards, every suspending call on this engine and on any [LoadedModel] it produced fails
     * with [IllegalStateException]. That is deliberately a hard failure rather than the silent
     * alternative: dispatching to a closed [kotlinx.coroutines.ExecutorCoroutineDispatcher]
     * cancels the calling coroutine's job and then reroutes the block to
     * [kotlinx.coroutines.Dispatchers.IO] — the native handle would be touched from an arbitrary
     * pool thread, which is precisely what this class's single-thread dispatcher exists to
     * prevent.
     */
    override fun close() {
        if (closed.getAndSet(true)) return
        llamaEngine.interrupt()
        dispatcher.close()
    }

    /**
     * Runs [block] on [dispatcher], refusing to dispatch onto a closed one. Every suspending entry
     * point goes through this rather than calling `withContext(dispatcher)` itself, so the check
     * cannot be forgotten at one site — and so it happens *before* the dispatch, which is the only
     * point at which it can still be turned into a clean failure (see [close]).
     *
     * Best-effort against a caller closing the engine concurrently with its own in-flight call:
     * nothing here can close that window, and nothing tries to. The owner is expected not to close
     * an engine it is still using.
     *
     * @throws IllegalStateException if [close] has been called.
     */
    private suspend fun <T> onEngineThread(block: suspend CoroutineScope.() -> T): T {
        checkOpen()
        return withContext(dispatcher, block)
    }

    /** See [onEngineThread] — split out for the one caller that dispatches by hand, `decode`. */
    private fun checkOpen() = check(!closed.get()) {
        "LlamaKtEngineImpl is closed — its dispatcher thread is gone and this call cannot run"
    }

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

    override suspend fun loadModel(config: ModelLoadConfig): LoadedModel = onEngineThread {
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
            kvCacheType = config.kvCacheType,
        )

        refreshKvUsedCells()
        LoadedModelImpl(info)
    }

    override fun kvCacheUsedCells(): Int = kvUsedCells.get()

    override suspend fun saveSession(path: String): Int = onEngineThread {
        val savedTokens = try {
            llamaEngine.saveSession(path)
        } catch (e: Exception) {
            throw InferenceException("Failed to save session to $path: ${e.message}", e)
        }
        if (savedTokens < 0) {
            throw InferenceException("Failed to save session to $path")
        }
        savedTokens
    }

    override suspend fun loadSession(path: String): Boolean = onEngineThread {
        val restored = try {
            llamaEngine.loadSession(path) >= 0
        } catch (e: Exception) {
            throw InferenceException("Failed to load session from $path: ${e.message}", e)
        }
        // A restore repopulates the cache without generating a token, so nothing else would ever
        // refresh the published figure: it would keep reading as empty until the next decode's
        // first token. That is not just a stale indicator — TEN-34's restore gate is "is the cache
        // empty", so leaving it at zero here would let a second attempt restore straight over a
        // cache this one just filled.
        if (restored) refreshKvUsedCells()
        restored
    }

    override suspend fun readMetadata(path: String): GgufInfo = onEngineThread {
        val meta = try {
            LlamaEngine.readMetadata(path)
        } catch (e: Exception) {
            throw ModelLoadException("Failed to read GGUF metadata for $path: ${e.message}", e)
        }
        (meta ?: throw ModelLoadException("Not a readable GGUF file: $path")).toGgufInfo()
    }

    /**
     * Which revision of the native KV-shape derivation this build carries — the same number
     * [readMetadata] reports in [GgufInfo.kvShapeVersion], but without needing a file. Feeds
     * [io.github.hokanosekai.tensai.data.models.KvCacheShapeBackfill], which has to know *which*
     * stored shapes are stale before it opens anything.
     *
     * Deliberately not on the [LlamaKtEngine] interface: it is a property of the native library
     * this class binds, not of "an inference engine", and putting it there would make five test
     * fakes carry a stub for a constant none of them has an opinion about. [RuntimeGraph] holds
     * the concrete type anyway.
     *
     * Not `suspend` and not routed through `onEngineThread`: it reads a compile-time constant out
     * of the shared library, touching neither the model handle nor llama.cpp state.
     */
    fun kvShapeVersion(): Long = LlamaEngine.kvShapeVersion()

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
        kvFullElementsPerToken = kvFullElementsPerToken,
        kvSwaElementsPerToken = kvSwaElementsPerToken,
        slidingWindow = slidingWindow,
        kvShapeVersion = kvShapeVersion,
    )

    /**
     * A model loaded on the enclosing [LlamaKtEngineImpl]'s single native
     * handle. Because llama.kt's `LlamaEngine` has exactly one handle per
     * instance, calling [LlamaKtEngineImpl.loadModel] again (or [free])
     * invalidates every [LoadedModel] previously returned by this engine.
     *
     * [free] runs on the enclosing engine's dispatcher, so it has to happen
     * before [LlamaKtEngineImpl.close] — see that method and this engine's
     * class KDoc for what a `free()` on a closed engine does instead (throws,
     * and leaves the weights resident).
     */
    private inner class LoadedModelImpl(override val metadata: LoadedModelInfo) : LoadedModel {

        override suspend fun decode(request: DecodeRequest): Flow<DecodeEvent> = callbackFlow {
            // The one dispatch this class makes by hand rather than through `onEngineThread`, so
            // the closed check is made by hand too (TEN-66). It sits here, in the cold flow's
            // body, rather than in `decode` itself: what matters is that the engine is still open
            // when the `launch(dispatcher)` below actually happens, and between building this flow
            // and collecting it there is an arbitrary amount of time for a close to land.
            checkOpen()
            val libMessages = request.messages.map { LlamaChatMessage(it.role.toLibRole(), it.content) }
            val reasoningBudget = request.sampling.reasoningBudget
            // With a split budget, llama.kt still needs a single nPredict — the whole decode,
            // reasoning and answer together, is held to the sum of the two caps (TEN-61/TEN-69).
            val requestedNPredict = reasoningBudget
                ?.let { it.reasoningMaxTokens + it.responseMaxTokens }
                ?: request.sampling.maxTokens

            // The blocking completion runs in its own coroutine so this body reaches
            // awaitClose immediately — that's what makes cancellation work: cancelling
            // the flow fires awaitClose, and interrupt() (safe from any thread) unblocks
            // an in-flight completion, prefill included.
            launch(dispatcher) {
                var tokenCount = 0
                var firstTokenAtMs = 0L
                val startMs = System.currentTimeMillis()
                val budgetTracker = reasoningBudget?.let { ReasoningBudgetTracker(it) }
                var budgetStopReason: StopReason? = null

                try {
                    val prompt = llamaEngine.formatChat(libMessages, request.sampling.enableThinking)
                    // TEN-65: the prompt itself already spends part of nCtxEffective — bound
                    // nPredict by whatever's actually left rather than handing requestedNPredict to
                    // llama.cpp blind. A long-enough conversation used to leave zero room to answer
                    // into, and llama.cpp would just return having generated nothing — reported,
                    // misleadingly, as a normal complete reply.
                    val promptTokens = llamaEngine.tokenize(prompt).size
                    val available = ContextBudget.available(metadata.nCtxEffective, promptTokens)

                    if (!ContextBudget.hasRoom(available)) {
                        trySend(DecodeEvent.Done(DecodeStats(0, 0f, 0L, StopReason.ContextBudgetExhausted)))
                    } else {
                        val nPredict = requestedNPredict.coerceAtMost(available)
                        val libParams = LlamaSamplingParams(
                            nPredict = nPredict,
                            // TEN-85: the reasoning cap is enforced *inside* llama.cpp, by a
                            // sampler that forces the template's own closing tag out once the cap
                            // is reached and then steps aside — the model writes its answer into
                            // what nPredict has left. This layer used to enforce it by
                            // interrupting the decode, which is why a model that reasoned past its
                            // cap produced a thinking block and no reply at all. Passed unclipped
                            // by `available`: a cap larger than the room left simply never fires,
                            // and clipping it would silently shorten the reasoning of exactly the
                            // conversations that are already short of context.
                            reasoningBudgetTokens = reasoningBudget?.reasoningMaxTokens ?: -1,
                            temperature = request.sampling.temperature,
                            topK = request.sampling.topK,
                            topP = request.sampling.topP,
                            minP = request.sampling.minP,
                            stopSequences = request.sampling.stopSequences,
                        )
                        val callback = TokenCallback { token ->
                            if (firstTokenAtMs == 0L) firstTokenAtMs = System.currentTimeMillis()
                            tokenCount++
                            // Runs on `dispatcher`, inside the blocking completion — the one place the
                            // KV fill can be sampled while a generation is under way (TEN-51).
                            refreshKvUsedCells()
                            trySend(DecodeEvent.Token(token))
                            // interrupt() is a plain flag write llama.cpp checks between tokens, safe
                            // to call from this same dispatcher thread mid-callback — it unblocks
                            // completion() below rather than tearing down the flow, so Done still
                            // fires with real stats (TEN-61, same mechanism TEN-60's `stop` relies on).
                            // Since TEN-85 the tracker only ever fires on the decode's *total*, so
                            // this is the last resort it was always meant to be: `nPredict` above
                            // says the same thing to llama.cpp, and cutting a reply short is
                            // something no per-phase cap does any more.
                            if (budgetTracker != null && budgetStopReason == null) {
                                budgetTracker.onToken(token)?.let { reason ->
                                    budgetStopReason = reason
                                    llamaEngine.interrupt()
                                }
                            }
                        }
                        // TEN-47: llama.cpp parses the reply with the parser it generated from the
                        // model's own chat template, so reasoning is recognised whatever the
                        // template marks it with. Best-effort by contract (see [ChatParseCallback]):
                        // a decode can legitimately produce none of these, hence
                        // `DecodeEvent.Parsed`'s "consumers must cope with this never arriving".
                        //
                        // TEN-69 also feeds it to the budget tracker, which is the only source that
                        // can tell reasoning from answer for a template that doesn't use `<think>`,
                        // or for a model that skipped reasoning altogether. It stays a *separate*
                        // callback from the TokenCallback above rather than being folded into it:
                        // the tracker's counting must remain one call per sampled token on the raw
                        // stream (TEN-61), and this one fires at most once per token and not at all
                        // for a token that left the parse unchanged. Phase in, counting out.
                        //
                        // Runs on `dispatcher` inside the same blocking completion as the token
                        // callback, so the tracker is still only ever touched from one thread.
                        val chatParseCallback = ChatParseCallback { content, reasoningContent ->
                            budgetTracker?.onParsed(content, reasoningContent)
                            trySend(DecodeEvent.Parsed(content, reasoningContent.ifEmpty { null }))
                        }
                        llamaEngine.completion(prompt, libParams, callback, chatParseCallback)

                        val elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1)
                        val ttftMs = if (firstTokenAtMs > 0L) firstTokenAtMs - startMs else 0L
                        val tokPerSec = tokenCount * 1000f / elapsedMs
                        // completion() returns Unit, so the reason has to be inferred: a budget
                        // tracked above wins if it fired; otherwise reaching nPredict (TEN-65: the
                        // smaller of requestedNPredict and what the context actually left available)
                        // is the one other ending we can recognise from out here (TEN-60/TEN-61).
                        // Compared against nPredict rather than request.sampling.maxTokens because
                        // TEN-65 may have clipped the cap below what the caller asked for, and that's
                        // still a real cutoff, not a Complete.
                        val stopReason = budgetStopReason
                            ?: if (tokenCount < nPredict) {
                                StopReason.Complete
                            } else if (reasoningBudget == null) {
                                StopReason.TokenBudget
                            } else {
                                budgetTracker!!.currentPhaseStopReason()
                            }
                        // Reached on a cancellation too, and deliberately: `awaitClose`'s
                        // interrupt() unblocks completion() rather than throwing, so the normal
                        // path runs to here with the channel already closed and this `trySend`
                        // silently fails. That is the intended outcome (a cancelled decode owes its
                        // collector no terminal event), and it is why this is `trySend` and not
                        // `send`. Not dead code: it is the one place a completed decode reports.
                        trySend(DecodeEvent.Done(DecodeStats(tokenCount, tokPerSec, ttftMs, stopReason)))
                    }
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

        override suspend fun tokenize(text: String): Int = onEngineThread {
            try {
                llamaEngine.tokenize(text).size
            } catch (e: Exception) {
                throw InferenceException("Tokenize failed: ${e.message}", e)
            }
        }

        override suspend fun free() = onEngineThread {
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
