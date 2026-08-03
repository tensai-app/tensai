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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
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

        val engine = LlamaKtEngineImpl()
        val info = engine.readMetadata(modelPath)
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
            queue.loadModel(
                ModelLoadConfig(
                    path = model.filePath,
                    nCtx = plan.nCtx,
                    // CPU, explicitly. The default (Auto) resolves to full Vulkan offload on
                    // this device, and Vulkan buffers are non-evictable — chained with the
                    // rest of the suite this froze the whole device once (2026-08-03).
                    // Device tests always pin CPU; GPU behavior is TEN-43's manual QA pass.
                    backend = BackendHint.Cpu,
                    kvCacheType = plan.kvCacheType,
                ),
            )

            assertTrue(
                "expected ModelReady after load, got ${queue.state.value}",
                queue.state.value is QueueState.ModelReady,
            )
        } finally {
            // Never leave 400MB of weights resident after this test, pass or fail.
            queue.unloadModel()
        }
    }
}
