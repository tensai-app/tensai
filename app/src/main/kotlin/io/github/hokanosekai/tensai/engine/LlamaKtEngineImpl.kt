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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.tensai.llamakt.ChatMessage as LlamaChatMessage
import com.tensai.llamakt.GgufMetadata as LlamaGgufMetadata
import com.tensai.llamakt.SamplingParams as LlamaSamplingParams
import com.tensai.llamakt.LoadStage as LlamaLoadStage

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
 * "Every such call" means every call that touches the handle. The two that
 * don't — [readMetadata], a static header read of a *file*, and
 * [kvShapeVersion], a compile-time constant — stay off [dispatcher] on
 * purpose: serializing them buys no safety and makes them wait behind
 * whatever inference is running (TEN-84). Each says so at its own site.
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
     * Runs [block] on [dispatcher], refusing to dispatch onto a closed one. Every entry point that
     * touches the native handle goes through this rather than calling `withContext(dispatcher)`
     * itself, so the check cannot be forgotten at one site — and so it happens *before* the
     * dispatch, which is the only point at which it can still be turned into a clean failure (see
     * [close]). [readMetadata] touches no handle and does its own [checkOpen] instead; see there.
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
        val libProgress = if (config.onProgress == null && config.onStage == null) {
            null
        } else {
            object : LoadProgressCallback {
                override fun onProgress(progress: Float): Boolean {
                    config.onProgress?.invoke(progress)
                    return true
                }

                override fun onStage(stage: Int) {
                    // An id this build does not know is a newer native lib: ignore it.
                    val mapped = when (LlamaLoadStage.fromId(stage)) {
                        LlamaLoadStage.Weights -> LoadStage.Weights
                        LlamaLoadStage.Context -> LoadStage.Context
                        LlamaLoadStage.Warmup -> LoadStage.Warmup
                        LlamaLoadStage.Ready -> LoadStage.Ready
                        null -> return
                    }
                    config.onStage?.invoke(mapped)
                }
            }
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
            name = config.name?.takeIf { it.isNotBlank() }
                ?: meta?.name?.takeIf { it.isNotBlank() }
                ?: File(config.path).name,
            nCtxEffective = config.nCtx,
            backend = backend,
            kvCacheType = config.kvCacheType,
            modelId = config.modelId,
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

    /**
     * **Not routed through [onEngineThread]** (TEN-84), unlike every other suspending call here.
     * [LlamaEngine.readMetadata] is `static`: it opens the file at [path], reads its GGUF header
     * with `no_alloc` and closes it (`nativeReadGgufMetadata` in `tensai_jni.cpp` takes a `jclass`,
     * not this instance's native handle). It touches no model, no context and no llama.cpp state
     * of this engine's, so [dispatcher] — which exists to serialize calls that *do* — has nothing
     * to protect here.
     *
     * What it had instead was a queue. [dispatcher] is one thread, and the app shares this engine
     * between [io.github.hokanosekai.tensai.runtime.InferenceQueue] and `ModelRepository`'s
     * metadata reads (see `RuntimeGraph`): a header read is milliseconds of I/O, but issued while
     * a generation is running it waited behind the whole generation — minutes, on a long reply —
     * and the import screen sat there saying nothing. [Dispatchers.IO] is where a blocking file
     * read belongs, and it makes the wait proportional to the work again.
     *
     * [checkOpen] stays. It is no longer needed for safety (the closed-dispatcher reroute
     * [onEngineThread]'s KDoc describes cannot happen on [Dispatchers.IO]), but [close]'s contract
     * is that every suspending call on this engine fails afterwards, and one entry point quietly
     * carrying on would be a worse surprise than the refusal.
     */
    override suspend fun readMetadata(path: String): GgufInfo {
        checkOpen()
        return withContext(Dispatchers.IO) {
            val meta = try {
                LlamaEngine.readMetadata(path)
            } catch (e: Exception) {
                throw ModelLoadException("Failed to read GGUF metadata for $path: ${e.message}", e)
            }
            (meta ?: throw ModelLoadException("Not a readable GGUF file: $path")).toGgufInfo()
        }
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
                var firstTokenAtMs = 0L
                val startMs = System.currentTimeMillis()
                val budgetTracker = reasoningBudget?.let { ReasoningBudgetTracker(it) }
                var budgetStopReason: StopReason? = null

                try {
                    val prompt = llamaEngine.formatChat(libMessages, request.sampling.enableThinking)
                    // TEN-113: a blank prompt for a non-empty conversation is a failed render, not
                    // a prompt. Thrown into the catch below (Error event) rather than tokenized and
                    // decoded from an empty context.
                    BlankPromptGuard.check(libMessages.size, prompt)?.let { throw it }
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
                        // Whether TEN-65's clip above actually bit. Kept because reaching a cap the
                        // caller asked for and reaching one the context window imposed are two
                        // different endings for the reader (TEN-79) — see
                        // [StopReason.ContextBudgetClipped].
                        val clipped = nPredict < requestedNPredict
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
                            repeatPenalty = request.sampling.repeatPenalty,
                            repeatLastN = RepeatPenaltyLastN,
                            stopSequences = request.sampling.stopSequences,
                        )
                        val callback = TokenCallback { token ->
                            // Only the *first* callback is worth a timestamp: this fires once per
                            // flushable piece of text, so counting the calls counts something that
                            // is not tokens (TEN-79, see the `completion` call below).
                            if (firstTokenAtMs == 0L) firstTokenAtMs = System.currentTimeMillis()
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
                        // Last look before committing to a call that can hold this thread for
                        // minutes. Everything above (`formatChat`, `tokenize`) is a blocking
                        // native call with no suspension point, so a cancellation landing during
                        // it is not delivered anywhere — and `awaitClose`'s interrupt(), the only
                        // other thing that fires, sets a flag that `completion` clears on entry
                        // when it rewinds the context. A stop arriving in that window is simply
                        // erased: the decode runs to the end, the "stop" button does nothing
                        // visible, and the next load waits out a generation nobody wanted. There
                        // is no second interrupt to try — the flag was consumed — so the check has
                        // to happen before the call rather than be retried after it.
                        coroutineContext.ensureActive()
                        // TEN-79: llama.cpp's own count of the tokens it sampled — the *only*
                        // figure comparable to nPredict. `tokenCount` is not it and never was: the
                        // token callback fires once per flushable piece of text, not once per
                        // token, so it under-counts every decode that held a token back (an
                        // accented character split across two tokens is enough — see
                        // `LlamaEngine.completion`'s @return). Comparing that undercount against
                        // nPredict is what let a reply cut off at its budget be reported as
                        // `Complete`, which is the same thing TEN-60 set out to fix.
                        val tokensPredicted =
                            llamaEngine.completion(prompt, libParams, callback, chatParseCallback)

                        // A negative count is not a count (TEN-91): a C++ exception escaped the
                        // decode and `tensai_jni.cpp`'s JNI guard answered with a sentinel rather
                        // than letting it unwind into the VM and kill the process. Before that
                        // guard existed there was nothing to handle here, because there was no
                        // process left to handle it.
                        //
                        // Reported as an error, not as a `Done`, because it is one. `-1` is below
                        // every `nPredict`, so letting it through to `stopReason` below would have
                        // read as `StopReason.Complete` and told the user their reply had finished
                        // normally. Tokens already streamed stay streamed — the collector keeps
                        // whatever text arrived, and loses only the ending, which is the same deal
                        // the OOM and exception branches below offer.
                        //
                        // An `if/else` rather than an early return: the `refreshKvUsedCells()` and
                        // `close()` below the `try` are what end this flow, and returning out of
                        // the coroutine would skip both.
                        if (tokensPredicted < 0) {
                            trySend(
                                DecodeEvent.Error(
                                    InferenceException(
                                        "Decode failed in the native engine " +
                                            "(completion returned $tokensPredicted)",
                                    ),
                                ),
                            )
                        } else {
                            val elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1)
                            val ttftMs = if (firstTokenAtMs > 0L) firstTokenAtMs - startMs else 0L
                            val tokPerSec = generationTokPerSec(tokensPredicted, elapsedMs, ttftMs)
                            val stopReason = DecodeOutcome.stopReason(
                                budgetStopReason = budgetStopReason,
                                tokensPredicted = tokensPredicted,
                                nPredict = nPredict,
                                clipped = clipped,
                                phaseStopReason = budgetTracker?.currentPhaseStopReason(),
                            )
                            // Reached on a cancellation too, and deliberately: `awaitClose`'s
                            // interrupt() unblocks completion() rather than throwing, so the normal
                            // path runs to here with the channel already closed and this `trySend`
                            // silently fails. That is the intended outcome (a cancelled decode owes
                            // its collector no terminal event), and it is why this is `trySend` and
                            // not `send`. Not dead code: it is the one place a completed decode
                            // reports.
                            trySend(DecodeEvent.Done(DecodeStats(tokensPredicted, tokPerSec, ttftMs, stopReason)))
                        }
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

        /**
         * TEN-88. [WarmUpPrompt] decides *what* to prefill; this decides where it runs: formatting,
         * the room check and the prefill are all one `async(dispatcher)`, because each is a
         * blocking native call that owns [dispatcher] for as long as it takes.
         *
         * Cancelling the caller interrupts the prefill, same mechanism as [decode]'s `awaitClose`,
         * and then *waits for it to return*: [coroutineScope] does not complete until `work` has,
         * so a caller that cancels and joins (the queue does, before a generation, a swap or a
         * save) really does get the engine thread back, and never starts its own call behind a
         * prefill that is still decoding. The `ensureActive()` before the native call is
         * [decode]'s last-look for the same reason: `nativePrefill` clears the interrupt flag on
         * entry, so a cancel landing during the formatting above it would be erased.
         *
         * [refreshKvUsedCells] runs on every way out, interrupted included: a prefill moves the
         * cache without a single token callback to refresh it, and the published figure is what
         * the restore gate ("is the cache empty") and the save skip ("same token count") read.
         */
        override suspend fun warmUp(messages: List<ChatMessage>, enableThinking: Boolean): Boolean {
            checkOpen()
            return coroutineScope {
                val work = async(dispatcher) {
                    try {
                        val prompt = WarmUpPrompt.build(messages) { rendered ->
                            llamaEngine.formatChat(
                                rendered.map { LlamaChatMessage(it.role.toLibRole(), it.content) },
                                enableThinking,
                            )
                        } ?: return@async false
                        // The same room test a decode makes (TEN-65), on the same count. A prompt
                        // that leaves nothing to answer into is not worth caching, and the native
                        // side would decline it anyway before touching the cache.
                        val promptTokens = llamaEngine.tokenize(prompt).size
                        if (!ContextBudget.hasRoom(ContextBudget.available(metadata.nCtxEffective, promptTokens))) {
                            return@async false
                        }
                        ensureActive()
                        val resident = llamaEngine.prefill(prompt)
                        // Negative is the native side failing, not declining: a failure, not a skip.
                        if (resident < 0) throw InferenceException("Prefill failed in the native engine (returned $resident)")
                        resident > 0
                    } finally {
                        refreshKvUsedCells()
                    }
                }
                try {
                    work.await()
                } catch (e: CancellationException) {
                    // Repeated, not once: see [interruptUntilDone].
                    interruptUntilDone(work) { llamaEngine.interrupt() }
                    throw e
                }
            }
        }

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

        /** `penalty_last_n`: llama.cpp's default window for the repeat penalty (TEN-114). */
        const val RepeatPenaltyLastN = 64
    }
}
