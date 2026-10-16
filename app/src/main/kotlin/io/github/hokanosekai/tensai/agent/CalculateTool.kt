package io.github.hokanosekai.tensai.agent

import java.time.Clock
import java.time.LocalDate
import org.json.JSONException
import org.json.JSONObject

/**
 * Exact arithmetic, unit conversions and time zone conversions: a small model computes badly and
 * guesses at conversions. One tool with three input shapes, picked by which argument is present, so
 * the schema stays one short entry in the prompt.
 */
class CalculateTool(private val clock: Clock = Clock.systemDefaultZone()) : Tool {
    override val name = "calculate"
    override val description =
        "Exact math. expression: + - * / ^ % ( ), sqrt abs round floor ceil min max log ln sin cos tan, pi e. " +
            "Or value+from+to to convert units. Or time+from+to to convert a time between zones."
    override val parametersSchema =
        """{"type":"object","properties":{"expression":{"type":"string"},"value":{"type":"number"},""" +
            """"from":{"type":"string","description":"unit or time zone"},"to":{"type":"string"},""" +
            """"time":{"type":"string","description":"HH:mm or yyyy-MM-dd HH:mm"}}}"""
    override val tier = ToolTier.Local

    override suspend fun execute(argsJson: String): ToolResult {
        val args = try {
            JSONObject(argsJson)
        } catch (e: JSONException) {
            return ToolResult.error("arguments are not a JSON object")
        }
        return try {
            when {
                args.stringArg("expression") != null -> {
                    val expression = args.stringArg("expression").orEmpty()
                    ToolResult.ok("$expression = ${MathExpression.format(MathExpression.evaluate(expression))}")
                }
                args.stringArg("time") != null -> ToolResult.ok(convertTime(args))
                args.stringArg("value") != null -> ToolResult.ok(convertUnit(args))
                else -> ToolResult.error("give expression, or value+from+to, or time+from+to")
            }
        } catch (e: MathException) {
            ToolResult.error(e.message ?: "invalid input")
        }
    }

    private fun convertUnit(args: JSONObject): String {
        val from = args.stringArg("from").orEmpty()
        val to = args.stringArg("to").orEmpty()
        if (from.isBlank() || to.isBlank()) throw MathException("value needs from and to units")
        val value = args.stringArg("value").orEmpty().toDoubleOrNull() ?: throw MathException("value must be a number")
        val result = UnitConversion.convert(value, from, to)
        if (result.isNaN() || result.isInfinite()) throw MathException("result is too large")
        return "${MathExpression.format(value)} $from = ${MathExpression.format(result)} $to"
    }

    private fun convertTime(args: JSONObject): String {
        val device = clock.zone
        val from = args.stringArg("from").orEmpty().takeIf { it.isNotBlank() }?.let(TimeZoneConversion::zone) ?: device
        val to = args.stringArg("to").orEmpty().takeIf { it.isNotBlank() }?.let(TimeZoneConversion::zone) ?: device
        return TimeZoneConversion.convert(args.stringArg("time").orEmpty(), from, to, LocalDate.now(clock.withZone(from)))
    }
}
