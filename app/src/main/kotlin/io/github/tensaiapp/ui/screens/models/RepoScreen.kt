package io.github.tensaiapp.ui.screens.models

import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tensaiapp.R
import io.github.tensaiapp.compat.Verdict
import io.github.tensaiapp.download.DownloadError
import io.github.tensaiapp.hf.HfError
import io.github.tensaiapp.hf.HfRepoDetail
import io.github.tensaiapp.runtime.RuntimeGraph
import io.github.tensaiapp.ui.components.MarkdownText
import io.github.tensaiapp.ui.components.TensaiBadge
import io.github.tensaiapp.ui.components.TensaiBadgeTone
import io.github.tensaiapp.ui.components.TensaiButton
import io.github.tensaiapp.ui.components.TensaiButtonSize
import io.github.tensaiapp.ui.components.TensaiButtonVariant
import io.github.tensaiapp.ui.components.TensaiDialog
import io.github.tensaiapp.ui.components.TensaiDialogAction
import io.github.tensaiapp.ui.components.TensaiMonoBadge
import io.github.tensaiapp.ui.components.TensaiProgressBar
import io.github.tensaiapp.ui.screens.settings.openLink
import io.github.tensaiapp.ui.theme.TensaiMono
import io.github.tensaiapp.ui.theme.TensaiShapes
import io.github.tensaiapp.ui.theme.tensaiColors
import io.github.tensaiapp.ui.theme.tensaiTints

/**
 * A Hugging Face repo (TEN-140): header, "Choose a file" (single choice among the quants, with the
 * verdict on this phone), the README, and the download button pinned at the bottom. Chrome (back
 * arrow, "Hugging Face") is owned by `TensaiShell`.
 *
 * @param preselectFile the file a pasted link pointed at, selected on open when the repo has it.
 * @param onLoadModel "Load model": the file is in the library, go to the loading screen.
 * @param onManageModels "Manage models" of the storage dialog: go to the library.
 * @param onOpenSettings "Open Settings" of the token card: Settings, with the token sheet open.
 */
@Composable
fun RepoScreen(
    repoId: String,
    preselectFile: String?,
    onLoadModel: (String) -> Unit,
    onManageModels: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val viewModel = remember(repoId, preselectFile) { RepoViewModel(scope, repoId, preselectFile) }
    LaunchedEffect(viewModel) { viewModel.start() }
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val pageUrl = remember(repoId) { runCatching { RuntimeGraph.hfClient.repoPageUrl(repoId) }.getOrNull() }

    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            when (val load = state.load) {
                RepoLoad.Loading -> RepoSkeleton()
                is RepoLoad.Failed -> RepoFailure(
                    error = load.error,
                    onRetry = viewModel::load,
                    onOpenSettings = onOpenSettings,
                    hasToken = state.hasToken,
                    pageUrl = pageUrl,
                    onOpenPage = { pageUrl?.let { openLink(context, it) } },
                )
                is RepoLoad.Loaded -> RepoBody(
                    state = state,
                    detail = load.detail,
                    onSelect = viewModel::select,
                    onToggleReadme = viewModel::toggleReadme,
                    pageUrl = pageUrl,
                    onOpenPage = { pageUrl?.let { openLink(context, it) } },
                )
            }
        }
        if (state.load is RepoLoad.Loaded) {
            ActionBar(
                action = state.action,
                notAllowed = state.notAllowed,
                onDownload = viewModel::download,
                onPause = viewModel::pause,
                onResume = viewModel::resume,
                onCancel = viewModel::cancel,
                onRetry = viewModel::retry,
                onLoad = onLoadModel,
                onOpenSettings = onOpenSettings,
                onOpenPage = { pageUrl?.let { openLink(context, it) } },
            )
        }
    }

    when (val dialog = state.dialog) {
        null -> Unit
        is RepoDialog.MobileData -> MobileDataDialog(
            sizeBytes = dialog.sizeBytes,
            onCancel = viewModel::dismissDialog,
            onConfirm = viewModel::confirmMobileData,
        )
        is RepoDialog.Storage -> StorageDialog(
            neededBytes = dialog.neededBytes,
            freeBytes = dialog.freeBytes,
            onCancel = viewModel::dismissDialog,
            onManage = {
                viewModel.dismissDialog()
                onManageModels()
            },
        )
    }
}

// ---- body

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RepoBody(
    state: RepoState,
    detail: HfRepoDetail,
    onSelect: (String) -> Unit,
    onToggleReadme: () -> Unit,
    pageUrl: String?,
    onOpenPage: () -> Unit,
) {
    val summary = detail.summary
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
    ) {
        Text(
            text = DiscoverLogic.displayName(summary.name),
            fontSize = 20.sp,
            lineHeight = 26.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp).semantics { heading() },
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text(
                text = summary.author,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            RepoStats(downloads = summary.downloads, likes = summary.likes)
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 12.dp),
        ) {
            summary.license?.let { TensaiMonoBadge(it) }
            if (detail.paramCount > 0) TensaiMonoBadge(formatParamCount(detail.paramCount))
            if (detail.contextLength > 0) {
                TensaiMonoBadge(stringResource(R.string.repo_context, RepoLogic.formatContext(detail.contextLength)))
            }
            cardChips(
                tools = detail.supportsTools,
                thinking = detail.supportsThinking,
                vision = detail.hasVision,
                noTemplate = !detail.hasChatTemplate,
                gated = summary.isGated,
            )
                .forEach { CardChipBadge(it) }
        }

        Eyebrow(stringResource(R.string.repo_choose_file))
        if (state.requestedUnsupported) {
            Text(
                text = stringResource(R.string.repo_file_unsupported),
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }
        if (state.rows.isEmpty()) {
            Text(
                text = stringResource(R.string.repo_no_files),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        } else {
            Card(modifier = Modifier.selectableGroup()) {
                state.rows.forEachIndexed { index, row ->
                    QuantRowItem(row, onClick = { onSelect(row.quant.fileName) })
                    if (index < state.rows.lastIndex) HorizontalDivider(color = MaterialTheme.tensaiTints.outline)
                }
            }
        }

        (state.readme as? ReadmeState.Loaded)?.let { readme ->
            Eyebrow(stringResource(R.string.repo_readme))
            ReadmeCard(readme.text, expanded = state.readmeExpanded, onToggle = onToggleReadme)
        }
        if (pageUrl != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            ) {
                TensaiButton(
                    text = stringResource(R.string.repo_open_on_hf),
                    onClick = onOpenPage,
                    variant = TensaiButtonVariant.Text,
                    trailingIcon = Icons.AutoMirrored.Rounded.OpenInNew,
                )
            }
        }
    }
}

@Composable
private fun Eyebrow(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.72.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 24.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        shape = TensaiShapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.tensaiTints.outline),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column { content() }
    }
}

/** The kit's `QuantRow`: radio, mono label with its badges, then size and verdict. Won't fit is dimmed, never disabled. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuantRowItem(row: QuantRow, onClick: () -> Unit) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(if (row.selected) MaterialTheme.tensaiTints.primary08 else Color.Transparent)
            .selectable(selected = row.selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .alpha(if (row.verdict == Verdict.WontFit || row.verdict.isBlocked) 0.55f else 1f),
    ) {
        Radio(row.selected)
        Column(modifier = Modifier.weight(1f)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val center = Modifier.align(Alignment.CenterVertically)
                Text(text = row.label, fontFamily = TensaiMono, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = center)
                if (row.recommended) TensaiBadge(stringResource(R.string.repo_badge_recommended), modifier = center)
                if (row.onDevice) TensaiBadge(stringResource(R.string.repo_badge_on_device), modifier = center, icon = Icons.Default.Check, tone = TensaiBadgeTone.Neutral)
                if (row.veryLowQuality) TensaiBadge(stringResource(R.string.repo_badge_very_low_quality), modifier = center, tone = TensaiBadgeTone.Warning)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 3.dp),
            ) {
                Text(text = formatFileSize(row.quant.sizeBytes), fontFamily = TensaiMono, fontSize = 12.sp, color = secondary)
                StatusIndicator(color = verdictColor(row.verdict), label = compatLabel(row.verdict), fontSize = 12f)
            }
        }
    }
}

@Composable
private fun Radio(on: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(20.dp)
            .border(2.dp, if (on) primary else MaterialTheme.tensaiTints.outlineStrong, CircleShape),
    ) {
        if (on) Box(modifier = Modifier.size(10.dp).background(primary, CircleShape))
    }
}

@Composable
private fun ReadmeCard(text: String, expanded: Boolean, onToggle: () -> Unit) {
    val shown = remember(text, expanded) {
        RepoLogic.slice(text, if (expanded) RepoLogic.README_MAX_CHARS else RepoLogic.README_PREVIEW_CHARS)
    }
    val hasMore = text.length > RepoLogic.README_PREVIEW_CHARS
    Card {
        Column(modifier = Modifier.padding(16.dp)) {
            Box(modifier = if (expanded) Modifier else Modifier.heightIn(max = 168.dp).clipToBounds()) {
                MarkdownText(markdown = shown, style = MaterialTheme.typography.bodyMedium)
            }
            if (hasMore) {
                TensaiButton(
                    text = stringResource(if (expanded) R.string.repo_show_less else R.string.repo_show_more),
                    onClick = onToggle,
                    variant = TensaiButtonVariant.Text,
                    size = TensaiButtonSize.Sm,
                    trailingIcon = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

// ---- states

@Composable
private fun RepoSkeleton() {
    val bar = MaterialTheme.colorScheme.surfaceVariant
    val loading = stringResource(R.string.discover_loading)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = loading },
    ) {
        Box(modifier = Modifier.fillMaxWidth(0.6f).height(22.dp).background(bar, RoundedCornerShape(6.dp)))
        Box(modifier = Modifier.padding(top = 10.dp).fillMaxWidth(0.35f).height(14.dp).background(bar, RoundedCornerShape(6.dp)))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 14.dp)) {
            repeat(3) { Box(modifier = Modifier.width(64.dp).height(22.dp).background(bar, CircleShape)) }
        }
        Box(modifier = Modifier.padding(top = 32.dp).fillMaxWidth().height(260.dp).background(bar, TensaiShapes.large))
    }
}

@Composable
private fun RepoFailure(
    error: HfError,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    hasToken: Boolean,
    pageUrl: String?,
    onOpenPage: () -> Unit,
) {
    val gate = RepoLogic.gateFor(error, hasToken)
    when {
        gate != null -> Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize().padding(20.dp),
        ) {
            if (gate == GateCard.NeedsLicense) {
                LicenseCard(onOpenPage = onOpenPage, onRetry = onRetry)
            } else {
                TokenCard(invalid = gate == GateCard.InvalidToken, onOpenSettings = onOpenSettings)
            }
            if (pageUrl != null && gate != GateCard.NeedsLicense) {
                TensaiButton(
                    text = stringResource(R.string.repo_open_on_hf),
                    onClick = onOpenPage,
                    variant = TensaiButtonVariant.Text,
                    trailingIcon = Icons.AutoMirrored.Rounded.OpenInNew,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
        else -> {
            val (icon, title, body) = when (error) {
                HfError.NotFound -> Triple(Icons.Default.SearchOff, R.string.repo_not_found_title, R.string.repo_not_found_body)
                HfError.Offline -> Triple(Icons.Default.CloudOff, R.string.discover_offline_title, R.string.repo_offline_body)
                is HfError.RateLimited -> Triple(Icons.Default.Timer, R.string.discover_rate_limited_title, R.string.discover_rate_limited_body)
                else -> Triple(Icons.Default.ErrorOutline, R.string.discover_error_title, R.string.discover_error_body)
            }
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                DiscoverEmpty(
                    icon = icon,
                    title = stringResource(title),
                    body = stringResource(body),
                    // A repo that does not exist does not come back by asking again.
                    action = if (error == HfError.NotFound) {
                        null
                    } else {
                        {
                            TensaiButton(
                                text = stringResource(R.string.discover_try_again),
                                onClick = onRetry,
                                variant = TensaiButtonVariant.Tonal,
                                icon = Icons.Default.Refresh,
                            )
                        }
                    },
                )
            }
        }
    }
}

// ---- the pinned button

@Composable
private fun ActionBar(
    action: RepoAction,
    notAllowed: Boolean,
    onDownload: () -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onLoad: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPage: () -> Unit,
) {
    if (action == RepoAction.None) return
    Surface(color = MaterialTheme.colorScheme.background) {
        Column {
            HorizontalDivider(color = MaterialTheme.tensaiTints.outline)
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                if (notAllowed) {
                    Text(
                        text = stringResource(R.string.repo_not_allowed),
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                when (action) {
                    RepoAction.None -> Unit
                    is RepoAction.Download -> TensaiButton(
                        text = stringResource(R.string.repo_download, formatFileSize(action.sizeBytes)),
                        onClick = onDownload,
                        size = TensaiButtonSize.Lg,
                        icon = Icons.Default.Download,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    is RepoAction.Load -> TensaiButton(
                        text = stringResource(R.string.repo_load),
                        onClick = { onLoad(action.modelId) },
                        variant = TensaiButtonVariant.Tonal,
                        size = TensaiButtonSize.Lg,
                        icon = Icons.Default.PlayArrow,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    is RepoAction.Downloading -> DownloadProgress(
                        done = action.doneBytes,
                        total = action.totalBytes,
                        bytesPerSecond = action.bytesPerSecond,
                        etaSeconds = action.etaSeconds,
                        paused = false,
                        onPauseOrResume = { onPause(action.id) },
                        onCancel = { onCancel(action.id) },
                    )
                    is RepoAction.Paused -> DownloadProgress(
                        done = action.doneBytes,
                        total = action.totalBytes,
                        bytesPerSecond = 0,
                        etaSeconds = null,
                        paused = true,
                        onPauseOrResume = { onResume(action.id) },
                        onCancel = { onCancel(action.id) },
                    )
                    is RepoAction.Queued -> {
                        Text(
                            text = if (action.ahead > 0) {
                                stringResource(R.string.repo_queued_ahead, action.ahead)
                            } else {
                                stringResource(R.string.repo_queued)
                            },
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        TensaiProgressBar(progress = null)
                        TensaiButton(
                            text = stringResource(R.string.download_action_cancel),
                            onClick = { onCancel(action.id) },
                            variant = TensaiButtonVariant.Outlined,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    is RepoAction.Failed -> {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(
                                imageVector = Icons.Default.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(22.dp),
                            )
                            Text(
                                text = stringResource(R.string.download_failed_title, downloadErrorText(action.error)),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            TensaiButton(
                                text = stringResource(R.string.repo_retry),
                                onClick = { onRetry(action.id) },
                                icon = Icons.Default.Refresh,
                                modifier = Modifier.weight(1f),
                            )
                            TensaiButton(
                                text = stringResource(R.string.download_action_cancel),
                                onClick = { onCancel(action.id) },
                                variant = TensaiButtonVariant.Outlined,
                            )
                        }
                    }
                    is RepoAction.Blocked -> {
                        Text(
                            text = stringResource(
                                if (action.verdict == Verdict.DraftModel) R.string.model_draft_explanation else R.string.model_unsupported_explanation,
                                action.architecture,
                            ),
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TensaiButton(
                            text = stringResource(R.string.repo_download_blocked),
                            onClick = {},
                            size = TensaiButtonSize.Lg,
                            icon = Icons.Default.Download,
                            enabled = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    is RepoAction.NeedsToken -> TokenCard(invalid = action.invalid, onOpenSettings = onOpenSettings)
                    is RepoAction.NeedsLicense -> LicenseCard(onOpenPage = onOpenPage, onRetry = { onRetry(action.id) })
                }
            }
        }
    }
}

/** Bar, "123 MB of 1.2 GB", speed and time left, then Pause (Resume when [paused]) and Cancel. */
@Composable
internal fun DownloadProgress(
    done: Long,
    total: Long,
    bytesPerSecond: Long,
    etaSeconds: Long?,
    paused: Boolean,
    onPauseOrResume: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    TensaiProgressBar(progress = fraction)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = if (paused) {
                stringResource(R.string.repo_paused_of, formatFileSize(done), formatFileSize(total))
            } else {
                stringResource(R.string.download_notification_size, formatFileSize(done), formatFileSize(total))
            },
            fontFamily = TensaiMono,
            fontSize = 12.sp,
            color = secondary,
        )
        if (!paused && bytesPerSecond > 0 && etaSeconds != null) {
            Text(
                text = stringResource(
                    R.string.repo_speed_eta,
                    Formatter.formatShortFileSize(context, bytesPerSecond),
                    etaText(etaSeconds),
                ),
                fontFamily = TensaiMono,
                fontSize = 12.sp,
                color = secondary,
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        TensaiButton(
            text = stringResource(if (paused) R.string.repo_resume else R.string.download_action_pause),
            onClick = onPauseOrResume,
            variant = TensaiButtonVariant.Tonal,
            icon = if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
            modifier = Modifier.weight(1f),
        )
        TensaiButton(
            text = stringResource(R.string.download_action_cancel),
            onClick = onCancel,
            variant = TensaiButtonVariant.Outlined,
        )
    }
}

@Composable
internal fun etaText(seconds: Long): String = when {
    seconds >= 3600 -> stringResource(R.string.download_eta_hours, seconds / 3600, (seconds % 3600) / 60)
    seconds >= 60 -> stringResource(R.string.download_eta_minutes, (seconds + 30) / 60)
    else -> stringResource(R.string.download_eta_seconds, seconds.coerceAtLeast(1))
}

@StringRes
internal fun downloadErrorRes(error: DownloadError?): Int = when (error) {
    DownloadError.Gated -> R.string.download_error_gated
    DownloadError.LicenseNotAccepted -> R.string.download_error_license
    DownloadError.InvalidToken -> R.string.download_error_invalid_token
    DownloadError.NotFound -> R.string.download_error_not_found
    DownloadError.ChecksumMismatch -> R.string.download_error_checksum
    DownloadError.SizeMismatch -> R.string.download_error_size
    DownloadError.DiskFull -> R.string.download_error_disk_full
    DownloadError.Network -> R.string.download_error_network
    DownloadError.AlreadyImported -> R.string.download_error_already_imported
    DownloadError.InvalidModel -> R.string.download_error_invalid_model
    is DownloadError.Http -> R.string.download_error_http
    DownloadError.Unknown, null -> R.string.download_error_unknown
}

/**
 * The kit's gated card: lock, what to do (accept the licence on the site, add a token), and the way
 * to Settings. [invalid] is the token that was refused: its own title and text.
 */
@Composable
private fun TokenCard(invalid: Boolean, onOpenSettings: () -> Unit) {
    Surface(
        shape = TensaiShapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.tensaiTints.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.tensaiColors.warning,
                    modifier = Modifier.size(22.dp),
                )
                Column {
                    Text(
                        text = stringResource(if (invalid) R.string.repo_token_invalid_title else R.string.repo_token_gated_title),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(if (invalid) R.string.repo_token_invalid_body else R.string.repo_token_gated_body),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            TensaiButton(
                text = stringResource(R.string.repo_token_button),
                onClick = onOpenSettings,
                icon = Icons.Default.Key,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * A token is set and Hugging Face still said no (403): the licence is not accepted on this account.
 * Nothing to change in Settings; accept it on the site, then try again.
 */
@Composable
private fun LicenseCard(onOpenPage: () -> Unit, onRetry: () -> Unit) {
    Surface(
        shape = TensaiShapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.tensaiTints.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.tensaiColors.warning,
                    modifier = Modifier.size(22.dp),
                )
                Column {
                    Text(
                        text = stringResource(R.string.repo_license_title),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(R.string.repo_license_body),
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                TensaiButton(
                    text = stringResource(R.string.repo_open_on_hf),
                    onClick = onOpenPage,
                    trailingIcon = Icons.AutoMirrored.Rounded.OpenInNew,
                    modifier = Modifier.weight(1f),
                )
                TensaiButton(
                    text = stringResource(R.string.repo_retry),
                    onClick = onRetry,
                    variant = TensaiButtonVariant.Outlined,
                    icon = Icons.Default.Refresh,
                )
            }
        }
    }
}

// ---- dialogs

/** "Download 1.2 GB on mobile data?" with the "Always allow" box (it sets the Settings preference). */
@Composable
internal fun MobileDataDialog(sizeBytes: Long, onCancel: () -> Unit, onConfirm: (always: Boolean) -> Unit) {
    var always by remember { mutableStateOf(false) }
    TensaiDialog(
        onDismissRequest = onCancel,
        icon = Icons.Default.SignalCellularAlt,
        iconTint = MaterialTheme.colorScheme.onSurfaceVariant,
        title = stringResource(R.string.repo_mobile_title, formatFileSize(sizeBytes)),
        body = stringResource(R.string.repo_mobile_body),
        actions = listOf(
            TensaiDialogAction(stringResource(R.string.repo_mobile_cancel), onCancel),
            TensaiDialogAction(stringResource(R.string.repo_mobile_confirm), { onConfirm(always) }),
        ),
        content = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = always, role = Role.Checkbox, onValueChange = { always = it }),
            ) {
                Checkbox(checked = always, onCheckedChange = null, modifier = Modifier.padding(12.dp))
                Text(text = stringResource(R.string.repo_mobile_always), fontSize = 14.5.sp)
            }
        },
    )
}

/** "Not enough storage": what the download needs and what is free, and the way to the library. */
@Composable
internal fun StorageDialog(neededBytes: Long, freeBytes: Long, onCancel: () -> Unit, onManage: () -> Unit) {
    TensaiDialog(
        onDismissRequest = onCancel,
        icon = Icons.Default.Storage,
        iconTint = MaterialTheme.tensaiColors.warning,
        title = stringResource(R.string.repo_storage_title),
        body = stringResource(R.string.repo_storage_body),
        actions = listOf(
            TensaiDialogAction(stringResource(R.string.action_cancel), onCancel),
            TensaiDialogAction(stringResource(R.string.repo_storage_manage), onManage),
        ),
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp)),
            ) {
                FigureRow(stringResource(R.string.repo_storage_needed), formatGigabytes(neededBytes))
                HorizontalDivider(color = MaterialTheme.tensaiTints.outline)
                FigureRow(stringResource(R.string.repo_storage_free), formatGigabytes(freeBytes))
            }
        },
    )
}

@Composable
private fun FigureRow(label: String, value: String) {
    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(text = label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, fontFamily = TensaiMono, fontSize = 13.sp)
    }
}
