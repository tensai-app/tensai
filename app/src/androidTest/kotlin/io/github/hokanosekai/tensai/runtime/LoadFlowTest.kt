package io.github.hokanosekai.tensai.runtime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hokanosekai.tensai.compat.DeviceProfiler
import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.engine.BackendHint
import io.github.hokanosekai.tensai.engine.LlamaKtEngineImpl
import io.github.hokanosekai.tensai.engine.ModelLoadConfig
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/**
 * End-to-end instrumented test for the TEN-30 loading flow: reads a real GGUF's header, builds
 * the [ModelEntity] the Room row would hold, derives the adaptive [LoadPolicy] plan for the
 * current device the same way
 * [io.github.hokanosekai.tensai.ui.screens.loading.LoadModelViewModel] does, and loads it through
 * the real [InferenceQueue] — same test-model convention as `EngineSmokeTest`, skipped (not
 * failed) when absent:
 *
 * ```
 * adb push some-small-model.gguf \
 *   /sdcard/Android/data/io.github.hokanosekai.tensai/files/test-model.gguf
 * ```
 */
@RunWith(AndroidJUnit4::class)
class LoadFlowTest {

    /**
     * Backstop for a coroutine stuck inside a non-cancellable native call ([withTimeout] can't
     * catch that — see [InferenceQueueTest]'s `globalTimeout` for the full TEN-55/TEN-17 rationale
     * and why 90s). Runs this test on a separate thread and fails it from the outside on timeout;
     * does not unstick the native call itself.
     */
    @get:Rule
    val globalTimeout: Timeout = Timeout.seconds(90)

    private val modelPath: String by lazy {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        File(dir, "test-model.gguf").absolutePath
    }

    @Test
    fun loadsViaAdaptivePolicyAndReachesModelReady() = runBlocking {
        assumeTrue(
            "No test model at $modelPath — skipping (adb push a GGUF there to run this test)",
            File(modelPath).exists(),
        )

        // TEN-66: this test builds an engine of its own, so this test closes it — an engine's
        // dispatcher thread outlives every model loaded on it and nothing else ever reclaims it.
        // Wraps everything from here down: a failure in the metadata read below must not leak the
        // thread either.
        val engine = LlamaKtEngineImpl()
        try {
            val info = withTimeout(READ_METADATA_TIMEOUT_MS) { engine.readMetadata(modelPath) }
            val model = ModelEntity(
                id = UUID.randomUUID().toString(),
                displayName = info.name.ifBlank { "test-model" },
                fileName = "test-model.gguf",
                filePath = modelPath,
                sizeBytes = info.fileSizeBytes,
                architecture = info.architecture,
                quantLabel = info.quantLabel,
                paramCount = info.paramCount,
                contextLength = info.contextLength,
                embeddingLength = info.embeddingLength,
                blockCount = info.blockCount,
                supportsThinking = info.supportsThinking,
                importedAt = 0L,
            )

            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val profile = DeviceProfiler(context).profile()
            val plan = LoadPolicy.compute(model, profile)

            val queue = InferenceQueue(engine)
            try {
                withTimeout(LOAD_TIMEOUT_MS) {
                    queue.loadModel(
                        ModelLoadConfig(
                            path = model.filePath,
                            nCtx = plan.nCtx,
                            // CPU, explicitly. The default (Auto) resolves to full Vulkan offload
                            // on this device, and Vulkan buffers are non-evictable — chained with
                            // the rest of the suite this froze the whole device once (2026-08-03).
                            // Device tests always pin CPU; GPU behavior is TEN-43's manual QA pass.
                            backend = BackendHint.Cpu,
                            kvCacheType = plan.kvCacheType,
                        ),
                    )
                }

                assertTrue(
                    "expected ModelReady after load, got ${queue.state.value}",
                    queue.state.value is QueueState.ModelReady,
                )
            } finally {
                // Never leave 400MB of weights resident after this test, pass or fail. Bounded like
                // the load above (TEN-55) — a stuck unload must fail loudly, not freeze the runner.
                withTimeout(UNLOAD_TIMEOUT_MS) { queue.unloadModel() }
            }
        } finally {
            // After the unload, never before: releasing the model runs on the very dispatcher this
            // shuts down. The queue is handed this engine, it does not own it — closing is the
            // construction site's job, i.e. here.
            engine.close()
        }
    }

    private companion object {
        /** Header-only GGUF read on the same single-thread engine dispatcher as [LOAD_TIMEOUT_MS]'s
         *  load; normally a few milliseconds, so this is a wide margin, not a tight bound. */
        const val READ_METADATA_TIMEOUT_MS = 15_000L

        /** See [io.github.hokanosekai.tensai.runtime.InferenceQueueTest]'s `LOAD_TIMEOUT_MS` for rationale. */
        const val LOAD_TIMEOUT_MS = 60_000L

        /** See [io.github.hokanosekai.tensai.runtime.InferenceQueueTest]'s `UNLOAD_OR_CANCEL_TIMEOUT_MS`. */
        const val UNLOAD_TIMEOUT_MS = 30_000L
    }
}
