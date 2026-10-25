package io.github.tensaiapp.bench

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests of the debug decode curve's pure parts: which points fit, the filler's size, the lines it logs. */
class DecodeCurveTest {

    private val setup = CurveSetup(model = "Qwen3 1.7B", backend = "CPU: ARM", nCtx = 4096, nThreads = 2, flashAttn = "auto", kv = "f16")

    @Test
    fun `points that do not fit the context are left out`() {
        assertEquals(listOf(0, 512, 1024, 2048), DecodeCurve.lengthsFor(4096))
        assertEquals(DecodeCurve.Lengths, DecodeCurve.lengthsFor(8192))
        assertEquals(listOf(0), DecodeCurve.lengthsFor(512))
        assertEquals(emptyList<Int>(), DecodeCurve.lengthsFor(200))
    }

    @Test
    fun `the largest point fits with its decode and headroom`() {
        assertEquals(4096, DecodeCurve.lengthsFor(4096 + DecodeCurve.EvalTokens + DecodeCurve.HeadroomTokens).last())
    }

    @Test
    fun `filler paragraphs round to the target`() {
        assertEquals(0, DecodeCurve.paragraphsFor(0, 60))
        assertEquals(9, DecodeCurve.paragraphsFor(512, 60))
        assertEquals(34, DecodeCurve.paragraphsFor(2048, 60))
        assertEquals(68, DecodeCurve.paragraphsFor(4096, 60))
    }

    @Test
    fun `prompt opens with its nonce, then the filler, then the instruction`() {
        val prompt = DecodeCurve.prompt("ab12cd34", paragraphs = 3)
        assertTrue(prompt.startsWith("[ab12cd34]"))
        assertTrue(prompt.endsWith(DecodeCurve.Instruction))
        assertEquals(3, prompt.windowed(DecodeCurve.Paragraph.length).count { it == DecodeCurve.Paragraph })
        assertEquals("[x]\n\n" + DecodeCurve.Instruction, DecodeCurve.prompt("x", paragraphs = 0))
    }

    @Test
    fun `two prompts of the same size differ from their first token on`() {
        val a = DecodeCurve.prompt("11111111", 2)
        val b = DecodeCurve.prompt("22222222", 2)
        assertEquals(1, a.commonPrefixWith(b).length)
    }

    @Test
    fun `rate is tokens per second rounded, null without a figure`() {
        assertEquals(8.0, DecodePoint.rate(128, 16_000)!!, 0.0)
        assertEquals(33.33, DecodePoint.rate(100, 3_000)!!, 0.0)
        assertEquals(null, DecodePoint.rate(0, 1_000))
        assertEquals(null, DecodePoint.rate(10, 0))
    }

    @Test
    fun `decode line carries the point and the load setup`() {
        val line = DecodePoint(1024, 1050, 128, decodeTps = 6.5, prefillTps = 40.2, ttftMs = 26_000, stop = "TokenBudget").toLogLine(setup)
        val json = Json.parseToJsonElement(line).jsonObject
        assertEquals("decode", json.getValue("event").jsonPrimitive.content)
        assertEquals(1024, json.getValue("ctx").jsonPrimitive.content.toInt())
        assertEquals(1050, json.getValue("prompt_tokens").jsonPrimitive.content.toInt())
        assertEquals(128, json.getValue("eval_tokens").jsonPrimitive.content.toInt())
        assertEquals(6.5, json.getValue("decode_tps").jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(40.2, json.getValue("prefill_tps").jsonPrimitive.content.toDouble(), 0.0)
        assertEquals(2, json.getValue("n_threads").jsonPrimitive.content.toInt())
        assertEquals("auto", json.getValue("flash_attn").jsonPrimitive.content)
        assertEquals("f16", json.getValue("kv").jsonPrimitive.content)
        assertTrue("error" !in json)
    }

    @Test
    fun `summary lists every point and the skipped lengths`() {
        val points = listOf(
            DecodePoint(0, 40, 128, 8.0, 30.0, 1_300, "TokenBudget"),
            DecodePoint(2048, 2080, 128, 5.1, 35.0, 59_000, "TokenBudget"),
        )
        val json = Json.parseToJsonElement(decodeSummaryLine(setup, points, skipped = listOf(4096))).jsonObject
        assertEquals("decode_summary", json.getValue("event").jsonPrimitive.content)
        assertEquals(2, json.getValue("points").jsonArray.size)
        assertEquals(listOf("4096"), json.getValue("skipped").jsonArray.map { it.jsonPrimitive.content })

        val lines = decodeDisplayLines(setup, points, skipped = listOf(4096))
        assertEquals("Qwen3 1.7B", lines[0])
        assertTrue(lines[1], lines[1].contains("n_threads 2") && lines[1].contains("kv f16"))
        assertTrue(lines[3], lines[3].trim().startsWith("0 ") && lines[3].contains("8.00"))
        assertTrue(lines[4], lines[4].trim().startsWith("2048 ") && lines[4].contains("5.10"))
        assertEquals("skipped (over n_ctx): 4096", lines.last())
    }
}
