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
import io.github.hokanosekai.tensai.ui.theme.TensaiMono
import io.github.hokanosekai.tensai.ui.theme.TensaiShapes
import java.util.Locale

/**
 * A single turn in the chat. Per the design: a user message is a `surface-variant` bubble
 * right-aligned; an assistant reply is flat text, full width, no bubble — with a mono stats chip
 * (and, for the last one, a regenerate action) once it's finished.
 *
 * Markdown rendering and thinking blocks are TEN-33's job — this draws [ChatMessageUi.content]
 * as-is.
 *
 * @param onRegenerate non-null only for the conversation's last assistant message once it's
 *   finished streaming — see `ChatScreen`, which decides that.
 */
@Composable
fun MessageBubble(
    message: ChatMessageUi,
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
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (message.stats != null || onRegenerate != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    message.stats?.let { StatsChip(it) }
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

@Composable
private fun StatsChip(stats: MessageStatsUi, modifier: Modifier = Modifier) {
    Surface(shape = TensaiShapes.small, color = MaterialTheme.colorScheme.surface, modifier = modifier) {
        Text(
            text = "%.1f tok/s · %d tok · TTFT %dms".format(
                Locale.US,
                stats.tokPerSec,
                stats.tokensGenerated,
                stats.ttftMs,
            ),
            fontFamily = TensaiMono,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
