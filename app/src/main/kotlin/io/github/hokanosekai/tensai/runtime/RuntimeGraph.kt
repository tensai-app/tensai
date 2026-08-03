package io.github.hokanosekai.tensai.runtime

import android.content.Context
import io.github.hokanosekai.tensai.compat.DeviceProfiler
import io.github.hokanosekai.tensai.data.models.ModelRepository
import io.github.hokanosekai.tensai.data.models.TensaiDb
import io.github.hokanosekai.tensai.engine.LlamaKtEngineImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Pragmatic, framework-free composition root for V1.
 *
 * A single lazily-constructed [InferenceQueue] shared by every client in
 * the app (Chat UI in V1, the API server in V2 — see [InferenceQueue]'s
 * KDoc for why that sharing is safe). Replace this with a real DI solution
 * (Hilt/Koin) if the object graph grows beyond this one dependency.
 */
object RuntimeGraph {

    private lateinit var appContext: Context

    /** Must be called once, before any other member is touched — see `TensaiApp.onCreate`. */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Backs both [inferenceQueue] and [ModelRepository]'s GGUF metadata reads (see
     * `LlamaKtEngine.readMetadata`'s KDoc — it's a header-only read of the *file*, not of whatever
     * this instance currently has loaded, so sharing it here is safe). It does mean a metadata
     * read made while [inferenceQueue] is mid-load or mid-generate is serialized behind that work
     * on the engine's single-thread dispatcher rather than running immediately — acceptable for
     * V1's import flow (no generation is expected to be active while importing a new model), but
     * worth revisiting if that stops holding.
     */
    private val llamaEngine: LlamaKtEngineImpl by lazy { LlamaKtEngineImpl() }

    val inferenceQueue: InferenceQueue by lazy { InferenceQueue(llamaEngine) }

    /**
     * Process-wide scope for observers that must outlive any single
     * `Activity` — currently `TensaiApp`'s start/stop observer for
     * [InferenceService] and that service's own notification-rendering
     * observer of [inferenceQueue]'s state. [SupervisorJob] so a failure in
     * one observer can't cancel the others.
     */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** See [DeviceProfiler]'s KDoc for what's cached vs. re-read on each [DeviceProfiler.profile] call. */
    val deviceProfiler: DeviceProfiler by lazy { DeviceProfiler(appContext) }

    private val database: TensaiDb by lazy { TensaiDb.build(appContext) }

    val modelRepository: ModelRepository by lazy {
        ModelRepository(
            context = appContext,
            modelDao = database.modelDao(),
            readMetadata = llamaEngine::readMetadata,
            currentLoadedModelName = {
                (inferenceQueue.state.value as? QueueState.ModelReady)?.info?.name
            },
        )
    }
}
