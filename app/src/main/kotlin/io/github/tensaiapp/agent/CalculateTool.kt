package io.github.tensaiapp.agent

import org.json.JSONException
import org.json.JSONObject

/**
 * Exact arithmetic: a small model computes badly. One required argument, so the model never has to
 * weigh optional ones; unit and time zone conversions live in [ConvertTool] and [ConvertTimeTool].
 */
class CalculateTool : Tool {
    override val name = "calculate"
    override val description = "Exact math: + - * / ^ % ( ) sqrt abs round floor ceil min max log (base 10) ln sin cos tan (radians) pi e"
    override val parametersSchema =
        """{"type":"object","properties":{"expression":{"type":"string"}},"required":["expression"]}"""
    override val tier = ToolTier.Local

    override suspend fun execute(argsJson: String): ToolResult {
        val args = try {
            JSONObject(argsJson)
        } catch (e: JSONException) {
            return ToolResult.error("arguments are not a JSON object")
        }
        val expression = args.stringArg("expression") ?: return ToolResult.error("give an expression")
        return try {
            ToolResult.ok("$expression = ${MathExpression.format(MathExpression.evaluate(expression))}")
        } catch (e: MathException) {
            ToolResult.error(e.message ?: "invalid input")
        }
    }
}
