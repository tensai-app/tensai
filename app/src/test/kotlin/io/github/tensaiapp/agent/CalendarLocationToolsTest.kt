package io.github.tensaiapp.agent

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `read_calendar` and `location` (TEN-179): range parsing, row to text, rounding, and the permission outcomes. */
class CalendarLocationToolsTest {

    private val paris = ZoneId.of("Europe/Paris")

    // Saturday 2026-10-17, noon in Paris
    private val clock = Clock.fixed(Instant.parse("2026-10-17T10:00:00Z"), paris)
    private val today = LocalDate.of(2026, 10, 17)

    private fun at(date: String, time: String): Long = LocalDate.parse(date).atTime(java.time.LocalTime.parse(time)).atZone(paris).toInstant().toEpochMilli()

    private fun utcDay(date: String): Long = LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    private class FakeCalendar(
        private val rows: List<CalendarEvent> = emptyList(),
        private val access: PermissionResult = PermissionResult.Granted,
        private val revoked: Boolean = false,
    ) : CalendarSource {
        var asked = 0
        var queries = mutableListOf<Triple<Long, Long, Int>>()

        override fun isGranted() = access == PermissionResult.Granted

        override suspend fun access(): PermissionResult {
            asked++
            return access
        }

        override fun events(startMillis: Long, endMillis: Long, limit: Int): List<CalendarEvent> {
            queries += Triple(startMillis, endMillis, limit)
            if (revoked) throw SecurityException("READ_CALENDAR")
            return rows
        }
    }

    private class FakeLocation(
        private val outcome: LocationOutcome,
        private val access: PermissionResult = PermissionResult.Granted,
        private val revoked: Boolean = false,
    ) : LocationSource {
        var asked = 0
        var fetched = 0

        override fun isGranted() = access == PermissionResult.Granted

        override suspend fun access(): PermissionResult {
            asked++
            return access
        }

        override suspend fun current(): LocationOutcome {
            fetched++
            if (revoked) throw SecurityException("ACCESS_COARSE_LOCATION")
            return outcome
        }
    }

    // range

    @Test
    fun noArgumentIsToday() {
        assertEquals(CalendarRange(today, today, false), calendarRange(null, null, today))
    }

    @Test
    fun dayTakesTheWeatherWordsAndYesterday() {
        assertEquals(today.plusDays(1), calendarRange("tomorrow", null, today).from)
        // 2026-10-17 is a Saturday: the next monday is the 19th
        assertEquals(LocalDate.of(2026, 10, 19), calendarRange("monday", null, today).to)
        assertEquals(LocalDate.of(2026, 11, 2), calendarRange("2026-11-02", null, today).from)
        assertEquals(CalendarRange(today.minusDays(1), today.minusDays(1), false), calendarRange("yesterday", null, today))
        assertEquals(today.minusDays(1), calendarRange("Yesterday", "today", today).from)
        // weather keeps refusing it
        assertNull(WeatherTool.Day.parse("yesterday"))
    }

    @Test
    fun toMakesARangeFromDay() {
        assertEquals(CalendarRange(today, LocalDate.of(2026, 10, 20), false), calendarRange(null, "2026-10-20", today))
        assertEquals(CalendarRange(today, today.plusDays(2), false), calendarRange("today", "2026-10-19", today))
        assertEquals(CalendarRange(today.minusDays(1), today.plusDays(1), false), calendarRange("yesterday", "tomorrow", today))
    }

    @Test
    fun aLongRangeIsCutTo31Days() {
        val range = calendarRange("2026-10-17", "2027-03-01", today)
        assertEquals(today, range.from)
        assertEquals(today.plusDays(30), range.to)
        assertTrue(range.capped)
        // 31 days (17 Oct to 16 Nov) is not cut, the 32nd day is
        assertFalse(calendarRange("2026-10-17", "2026-11-16", today).capped)
        val thirtyTwo = calendarRange("2026-10-17", "2026-11-17", today)
        assertTrue(thirtyTwo.capped)
        assertEquals(LocalDate.of(2026, 11, 16), thirtyTwo.to)
    }

    @Test
    fun badRangesSayWhy() {
        val bad = runCatching { calendarRange("someday", null, today) }.exceptionOrNull()
        assertTrue(bad?.message, bad is IllegalArgumentException && bad.message!!.startsWith("day must be"))
        val backwards = runCatching { calendarRange("2026-10-20", "2026-10-18", today) }.exceptionOrNull()
        assertTrue(backwards?.message, backwards?.message!!.contains("before"))
        assertTrue(runCatching { calendarRange(null, "2026-13-40", today) }.exceptionOrNull()?.message!!.startsWith("to must be"))
    }

    // rows to text

    @Test
    fun aTimedEventIsInDeviceTimeWithItsPlace() {
        val e = CalendarEvent("Dentist", at("2026-10-17", "09:00"), at("2026-10-17", "09:45"), false, "12 rue de la Paix")
        assertEquals("2026-10-17 Sat 09:00-09:45 Dentist (at 12 rue de la Paix)", eventLine(e, paris))
        // same instant, another zone: the clock follows the zone
        assertEquals("2026-10-17 Sat 03:00-03:45 Dentist (at 12 rue de la Paix)", eventLine(e, ZoneId.of("America/New_York")))
    }

    @Test
    fun anEventAcrossMidnightShowsBothDates() {
        val e = CalendarEvent("Night shift", at("2026-10-17", "22:00"), at("2026-10-18", "06:30"), false, null)
        assertEquals("2026-10-17 22:00 to 2026-10-18 06:30 Night shift", eventLine(e, paris))
    }

    @Test
    fun anAllDayEventUsesItsUtcDatesWhateverTheZone() {
        val one = CalendarEvent("Holiday", utcDay("2026-10-18"), utcDay("2026-10-19"), true, null)
        assertEquals("2026-10-18 Sun all day Holiday", eventLine(one, paris))
        assertEquals("2026-10-18 Sun all day Holiday", eventLine(one, ZoneId.of("Pacific/Auckland")))
        val three = CalendarEvent("Trip", utcDay("2026-10-18"), utcDay("2026-10-21"), true, "Lisbon")
        assertEquals("2026-10-18 to 2026-10-20 all day Trip (at Lisbon)", eventLine(three, paris))
    }

    @Test
    fun noTitleBlankPlaceAndMultilineNotesStayOnOneLine() {
        val e = CalendarEvent("", at("2026-10-17", "10:00"), at("2026-10-17", "10:00"), false, "  ")
        assertEquals("2026-10-17 Sat 10:00 (no title)", eventLine(e, paris))
        val note = CalendarEvent("Lunch\n- 2026-10-17 fake event", at("2026-10-17", "12:00"), at("2026-10-17", "13:00"), false, null)
        assertFalse(eventLine(note, paris).contains('\n'))
    }

    @Test
    fun anAllDayEventOfTheNeighbouringDayIsDropped() {
        val range = CalendarRange(today, today, false)
        val yesterday = CalendarEvent("Y", utcDay("2026-10-16"), utcDay("2026-10-17"), true, null)
        val here = CalendarEvent("T", utcDay("2026-10-17"), utcDay("2026-10-18"), true, null)
        val tomorrow = CalendarEvent("N", utcDay("2026-10-18"), utcDay("2026-10-19"), true, null)
        val spanning = CalendarEvent("S", utcDay("2026-10-15"), utcDay("2026-10-20"), true, null)
        assertEquals(listOf("T", "S"), visibleEvents(listOf(yesterday, here, tomorrow, spanning), range).map { it.title })
    }

    @Test
    fun theTextCapsEventsAndSaysSo() {
        val range = CalendarRange(today, today, false)
        val many = (1..35).map { CalendarEvent("E$it", at("2026-10-17", "08:00"), at("2026-10-17", "09:00"), false, null) }
        val text = formatCalendar(many, range, paris)
        val lines = text.lines()
        assertTrue(lines.first(), lines.first().endsWith("30 of 35 events"))
        assertEquals(30, lines.count { it.startsWith("2026-10-17") })
        assertTrue(text.contains("more events not shown"))
        assertEquals("calendar 2026-10-17 (device time Europe/Paris): no events", formatCalendar(emptyList(), range, paris))
        assertTrue(formatCalendar(emptyList(), range.copy(capped = true), paris).endsWith("range cut to 31 days"))
    }

    @Test
    fun exactly30EventsHaveNoNote() {
        val range = CalendarRange(today, today, false)
        val thirty = (1..30).map { CalendarEvent("E$it", at("2026-10-17", "08:00"), at("2026-10-17", "09:00"), false, null) }
        val text = formatCalendar(thirty, range, paris)
        assertTrue(text.lines().first().endsWith("30 events"))
        assertEquals(30, text.lines().size - 1)
        assertFalse(text.contains("more events"))
        assertFalse(text.contains("incomplete"))
    }

    @Test
    fun aFullProviderReadSaysTheListMayBeIncomplete() {
        val range = CalendarRange(today, today, false)
        val rows = (1..CALENDAR_MAX_ROWS).map { CalendarEvent("E$it", at("2026-10-17", "08:00"), at("2026-10-17", "09:00"), false, null) }
        assertTrue(formatCalendar(rows, range, paris).contains("list may be incomplete, ask for a shorter range"))
        // few events shown (the rest are neighbouring all-day ones) still says it
        val mostlyDropped = rows.map { it.copy(allDay = true, startMillis = utcDay("2026-10-25"), endMillis = utcDay("2026-10-26")) }
        val text = formatCalendar(mostlyDropped, range, paris)
        assertTrue(text.contains("no events"))
        assertTrue(text.contains("list may be incomplete"))
        assertFalse(formatCalendar(rows.dropLast(1), range, paris).contains("incomplete"))
    }

    @Test
    fun eventsAreChronologicalPerDayWithAllDayFirstInParis() {
        val range = CalendarRange(today, today.plusDays(1), false)
        // as the provider orders them, by instant: the all-day event (UTC midnight, 02:00 in Paris) sits among the timed ones
        val rows = listOf(
            CalendarEvent("late", at("2026-10-17", "23:30"), at("2026-10-17", "23:45"), false, null),
            CalendarEvent("night", at("2026-10-18", "01:00"), at("2026-10-18", "01:30"), false, null),
            CalendarEvent("holiday", utcDay("2026-10-18"), utcDay("2026-10-19"), true, null),
            CalendarEvent("morning", at("2026-10-18", "08:00"), at("2026-10-18", "09:00"), false, null),
        )
        val titles = formatCalendar(rows.reversed(), range, paris).lines().drop(1)
        assertEquals(
            listOf(
                "2026-10-17 Sat 23:30-23:45 late",
                "2026-10-18 Sun all day holiday",
                "2026-10-18 Sun 01:00-01:30 night",
                "2026-10-18 Sun 08:00-09:00 morning",
            ),
            titles,
        )
    }

    @Test
    fun aPlaceIsCutTo60CharactersAndATitleTo120() {
        val long = CalendarEvent("T".repeat(200), at("2026-10-17", "10:00"), at("2026-10-17", "11:00"), false, "P".repeat(100))
        val line = eventLine(long, paris)
        assertTrue(line.contains("T".repeat(120) + " (at " + "P".repeat(60) + ")"))
        assertFalse(line.contains("T".repeat(121)))
    }

    // the tool

    @Test
    fun theToolQueriesTheLocalDaysAndAnswersText() = runBlocking {
        val cal = FakeCalendar(listOf(CalendarEvent("Dentist", at("2026-10-18", "09:00"), at("2026-10-18", "10:00"), false, null)))
        val result = ReadCalendarTool(cal, { true }, clock).execute("""{"day":"tomorrow"}""")

        assertFalse(result.isError)
        assertTrue(result.text, result.text.contains("2026-10-18 Sun 09:00-10:00 Dentist"))
        assertEquals(Triple(at("2026-10-18", "00:00"), at("2026-10-19", "00:00"), CALENDAR_MAX_ROWS), cal.queries.single())
        // no argument at all, and a null one
        ReadCalendarTool(cal, { true }, clock).execute("{}")
        ReadCalendarTool(cal, { true }, clock).execute("""{"day":null,"to":null}""")
        assertEquals(at("2026-10-17", "00:00"), cal.queries[1].first)
        assertEquals(at("2026-10-17", "00:00"), cal.queries[2].first)
    }

    @Test
    fun theToolReportsBadArgumentsWithoutQuerying() = runBlocking {
        val cal = FakeCalendar()
        val tool = ReadCalendarTool(cal, { true }, clock)
        assertTrue(tool.execute("""{"day":"yesterday-ish"}""").isError)
        assertEquals("arguments are not a JSON object", tool.execute("[1]").text)
        assertTrue(cal.queries.isEmpty())
        assertEquals(0, cal.asked)
    }

    @Test
    fun calendarRefusalGoesThroughTheGate() = runBlocking {
        val denied = FakeCalendar(access = PermissionResult.Denied)
        val result = ReadCalendarTool(denied, { true }, clock).execute("{}")
        assertTrue(result.refused)
        assertTrue(result.isError)
        assertEquals(CalendarRefusedText, result.text)
        assertNull(blockedPermission(result.text))
        assertTrue(denied.queries.isEmpty())

        val blocked = ReadCalendarTool(FakeCalendar(access = PermissionResult.PermanentlyDenied), { true }, clock).execute("{}")
        assertTrue(blocked.refused)
        assertEquals("calendar", blockedPermission(blocked.text))
        assertEquals(CalendarBlockedText, JSONObject(blocked.text).getString("error"))
    }

    @Test
    fun calendarFromTheBackgroundFailsBeforeTheDialog() = runBlocking {
        val cal = FakeCalendar(access = PermissionResult.Denied)
        val result = ReadCalendarTool(cal, { false }, clock).execute("{}")
        assertTrue(result.isError)
        assertFalse(result.refused)
        assertEquals(BACKGROUND_ERROR, result.text)
        assertEquals(0, cal.asked)
        // already granted: no dialog needed, so the background is fine
        assertFalse(ReadCalendarTool(FakeCalendar(), { false }, clock).execute("{}").isError)
    }

    @Test
    fun calendarToolIsAnAndroidPermissionTool() {
        val tool = ReadCalendarTool(FakeCalendar(), { true }, clock)
        assertEquals(ToolTier.AndroidPermission, tool.tier)
        assertTrue(tool.waitsForUser)
        assertEquals("read_calendar", tool.name)
    }

    // location

    @Test
    fun coordinatesAreRoundedToTwoDecimals() {
        assertEquals(48.11, roundCoordinate(48.11198), 0.0)
        assertEquals(-1.67, roundCoordinate(-1.67429), 0.0)
        assertEquals(-1.68, roundCoordinate(-1.675001), 0.0)
        assertEquals(0.0, roundCoordinate(0.004), 0.0)
        assertEquals(180.0, roundCoordinate(179.9999), 0.0)
    }

    @Test
    fun theFixIsFormattedRoundedWithItsTime() {
        val fix = LocationFix(48.111983, -1.674291, 1499.6f, at("2026-10-17", "14:03:27"))
        val json = JSONObject(formatLocation(fix, paris))

        assertEquals(48.11, json.getDouble("latitude"), 0.0)
        assertEquals(-1.67, json.getDouble("longitude"), 0.0)
        assertEquals(1500, json.getInt("accuracy_m"))
        assertEquals("2026-10-17 14:03", json.getString("fix_time"))
        assertFalse(json.getString("note").contains("weather"))
        // the unrounded digits are nowhere
        assertFalse(formatLocation(fix, paris).contains("48.111"))
        assertFalse(JSONObject(formatLocation(fix.copy(accuracyMeters = null), paris)).has("accuracy_m"))
    }

    @Test
    fun theLocationToolAnswersAndFails() = runBlocking {
        val fix = LocationFix(48.111983, -1.674291, 900f, at("2026-10-17", "14:03"))
        val ok = LocationTool(FakeLocation(LocationOutcome.Found(fix)), { true }, clock).execute("{}")
        assertFalse(ok.isError)
        assertEquals(48.11, JSONObject(ok.text).getDouble("latitude"), 0.0)

        val off = LocationTool(FakeLocation(LocationOutcome.Failed(LocationFailure.Off)), { true }, clock).execute("{}")
        assertTrue(off.isError)
        assertFalse(off.refused)
        assertEquals(LocationFailure.Off.message, off.text)
        val none = LocationTool(FakeLocation(LocationOutcome.Failed(LocationFailure.Unavailable)), { true }, clock).execute("{}")
        assertEquals(LocationFailure.Unavailable.message, none.text)
        assertTrue(LocationFailure.Off.message != LocationFailure.Unavailable.message)
    }

    @Test
    fun locationRefusalGoesThroughTheGate() = runBlocking {
        val fix = LocationOutcome.Found(LocationFix(1.0, 2.0, null, 0L))
        val denied = FakeLocation(fix, PermissionResult.Denied)
        val result = LocationTool(denied, { true }, clock).execute("{}")
        assertTrue(result.refused)
        assertEquals(LocationRefusedText, result.text)
        assertNull(blockedPermission(result.text))
        assertEquals(0, denied.fetched)

        val blocked = LocationTool(FakeLocation(fix, PermissionResult.PermanentlyDenied), { true }, clock).execute("{}")
        assertTrue(blocked.refused)
        assertEquals("location", blockedPermission(blocked.text))
        assertEquals(LocationBlockedText, JSONObject(blocked.text).getString("error"))
    }

    @Test
    fun locationFromTheBackgroundFailsBeforeTheDialog() = runBlocking {
        val location = FakeLocation(LocationOutcome.Failed(LocationFailure.Off), PermissionResult.Denied)
        val result = LocationTool(location, { false }, clock).execute("{}")
        assertEquals(BACKGROUND_ERROR, result.text)
        assertEquals(0, location.asked)
        val tool = LocationTool(location, { true }, clock)
        assertEquals(ToolTier.AndroidPermission, tool.tier)
        assertTrue(tool.waitsForUser)
    }


    // revoked after the check, stale fixes, first use

    @Test
    fun aRevokedCalendarPermissionPointsToTheSettings() = runBlocking {
        val result = ReadCalendarTool(FakeCalendar(revoked = true), { true }, clock).execute("{}")
        assertTrue(result.refused)
        assertEquals("calendar", blockedPermission(result.text))
        assertEquals(CalendarBlockedText, JSONObject(result.text).getString("error"))
    }

    @Test
    fun aRevokedLocationPermissionPointsToTheSettings() = runBlocking {
        val fix = LocationOutcome.Found(LocationFix(1.0, 2.0, null, clock.millis()))
        val result = LocationTool(FakeLocation(fix, revoked = true), { true }, clock).execute("{}")
        assertTrue(result.refused)
        assertEquals("location", blockedPermission(result.text))
        assertEquals(LocationBlockedText, JSONObject(result.text).getString("error"))
    }

    @Test
    fun aRevokedContactsPermissionPointsToTheSettings() = runBlocking {
        val contacts = object : ContactsSource {
            override fun isGranted() = true
            override suspend fun access() = PermissionResult.Granted
            override fun load(): List<Contact> = throw SecurityException("READ_CONTACTS")
        }
        val result = FindContactTool(contacts) { true }.execute("""{"name":"jade"}""")
        assertTrue(result.refused)
        assertEquals("contacts", blockedPermission(result.text))
        assertEquals(ContactsBlockedText, JSONObject(result.text).getString("error"))
    }

    @Test
    fun aLastKnownFixOlderThanAnHourIsNotGiven() = runBlocking {
        fun run(ageMs: Long) = runBlocking {
            val fix = LocationOutcome.Found(LocationFix(48.1, -1.6, 2000f, clock.millis() - ageMs))
            LocationTool(FakeLocation(fix), { true }, clock).execute("{}")
        }
        assertFalse(run(LOCATION_MAX_AGE_MS).isError)
        val stale = run(LOCATION_MAX_AGE_MS + 1)
        assertTrue(stale.isError)
        assertFalse(stale.refused)
        assertEquals(LocationFailure.Stale.message, stale.text)
        assertTrue(stale.text.startsWith("no recent position"))
    }

    @Test
    fun theUnavailableTextDoesNotBlameTheNetwork() {
        assertFalse(LocationFailure.Unavailable.message.contains("network"))
    }

    @Test
    fun aGrantedPermissionIsAskedOnceAndTheToolRuns() = runBlocking {
        val cal = FakeCalendar()
        ReadCalendarTool(cal, { true }, clock).execute("{}")
        assertEquals(1, cal.asked)
        assertEquals(1, cal.queries.size)

        val location = FakeLocation(LocationOutcome.Found(LocationFix(1.0, 2.0, null, clock.millis())))
        assertFalse(LocationTool(location, { true }, clock).execute("{}").isError)
        assertEquals(1, location.asked)
        assertEquals(1, location.fetched)
    }
}
