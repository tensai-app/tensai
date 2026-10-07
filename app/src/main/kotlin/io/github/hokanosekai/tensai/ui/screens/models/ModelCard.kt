package io.github.hokanosekai.tensai.ui.screens.models

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.hokanosekai.tensai.R
import io.github.hokanosekai.tensai.compat.Compatibility
import io.github.hokanosekai.tensai.compat.CompatibilityChecker
import io.github.hokanosekai.tensai.compat.Verdict
import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.runtime.RuntimeGraph
import io.github.hokanosekai.tensai.ui.theme.TensaiMono
import io.github.hokanosekai.tensai.ui.theme.TensaiShapes
import io.github.hokanosekai.tensai.ui.theme.tensaiColors
import io.github.hokanosekai.tensai.ui.theme.tensaiTints
import java.io.File

/**
 * Card for a single imported model — name, params, quant/size badges, RAM-fit dot, and a
 * full-width "Load model" action. Bordered in primary + a "loaded" chip when [isLoaded].
 *
 * If [model]'s file no longer exists on disk (its DB row outlived the file — e.g. storage was
 * wiped outside the app), [CompatIndicator] is replaced by [MissingFileBadge] and the "Load
 * model" action is replaced by "Remove", since there's nothing left to load (or by "Unload" when
 * the model is still resident, TEN-107: it cannot be removed until released). The delete icon
 * (top-right) always fires [onDeleteClick] with the file's current existence, letting the caller
 * decide whether a confirmation is warranted — see `ModelsScreen`'s handling of it.
 *
 * A [Verdict.WontFit] model gets the third treatment (TEN-39): the red dot keeps its label, the
 * figures behind it are spelled out underneath ("needs ~6.2 GB, your phone has 8.0 GB" — from
 * [compatExplanation], the one place those numbers become words), and the filled "Load model"
 * button steps down to a quiet text action. Not disabled: the estimate is an estimate, and the
 * user is allowed to disagree with it. Tapping it still lands on `LoadingScreen`'s own
 * [io.github.hokanosekai.tensai.ui.screens.loading.LoadUiState.Blocked] confirmation, which is
 * deliberate — the card only declines to *recommend* the load, the dialog is what actually asks.
 */
@Composable
internal fun ModelCard(
    model: ModelEntity,
    isLoaded: Boolean,
    onClick: () -> Unit,
    onLoadClick: () -> Unit,
    onDeleteClick: (fileExists: Boolean) -> Unit,
    onUnloadClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fileExists = rememberFileExists(model.filePath)
    // One check per card, shared by the badge row and the action below it — the two must never
    // reach different conclusions about the same model.
    val compatibility = if (fileExists) modelCompatibility(model) else null
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
                            text = stringResource(R.string.model_loaded_badge),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
                IconButton(onClick = { onDeleteClick(fileExists) }, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.model_delete_named, model.displayName),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                if (compatibility != null) CompatIndicator(compatibility) else MissingFileBadge()
            }

            if (compatibility?.verdict == Verdict.WontFit) {
                Text(
                    text = compatExplanation(compatibility),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextButton(
                    onClick = onLoadClick,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(stringResource(R.string.model_compat_load_anyway))
                }
            } else if (fileExists) {
                Button(onClick = onLoadClick, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    Text(stringResource(R.string.model_load))
                }
            } else {
                val unload = missingFileAction(isLoaded) == MissingFileAction.Unload
                Button(
                    onClick = { if (unload) onUnloadClick() else onDeleteClick(false) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.tensaiTints.error12,
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    Text(stringResource(if (unload) R.string.model_unload else R.string.model_remove))
                }
            }
        }
    }
}

/** Dot + label shown instead of [CompatIndicator] when a model's file is gone — see [ModelCard]. */
@Composable
private fun MissingFileBadge(modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier) {
        Box(modifier = Modifier.size(8.dp).background(MaterialTheme.colorScheme.error, CircleShape))
        Text(
            text = stringResource(R.string.model_file_missing),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Whether [filePath] exists on disk. A single `stat()` call, same "cheap enough for a
 * composable" bar as [modelCompatibility]'s `ActivityManager` query below — not worth a
 * dispatcher hop. Recomputed whenever [filePath] changes, e.g. this composable being reused for
 * a different [ModelEntity] after the list reorders.
 */
// The body below does assign `value` directly — this suppresses a compose-runtime lint false
// positive (ProduceStateDoesNotAssignValue can't see the assignment under Kotlin 2.0's K2 UAST
// in this AGP/lint version) rather than a real bug.
@Suppress("ProduceStateDoesNotAssignValue")
@Composable
internal fun rememberFileExists(filePath: String): Boolean {
    val exists by produceState(initialValue = true, filePath) {
        value = File(filePath).exists()
    }
    return exists
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

/**
 * Dot + short label for a [CompatibilityChecker] verdict on this device.
 *
 * Takes the [Compatibility] rather than the model it came from (TEN-39): callers that also show
 * the explanation next to it — [ModelCard], the onboarding's import step — would otherwise run the
 * check twice against a [io.github.hokanosekai.tensai.compat.DeviceProfile] read at two different
 * instants, and `availableRamBytes` moves between them. One check, one verdict, one sentence.
 */
@Composable
internal fun CompatIndicator(compatibility: Compatibility, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier) {
        CompatDot(compatibility.verdict)
        Text(
            text = compatLabel(compatibility.verdict),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Small colored dot for a [Verdict] — [tensaiColors.success]/[tensaiColors.warning]/error. */
@Composable
internal fun CompatDot(verdict: Verdict, modifier: Modifier = Modifier) {
    val color = verdictColor(verdict)
    Box(modifier = modifier.size(8.dp).background(color, CircleShape))
}

@Composable
internal fun verdictColor(verdict: Verdict): Color = when (verdict) {
    Verdict.RunsWell -> MaterialTheme.tensaiColors.success
    Verdict.TightFit -> MaterialTheme.tensaiColors.warning
    Verdict.WontFit -> MaterialTheme.colorScheme.error
}

/**
 * [CompatibilityChecker.check] against the current [io.github.hokanosekai.tensai.compat.DeviceProfile].
 * Deliberately not `remember`-ed: the device's RAM fits changes between recompositions (other
 * apps, GC, …) and the check itself is cheap (an `ActivityManager` query plus arithmetic), so it's
 * recomputed on every recomposition rather than risk showing a stale verdict.
 */
@Composable
internal fun modelCompatibility(model: ModelEntity) =
    CompatibilityChecker.check(model, RuntimeGraph.deviceProfiler.profile())
