package io.github.hokanosekai.tensai.runtime

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
    val inferenceQueue: InferenceQueue by lazy { InferenceQueue(LlamaKtEngineImpl()) }

    /**
     * Process-wide scope for observers that must outlive any single
     * `Activity` — currently `TensaiApp`'s start/stop observer for
     * [InferenceService] and that service's own notification-rendering
     * observer of [inferenceQueue]'s state. [SupervisorJob] so a failure in
     * one observer can't cancel the others.
     */
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
