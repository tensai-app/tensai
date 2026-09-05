package io.github.hokanosekai.tensai.ui.components

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.hokanosekai.tensai.ui.theme.TensaiShapes
import io.github.hokanosekai.tensai.ui.theme.tensaiTints

/** Max length of the short label shown in [InputPill]'s model chip — the full name lives on [io.github.hokanosekai.tensai.ui.navigation.TensaiTopBar]'s pill. */
private const val ModelChipLabelMaxLength = 18

/**
 * Trims a model name down to what fits [InputPill]'s chip without squeezing the text field —
 * e.g. "Qwen3-4B-Instruct-Q4_K_M" -> "Qwen3-4B-Instruct…". Shared
 * by both of the pill's callers ([io.github.hokanosekai.tensai.ui.screens.chat.ChatScreen] and
 * Home) so the chip reads the same on either screen.
 */
internal fun shortModelLabel(name: String): String =
    if (name.length <= ModelChipLabelMaxLength) name else name.take(ModelChipLabelMaxLength).trimEnd() + "…"

/**
 * The app's single text-entry affordance — a pill holding the active model chip, a plain text
 * field, and a send/stop action. Shared by
 * [io.github.hokanosekai.tensai.ui.screens.chat.ChatScreen] and, per TEN-37, Home.
 *
 * V1 is deliberately spare (no attach/mic actions, per the design): the trailing button toggles
 * between send and stop as [isGenerating] flips, and a lavender ring animates around the whole
 * pill for the same duration. The ring respects the system's "remove animations" accessibility
 * toggle the same way [KanjiGlyph] does — reduced motion gets a static ring, not a spinning one.
 */
@Composable
fun InputPill(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isGenerating: Boolean,
    modelLabel: String,
    onModelClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        if (isGenerating) {
            GenerationRing(modifier = Modifier.matchParentSize())
        }

        Surface(
            shape = TensaiShapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(3.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                ModelChip(label = modelLabel, onClick = onModelClick)

                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { innerField ->
                        if (value.isEmpty()) {
                            Text(
                                text = "Message tensai…",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerField()
                    },
                )

                IconButton(
                    onClick = { if (isGenerating) onStop() else onSend() },
                    enabled = isGenerating || value.isNotBlank(),
                ) {
                    Icon(
                        imageVector = if (isGenerating) Icons.Default.Stop else Icons.AutoMirrored.Filled.Send,
                        contentDescription = if (isGenerating) "Stop" else "Send",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

/**
 * The animated (or, under reduced motion, static) ring drawn behind the pill while generating.
 *
 * The bright arc travels *around* the pill's fixed perimeter — the geometry never moves. We clip
 * to the border band (outer rounded-rect minus inner) and rotate only the sweep-gradient fill
 * inside that clip. Rotating the whole bordered box instead (an earlier approach) spun the
 * rounded-rect corners as a rigid body, which reads as the ring turning on itself rather than
 * light running along the edge.
 */
@Composable
private fun GenerationRing(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val animatorDurationScale = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }
    val infiniteTransition = rememberInfiniteTransition(label = "inputPillRing")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = if (animatorDurationScale == 0f) snap() else tween(durationMillis = 2400, easing = LinearEasing),
        ),
        label = "inputPillRingAngle",
    )

    val primary = MaterialTheme.colorScheme.primary
    val strokePx = with(LocalDensity.current) { 2.dp.toPx() }
    val radiusPx = with(LocalDensity.current) { 28.dp.toPx() }

    Box(
        modifier = modifier.drawWithCache {
            // The bright arc is a narrow wedge that trails off into transparency both ways —
            // matches the DS conic-gradient (opaque at the seam, transparent across the middle).
            val brush = Brush.sweepGradient(
                0.0f to primary,
                0.15f to Color.Transparent,
                0.85f to Color.Transparent,
                1.0f to primary,
                center = Offset(size.width / 2f, size.height / 2f),
            )
            val outer = Path().apply {
                addRoundRect(RoundRect(Rect(0f, 0f, size.width, size.height), CornerRadius(radiusPx, radiusPx)))
            }
            val inner = Path().apply {
                addRoundRect(
                    RoundRect(
                        Rect(strokePx, strokePx, size.width - strokePx, size.height - strokePx),
                        CornerRadius(radiusPx - strokePx, radiusPx - strokePx),
                    ),
                )
            }
            // The band = outer minus inner: this is the fixed pill border the arc runs along.
            val band = Path.combine(PathOperation.Difference, outer, inner)
            onDrawBehind {
                clipPath(band) {
                    rotate(angle) { drawRect(brush) }
                }
            }
        },
    )
}

@Composable
private fun ModelChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, shape = TensaiShapes.small, color = MaterialTheme.tensaiTints.primary12, modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}
