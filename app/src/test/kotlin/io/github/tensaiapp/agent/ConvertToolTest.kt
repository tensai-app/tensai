package io.github.tensaiapp.agent

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `convert` and `convert_time` (TEN-175, split from `calculate` in TEN-192): unit families and time zones. */
class ConvertToolTest {

    private val clock = Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZoneId.of("Europe/Paris"))
    private val convert = ConvertTool()
    private val time = ConvertTimeTool(clock)

    private fun run(args: String) = runBlocking { convert.execute(args) }

    private fun runTime(args: String) = runBlocking { time.execute(args) }

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
    fun theUnitToolNeedsAllThreeArguments() {
        assertEquals("give value, from and to", run("""{"value":5,"from":"km"}""").text)
        assertTrue(run("{}").isError)
        assertTrue(run("nope").isError)
        assertTrue(run("""{"value":null,"from":"km","to":"m"}""").isError)
        assertEquals("value must be a number", run("""{"value":"Infinity","from":"km","to":"m"}""").text)
        assertEquals("value must be a number", run("""{"value":"NaN","from":"km","to":"m"}""").text)
    }

    @Test
    fun aTimeIsShownInAnotherZone() {
        val result = runTime("""{"time":"14:00","from":"Europe/Paris","to":"America/New_York"}""")

        assertFalse(result.isError)
        assertEquals("2026-10-16 14:00 Europe/Paris (UTC+02:00) = 2026-10-16 08:00 America/New_York (UTC-04:00)", result.text)
    }

    @Test
    fun aLocalTimeInAClockGapOrAnImpossibleDateIsRefused() {
        val gap = runTime("""{"time":"2026-03-29 02:30","from":"Europe/Paris","to":"UTC"}""")
        assertTrue(gap.isError)
        assertTrue(gap.text.contains("does not exist"))

        val date = runTime("""{"time":"2026-02-30 10:00","from":"UTC","to":"UTC"}""")
        assertTrue(date.isError)
        assertTrue(date.text.contains("bad time"))
    }

    @Test
    fun aFullDateTimeCrossesMidnightAnd() {
        val result = runTime("""{"time":"2026-10-16T23:30","from":"UTC","to":"Asia/Tokyo"}""")
        assertEquals("2026-10-16 23:30 UTC (UTC+00:00) = 2026-10-17 08:30 Asia/Tokyo (UTC+09:00)", result.text)
    }

    @Test
    fun badZonesAndTimesAreExplained() {
        val zone = runTime("""{"time":"14:00","from":"Paris","to":"UTC"}""")
        assertTrue(zone.isError)
        assertTrue(zone.text.contains("unknown time zone 'Paris'"))

        val bad = runTime("""{"time":"tomorrow","from":"UTC","to":"UTC"}""")
        assertTrue(bad.isError)
        assertTrue(bad.text.contains("bad time"))

        assertEquals("give time, from and to zones", runTime("""{"from":"UTC","to":"UTC"}""").text)
        assertEquals("give time, from and to zones", runTime("""{"time":"14:00","from":"UTC"}""").text)
        assertEquals("give time, from and to zones", runTime("""{"time":"14:00","from":" ","to":"UTC"}""").text)
        assertTrue(runTime("nope").isError)
    }

    @Test
    fun theSchemasAreShortAndTheToolsAreLocal() {
        for (tool in listOf(convert, time)) {
            assertEquals(ToolTier.Local, tool.tier)
            assertFalse(tool.sendsOrDeletes)
            assertTrue(ToolArgs.check(tool.parametersSchema, "{}") != null)
        }
        assertEquals(null, ToolArgs.check(convert.parametersSchema, """{"value":5,"from":"km","to":"mi"}"""))
        assertEquals(null, ToolArgs.check(time.parametersSchema, """{"time":"14:00","from":"UTC","to":"UTC"}"""))
        assertTrue(convert.parametersSchema.length < 160)
        assertTrue(time.parametersSchema.length < 260)
    }
}
