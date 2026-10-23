package io.github.tensaiapp.agent

import org.json.JSONException
import org.json.JSONObject

/** The system clipboard, so [ClipboardTool] tests without Android. */
interface ClipboardAccess {
    /**
     * The text on the clipboard, `null` when it holds none or when Android hands back no clip (it does so for
     * an app that is not focused). May throw [SecurityException] when Android refuses the read.
     */
    fun read(): String?

    fun write(text: String)
}

/**
 * Reads or writes the clipboard text.
 *
 * Not [sendsOrDeletes]: `set` replaces what was copied, which is the whole point of asking and is
 * undone by copying again, so a confirmation on every call would only get in the way. `get` is the
 * sensitive side (a password manager's copy) and it stays local: the result goes to the on-device
 * model only.
 *
 * Android 10+ lets only the focused app read the clipboard. tensai is in front while the user chats, so it
 * works; otherwise Android answers with a null clip, which cannot be told from an empty clipboard (hence the
 * wording of [EMPTY]), or with a [SecurityException], reported as [UNAVAILABLE].
 *
 * Known risk, partly addressed: `get` and `set` run without asking, so text from a web page or a file the model read
 * could steer it into copying something to or from the clipboard. What `get` read cannot leave the phone unseen:
 * `web_fetch` asks for any link neither the user nor a search gave, and `open_url` always asks. What `get` reads was
 * copied from anywhere, so it counts as [returnsUntrustedContent]: any later `remember` in the conversation asks.
 * Confirming `get` itself is a later decision.
 */
class ClipboardTool(private val clipboard: ClipboardAccess) : Tool {
    override val name = "clipboard"
    override val description = "Reads (action get) or replaces (action set, with text) the text on the clipboard."
    override val parametersSchema =
        """{"type":"object","properties":{"action":{"type":"string","enum":["get","set"]},"text":{"type":"string"}},"required":["action"]}"""
    override val tier = ToolTier.Local
    override val returnsUntrustedContent = true

    override suspend fun execute(argsJson: String): ToolResult {
        val args = try {
            JSONObject(argsJson)
        } catch (e: JSONException) {
            return ToolResult.error("arguments are not a JSON object")
        }
        return when (val action = args.stringArg("action").orEmpty()) {
            "get" -> get()
            "set" -> {
                val text = args.stringArg("text") ?: return ToolResult.error("set needs text")
                try {
                    clipboard.write(text)
                } catch (e: SecurityException) {
                    return ToolResult.error(UNAVAILABLE)
                }
                ToolResult.ok("copied")
            }
            else -> ToolResult.error("action must be get or set, got '$action'")
        }
    }

    private fun get(): ToolResult {
        val text = try {
            clipboard.read()
        } catch (e: SecurityException) {
            return ToolResult.error(UNAVAILABLE)
        }
        return when {
            text.isNullOrEmpty() -> ToolResult.ok(EMPTY)
            text.length > MAX_CHARS -> ToolResult.ok(text.take(MAX_CHARS) + "... (truncated, ${text.length} characters)")
            else -> ToolResult.ok(text)
        }
    }

    companion object {
        const val MAX_CHARS = 2000
        const val EMPTY = "The clipboard is empty, or Android blocked reading it."
        const val UNAVAILABLE = "the clipboard is not available: Android only lets the app in the foreground use it"
    }
}
