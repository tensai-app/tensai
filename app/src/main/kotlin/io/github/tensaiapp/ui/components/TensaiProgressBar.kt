package io.github.tensaiapp.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.tensaiapp.ui.theme.tensaiTints

/**
 * Kit `ProgressBar`: rounded track (primary-16), primary fill. [progress] is `0f..1f`, or `null`
 * for an indeterminate bar (a segment sliding along the track; static under reduced motion).
 * [pulse] keeps a determinate fill at its value but breathes its opacity, for a phase that is
 * still working with no progress to report (static under reduced motion).
 */
@Composable
fun TensaiProgressBar(
    progress: Float?,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
    pulse: Boolean = false,
) {
    val range = if (progress == null || pulse) {
        ProgressBarRangeInfo.Indeterminate
    } else {
        ProgressBarRangeInfo(progress.coerceIn(0f, 1f), 0f..1f)
    }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics { progressBarRangeInfo = range }
            .clip(CircleShape)
            .background(MaterialTheme.tensaiTints.primary16),
    ) {
        val fill = Modifier.fillMaxHeight().clip(CircleShape).background(MaterialTheme.colorScheme.primary)
        if (progress != null) {
            val reducedMotion = rememberReducedMotion()
            val alpha = if (pulse && !reducedMotion) {
                val a by rememberInfiniteTransition(label = "pulseBar").animateFloat(
                    initialValue = 0.55f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
                    label = "pulseBarAlpha",
                )
                a
            } else {
                1f
            }
            Box(fill.fillMaxWidth(progress.coerceIn(0f, 1f)).alpha(alpha))
        } else {
            val reducedMotion = rememberReducedMotion()
            val segment = maxWidth * 0.35f
            val travel = maxWidth - segment
            val position by rememberInfiniteTransition(label = "indeterminateBar").animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Reverse),
                label = "indeterminateBarPosition",
            )
            Box(
                fill.width(segment).offset(x = if (reducedMotion) 0.dp else travel * position),
            )
        }
    }
}
