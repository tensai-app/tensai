package io.github.hokanosekai.tensai.ui.components

import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.hokanosekai.tensai.ui.theme.TensaiShapes
import io.github.hokanosekai.tensai.ui.theme.tensaiTints

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

/** The animated (or, under reduced motion, static) ring drawn behind the pill while generating. */
@Composable
private fun GenerationRing(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val animatorDurationScale = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }
    val infiniteTransition = rememberInfiniteTransition(label = "inputPillRing")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = if (animatorDurationScale == 0f) snap() else tween(durationMillis = 1600, easing = LinearEasing),
        ),
        label = "inputPillRingRotation",
    )

    val primary = MaterialTheme.colorScheme.primary
    val ringBrush = remember(primary) {
        Brush.sweepGradient(listOf(Color.Transparent, primary, Color.Transparent))
    }

    Box(
        modifier = modifier
            .rotate(rotation)
            .border(width = 2.dp, brush = ringBrush, shape = TensaiShapes.extraLarge),
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
