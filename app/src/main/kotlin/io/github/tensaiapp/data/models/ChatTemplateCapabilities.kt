package io.github.tensaiapp.data.models

import io.github.tensaiapp.engine.GgufInfo

/**
 * What a model can do, read off its Jinja chat template (TEN-154, TEN-149). No GGUF key says it, so
 * the template is the only evidence. One rule for both sources: the Hub reports the template of a
 * repo ([io.github.tensaiapp.hf.HfParser]) and the native header read returns the one of
 * a local file ([io.github.tensaiapp.engine.GgufInfo.chatTemplate]).
 */
object ChatTemplateCapabilities {
    // Only the `tools` variable: `tool_calls` also shows up in templates that merely render history.
    private val TOOLS = Regex("\\btools\\b")

    /** The template references the `tools` variable. */
    fun supportsTools(template: String?): Boolean = !template.isNullOrEmpty() && TOOLS.containsMatchIn(template)

    /** The GGUF carries a chat template of its own (TEN-159). Empty means llama.cpp will fall back to ChatML. */
    fun hasChatTemplate(info: GgufInfo): Boolean = info.chatTemplate.isNotEmpty()

    /** An installed model: the default template takes tools, or the GGUF ships a separate `tool_use` template. */
    fun supportsTools(info: GgufInfo): Boolean = info.hasToolUseTemplate || supportsTools(info.chatTemplate)

    /** An installed model, from the template like the Hub; the native flag only stands in when the template is empty. */
    fun supportsThinking(info: GgufInfo): Boolean =
        if (info.chatTemplate.isEmpty()) info.supportsThinking else supportsThinking(info.chatTemplate)

    /** Same rule as the native one (kept only as the fallback for an empty template): `enable_thinking` (Qwen3) or `<think>` blocks. Keep them equal. */
    fun supportsThinking(template: String?): Boolean =
        !template.isNullOrEmpty() && (template.contains("enable_thinking") || template.contains("<think>"))
}
