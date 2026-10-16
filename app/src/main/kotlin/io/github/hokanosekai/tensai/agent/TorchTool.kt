package io.github.hokanosekai.tensai.agent

import org.json.JSONException
import org.json.JSONObject

/** The camera flash used as a torch, so [TorchTool] tests without Android. */
interface TorchControl {
    /** False when the device has no flash unit. */
    val available: Boolean

    /** @throws IllegalStateException when the flash cannot be switched, with a message fit for the model. */
    fun set(on: Boolean)
}

/** Turns the flashlight on or off. Reversible and harmless, so no confirmation. */
class TorchTool(private val torch: TorchControl) : Tool {
    override val name = "torch"
    override val description = "Turns the phone's flashlight on or off."
    override val parametersSchema =
        """{"type":"object","properties":{"state":{"type":"string","enum":["on","off"]}},"required":["state"]}"""
    override val tier = ToolTier.Local

    override suspend fun execute(argsJson: String): ToolResult {
        val args = try {
            JSONObject(argsJson)
        } catch (e: JSONException) {
            return ToolResult.error("arguments are not a JSON object")
        }
        val on = when (val state = args.stringArg("state").orEmpty().lowercase()) {
            "on" -> true
            "off" -> false
            else -> return ToolResult.error("state must be on or off, got '$state'")
        }
        if (!torch.available) return ToolResult.error("this device has no flashlight")
        return try {
            torch.set(on)
            ToolResult.ok(if (on) "torch on" else "torch off")
        } catch (e: IllegalStateException) {
            ToolResult.error(e.message ?: "the flashlight is not available right now")
        }
    }
}
