package io.github.tensaiapp.agent

import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import org.json.JSONException
import org.json.JSONObject

/**
 * Date, time and time zone of the device: a small model has no clock of its own. An optional IANA
 * `timezone` gives the time elsewhere in one call, a blank one counts as absent.
 */
class DateTimeTool(private val clock: Clock = Clock.systemDefaultZone()) : Tool {
    override val name = "datetime"
    override val description =
        "Returns the current date, time and time zone of the user's device, or of another place given its IANA timezone. " +
            "Call it for anything that depends on now."
    override val parametersSchema =
        """{"type":"object","properties":{"timezone":{"type":"string","description":"IANA zone, e.g. Asia/Tokyo"}}}"""
    override val tier = ToolTier.Local

    override suspend fun execute(argsJson: String): ToolResult {
        val args = try {
            JSONObject(argsJson)
        } catch (e: JSONException) {
            return ToolResult.error("arguments are not a JSON object")
        }
        val zoneName = args.stringArg("timezone").orEmpty()
        val now = try {
            if (zoneName.isBlank()) ZonedDateTime.now(clock) else ZonedDateTime.now(clock.withZone(TimeZoneConversion.zone(zoneName)))
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
        return ToolResult.ok(json.toString())
    }
}
