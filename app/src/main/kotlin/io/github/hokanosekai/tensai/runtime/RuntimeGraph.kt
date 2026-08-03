package io.github.hokanosekai.tensai.runtime

import io.github.hokanosekai.tensai.engine.LlamaKtEngineImpl

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
}
