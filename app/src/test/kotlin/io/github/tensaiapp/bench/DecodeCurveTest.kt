package io.github.tensaiapp.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests of the debug decode curve's pure parts: which points fit, the filler's size. */
class DecodeCurveTest {

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
    fun `a point needs room for the tokens decoded at it`() {
        assertEquals(4096 + 256 + DecodeCurve.HeadroomTokens, DecodeCurve.contextNeeded(4096, evalTokens = 256))
        assertEquals(listOf(0, 512, 1024, 2048), DecodeCurve.lengthsFor(4096 + 200, evalTokens = 256))
        assertEquals(DecodeCurve.Lengths, DecodeCurve.lengthsFor(4096 + 200, evalTokens = 64))
        assertEquals(listOf(512, 2048), DecodeCurve.lengthsFor(8192, listOf(512, 2048), evalTokens = 64))
    }
}
