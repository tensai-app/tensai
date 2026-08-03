package io.github.hokanosekai.tensai.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented smoke test for [LlamaKtEngineImpl] against a real GGUF.
 *
 * Requires a model pushed to `/data/local/tmp/test-model.gguf` on the
 * device — skipped (not failed) when absent, since no model ships with
 * the repo:
 *
 * ```
 * adb push some-small-model.gguf /data/local/tmp/test-model.gguf
 * ```
 */
@RunWith(AndroidJUnit4::class)
class EngineSmokeTest {

    private val modelPath = "/data/local/tmp/test-model.gguf"

    @Test
    fun streamsTokensAndCompletesWithStats() = runBlocking {
        assumeTrue(
            "No test model at $modelPath — skipping (adb push a GGUF there to run this test)",
            File(modelPath).exists(),
        )

        val engine: LlamaKtEngine = LlamaKtEngineImpl()
        val model = engine.loadModel(
            ModelLoadConfig(path = modelPath, nCtx = 512, backend = BackendHint.Cpu),
        )

        try {
            val request = DecodeRequest(
                messages = listOf(ChatMessage(role = MessageRole.User, content = "Say hi")),
                sampling = SamplingConfig(maxTokens = 16),
            )

            val events = model.decode(request).toList()

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
            model.free()
        }
    }
}
