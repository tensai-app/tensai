package io.github.tensaiapp.ui.components

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.github.tensaiapp.R
import io.github.tensaiapp.ui.screens.chat.ChatMessageUi
import io.github.tensaiapp.ui.screens.chat.MessageStatsUi
import io.github.tensaiapp.ui.screens.chat.ReplyEnding
import io.github.tensaiapp.ui.screens.chat.TruncationReason
import io.github.tensaiapp.ui.theme.TensaiMono
import io.github.tensaiapp.ui.theme.TensaiShapes
import io.github.tensaiapp.ui.theme.tensaiTints
import java.util.Locale

/**
 * A single turn in the chat. Per the design: a user message is a `surface-variant` bubble
 * right-aligned; an assistant reply is a [ThinkingBlock] (if [ChatMessageUi.thinking] is present)
 * followed by [ChatMessageUi.content] rendered as [MarkdownText], full width, no bubble — with a
 * mono stats chip (or an [EndingChip], if generation broke or was stopped) and, for the last one, a
 * regenerate action, once it's finished. Long-pressing a message opens [MessageActionsMenu] and a
 * finished reply carries a copy button (TEN-184), see [copyableText] and [showsCopyButton].
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
    isGenerating: Boolean,
    modifier: Modifier = Modifier,
) {
    val copyText = copyableText(message)?.takeUnless { isGenerating }
    val copiedToast = stringResource(R.string.chat_copied)
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    val copy = { text: String ->
        clipboard.setText(AnnotatedString(text))
        // From Android 13 the system shows its own clipboard preview, a second confirmation would double it.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, copiedToast, Toast.LENGTH_SHORT).show()
        }
    }
    // The one list of actions the menu draws: Edit, Regenerate and Select text join it without a new gesture.
    val actions = remember(copyText) {
        listOfNotNull(
            copyText?.let { text -> MessageAction(R.string.chat_copy, Icons.Default.ContentCopy) { copy(text) } },
        )
    }
    val copyLabel = stringResource(R.string.chat_copy)
    val bubbleSemantics = Modifier.semantics {
        if (copyText != null) customActions = listOf(CustomAccessibilityAction(copyLabel) { copy(copyText); true })
    }
    // Long press only on the message body: the reasoning block keeps its own tap and ignores long presses.
    // The menu opens under the finger: DropdownMenu offsets from its anchor's bottom-start, so the press
    // point is kept with the anchor's height.
    var pressAt by remember { mutableStateOf(Offset.Zero) }
    var anchorHeight by remember { mutableIntStateOf(0) }
    val longPress = if (actions.isEmpty()) {
        Modifier
    } else {
        Modifier
            .onSizeChanged { anchorHeight = it.height }
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { at ->
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    pressAt = at
                    menuOpen = true
                })
            }
    }
    val menuOffset = with(LocalDensity.current) { DpOffset(pressAt.x.toDp(), (pressAt.y - anchorHeight).toDp()) }
    if (message.isUser) {
        Box(modifier = modifier.fillMaxWidth().then(bubbleSemantics), contentAlignment = Alignment.CenterEnd) {
            // Design: at most 82% of the row, 20dp corners with the bottom-end one tucked to 6dp,
            // 15/22 body text (between bodyMedium and bodyLarge, so a local style).
            Box(modifier = Modifier.fillMaxWidth(0.82f), contentAlignment = Alignment.CenterEnd) {
                // The bubble itself anchors the menu, not the 82% box around it.
                Box(modifier = longPress) {
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
                    MessageActionsMenu(expanded = menuOpen, actions = actions, offset = menuOffset, onDismiss = { menuOpen = false })
                }
            }
        }
    } else {
        Column(modifier = modifier.fillMaxWidth().then(bubbleSemantics)) {
            message.thinking?.let { thinking ->
                ThinkingBlock(
                    thinking = thinking,
                    isActive = isThinkingActive,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }

            val showCopy = showsCopyButton(message, isGenerating)
            if (message.content.isNotEmpty()) {
                Box(modifier = longPress) {
                    MarkdownText(
                        markdown = message.content,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    MessageActionsMenu(expanded = menuOpen, actions = actions, offset = menuOffset, onDismiss = { menuOpen = false })
                }
            }

            if (message.stats != null || message.ending != ReplyEnding.Normal || onRegenerate != null || showCopy) {
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
                    if (showCopy && copyText != null) {
                        IconButton(onClick = { copy(copyText) }, modifier = Modifier.size(28.dp)) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = stringResource(R.string.chat_copy_reply),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
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

/** One entry of [MessageActionsMenu]. */
internal class MessageAction(val labelRes: Int, val icon: ImageVector, val onClick: () -> Unit)

/** The long-press menu of a message, styled like the sort menu of the Discover pane. Draws nothing when [actions] is empty. */
@Composable
private fun MessageActionsMenu(expanded: Boolean, actions: List<MessageAction>, offset: DpOffset, onDismiss: () -> Unit) {
    if (actions.isEmpty()) return
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = offset,
        shape = TensaiShapes.medium,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, MaterialTheme.tensaiTints.outline),
    ) {
        actions.forEach { action ->
            DropdownMenuItem(
                text = { Text(text = stringResource(action.labelRes), fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface) },
                leadingIcon = {
                    Icon(
                        imageVector = action.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                },
                onClick = {
                    onDismiss()
                    action.onClick()
                },
            )
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
        val figures = statsFigures(stats)
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

/** The figures part of the stats chip. tok/s is left out when it could not be measured (TEN-109). */
internal fun statsFigures(stats: MessageStatsUi): String =
    if (stats.tokPerSec > 0f) {
        "%.1f tok/s · %d tok · TTFT %dms".format(Locale.US, stats.tokPerSec, stats.tokensGenerated, stats.ttftMs)
    } else {
        "%d tok · TTFT %dms".format(Locale.US, stats.tokensGenerated, stats.ttftMs)
    }
