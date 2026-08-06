package io.github.hokanosekai.tensai.ui.components

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hokanosekai.tensai.ui.theme.TensaiMotion
import io.github.hokanosekai.tensai.ui.theme.TensaiShapes
import io.github.hokanosekai.tensai.ui.theme.tensaiTints

/**
 * The collapsible "reasoning" card shown above an assistant reply's [MarkdownText] when
 * [ChatMessageUi.thinking][io.github.hokanosekai.tensai.ui.screens.chat.ChatMessageUi.thinking] is
 * non-null (TEN-33) — the model's separated `<think>...</think>` content, see
 * [io.github.hokanosekai.tensai.data.chat.ThinkingStreamParser].
 *
 * Collapsed by default. The header's label is deliberately time-free: there is no dedicated
 * measurement of how long the model spent thinking (that would need either persisting a
 * start/end timestamp pair — a schema change TEN-33 doesn't otherwise need — or estimating from
 * token throughput, which would be a fabricated number dressed up as a real one), so this shows
 * "Thinking…" while [isActive] and "Reasoning" once it's settled, rather than inventing a duration.
 *
 * @param thinking the reasoning text — rendered plainly (no markdown), matching the DS's body copy
 *   for this block.
 * @param isActive whether the reply this belongs to is still actively streaming its thinking
 *   (drives the header's label and the shimmer) — see `ChatScreen`'s `isThinkingActive` for how
 *   that's derived.
 */
@Composable
fun ThinkingBlock(
    thinking: String,
    isActive: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(TensaiMotion.DurBase, easing = TensaiMotion.EasingStandard),
        label = "thinkingChevron",
    )

    Surface(
        shape = TensaiShapes.medium,
        color = MaterialTheme.tensaiTints.primary08,
        border = BorderStroke(1.dp, MaterialTheme.tensaiTints.primary12),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            Box(modifier = Modifier.clickable { expanded = !expanded }) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Psychology,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = if (isActive) "Thinking…" else "Reasoning",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                    Icon(
                        imageVector = Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp).rotate(chevronRotation),
                    )
                }
                if (isActive) ThinkingShimmer(modifier = Modifier.matchParentSize())
            }

            if (expanded) {
                Text(
                    text = thinking,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 40.dp, end = 12.dp, bottom = 12.dp),
                )
            }
        }
    }
}

/**
 * The lavender shimmer that sweeps across [ThinkingBlock]'s header while thinking is active —
 * skipped under the system's "remove animations" accessibility setting, same convention (and same
 * [Settings.Global.ANIMATOR_DURATION_SCALE] read) as [InputPill]'s generation ring.
 */
@Composable
private fun ThinkingShimmer(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val reducedMotion = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    if (reducedMotion) return

    val infiniteTransition = rememberInfiniteTransition(label = "thinkingShimmer")
    val sweep by infiniteTransition.animateFloat(
        initialValue = -0.4f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(animation = tween(1400, easing = LinearEasing)),
        label = "thinkingShimmerSweep",
    )
    val highlight = MaterialTheme.tensaiTints.primary24

    Box(
        modifier = modifier.drawWithCache {
            val bandWidth = size.width * 0.4f
            val brush = Brush.linearGradient(
                colors = listOf(Color.Transparent, highlight, Color.Transparent),
                start = Offset(size.width * sweep - bandWidth, 0f),
                end = Offset(size.width * sweep + bandWidth, 0f),
            )
            onDrawBehind { drawRect(brush) }
        },
    )
}
