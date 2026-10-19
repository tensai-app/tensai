package io.github.tensaiapp.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tensai.llamakt.ChatMessage
import com.tensai.llamakt.LlamaEngine
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import org.junit.runner.RunWith

/**
 * TEN-70: strings cross the JNI boundary as real UTF-8, not JNI's modified UTF-8 (CESU-8).
 *
 * A contract test, not a regression test on ART. The conversion it was written for passes it
 * too (measured 2026-10-05 by reverting tensai_jni.cpp alone): ART emits and accepts 4-byte
 * UTF-8 for supplementary characters, against the JNI spec. It would catch a runtime that
 * follows the spec, or a later change back to GetStringUTFChars on one.
 *
 * Talks to [LlamaEngine] directly: [LoadedModel.tokenize] only returns a count
 * and there is no detokenize API, so the checks are properties that hold for any vocab rather
 * than exact token ids. Same model and skip rule as [EngineSmokeTest].
 */
@RunWith(AndroidJUnit4::class)
class JniUtf8BoundaryTest {

    /** Same backstop as [EngineSmokeTest.globalTimeout]. */
    @get:Rule
    val globalTimeout: Timeout = Timeout.seconds(90)

    private val modelPath: String by lazy {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)
        File(dir, "test-model.gguf").absolutePath
    }

    @Test
    fun emojiSurvivesFormatChatAndTokenizesAsFourByteUtf8() {
        assumeTrue(
            "No test model at $modelPath, skipping (adb push a GGUF there to run this test)",
            File(modelPath).exists(),
        )
        val engine = LlamaEngine()
        engine.load(modelPath, nGpuLayers = 0, nCtx = 512)
        try {
            // In through jstring_to_std, copied verbatim by the chat template, out through
            // std_to_jstring: 2-, 3- and 4-byte characters must all come back intact.
            val content = "smile 🙂 é日 ok"
            val prompt = engine.formatChat(listOf(ChatMessage("user", content)), enableThinking = false)
            assertTrue("formatted prompt lost the message text: $prompt", prompt.contains(content))

            // nativeTokenize does not add BOS. U+1F642 is 4 bytes of UTF-8: a byte-level BPE vocab
            // (Qwen, Llama 3) yields at most one token per byte, a SentencePiece one either a whole
            // piece or <0xXX> byte fallback tokens, plus possibly one dummy-prefix token. So <= 5.
            // The old CESU-8 input was 6 bytes no tokenizer ever saw merged (ED A0 BD ED B9 82),
            // which comes out at 6 or more tokens on both kinds of vocab.
            val emojiTokens = engine.tokenize("🙂")
            assertTrue(
                "U+1F642 tokenized into ${emojiTokens.size} tokens (${emojiTokens.toList()}), " +
                    "expected 1..5: the tokenizer is likely seeing CESU-8",
                emojiTokens.size in 1..5,
            )
        } finally {
            engine.free()
        }
    }
}
