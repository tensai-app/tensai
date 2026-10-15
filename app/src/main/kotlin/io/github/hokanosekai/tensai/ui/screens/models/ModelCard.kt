package io.github.hokanosekai.tensai.ui.screens.models

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Eject
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hokanosekai.tensai.R
import io.github.hokanosekai.tensai.compat.Compatibility
import io.github.hokanosekai.tensai.compat.CompatibilityChecker
import io.github.hokanosekai.tensai.data.BackendPreference
import io.github.hokanosekai.tensai.compat.Verdict
import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.runtime.EffectiveBackend
import io.github.hokanosekai.tensai.runtime.RuntimeGraph
import io.github.hokanosekai.tensai.ui.components.TensaiBadge
import io.github.hokanosekai.tensai.ui.components.TensaiButton
import io.github.hokanosekai.tensai.ui.components.TensaiButtonSize
import io.github.hokanosekai.tensai.ui.components.TensaiButtonVariant
import io.github.hokanosekai.tensai.ui.components.TensaiMonoBadge
import io.github.hokanosekai.tensai.ui.components.rememberReducedMotion
import io.github.hokanosekai.tensai.ui.screens.settings.openLink
import io.github.hokanosekai.tensai.ui.theme.TensaiMono
import io.github.hokanosekai.tensai.ui.theme.TensaiShapes
import io.github.hokanosekai.tensai.ui.theme.tensaiColors
import io.github.hokanosekai.tensai.ui.theme.tensaiTints
import kotlinx.coroutines.flow.map

/**
 * Card for a single imported model: name, params, status top-right, badges, and one full-width
 * action chosen by [modelAction]. Bordered in primary and badged "Loaded" when [isLoaded].
 *
 * - normal: tonal "Load model"
 * - loaded: outlined "Unload"
 * - won't fit (and not loaded): a "Needs X, phone has Y" line and an outlined "Load anyway". Not
 *   disabled, the estimate is an estimate; `LoadingScreen` asks again before it really loads
 * - file missing ([fileExists] false): error dot and label instead of the verdict, no backend
 *   badge, error-tonal "Unload" (still resident, TEN-107) or "Remove"
 *
 * The overflow menu (details, delete) replaces the old trash icon. [onDeleteClick] gets the file's
 * current existence so the caller can decide whether a confirmation is warranted.
 *
 * [backend] is what a load would use (see [backendTag]), resolved by the caller from one hoisted
 * preference collector; `null` shows no backend badge.
 *
 * The onboarding uses it as a read-only card (TEN-118): [noAction] drops the button, the overflow
 * menu and the tap target, [link] adds a mono "open in the browser" row, and [extraBadge] is one
 * more badge after the others ("Imported"). The click callbacks are then unused, hence their defaults.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelCard(
    model: ModelEntity,
    compatibility: Compatibility?,
    backend: EffectiveBackend?,
    modifier: Modifier = Modifier,
    fileExists: Boolean = true,
    isLoaded: Boolean = false,
    onClick: () -> Unit = {},
    onLoadClick: () -> Unit = {},
    onDeleteClick: (fileExists: Boolean) -> Unit = {},
    onUnloadClick: () -> Unit = {},
    onDetailsClick: () -> Unit = {},
    link: ModelCardLink? = null,
    noAction: Boolean = false,
    extraBadge: (@Composable () -> Unit)? = null,
) {
    // No ripple on a card this big: a press just settles it to 0.99, like the kit's ModelCard.
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reducedMotion = rememberReducedMotion()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.99f else 1f,
        animationSpec = if (reducedMotion) snap() else spring(),
        label = "modelCardScale",
    )
    val openDetails = stringResource(R.string.model_open_details)
    val wontFit = compatibility?.verdict == Verdict.WontFit && !isLoaded
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(TensaiShapes.large)
            .then(
                if (noAction) {
                    Modifier
                } else {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClickLabel = openDetails,
                        role = Role.Button,
                        onClick = onClick,
                    )
                },
            ),
        shape = TensaiShapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (isLoaded) MaterialTheme.colorScheme.primary else MaterialTheme.tensaiTints.outline),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.displayName,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 22.sp,
                    )
                    Text(
                        text = formatParamCount(model.paramCount),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                ModelStatus(compatibility, modifier = Modifier.padding(top = 2.dp).widthIn(max = 140.dp))
                if (!noAction) {
                    ModelOverflowMenu(
                        modelName = model.displayName,
                        onDetails = onDetailsClick,
                        onDelete = { onDeleteClick(fileExists) },
                    )
                }
            }

            ModelBadges(model, backend, isLoaded, modifier = Modifier.padding(top = 12.dp), extraBadge = extraBadge)

            if (link != null) {
                ModelLinkRow(link, modifier = Modifier.padding(top = 4.dp))
            }

            if (wontFit && compatibility != null) {
                WontFitLine(compatibility, modifier = Modifier.padding(top = 8.dp))
            }

            if (!noAction) {
                ModelActionButton(
                    action = modelAction(isLoaded, fileExists, wontFit),
                    onLoad = onLoadClick,
                    onUnload = onUnloadClick,
                    onRemove = { onDeleteClick(false) },
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
            }
        }
    }
}

/** A web page a [ModelCard] points at: [label] is what is shown (no scheme), [url] what is opened. */
internal data class ModelCardLink(val label: String, val url: String)

/**
 * The card's link row: [ModelCardLink.label] in primary mono and an open-in-new icon, 48dp tall to
 * be a real tap target. Opens the browser, or says there is none (same path as the licenses screen).
 */
@Composable
internal fun ModelLinkRow(link: ModelCardLink, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val description = stringResource(R.string.licenses_open_url, link.label)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button) { openLink(context, link.url) }
            .semantics { contentDescription = description },
    ) {
        Text(
            text = link.label,
            fontFamily = TensaiMono,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f, fill = false).clearAndSetSemantics { },
        )
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(14.dp),
        )
    }
}

/** Header status slot: the verdict, or "File missing" in error color when there is no verdict. */
@Composable
internal fun ModelStatus(compatibility: Compatibility?, modifier: Modifier = Modifier, fontSize: Float = 12f) {
    if (compatibility != null) {
        StatusIndicator(
            color = verdictColor(compatibility.verdict),
            label = compatLabel(compatibility.verdict),
            modifier = modifier,
            fontSize = fontSize,
        )
    } else {
        StatusIndicator(
            color = MaterialTheme.colorScheme.error,
            label = stringResource(R.string.model_file_missing),
            labelColor = MaterialTheme.colorScheme.error,
            modifier = modifier,
            fontSize = fontSize,
        )
    }
}

/**
 * The Settings backend preference, `null` until DataStore has delivered it (not a guessed Auto: a
 * wrong badge for a frame is worse than none). One collector per screen, hoisted by the caller.
 */
@Composable
internal fun rememberBackendPreference(): BackendPreference? {
    val flow = remember { RuntimeGraph.backendRepository.preference.map<BackendPreference, BackendPreference?> { it } }
    val preference by flow.collectAsState(initial = null)
    return preference
}

/**
 * [backendTag] for this device off the live Settings preference, `null` until that is loaded or
 * when there is no verdict. For screens showing a single model; the Models list collects the
 * preference once itself and calls [backendTag] per model.
 */
@Composable
internal fun rememberBackendTag(compatibility: Compatibility?): EffectiveBackend? {
    val preference = rememberBackendPreference() ?: return null
    val profile = remember { RuntimeGraph.deviceProfiler.profile() }
    return backendTag(compatibility, preference, profile.hasVulkan, profile.hasOpenCl)
}

/** Display name of a backend, shared by the card badge and the detail row. */
@Composable
internal fun EffectiveBackend.displayName(): String = when (this) {
    EffectiveBackend.Vulkan -> stringResource(R.string.models_filter_vulkan)
    EffectiveBackend.OpenCl -> label
    EffectiveBackend.Cpu -> stringResource(R.string.models_filter_cpu)
}

/** "Loaded" first, then quant, size, and the backend a load would use (none without a verdict). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ModelBadges(
    model: ModelEntity,
    backend: EffectiveBackend?,
    isLoaded: Boolean,
    modifier: Modifier = Modifier,
    extraBadge: (@Composable () -> Unit)? = null,
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier,
    ) {
        if (isLoaded) TensaiBadge(stringResource(R.string.model_loaded_badge))
        TensaiMonoBadge(model.quantLabel)
        TensaiMonoBadge(formatFileSize(model.sizeBytes))
        if (backend != null) {
            TensaiBadge(
                backend.displayName(),
                icon = if (backend == EffectiveBackend.Cpu) Icons.Default.Memory else Icons.Default.Bolt,
            )
        }
        extraBadge?.invoke()
    }
}

/**
 * "Needs X, phone has Y" under the badges of a model that won't fit, figures in mono. Both come
 * off the [Compatibility] that produced the verdict (the total RAM, not the free RAM: that is what
 * the verdict is measured against, so "free" would misreport it).
 */
@Composable
internal fun WontFitLine(compatibility: Compatibility, modifier: Modifier = Modifier) {
    val needs = formatGigabytes(compatibility.estimatedBytes)
    val has = formatGigabytes(compatibility.deviceRamBytes)
    Text(
        text = monoFigures(stringResource(R.string.model_compat_needs, needs, has), listOf(needs, has)),
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** The full-width button for a [ModelAction]. */
@Composable
internal fun ModelActionButton(
    action: ModelAction,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val error = MaterialTheme.colorScheme.error
    val errorTint = MaterialTheme.tensaiTints.error12
    when (action) {
        ModelAction.Load -> TensaiButton(
            text = stringResource(R.string.model_load),
            onClick = onLoad,
            variant = TensaiButtonVariant.Tonal,
            icon = Icons.Default.PlayArrow,
            modifier = modifier,
        )
        ModelAction.LoadAnyway -> TensaiButton(
            text = stringResource(R.string.model_compat_load_anyway),
            onClick = onLoad,
            variant = TensaiButtonVariant.Outlined,
            icon = Icons.Default.PlayArrow,
            modifier = modifier,
        )
        ModelAction.Unload -> TensaiButton(
            text = stringResource(R.string.model_unload),
            onClick = onUnload,
            variant = TensaiButtonVariant.Outlined,
            icon = Icons.Default.Eject,
            modifier = modifier,
        )
        ModelAction.UnloadMissing -> TensaiButton(
            text = stringResource(R.string.model_unload),
            onClick = onUnload,
            variant = TensaiButtonVariant.Tonal,
            icon = Icons.Default.Eject,
            containerColor = errorTint,
            contentColor = error,
            modifier = modifier,
        )
        ModelAction.Remove -> TensaiButton(
            text = stringResource(R.string.model_remove),
            onClick = onRemove,
            variant = TensaiButtonVariant.Tonal,
            icon = Icons.Default.Delete,
            containerColor = errorTint,
            contentColor = error,
            modifier = modifier,
        )
    }
}

/** more_vert trigger (48dp target) and its menu: surface, hairline, radius 16, "Model details" / "Delete model". */
@Composable
private fun ModelOverflowMenu(modelName: String, onDetails: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    // Takes only the icon's 24dp in the header row, so the dots sit on the status line and against
    // the card's padding instead of a 48dp box pushing them down and in (TEN-132). The 48dp touch
    // target still overhangs that slot by 12dp on each side.
    Box(
        modifier = Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints)
            val slot = 24.dp.roundToPx()
            val overhang = (placeable.width - slot) / 2
            layout(slot, slot) { placeable.place(-overhang, -overhang) }
        },
    ) {
        IconButton(onClick = { open = true }, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = stringResource(R.string.model_options),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = TensaiShapes.medium,
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, MaterialTheme.tensaiTints.outline),
            modifier = Modifier.widthIn(min = 196.dp),
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.model_details_menu), fontSize = 14.5.sp, fontWeight = FontWeight.Medium) },
                leadingIcon = {
                    Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(20.dp))
                },
                onClick = {
                    open = false
                    onDetails()
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.model_delete),
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp),
                    )
                },
                onClick = {
                    open = false
                    onDelete()
                },
            )
        }
    }
}

/** The header's top-right slot: 9dp dot + medium label, shrinkable so the name keeps room. */
@Composable
internal fun StatusIndicator(
    color: Color,
    label: String,
    modifier: Modifier = Modifier,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    fontSize: Float = 12f,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = modifier) {
        Box(modifier = Modifier.size(9.dp).background(color, CircleShape))
        Text(
            text = label,
            fontSize = fontSize.sp,
            fontWeight = FontWeight.Medium,
            color = labelColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

/**
 * Dot + short label for a [CompatibilityChecker] verdict on this device.
 *
 * Takes the [Compatibility] rather than the model it came from (TEN-39): callers that also show
 * the explanation next to it, [ModelCard] and the onboarding's import step, would otherwise run the
 * check twice against a [io.github.hokanosekai.tensai.compat.DeviceProfile] read at two different
 * instants, and `availableRamBytes` moves between them. One check, one verdict, one sentence.
 */
@Composable
internal fun CompatIndicator(compatibility: Compatibility, modifier: Modifier = Modifier) {
    StatusIndicator(verdictColor(compatibility.verdict), compatLabel(compatibility.verdict), modifier)
}

@Composable
internal fun verdictColor(verdict: Verdict): Color = when (verdict) {
    Verdict.RunsWell -> MaterialTheme.tensaiColors.success
    Verdict.TightFit -> MaterialTheme.tensaiColors.warning
    Verdict.WontFit -> MaterialTheme.colorScheme.error
}

/**
 * [CompatibilityChecker.check] against the current [io.github.hokanosekai.tensai.compat.DeviceProfile].
 * Deliberately not `remember`-ed: free RAM changes between recompositions and the check is cheap, so
 * the single-model screen (onboarding) recomputes it rather than show a stale verdict. The Models
 * list and the detail screen do the opposite on purpose: one verdict per model, kept until the
 * library changes, so the filter, the card and the detail agree.
 */
@Composable
internal fun modelCompatibility(model: ModelEntity) =
    CompatibilityChecker.check(model, RuntimeGraph.deviceProfiler.profile())
