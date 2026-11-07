package io.github.tensaiapp.agent

import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import org.json.JSONException
import org.json.JSONObject

/**
 * Date, time and time zone of the device: a small model has no clock of its own. An optional IANA
 * `timezone` gives the time elsewhere in one call; a blank one, or one that names no place ([isPlaceholder]: "auto",
 * "System", "my timezone"), counts as absent. The time in a zone whose clocks read differently from the device's also
 * carries the device's own (`deviceTime`, `deviceTimezone`), so a local question still has its answer after a zone like
 * "UTC" it never needed.
 *
 * @param clock for tests; `null` reads the device's zone at each call, so a time zone change applies at once.
 */
class DateTimeTool(private val clock: Clock? = null) : Tool {
    override val name = "datetime"
    override val description =
        "Current date, time and time zone, here or in another IANA timezone. Call it for anything that depends on now."
    override val parametersSchema =
        """{"type":"object","properties":{"timezone":{"type":"string","description":"Only for another place, e.g. Asia/Tokyo. Omit for the user's local time."}}}"""
    override val tier = ToolTier.Local

    override suspend fun execute(argsJson: String): ToolResult {
        val args = try {
            JSONObject(argsJson)
        } catch (e: JSONException) {
            return ToolResult.error("arguments are not a JSON object")
        }
        val zoneName = args.stringArg("timezone").orEmpty()
        val clock = clock ?: Clock.systemDefaultZone()
        val now = try {
            if (isPlaceholder(zoneName)) ZonedDateTime.now(clock) else ZonedDateTime.now(clock.withZone(TimeZoneConversion.zone(zoneName)))
        } catch (e: MathException) {
            return ToolResult.error("timezone: ${e.message}")
        }
        val json = JSONObject()
            .put("iso", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            .put("date", now.toLocalDate().toString())
            .put("time", now.toLocalTime().withNano(0).toString())
            .put("dayOfWeek", now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH))
            .put("timezone", now.zone.id)
            .put("utcOffset", now.offset.id)
        val device = now.withZoneSameInstant(clock.zone)
        if (device.offset != now.offset) {
            json.put("deviceTime", device.toLocalDateTime().truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .put("deviceTimezone", clock.zone.id)
        }
        return ToolResult.ok(json.toString())
    }

    /** "2026-11-07 23:04 (Asia/Tokyo)", from [execute]'s JSON. */
    override fun fallbackAnswer(output: String): String? = runCatching {
        val json = JSONObject(output)
        "${json.getString("date")} ${json.getString("time").take(5)} (${json.getString("timezone")})"
    }.getOrNull()

    internal companion object {
        private val ZoneWords = setOf("time", "zone", "timezone", "tz", "the", "s")
        private val PlaceholderWords = setOf(
            "auto", "local", "system", "device", "phone", "current", "default", "here", "now", "user", "users", "mine",
            "my", "me", "your", "own",
        )

        /**
         * Whether [zone] names no place: blank, or only words like "auto", "local", "system", "my" or "current", with or
         * without "time" or "timezone", any case. What a model writes when it fills the argument for the user's own time.
         * A value with no ASCII word ("+09:00", "東京") is not one: it goes to the zone lookup.
         */
        fun isPlaceholder(zone: String): Boolean {
            if (zone.isBlank()) return true
            val words = zone.lowercase(Locale.ROOT).split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
            return words.isNotEmpty() && words.filter { it !in ZoneWords }.all { it in PlaceholderWords }
        }
    }
}
