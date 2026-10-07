package io.github.hokanosekai.tensai.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.hokanosekai.tensai.R
import io.github.hokanosekai.tensai.ui.screens.chat.ChatMessageUi
import io.github.hokanosekai.tensai.ui.screens.chat.MessageStatsUi
import io.github.hokanosekai.tensai.ui.screens.chat.ReplyEnding
import io.github.hokanosekai.tensai.ui.screens.chat.TruncationReason
import io.github.hokanosekai.tensai.ui.theme.TensaiMono
import io.github.hokanosekai.tensai.ui.theme.TensaiShapes
import java.util.Locale

/**
 * A single turn in the chat. Per the design: a user message is a `surface-variant` bubble
 * right-aligned; an assistant reply is a [ThinkingBlock] (if [ChatMessageUi.thinking] is present)
 * followed by [ChatMessageUi.content] rendered as [MarkdownText], full width, no bubble — with a
 * mono stats chip (or an [EndingChip], if generation broke or was stopped) and, for the last one, a
 * regenerate action, once it's finished.
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
            // Design: at most 82% of the row, 20dp corners with the bottom-end one tucked to 6dp,
            // 15/22 body text (between bodyMedium and bodyLarge, so a local style).
            Box(modifier = Modifier.fillMaxWidth(0.82f), contentAlignment = Alignment.CenterEnd) {
                Surface(
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 6.dp, bottomStart = 20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        text = message.content,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                    )
                }
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

            if (message.stats != null || message.ending != ReplyEnding.Normal || onRegenerate != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    message.stats?.let { StatsChip(it) }
                    // Exhaustive over one field, so the row can only ever carry one of these —
                    // "generation failed" and "stopped" answer the same question and cannot both
                    // be the answer. See ReplyEnding.
                    when (message.ending) {
                        ReplyEnding.Normal -> Unit
                        ReplyEnding.Failed -> EndingChip(
                            text = stringResource(R.string.chat_reply_failed),
                            color = MaterialTheme.colorScheme.error,
                        )
                        ReplyEnding.Stopped -> EndingChip(
                            text = stringResource(R.string.chat_reply_stopped),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    onRegenerate?.let { regenerate ->
                        IconButton(onClick = regenerate, modifier = Modifier.size(28.dp)) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.chat_regenerate),
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
 * Marks *how* a reply's generation ended, when that is worth a word — "generation failed" (TEN-52)
 * or "stopped" (TEN-39). Sits in the same row as [StatsChip], with the same mono chip shape, so a
 * reply that broke, one that was cut short and one that hit its budget all read the same way, and
 * the regenerate action all three want is the very next thing in the row.
 *
 * Its own chip rather than a [MessageStatsUi.truncation] suffix because neither a failed nor a
 * stopped decode reports stats to hang a suffix on.
 *
 * [color] is the whole difference between the two: a failure is `error`, a stop is
 * `onSurfaceVariant`. Stopping is not a fault and must not be dressed as one — the user did it on
 * purpose. The chip is there so that they can still tell, later, that this particular reply was
 * theirs to cut short rather than the model's to end.
 */
@Composable
private fun EndingChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Surface(shape = TensaiShapes.small, color = MaterialTheme.colorScheme.surface, modifier = modifier) {
        Text(
            text = text,
            fontFamily = TensaiMono,
            style = MaterialTheme.typography.labelSmall,
            color = color,
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
            TruncationReason.Reasoning -> stringResource(R.string.chat_stats_cut_reasoning)
            TruncationReason.Response -> stringResource(R.string.chat_stats_cut_limit)
            // Not "at limit": the limit was never reached (TEN-79). Worded so a reader who is
            // about to go raise max-tokens doesn't — the banner above says the rest.
            TruncationReason.Context -> stringResource(R.string.chat_stats_cut_context)
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
