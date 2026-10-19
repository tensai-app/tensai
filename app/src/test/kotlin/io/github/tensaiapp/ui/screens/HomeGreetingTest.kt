package io.github.tensaiapp.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The empty chat's greeting as a function of the hour (TEN-37, TEN-169), the one half of the greeting that can be
 * proven without a device. What it *looks* like is a device question; which of the four bands a
 * given hour falls into is not, so every boundary is pinned here, including the one that wraps
 * past midnight.
 */
class HomeGreetingTest {

    @Test
    fun midnightIsNight() {
        assertEquals(Greeting.Night, greetingFor(0))
    }

    @Test
    fun nightRunsUntilFiveAndMorningStartsThere() {
        assertEquals("04:59 is still night", Greeting.Night, greetingFor(4))
        assertEquals("05:00 is the first morning hour", Greeting.Morning, greetingFor(5))
    }

    @Test
    fun morningRunsUntilNoonAndAfternoonStartsThere() {
        assertEquals("11:59 is still morning", Greeting.Morning, greetingFor(11))
        assertEquals("12:00 is the first afternoon hour", Greeting.Afternoon, greetingFor(12))
    }

    @Test
    fun afternoonRunsUntilSixAndEveningStartsThere() {
        assertEquals("17:59 is still afternoon", Greeting.Afternoon, greetingFor(17))
        assertEquals("18:00 is the first evening hour", Greeting.Evening, greetingFor(18))
    }

    @Test
    fun eveningRunsUntilTenAndNightStartsThere() {
        assertEquals("21:59 is still evening", Greeting.Evening, greetingFor(21))
        assertEquals("22:00 is the first night hour", Greeting.Night, greetingFor(22))
    }

    @Test
    fun theLastMinuteOfTheDayIsNightNotEvening() {
        assertEquals(
            "the night band wraps midnight — 23:59 and 00:01 must read the same",
            Greeting.Night,
            greetingFor(23),
        )
    }

    @Test
    fun everyHourOfTheDayHasAGreeting() {
        val bands = (0..23).map { greetingFor(it) }

        assertEquals(
            "all four bands are reachable — none of them is dead code",
            Greeting.entries.toSet(),
            bands.toSet(),
        )
    }
}
