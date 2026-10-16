package io.github.hokanosekai.tensai.ui.screens

/**
 * Which of the empty chat's four greetings the clock calls for, see [greetingFor]. The screen maps these
 * to their string resources, so the bands stay a plain function of the hour and nothing here has
 * to know about locales.
 */
enum class Greeting {
    /** 05:00–11:59. */
    Morning,

    /** 12:00–17:59. */
    Afternoon,

    /** 18:00–21:59. */
    Evening,

    /** 22:00–04:59 — the band that wraps past midnight, hence the `else` branch in [greetingFor]. */
    Night,
}

/**
 * Picks the empty chat's greeting from the hour of day.
 *
 * Deliberately takes the hour rather than reading a clock: "which greeting at 04:59 vs. 05:00" is
 * the only part of the greeting worth proving, and it is only provable if the time comes in as an
 * argument. `ChatEmptyState` supplies `LocalTime.now().hour`.
 *
 * @param hour hour of day, 0..23. Anything outside that range lands in [Greeting.Night] along with
 *   the late/early band it shares an `else` with — this is not a validated input, callers are
 *   expected to hand over a real hour.
 */
fun greetingFor(hour: Int): Greeting = when (hour) {
    in 5..11 -> Greeting.Morning
    in 12..17 -> Greeting.Afternoon
    in 18..21 -> Greeting.Evening
    else -> Greeting.Night
}
