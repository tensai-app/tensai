package io.github.hokanosekai.tensai.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/**
 * Instrumented smoke test for [LlamaKtEngineImpl] against a real GGUF.
 *
 * Requires a model in the app's external files dir — skipped (not
 * failed) when absent, since no model ships with the repo. Note:
 * `/data/local/tmp` is NOT usable here (SELinux `shell_data_file`, the
 * app domain can't read it regardless of mode bits):
 *
 * ```
 * adb push some-small-model.gguf \
 *   /sdcard/Android/data/io.github.hokanosekai.tensai/files/test-model.gguf
 * ```
 */
@RunWith(AndroidJUnit4::class)
class EngineSmokeTest {

    /**
     * Backstop for a coroutine stuck inside a non-cancellable native call ([withTimeout] can't
     * catch that — see [io.github.hokanosekai.tensai.runtime.InferenceQueueTest]'s `globalTimeout`
     * for the full TEN-55/TEN-17 rationale and why 90s). Runs this test on a separate thread and
     * fails it from the outside on timeout; does not unstick the native call itself.
     */
    @get:Rule
    val globalTimeout: Timeout = Timeout.seconds(90)

    private val modelPath: String by lazy {
        val dir = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().targetContext.getExternalFilesDir(null)
        File(dir, "test-model.gguf").absolutePath
    }

    @Test
    fun streamsTokensAndCompletesWithStats() = runBlocking {
        assumeTrue(
            "No test model at $modelPath — skipping (adb push a GGUF there to run this test)",
            File(modelPath).exists(),
        )

        val engine: LlamaKtEngine = LlamaKtEngineImpl()
        val model = withTimeout(LOAD_TIMEOUT_MS) {
            engine.loadModel(ModelLoadConfig(path = modelPath, nCtx = 512, backend = BackendHint.Cpu))
        }

        try {
            val request = DecodeRequest(
                messages = listOf(ChatMessage(role = MessageRole.User, content = "Say hi")),
                sampling = SamplingConfig(maxTokens = 16),
            )

            val events = withTimeout(GENERATE_TIMEOUT_MS) { model.decode(request).toList() }

            val tokenEvents = events.filterIsInstance<DecodeEvent.Token>()
            val doneEvents = events.filterIsInstance<DecodeEvent.Done>()
            val errorEvents = events.filterIsInstance<DecodeEvent.Error>()

            assertTrue("expected no error events, got $errorEvents", errorEvents.isEmpty())
            assertTrue("expected at least one token event", tokenEvents.isNotEmpty())
            assertTrue("expected exactly one done event, got ${doneEvents.size}", doneEvents.size == 1)

            val stats = doneEvents.single().stats
            assertTrue("tokensGenerated should be > 0, was ${stats.tokensGenerated}", stats.tokensGenerated > 0)
            assertTrue("tokPerSec should be > 0, was ${stats.tokPerSec}", stats.tokPerSec > 0f)
        } finally {
            // Bounded like the load above (TEN-55) — a `free()` that never returns must fail this
            // test rather than freeze the runner.
            withTimeout(UNLOAD_TIMEOUT_MS) { model.free() }
        }
    }

    private companion object {
        /** See [io.github.hokanosekai.tensai.runtime.InferenceQueueTest]'s `LOAD_TIMEOUT_MS` for rationale. */
        const val LOAD_TIMEOUT_MS = 60_000L

        /** See [io.github.hokanosekai.tensai.runtime.InferenceQueueTest]'s `UNLOAD_OR_CANCEL_TIMEOUT_MS`. */
        const val UNLOAD_TIMEOUT_MS = 30_000L

        /** See [io.github.hokanosekai.tensai.runtime.InferenceQueueTest]'s `GENERATE_TIMEOUT_MS`. */
        const val GENERATE_TIMEOUT_MS = 60_000L
    }
}
