package io.github.hokanosekai.tensai.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.layout.layout
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.ripple
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.hokanosekai.tensai.R
import io.github.hokanosekai.tensai.ui.theme.TensaiMono
import io.github.hokanosekai.tensai.ui.theme.TensaiShapes
import io.github.hokanosekai.tensai.ui.theme.tensaiTints

/**
 * The app's single text-entry affordance: a rounded surface with the text
 * field on top and, below it, a model chip (left) and the send/stop action (right). Shared by
 * [io.github.hokanosekai.tensai.ui.screens.chat.ChatScreen] and, per TEN-37, Home.
 *
 * V1 is deliberately spare (no attach/mic actions, per the design): the trailing button toggles
 * between send and stop as [canStop] flips, and a lavender ring animates around the whole
 * pill while [isGenerating]. The ring respects the system's "remove animations" accessibility
 * toggle the same way [KanjiGlyph] does — reduced motion gets a static ring, not a spinning one.
 *
 * @param modelLabel the resident model's name, or the "no model" label (TEN-129).
 * @param onModelClick tap on the model chip: opens the Models screen.
 * @param isGenerating a generation is running — app-wide, not necessarily this screen's. Shows the
 *   ring and blocks sending: the queue runs one generation at a time, and a send issued now would
 *   only be rejected.
 * @param canStop the running generation is one this screen may stop (TEN-79), which is what turns
 *   the button into a stop button. Defaults to [isGenerating] for callers with a single
 *   conversation in play; [io.github.hokanosekai.tensai.ui.screens.chat.ChatScreen] passes the
 *   narrower answer, so a reply streaming in *another* conversation shows as busy rather than
 *   offering a stop that would cut off something the user cannot see.
 * @param translucent lets the content scrolling under the pill show faintly through it (TEN-133).
 *   Chat overlays the pill on its message list; Home keeps the opaque default.
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
    canStop: Boolean = isGenerating,
    translucent: Boolean = false,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        if (isGenerating) {
            GenerationRing(modifier = Modifier.matchParentSize())
        }

        Surface(
            shape = TensaiShapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceVariant.let { if (translucent) it.copy(alpha = TranslucentAlpha) else it },
            modifier = Modifier.fillMaxWidth().padding(3.dp),
        ) {
            // The bottom row sits ControlInset from the pill's side and bottom edges, so the chip and
            // the send circle (radius ControlHeight / 2) are concentric with the pill's 28dp corners:
            // inner radius = outer radius minus inset (TEN-134).
            Column(modifier = Modifier.padding(start = ControlInset, end = ControlInset, top = 6.dp, bottom = ControlInset)) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    decorationBox = { innerField ->
                        if (value.isEmpty()) {
                            Text(
                                text = stringResource(R.string.input_placeholder),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        innerField()
                    },
                )

                // SpaceBetween rather than a weighted spacer: two weights split the row in half and
                // cut a model name short with room to spare. The chip takes what the button leaves.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ModelChip(
                        label = modelLabel,
                        onClick = onModelClick,
                        modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
                    )
                    SendButton(
                        canStop = canStop,
                        onClick = { if (canStop) onStop() else onSend() },
                        // Busy without a stop to offer leaves the button disabled rather than enabled
                        // on a send the queue would refuse, with the ring still turning to say why.
                        enabled = canStop || (!isGenerating && value.isNotBlank()),
                    )
                }
            }
        }
    }
}

/** The resident model's name, bottom-left of the pill: a tap opens Models, like the top bar pill used to (TEN-129). */
@Composable
private fun ModelChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    // No 48dp minimum here: it would make the row taller than the chip (TEN-134). The chip is wide,
    // a 32dp tall target is still easy to hit.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        ModelChipSurface(label, onClick, modifier)
    }
}

@Composable
private fun ModelChipSurface(label: String, onClick: () -> Unit, modifier: Modifier) {
    Surface(onClick = onClick, shape = CircleShape, color = MaterialTheme.tensaiTints.primary12, modifier = modifier) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.height(ControlHeight).padding(horizontal = 12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = TensaiMono),
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Pill ground opacity when [InputPill]'s `translucent`: text beneath stays a faint shadow, never readable. */
private const val TranslucentAlpha = 0.95f

/** Shared by the model chip and the send button so the pill's bottom row lines up (TEN-129). */
private val ControlHeight = 32.dp

/** Pill corner radius (28dp) minus the controls' radius (16dp): what keeps them concentric. */
private val ControlInset = 12.dp

/**
 * Send (an up arrow) or stop, in a tonal violet circle as tall as the model chip: the circle in a
 * stronger primary tint, the glyph in full primary, lighter than its ground in Calm Night. Disabled
 * fades both rather than greying them out, so the button stays part of the pill.
 */
@Composable
private fun SendButton(canStop: Boolean, onClick: () -> Unit, enabled: Boolean) {
    val tints = MaterialTheme.tensaiTints
    val primary = MaterialTheme.colorScheme.primary
    // Lays out as the 32dp circle only, so the row is as tall as its controls and the gap under
    // them matches the gap beside them (TEN-134); the 48dp touch target overhangs that slot.
    Box(
        modifier = Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0, maxWidth = Constraints.Infinity, maxHeight = Constraints.Infinity))
            val slot = ControlHeight.roundToPx()
            layout(slot, slot) { placeable.place((slot - placeable.width) / 2, (slot - placeable.height) / 2) }
        },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(TouchTarget)
                .clickable(
                    enabled = enabled,
                    role = Role.Button,
                    interactionSource = null,
                    indication = ripple(bounded = false, radius = ControlHeight / 2),
                    onClick = onClick,
                ),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(ControlHeight)
                    .background(if (enabled) tints.primary24 else tints.primary08, CircleShape),
            ) {
                Icon(
                    imageVector = if (canStop) Icons.Rounded.Stop else Icons.Rounded.ArrowUpward,
                    contentDescription = stringResource(if (canStop) R.string.input_stop else R.string.input_send),
                    tint = if (enabled) primary else primary.copy(alpha = 0.38f),
                    modifier = Modifier.size(if (canStop) 16.dp else 20.dp),
                )
            }
        }
    }
}

private val TouchTarget = 48.dp

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
    val reducedMotion = animatorDurationScale == 0f
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
                    if (reducedMotion) {
                        // Under "remove animations" the arc cannot travel, and freezing it left a
                        // lone wedge fading out mid-edge that read as a rendering glitch. A full,
                        // still ring says "generating" without motion.
                        drawRect(primary)
                    } else {
                        rotate(angle) { drawRect(brush) }
                    }
                }
            }
        },
    )
}
