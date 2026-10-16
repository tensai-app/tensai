package io.github.hokanosekai.tensai.ui.components

import io.github.hokanosekai.tensai.ui.screens.chat.ChatMessageUi

/**
 * What "copy" puts on the clipboard for [message] (TEN-184): the user's text, or the reply's markdown
 * source. Never the reasoning, the stats or the tool lines, and only this one message of a turn that
 * the tool calls split into several. `null` when there is nothing to copy, i.e. a call-only assistant
 * message that renders just a reasoning block.
 */
internal fun copyableText(message: ChatMessageUi): String? = message.content.takeIf { it.isNotEmpty() }

/**
 * Whether a reply shows the copy button in its stats row. Not under user messages (they copy from the
 * long-press menu), not while [isGenerating] this very message, and not without content. Stats are not
 * required: a stopped reply and rows from before stats existed still copy.
 */
internal fun showsCopyButton(message: ChatMessageUi, isGenerating: Boolean): Boolean =
    !message.isUser && !isGenerating && copyableText(message) != null
