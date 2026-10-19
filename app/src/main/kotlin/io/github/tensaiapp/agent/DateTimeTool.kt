package io.github.tensaiapp.agent

import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import org.json.JSONObject

/** Date, time and time zone of the device: a small model has no clock of its own. */
class DateTimeTool(private val clock: Clock = Clock.systemDefaultZone()) : Tool {
    override val name = "datetime"
    override val description = "Returns the current date, time and time zone of the user's device. Call it for anything that depends on now."
    override val parametersSchema = """{"type":"object","properties":{}}"""
    override val tier = ToolTier.Local

    override suspend fun execute(argsJson: String): ToolResult {
        val now = ZonedDateTime.now(clock)
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
