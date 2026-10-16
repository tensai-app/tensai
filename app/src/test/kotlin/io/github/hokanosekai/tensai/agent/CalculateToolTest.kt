package io.github.hokanosekai.tensai.agent

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `calculate` (TEN-175): the parser, the unit families and the time zone conversion. */
class CalculateToolTest {

    private val clock = Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZoneId.of("Europe/Paris"))
    private val tool = CalculateTool(clock)

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

    private fun unit(value: Double, from: String, to: String) = UnitConversion.convert(value, from, to)

    @Test
    fun everyUnitFamilyConverts() {
        assertEquals(1.609344, unit(1.0, "mi", "km"), 1e-9)
        assertEquals(12.0, unit(1.0, "ft", "in"), 1e-9)
        assertEquals(0.45359237, unit(1.0, "lb", "kg"), 1e-9)
        assertEquals(1000.0, unit(1.0, "kg", "g"), 1e-9)
        assertEquals(3.785411784, unit(1.0, "gal", "l"), 1e-9)
        assertEquals(1000.0, unit(1.0, "l", "ml"), 1e-9)
        assertEquals(100.0, unit(360.0, "km/h", "m/s"), 1e-9)
        assertEquals(62.1371, unit(100.0, "km/h", "mph"), 1e-3)
        assertEquals(1000.0, unit(1.0, "GB", "MB"), 1e-9)
        assertEquals(1024.0, unit(1.0, "KiB", "bytes"), 1e-9)
        assertEquals(8.0, unit(1.0, "byte", "bit"), 1e-9)
        assertEquals(90.0, unit(1.5, "h", "min"), 1e-9)
        assertEquals(14.0, unit(2.0, "weeks", "days"), 1e-9)
    }

    @Test
    fun temperatureHasAnOffset() {
        assertEquals(212.0, unit(100.0, "C", "F"), 1e-9)
        assertEquals(37.0, unit(98.6, "F", "C"), 1e-9)
        assertEquals(273.15, unit(0.0, "celsius", "kelvin"), 1e-9)
        assertEquals(-459.67, unit(0.0, "K", "F"), 1e-9)
    }

    @Test
    fun badUnitsAreExplained() {
        val mismatch = run("""{"value":5,"from":"km","to":"kg"}""")
        assertTrue(mismatch.isError)
        assertTrue(mismatch.text.contains("cannot convert length (km) to mass (kg)"))

        val unknown = run("""{"value":5,"from":"parsec","to":"km"}""")
        assertTrue(unknown.isError)
        assertTrue(unknown.text.contains("unknown unit 'parsec'"))

        assertTrue(run("""{"value":5,"from":"km"}""").isError)
        assertTrue(run("""{"value":"abc","from":"km","to":"m"}""").isError)
        assertTrue(run("""{"value":5,"from":"c","to":"km"}""").text.contains("temperature"))
    }

    @Test
    fun theToolConvertsUnits() {
        assertEquals("5 km = 3.106855961 mi", run("""{"value":5,"from":"km","to":"mi"}""").text)
        assertEquals("100 c = 212 f", run("""{"value":100,"from":"c","to":"f"}""").text)
    }

    @Test
    fun aTimeIsShownInAnotherZone() {
        val result = run("""{"time":"14:00","from":"Europe/Paris","to":"America/New_York"}""")

        assertFalse(result.isError)
        assertEquals("2026-10-16 14:00 Europe/Paris (UTC+02:00) = 2026-10-16 08:00 America/New_York (UTC-04:00)", result.text)
    }

    @Test
    fun aLocalTimeInAClockGapOrAnImpossibleDateIsRefused() {
        val gap = run("""{"time":"2026-03-29 02:30","from":"Europe/Paris","to":"UTC"}""")
        assertTrue(gap.isError)
        assertTrue(gap.text.contains("does not exist"))

        val date = run("""{"time":"2026-02-30 10:00","from":"UTC","to":"UTC"}""")
        assertTrue(date.isError)
        assertTrue(date.text.contains("bad time"))
    }

    @Test
    fun roundIsHalfUpAndAJsonNullIsAMissingArgument() {
        assertEquals("1e20", calc("round(1e20)"))
        assertEquals("-2", calc("round(-2.5)"))
        assertTrue(run("""{"expression":null}""").isError)
        assertEquals("give expression, or value+from+to, or time+from+to", run("""{"expression":null}""").text)
    }

    @Test
    fun aFullDateTimeCrossesMidnightAndTheMissingZoneIsTheDeviceOne() {
        val result = run("""{"time":"2026-10-16T23:30","from":"UTC","to":"Asia/Tokyo"}""")
        assertEquals("2026-10-16 23:30 UTC (UTC+00:00) = 2026-10-17 08:30 Asia/Tokyo (UTC+09:00)", result.text)

        val toDevice = run("""{"time":"2026-10-16 12:00","from":"UTC"}""")
        assertEquals("2026-10-16 12:00 UTC (UTC+00:00) = 2026-10-16 14:00 Europe/Paris (UTC+02:00)", toDevice.text)
    }

    @Test
    fun badZonesAndTimesAreExplained() {
        val zone = run("""{"time":"14:00","from":"Paris","to":"UTC"}""")
        assertTrue(zone.isError)
        assertTrue(zone.text.contains("unknown time zone 'Paris'"))

        val time = run("""{"time":"tomorrow","from":"UTC","to":"UTC"}""")
        assertTrue(time.isError)
        assertTrue(time.text.contains("bad time"))
    }

    @Test
    fun theSchemaIsShortAndTheToolIsLocal() {
        assertEquals(ToolTier.Local, tool.tier)
        assertFalse(tool.sendsOrDeletes)
        assertEquals(null, ToolArgs.check(tool.parametersSchema, "{}"))
        assertTrue(tool.parametersSchema.length < 400)
    }
}
