package io.github.tensaiapp.agent

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import org.json.JSONException
import org.json.JSONObject

/**
 * The calendar for `read_calendar` (TEN-179). Only what the model needs to answer "what do I have tomorrow": title,
 * time and place. No attendees, notes or organizer, they stay in the calendar app.
 */

/**
 * One occurrence of an event (a recurring event gives one per day it falls on). An all-day event is stored by Android as
 * UTC midnight to UTC midnight, whatever the time zone of the phone: [startMillis] and [endMillis] are those instants.
 */
data class CalendarEvent(val title: String, val startMillis: Long, val endMillis: Long, val allDay: Boolean, val location: String?)

/** The Android side of the calendar, so the tool tests without Android. */
interface CalendarSource {
    /** Whether READ_CALENDAR is granted: asking would show no dialog. */
    fun isGranted(): Boolean

    /** Asks for READ_CALENDAR when it is not granted yet: suspends while Android shows the dialog. */
    suspend fun access(): PermissionResult

    /**
     * The visible calendars' occurrences that overlap [startMillis, endMillis), earliest first, at most [limit] rows.
     * Needs [access] granted.
     */
    fun events(startMillis: Long, endMillis: Long, limit: Int): List<CalendarEvent>
}

const val CalendarRefusedText = "the user refused access to the calendar. Tell the user, do not retry."

const val CalendarBlockedText =
    "calendar access is blocked in Android settings. Tell the user to allow Calendar for tensai in the app settings, do not retry."

/** The days `read_calendar` covers, [from] and [to] included. [capped]: the range asked for was longer than [CALENDAR_MAX_DAYS]. */
internal data class CalendarRange(val from: LocalDate, val to: LocalDate, val capped: Boolean)

internal const val CALENDAR_MAX_DAYS = 31
internal const val CALENDAR_MAX_EVENTS = 30

/** Rows read from the provider: more than is shown, so the all-day events of a neighbouring day can be dropped, see [visibleEvents]. */
internal const val CALENDAR_MAX_ROWS = 200

/**
 * The range the arguments name. `day` is one day (default today), `to` makes it a range ending on that day, included.
 * Both take today, yesterday, tomorrow, a weekday or YYYY-MM-DD. A range over [CALENDAR_MAX_DAYS] days is cut short.
 * @throws IllegalArgumentException with what the model reads when a value does not parse or `to` is before `day`.
 */
internal fun calendarRange(day: String?, to: String?, today: LocalDate): CalendarRange {
    fun parse(name: String, text: String): LocalDate =
        // Only here: weather forecasts no past day, a calendar is asked "what did I have yesterday".
        if (text.trim().lowercase() == "yesterday") today.minusDays(1) else WeatherTool.Day.parse(text)?.resolve(today)
            ?: throw IllegalArgumentException("$name must be today, yesterday, tomorrow, a weekday like friday, or a date YYYY-MM-DD, got '$text'")
    val start = day?.let { parse("day", it) } ?: today
    val end = to?.let { parse("to", it) } ?: start
    if (end.isBefore(start)) throw IllegalArgumentException("to ($end) is before day ($start)")
    val last = start.plusDays(CALENDAR_MAX_DAYS - 1L)
    return if (end.isAfter(last)) CalendarRange(start, last, capped = true) else CalendarRange(start, end, capped = false)
}

private val whitespace = Regex("\\s+")

private const val MAX_TITLE = 120
private const val MAX_PLACE = 60

/** A title or a place on one line, so a note pasted in it cannot pass for a second event. */
private fun oneLine(text: String, max: Int): String = text.trim().replace(whitespace, " ").take(max)

private fun allDayDate(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

/** The last day an all-day event covers: its end is the exclusive UTC midnight after it. */
private fun allDayLast(e: CalendarEvent): LocalDate = maxOf(allDayDate(e.startMillis), allDayDate(e.endMillis).minusDays(1))

/**
 * The events that fall on [range]. A timed event overlaps it by construction (the query), an all-day one is stored in
 * UTC and can be picked up for the next or previous local day: it stays when its own dates meet the range.
 */
internal fun visibleEvents(events: List<CalendarEvent>, range: CalendarRange): List<CalendarEvent> = events.filter { e ->
    !e.allDay || (!allDayDate(e.startMillis).isAfter(range.to) && !allDayLast(e).isBefore(range.from))
}

private fun dayName(date: LocalDate) = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)

/** "2026-10-17 Fri 09:00-10:00 Dentist (at Rue X)": one line, the date on it so a range reads without a header per day. */
internal fun eventLine(e: CalendarEvent, zone: ZoneId): String {
    val title = oneLine(e.title, MAX_TITLE).ifEmpty { "(no title)" }
    val timing = if (e.allDay) {
        val first = allDayDate(e.startMillis)
        val last = allDayLast(e)
        if (first == last) "$first ${dayName(first)} all day" else "$first to $last all day"
    } else {
        val start = Instant.ofEpochMilli(e.startMillis).atZone(zone)
        val end = Instant.ofEpochMilli(e.endMillis).atZone(zone)
        val startTime = start.toLocalTime().truncatedTo(ChronoUnit.MINUTES)
        val endTime = end.toLocalTime().truncatedTo(ChronoUnit.MINUTES)
        val date = start.toLocalDate()
        when {
            e.endMillis <= e.startMillis -> "$date ${dayName(date)} $startTime"
            date == end.toLocalDate() -> "$date ${dayName(date)} $startTime-$endTime"
            else -> "$date $startTime to ${end.toLocalDate()} $endTime"
        }
    }
    val place = e.location?.let { oneLine(it, MAX_PLACE) }?.takeIf { it.isNotEmpty() }
    return if (place == null) "$timing $title" else "$timing $title (at $place)"
}

/** Per day, the all-day events first, then by start: the provider orders by instant, which puts an all-day event (UTC midnight) among the timed ones. */
private fun chronological(zone: ZoneId) = compareBy<CalendarEvent>(
    { if (it.allDay) allDayDate(it.startMillis) else Instant.ofEpochMilli(it.startMillis).atZone(zone).toLocalDate() },
    { !it.allDay },
    { it.startMillis },
)

/**
 * What the model reads: a header with the range, one line per event (at most [CALENDAR_MAX_EVENTS]), and a note when the
 * range was cut short or events were left out. Times are in the phone's time zone [zone].
 */
internal fun formatCalendar(events: List<CalendarEvent>, range: CalendarRange, zone: ZoneId): String {
    val shown = visibleEvents(events, range).sortedWith(chronological(zone))
    val span = if (range.from == range.to) "${range.from}" else "${range.from} to ${range.to}"
    val count = when {
        shown.isEmpty() -> "no events"
        shown.size > CALENDAR_MAX_EVENTS -> "$CALENDAR_MAX_EVENTS of ${shown.size} events"
        else -> "${shown.size} events"
    }
    return buildString {
        append("calendar ").append(span).append(" (device time ").append(zone.id).append("): ").append(count)
        shown.take(CALENDAR_MAX_EVENTS).forEach { append('\n').append(eventLine(it, zone)) }
        if (shown.size > CALENDAR_MAX_EVENTS) append("\nmore events not shown, ask for a shorter range")
        // The provider was cut at its row limit: events past it are missing even if few are shown.
        if (events.size >= CALENDAR_MAX_ROWS) append("\nlist may be incomplete, ask for a shorter range")
        if (range.capped) append("\nrange cut to $CALENDAR_MAX_DAYS days")
    }
}

/**
 * Reads the events of a day or a range from the Android calendar (TEN-179), recurring events expanded, visible calendars
 * only. Asks the permission at first use ([waitsForUser]: the runtime's timeout does not apply while the dialog is up).
 * An invite's title and place are written by whoever sent it: [returnsUntrustedContent].
 */
class ReadCalendarTool(
    private val calendar: CalendarSource,
    private val inForeground: () -> Boolean,
    private val clock: Clock = Clock.systemDefaultZone(),
) : Tool {
    override val name = "read_calendar"
    override val description =
        "Lists the events of the user's calendar: title, time, place. day is one day (default today), add to for a range. " +
            "Forms: today, yesterday, tomorrow, a weekday like friday, or YYYY-MM-DD. At most $CALENDAR_MAX_DAYS days."
    override val parametersSchema =
        """{"type":"object","properties":{"day":{"type":"string","description":"today, yesterday, tomorrow, a weekday like friday, or YYYY-MM-DD. Default today"},""" +
            """"to":{"type":"string","description":"last day of a range starting at day, included, same forms"}}}"""
    override val tier = ToolTier.AndroidPermission
    override val waitsForUser = true
    override val returnsUntrustedContent = true

    override suspend fun execute(argsJson: String): ToolResult {
        val args = try {
            JSONObject(argsJson.ifBlank { "{}" })
        } catch (e: JSONException) {
            return ToolResult.error("arguments are not a JSON object")
        }
        val zone = clock.zone
        val range = try {
            calendarRange(
                args.stringArg("day")?.trim()?.takeIf { it.isNotEmpty() },
                args.stringArg("to")?.trim()?.takeIf { it.isNotEmpty() },
                LocalDate.now(clock),
            )
        } catch (e: IllegalArgumentException) {
            return ToolResult.error(e.message ?: "invalid range")
        }
        // The permission dialog needs the screen: from the background the call would wait on a dialog nobody sees.
        if (!calendar.isGranted() && !inForeground()) return ToolResult.error(BACKGROUND_ERROR)
        refusalFor(calendar.access(), "calendar", CalendarRefusedText, CalendarBlockedText)?.let { return it }
        val events = try {
            calendar.events(
            range.from.atStartOfDay(zone).toInstant().toEpochMilli(),
            range.to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli(),
            CALENDAR_MAX_ROWS,
            )
        } catch (e: SecurityException) {
            // Revoked after the check: only the app settings can grant it again.
            return blockedResult("calendar", CalendarBlockedText)
        }
        return ToolResult.ok(formatCalendar(events, range, zone))
    }
}
