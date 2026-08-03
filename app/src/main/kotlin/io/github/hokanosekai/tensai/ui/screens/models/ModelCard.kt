package io.github.hokanosekai.tensai.ui.screens.models

import android.app.ActivityManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.ui.theme.TensaiMono
import io.github.hokanosekai.tensai.ui.theme.TensaiShapes
import io.github.hokanosekai.tensai.ui.theme.tensaiColors
import io.github.hokanosekai.tensai.ui.theme.tensaiTints

/** Rough fit of a model's file size against this device's total RAM — see [compatLevelFor]. */
private enum class CompatLevel { Good, Tight, Poor }

/**
 * Card for a single imported model — name, params, quant/size badges, RAM-fit dot, and a
 * full-width "Load model" action. Bordered in primary + a "loaded" chip when [isLoaded].
 */
@Composable
internal fun ModelCard(
    model: ModelEntity,
    isLoaded: Boolean,
    onClick: () -> Unit,
    onLoadClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = TensaiShapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = if (isLoaded) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = model.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (isLoaded) {
                    Surface(shape = TensaiShapes.extraLarge, color = MaterialTheme.tensaiTints.primary12) {
                        Text(
                            text = "loaded",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            Text(
                text = formatParamCount(model.paramCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 10.dp),
            ) {
                MonoBadge(model.quantLabel)
                MonoBadge(formatFileSize(model.sizeBytes))
                CompatDot(model.sizeBytes)
            }

            Button(onClick = onLoadClick, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Text("Load model")
            }
        }
    }
}

@Composable
private fun MonoBadge(text: String, modifier: Modifier = Modifier) {
    Surface(shape = TensaiShapes.small, color = MaterialTheme.colorScheme.surface, modifier = modifier) {
        Text(
            text = text,
            fontFamily = TensaiMono,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** Small colored dot hinting whether [sizeBytes] comfortably fits this device's RAM. */
@Composable
private fun CompatDot(sizeBytes: Long, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val level = remember(context, sizeBytes) { compatLevelFor(context, sizeBytes) }
    val color = when (level) {
        CompatLevel.Good -> MaterialTheme.tensaiColors.success
        CompatLevel.Tight -> MaterialTheme.tensaiColors.warning
        CompatLevel.Poor -> MaterialTheme.colorScheme.error
    }
    Box(modifier = modifier.size(8.dp).background(color, CircleShape))
}

/**
 * Heuristic only: a GGUF file roughly needs its own size plus KV-cache/activation overhead
 * resident in RAM to run at a reasonable context length. No real load-time budget exists yet
 * (that's TEN-30's job) — this just flags "this file is a large fraction of total device RAM"
 * before the user commits to a load.
 */
private fun compatLevelFor(context: Context, sizeBytes: Long): CompatLevel {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        ?: return CompatLevel.Tight
    val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
    if (memoryInfo.totalMem <= 0) return CompatLevel.Tight
    val ratio = sizeBytes.toDouble() / memoryInfo.totalMem
    return when {
        ratio < 0.5 -> CompatLevel.Good
        ratio < 0.8 -> CompatLevel.Tight
        else -> CompatLevel.Poor
    }
}
