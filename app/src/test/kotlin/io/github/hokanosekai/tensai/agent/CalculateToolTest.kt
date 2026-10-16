package io.github.hokanosekai.tensai.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `calculate` (TEN-175, TEN-192): the parser and the one-argument tool. */
class CalculateToolTest {

    private val tool = CalculateTool()

    private fun run(args: String) = runBlocking { tool.execute(args) }

    private fun calc(expression: String) = MathExpression.format(MathExpression.evaluate(expression))

    private fun failure(expression: String): String =
        try {
            MathExpression.evaluate(expression)
            error("expected a MathException for '$expression'")
        } catch (e: MathException) {
            e.message.orEmpty()
        }

    @Test
    fun precedenceAndAssociativity() {
        assertEquals("18", calc("2*3**2"))
        assertEquals("512", calc("2**3**2"))
        assertEquals("-4", calc("-2**2"))
        assertEquals("14", calc("2 + 3 * 4"))
        assertEquals("20", calc("(2 + 3) * 4"))
        assertEquals("-4", calc("-2^2"))
        assertEquals("512", calc("2^3^2"))
        assertEquals("0.5", calc("2^-1"))
        assertEquals("1", calc("10 % 3"))
        assertEquals("2.5", calc("10 / 4"))
        assertEquals("8", calc("2 ** 3"))
        assertEquals("-3", calc("--3 - 6"))
        assertEquals("1500", calc("1.5e3"))
    }

    @Test
    fun functionsAndConstants() {
        assertEquals("4", calc("sqrt(16)"))
        assertEquals("3", calc("abs(-3)"))
        assertEquals("3", calc("round(2.5)"))
        assertEquals("2", calc("floor(2.9)"))
        assertEquals("3", calc("ceil(2.1)"))
        assertEquals("1", calc("min(4, 1, 9)"))
        assertEquals("9", calc("max(4, 1, 9)"))
        assertEquals("2", calc("log(100)"))
        assertEquals("1", calc("ln(e)"))
        assertEquals("0", calc("sin(0)"))
        assertEquals("1", calc("cos(0)"))
        assertEquals("1", calc("tan(pi / 4)"))
        assertEquals("3.141592654", calc("pi"))
        assertEquals("6.283185307", calc("2*PI"))
    }

    @Test
    fun badInputIsExplained() {
        assertTrue(failure("").contains("empty"))
        assertTrue(failure("1 +").contains("ends too early"))
        assertTrue(failure("(1 + 2").contains("missing ')'"))
        assertTrue(failure("1 + 2)").contains("unexpected ')'"))
        assertTrue(failure("foo + 1").contains("unknown name 'foo'"))
        assertTrue(failure("foo(1)").contains("unknown function 'foo'"))
        assertTrue(failure("sqrt(1, 2)").contains("takes 1 argument"))
        assertTrue(failure("min(1)").contains("at least 2"))
        assertTrue(failure("1 / 0").contains("division by zero"))
        assertTrue(failure("0 / 0").contains("division by zero"))
        assertTrue(failure("0 % 0").contains("division by zero"))
        assertTrue(failure("round(sqrt(-1))").contains("round needs a finite number"))
        assertTrue(failure("2e").contains("unexpected 'e'"))
        assertTrue(failure("sqrt(-1)").contains("undefined"))
        assertTrue(failure("1.2.3").contains("bad number"))
        assertTrue(failure("System.exit(0)").isNotEmpty())
        assertTrue(failure("(".repeat(200) + "1" + ")".repeat(200)).contains("nested too deeply"))
        assertTrue(failure("1+".repeat(300)).contains("too long"))
    }

    @Test
    fun resultsAreFormattedCompactly() {
        assertEquals("0.3", MathExpression.format(0.1 + 0.2))
        assertEquals("0.3333333333", MathExpression.format(1.0 / 3))
        assertEquals("1e20", MathExpression.format(1e20))
        assertEquals("0", MathExpression.format(0.0))
        assertEquals("-7", MathExpression.format(-7.0))
    }

    @Test
    fun theToolAnswersWithTheExpressionAndItsValue() {
        val ok = run("""{"expression":"2*(3+4)"}""")
        assertFalse(ok.isError)
        assertEquals("2*(3+4) = 14", ok.text)

        val bad = run("""{"expression":"2*"}""")
        assertTrue(bad.isError)
        assertTrue(bad.text.contains("ends too early"))
    }

    @Test
    fun theToolAsksForAnInputWhenThereIsNone() {
        assertTrue(run("{}").isError)
        assertTrue(run("nope").isError)
    }

    @Test
    fun roundIsHalfUpAndAJsonNullIsAMissingArgument() {
        assertEquals("1e20", calc("round(1e20)"))
        assertEquals("-2", calc("round(-2.5)"))
        assertTrue(run("""{"expression":null}""").isError)
        assertEquals("give an expression", run("""{"expression":null}""").text)
    }

    @Test
    fun conversionArgumentsAreNoLongerAnInput() {
        assertTrue(run("""{"value":5,"from":"km","to":"mi"}""").isError)
    }

    @Test
    fun theSchemaIsShortAndTheToolIsLocal() {
        assertEquals(ToolTier.Local, tool.tier)
        assertFalse(tool.sendsOrDeletes)
        assertEquals(null, ToolArgs.check(tool.parametersSchema, """{"expression":"1+1"}"""))
        assertTrue(ToolArgs.check(tool.parametersSchema, "{}") != null)
        assertTrue(tool.parametersSchema.length < 100)
    }
}
