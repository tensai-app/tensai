package io.github.tensaiapp.agent

import java.time.Clock
import java.time.LocalDate
import org.json.JSONException
import org.json.JSONObject

/** Unit conversions (length, mass, volume, speed, data, time, temperature), all three arguments required. */
class ConvertTool : Tool {
    override val name = "convert"
    override val description = "Converts a value between units, e.g. 5 mi to km."
    override val parametersSchema =
        """{"type":"object","properties":{"value":{"type":"number"},"from":{"type":"string"},"to":{"type":"string"}},""" +
            """"required":["value","from","to"]}"""
    override val tier = ToolTier.Local

    override suspend fun execute(argsJson: String): ToolResult {
        val args = try {
            JSONObject(argsJson)
        } catch (e: JSONException) {
            return ToolResult.error("arguments are not a JSON object")
        }
        return try {
            val from = args.stringArg("from").orEmpty()
            val to = args.stringArg("to").orEmpty()
            if (from.isBlank() || to.isBlank()) throw MathException("give value, from and to")
            val value = args.stringArg("value").orEmpty().toDoubleOrNull()?.takeIf { it.isFinite() }
                ?: throw MathException("value must be a number")
            val result = UnitConversion.convert(value, from, to)
            if (result.isNaN() || result.isInfinite()) throw MathException("result is too large")
            ToolResult.ok("${MathExpression.format(value)} $from = ${MathExpression.format(result)} $to")
        } catch (e: MathException) {
            ToolResult.error(e.message ?: "invalid input")
        }
    }
}

/** A time shown in another IANA time zone. */
class ConvertTimeTool(private val clock: Clock = Clock.systemDefaultZone()) : Tool {
    override val name = "convert_time"
    override val description = "Converts a time between time zones, e.g. 14:00 Europe/Paris to America/New_York."
    override val parametersSchema =
        """{"type":"object","properties":{"time":{"type":"string","description":"HH:mm or yyyy-MM-dd HH:mm"},""" +
            """"from":{"type":"string","description":"IANA zone, e.g. Europe/Paris"},""" +
            """"to":{"type":"string","description":"IANA zone"}},"required":["time","from","to"]}"""
    override val tier = ToolTier.Local

    override suspend fun execute(argsJson: String): ToolResult {
        val args = try {
            JSONObject(argsJson)
        } catch (e: JSONException) {
            return ToolResult.error("arguments are not a JSON object")
        }
        return try {
            val time = args.stringArg("time").orEmpty()
            val fromName = args.stringArg("from").orEmpty()
            val toName = args.stringArg("to").orEmpty()
            if (time.isBlank() || fromName.isBlank() || toName.isBlank()) throw MathException("give time, from and to zones")
            val from = TimeZoneConversion.zone(fromName)
            val to = TimeZoneConversion.zone(toName)
            ToolResult.ok(TimeZoneConversion.convert(time, from, to, LocalDate.now(clock.withZone(from))))
        } catch (e: MathException) {
            ToolResult.error(e.message ?: "invalid input")
        }
    }
}
