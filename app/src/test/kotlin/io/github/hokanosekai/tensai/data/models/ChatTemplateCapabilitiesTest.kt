package io.github.hokanosekai.tensai.data.models

import io.github.hokanosekai.tensai.engine.GgufInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTemplateCapabilitiesTest {

    @Test
    fun toolsAreDetectedFromTheToolsVariableOrToolCalls() {
        assertTrue(ChatTemplateCapabilities.supportsTools("{%- if tools %}x{%- endif %}"))
    }

    @Test
    fun toolsNeedAWholeWordOrAToolCallMarker() {
        assertFalse(ChatTemplateCapabilities.supportsTools("{{ messages }} nothing special"))
        assertFalse(ChatTemplateCapabilities.supportsTools("toolset and toolbox only"))
        assertFalse(ChatTemplateCapabilities.supportsTools("<tool_call> {{ message.tool_calls }} renders history only"))
    }

    private fun info(template: String, toolUse: Boolean = false, nativeThinking: Boolean = false) = GgufInfo(
        architecture = "qwen3", name = "", quantLabel = "Q4_K_M", contextLength = 1, embeddingLength = 1,
        blockCount = 1, paramCount = 1, vocabSize = 1, fileSizeBytes = 0, supportsThinking = nativeThinking,
        chatTemplate = template, hasToolUseTemplate = toolUse,
    )

    @Test
    fun anInstalledModelReadsBothFromItsTemplateAndTheToolUseTemplate() {
        assertTrue(ChatTemplateCapabilities.supportsTools(info("{{ messages }}", toolUse = true)))
        assertTrue(ChatTemplateCapabilities.supportsTools(info("{% if tools %}x{% endif %}")))
        assertFalse(ChatTemplateCapabilities.supportsTools(info("{{ messages }}")))
        // The template wins over the native flag; the native flag only stands in for an empty one.
        assertFalse(ChatTemplateCapabilities.supportsThinking(info("{{ messages }}", nativeThinking = true)))
        assertTrue(ChatTemplateCapabilities.supportsThinking(info("", nativeThinking = true)))
    }

    @Test
    fun noTemplateMeansNoCapability() {
        assertFalse(ChatTemplateCapabilities.supportsTools(null))
        assertFalse(ChatTemplateCapabilities.supportsTools(""))
        assertFalse(ChatTemplateCapabilities.supportsThinking(null))
        assertFalse(ChatTemplateCapabilities.supportsThinking(""))
    }

    @Test
    fun thinkingIsDetectedFromEnableThinkingOrThinkBlocks() {
        assertTrue(ChatTemplateCapabilities.supportsThinking("{%- if enable_thinking is defined %}x{%- endif %}"))
        assertTrue(ChatTemplateCapabilities.supportsThinking("<|im_start|>assistant\n<think>\n"))
        assertFalse(ChatTemplateCapabilities.supportsThinking("{{ messages }} nothing special"))
        assertFalse(ChatTemplateCapabilities.supportsThinking("</think> alone is not an opener"))
    }
}
