package io.github.hokanosekai.tensai.ui.screens.chat

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.hokanosekai.tensai.R
import io.github.hokanosekai.tensai.ui.components.rememberReducedMotion
import io.github.hokanosekai.tensai.ui.theme.tensaiColors
import io.github.hokanosekai.tensai.ui.theme.tensaiTints

private const val StartAngle = -90f
private const val BusySweepFraction = 0.18f

/**
 * The chat header's context gauge (TEN-160): [state]'s fill as an arc from 12 o'clock, clockwise,
 * in the tone [contextTone] picks. With no model the track is dashed and empty. While [busy] the
 * arc holds its value and a short, faint one circles the track, unless the system's "remove
 * animations" is on.
 */
@Composable
fun ContextRing(
    state: ContextUsageState,
    busy: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fraction = (state as? ContextUsageState.Loaded)?.fraction() ?: 0f
    val tone = when (contextTone(fraction)) {
        ContextTone.Primary -> MaterialTheme.colorScheme.primary
        ContextTone.Warning -> MaterialTheme.tensaiColors.warning
        ContextTone.Error -> MaterialTheme.colorScheme.error
    }
    val track = MaterialTheme.tensaiTints.outlineStrong
    val noModelTrack = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val description = when (state) {
        ContextUsageState.NoModel -> stringResource(R.string.context_ring_no_model)
        is ContextUsageState.Loaded -> stringResource(
            R.string.context_ring_description,
            contextPercent(state.used, state.length),
            formatTokens(state.used),
            formatContextLength(state.length),
        )
    }

    // Read in the draw scope only, so a frame of the spin redraws the canvas without recomposing.
    // Created only while spinning: an idle infinite transition would keep asking for frames.
    val spinning = busy && !rememberReducedMotion()
    val rotation = if (spinning) {
        rememberInfiniteTransition(label = "contextRingBusy").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 1600, easing = LinearEasing), RepeatMode.Restart),
            label = "contextRingBusyAngle",
        )
    } else {
        null
    }
    val density = LocalDensity.current
    val strokePx = with(density) { 2.5.dp.toPx() }
    val dashPx = with(density) { floatArrayOf(2.2.dp.toPx(), 2.38.dp.toPx()) }

    // An IconButton, like the ⋮ next to it: same 48dp target and same 40dp pressed state.
    IconButton(
        onClick = onClick,
        modifier = modifier.semantics { contentDescription = description },
    ) {
        Canvas(modifier = Modifier.size(20.dp)) {
            val radius = 8.75.dp.toPx()
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2, radius * 2)
            if (state is ContextUsageState.NoModel) {
                drawCircle(
                    color = noModelTrack,
                    radius = radius,
                    style = Stroke(width = strokePx, pathEffect = PathEffect.dashPathEffect(dashPx)),
                )
            } else {
                drawCircle(color = track, radius = radius, style = Stroke(width = strokePx))
                if (fraction > 0f) {
                    drawArc(
                        color = tone,
                        startAngle = StartAngle,
                        sweepAngle = 360f * fraction,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = strokePx, cap = if (fraction >= 1f) StrokeCap.Butt else StrokeCap.Round),
                    )
                }
            }
            if (rotation != null) {
                drawArc(
                    color = tone.copy(alpha = 0.35f),
                    startAngle = StartAngle + rotation.value,
                    sweepAngle = 360f * BusySweepFraction,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round),
                )
            }
        }
    }
}
