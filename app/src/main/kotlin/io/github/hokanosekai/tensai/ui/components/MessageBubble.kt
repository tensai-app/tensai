package io.github.hokanosekai.tensai.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.hokanosekai.tensai.ui.screens.chat.ChatMessageUi
import io.github.hokanosekai.tensai.ui.screens.chat.MessageStatsUi
import io.github.hokanosekai.tensai.ui.screens.chat.TruncationReason
import io.github.hokanosekai.tensai.ui.theme.TensaiMono
import io.github.hokanosekai.tensai.ui.theme.TensaiShapes
import java.util.Locale

/**
 * A single turn in the chat. Per the design: a user message is a `surface-variant` bubble
 * right-aligned; an assistant reply is a [ThinkingBlock] (if [ChatMessageUi.thinking] is present)
 * followed by [ChatMessageUi.content] rendered as [MarkdownText], full width, no bubble — with a
 * mono stats chip (or a [FailedChip], if generation broke) and, for the last one, a regenerate
 * action, once it's finished.
 *
 * A user message is drawn as plain [Text], not [MarkdownText] — nothing the user typed should be
 * reinterpreted as markdown.
 *
 * @param isThinkingActive whether this message's [ChatMessageUi.thinking] is still being streamed
 *   — see `ChatScreen`, which derives that (last assistant message, generating, no content yet).
 *   Ignored (and unused) when [ChatMessageUi.thinking] is `null`.
 * @param onRegenerate non-null only for the conversation's last assistant message once it's
 *   finished streaming — see `ChatScreen`, which decides that.
 */
@Composable
fun MessageBubble(
    message: ChatMessageUi,
    isThinkingActive: Boolean,
    onRegenerate: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    if (message.isUser) {
        Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Surface(
                shape = TensaiShapes.large,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.widthIn(max = 320.dp),
            ) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    } else {
        Column(modifier = modifier.fillMaxWidth()) {
            message.thinking?.let { thinking ->
                ThinkingBlock(
                    thinking = thinking,
                    isActive = isThinkingActive,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            if (message.content.isNotEmpty()) {
                MarkdownText(
                    markdown = message.content,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }

            if (message.stats != null || message.failed || onRegenerate != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    message.stats?.let { StatsChip(it) }
                    if (message.failed) FailedChip()
                    onRegenerate?.let { regenerate ->
                        IconButton(onClick = regenerate, modifier = Modifier.size(28.dp)) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Regenerate",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Marks a reply whose generation ended on an engine error (TEN-52). Sits in the same row, with the
 * same mono chip shape and the same error colour as [StatsChip]'s "cut off" suffixes: a reply that
 * broke and a reply that hit its budget are the same kind of news for the reader, and the
 * regenerate action they both want is the very next thing in the row.
 *
 * Its own chip rather than a [MessageStatsUi.truncation] case because a failed decode reports no
 * stats to hang a suffix on. When a reply failed before producing anything at all, the exception's
 * own text is the message body (see `ChatViewModel.streamInto`) and this chip labels it.
 */
@Composable
private fun FailedChip(modifier: Modifier = Modifier) {
    Surface(shape = TensaiShapes.small, color = MaterialTheme.colorScheme.surface, modifier = modifier) {
        Text(
            text = "generation failed",
            fontFamily = TensaiMono,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun StatsChip(stats: MessageStatsUi, modifier: Modifier = Modifier) {
    Surface(shape = TensaiShapes.small, color = MaterialTheme.colorScheme.surface, modifier = modifier) {
        val figures = "%.1f tok/s · %d tok · TTFT %dms".format(
            Locale.US,
            stats.tokPerSec,
            stats.tokensGenerated,
            stats.ttftMs,
        )
        // A reply cut off at the budget used to be indistinguishable from a finished one — same
        // chip, same figures, the text just stopped (TEN-60). Reasoning cut short and answer cut
        // short call for different reactions from the user (wait longer vs. it's just short), so
        // TEN-61 tells them apart too — both stay a short suffix, not a sentence.
        val suffix = when (stats.truncation) {
            TruncationReason.None -> null
            TruncationReason.Reasoning -> "cut off while reasoning"
            TruncationReason.Response -> "cut off at limit"
        }
        Text(
            text = if (suffix != null) "$figures · $suffix" else figures,
            fontFamily = TensaiMono,
            style = MaterialTheme.typography.labelSmall,
            color = if (stats.truncation != TruncationReason.None) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
