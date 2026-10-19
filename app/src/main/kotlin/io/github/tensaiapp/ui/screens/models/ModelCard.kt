package io.github.tensaiapp.ui.screens.models

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
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Eject
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Icon
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
import io.github.tensaiapp.R
import io.github.tensaiapp.compat.Compatibility
import io.github.tensaiapp.compat.CompatibilityChecker
import io.github.tensaiapp.data.BackendPreference
import io.github.tensaiapp.compat.Verdict
import io.github.tensaiapp.data.models.ModelEntity
import io.github.tensaiapp.runtime.EffectiveBackend
import io.github.tensaiapp.runtime.RuntimeGraph
import io.github.tensaiapp.ui.components.TensaiBadge
import io.github.tensaiapp.ui.components.TensaiButton
import io.github.tensaiapp.ui.components.TensaiButtonSize
import io.github.tensaiapp.ui.components.TensaiButtonVariant
import io.github.tensaiapp.ui.components.TensaiMonoBadge
import io.github.tensaiapp.ui.components.rememberReducedMotion
import io.github.tensaiapp.ui.screens.settings.openLink
import io.github.tensaiapp.ui.theme.TensaiMono
import io.github.tensaiapp.ui.theme.TensaiShapes
import io.github.tensaiapp.ui.theme.tensaiColors
import io.github.tensaiapp.ui.theme.tensaiTints
import kotlinx.coroutines.flow.map

/**
 * Card for a single imported model on the shared [ModelCardFrame]: name, fit status top-right,
 * "author · params", "Q4_K_M · 1.9 GB · CPU", the chips, and one full-width action chosen by
 * [modelAction]. Bordered in primary and chipped "Loaded" when [isLoaded].
 *
 * - normal: tonal "Load model"
 * - loaded: outlined "Unload"
 * - won't fit (and not loaded): a "Needs X, phone has Y" line and an outlined "Load anyway". Not
 *   disabled, the estimate is an estimate; `LoadingScreen` asks again before it really loads
 * - file missing ([fileExists] false): error dot and label instead of the verdict, no backend in
 *   the facts, error-tonal "Unload" (still resident, TEN-107) or "Remove"
 *
 * No overflow menu (TEN-155): the tap opens the details, which hold Delete. [onDeleteClick] is the
 * "Remove" of a missing file.
 *
 * [backend] is what a load would use (see [backendTag]), resolved by the caller from one hoisted
 * preference collector; `null` shows none.
 *
 * The onboarding uses it as a read-only card (TEN-118): [noAction] drops the button and the tap
 * target, [link] adds a mono "open in the browser" row, [imported] adds the "Imported" chip and [downloaded] the "Downloaded" one.
 * The click callbacks are then unused, hence their defaults.
 */
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
    link: ModelCardLink? = null,
    noAction: Boolean = false,
    imported: Boolean = false,
    downloaded: Boolean = false,
) {
    val wontFit = compatibility?.verdict == Verdict.WontFit && !isLoaded
    val blocked = compatibility?.verdict?.isBlocked == true && !isLoaded
    val params = if (model.paramCount > 0) formatParamCount(model.paramCount) else null
    ModelCardFrame(
        title = model.shownName,
        status = { ModelStatus(compatibility, fileExists) },
        subtitle = cardSubtitle(repoOwner(model.sourceRepo), params),
        facts = cardFacts(model.displayQuant, model.sizeBytes, if (fileExists) backend else null),
        chips = cardChips(
            loaded = isLoaded,
            tools = model.toolsAvailable,
            thinking = model.thinkingAvailable,
            noTemplate = !model.hasChatTemplate,
            imported = imported,
            downloaded = downloaded,
        ),
        modifier = modifier,
        highlighted = isLoaded,
        onClick = if (noAction) null else onClick,
        onClickLabel = stringResource(R.string.model_open_details),
        extra = link?.let { { ModelLinkRow(it, modifier = Modifier.padding(top = 4.dp)) } },
        footer = if (noAction) {
            null
        } else {
            {
                if (wontFit && compatibility != null) {
                    WontFitLine(compatibility, modifier = Modifier.padding(bottom = 8.dp))
                }
                if (blocked && compatibility != null) {
                    BlockedLine(compatibility, modifier = Modifier.padding(bottom = 8.dp))
                }
                ModelActionButton(
                    action = modelAction(isLoaded, fileExists, wontFit, blocked),
                    onLoad = onLoadClick,
                    onUnload = onUnloadClick,
                    onRemove = { onDeleteClick(false) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
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
            modifier = Modifier.size(15.dp),
        )
    }
}

/**
 * Header status slot, keyed on [fileExists]: a missing file shows the error dot and "File missing"
 * whatever the verdict. With the file there, a null [compatibility] (preferences not loaded yet)
 * shows nothing rather than a guessed status.
 */
@Composable
internal fun ModelStatus(compatibility: Compatibility?, fileExists: Boolean, modifier: Modifier = Modifier, fontSize: Float = 12f) {
    if (fileExists) {
        if (compatibility == null) return
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
        fontSize = 12.5.sp,
        lineHeight = 18.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** Why a blocked model cannot be loaded, in the spot [WontFitLine] takes for a memory verdict (TEN-167). */
@Composable
internal fun BlockedLine(compatibility: Compatibility, modifier: Modifier = Modifier) {
    Text(
        text = blockedExplanation(compatibility),
        fontSize = 12.5.sp,
        lineHeight = 18.sp,
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
        ModelAction.LoadBlocked -> TensaiButton(
            text = stringResource(R.string.model_load),
            onClick = onLoad,
            variant = TensaiButtonVariant.Tonal,
            icon = Icons.Default.PlayArrow,
            enabled = false,
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
 * check twice against a [io.github.tensaiapp.compat.DeviceProfile] read at two different
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
    Verdict.WontFit, Verdict.Unsupported, Verdict.DraftModel -> MaterialTheme.colorScheme.error
}

/**
 * [CompatibilityChecker.check] against the current [io.github.tensaiapp.compat.DeviceProfile].
 * `remember`-ed per model and context preferences, like the Models list and the detail screen: one
 * verdict kept until its inputs change, so the card and the summary agree and a recomposition does
 * not re-read the device. Null until DataStore delivers the preferences, so no verdict is shown from
 * guessed defaults (same as the backend preference).
 */
@Composable
internal fun modelCompatibility(model: ModelEntity): Compatibility? {
    val prefs by remember { RuntimeGraph.contextRepository.prefs }.collectAsState(initial = null)
    val current = prefs ?: return null
    return remember(model, current) {
        CompatibilityChecker.check(model, RuntimeGraph.deviceProfiler.profile(), current.setting, current.autoShare)
    }
}
