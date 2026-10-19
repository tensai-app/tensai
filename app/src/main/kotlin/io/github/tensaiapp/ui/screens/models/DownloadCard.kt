package io.github.tensaiapp.ui.screens.models

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tensaiapp.R
import io.github.tensaiapp.download.DownloadError
import io.github.tensaiapp.ui.components.TensaiButton
import io.github.tensaiapp.ui.components.TensaiButtonVariant
import io.github.tensaiapp.ui.components.TensaiMonoBadge
import io.github.tensaiapp.ui.components.TensaiProgressBar
import io.github.tensaiapp.ui.theme.TensaiMono
import io.github.tensaiapp.ui.theme.TensaiShapes
import io.github.tensaiapp.ui.theme.tensaiTints

/**
 * A download of the queue as a card of "On device" (TEN-142), same anatomy as [ModelCard]: name and
 * repo, state top-right, quant and size badges, then what the state offers.
 *
 * - downloading: bar, "123 MB of 1.2 GB", speed and time left, Pause and Cancel
 * - paused: bar, "Paused · 123 MB of 1.2 GB", Resume and Cancel
 * - queued: "Waiting · n ahead", Cancel
 * - failed: the reason, Retry (or Open when only the repo screen can fix it) and Remove
 *
 * No compatibility verdict and no backend badge: the queue row knows the file's size, not its
 * architecture or parameter count, and a verdict from the size alone would be a guess. The repo
 * screen the card opens has the rating.
 *
 * @param onOpen the card was tapped: go to its repo screen.
 */
@Composable
internal fun DownloadCard(
    card: DownloadCardModel,
    onOpen: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(TensaiShapes.large)
            .clickable(onClickLabel = stringResource(R.string.download_card_open_repo), role = Role.Button, onClick = onOpen),
        shape = TensaiShapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.tensaiTints.outline),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = card.name,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = 22.sp,
                    )
                    Text(
                        text = stringResource(R.string.model_source_repo, card.repo),
                        fontFamily = TensaiMono,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                DownloadStatus(card.state, modifier = Modifier.padding(top = 2.dp).widthIn(max = 140.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 12.dp)) {
                if (card.quant.isNotBlank()) TensaiMonoBadge(card.quant)
                TensaiMonoBadge(formatFileSize(card.sizeBytes))
            }

            DownloadStateBody(
                state = card.state,
                onOpen = onOpen,
                onPause = onPause,
                onResume = onResume,
                onCancel = onCancel,
                onRetry = onRetry,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }
}

/**
 * What a download offers in each state (see [DownloadCard]), also used by the model detail screen
 * for the "Download again" of a missing file.
 */
@Composable
internal fun DownloadStateBody(
    state: DownloadCardState,
    onOpen: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = modifier) {
        when (state) {
            is DownloadCardState.Downloading -> DownloadProgress(
                done = state.doneBytes,
                total = state.totalBytes,
                bytesPerSecond = state.bytesPerSecond,
                etaSeconds = state.etaSeconds,
                paused = false,
                onPauseOrResume = onPause,
                onCancel = onCancel,
            )
            is DownloadCardState.Paused -> DownloadProgress(
                done = state.doneBytes,
                total = state.totalBytes,
                bytesPerSecond = 0,
                etaSeconds = null,
                paused = true,
                onPauseOrResume = onResume,
                onCancel = onCancel,
            )
            is DownloadCardState.Queued -> {
                Text(
                    text = if (state.ahead > 0) {
                        stringResource(R.string.download_card_waiting_ahead, state.ahead)
                    } else {
                        stringResource(R.string.download_card_waiting)
                    },
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                TensaiProgressBar(progress = null)
                TensaiButton(
                    text = stringResource(R.string.download_action_cancel),
                    onClick = onCancel,
                    variant = TensaiButtonVariant.Outlined,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            is DownloadCardState.Failed -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(22.dp),
                    )
                    Text(
                        text = stringResource(R.string.download_failed_title, downloadErrorText(state.error)),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    when (state.primary) {
                        FailedPrimary.Retry -> TensaiButton(
                            text = stringResource(R.string.repo_retry),
                            onClick = onRetry,
                            icon = Icons.Default.Refresh,
                            modifier = Modifier.weight(1f),
                        )
                        FailedPrimary.OpenRepo -> TensaiButton(
                            text = stringResource(R.string.download_card_open),
                            onClick = onOpen,
                            icon = Icons.AutoMirrored.Rounded.OpenInNew,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    TensaiButton(
                        text = stringResource(R.string.model_remove),
                        onClick = onCancel,
                        variant = TensaiButtonVariant.Outlined,
                    )
                }
            }
        }
    }
}

/** The header's top-right slot: a dot and the state in words. */
@Composable
private fun DownloadStatus(state: DownloadCardState, modifier: Modifier = Modifier) {
    val (color: Color, label: Int) = when (state) {
        is DownloadCardState.Downloading -> MaterialTheme.colorScheme.primary to R.string.download_card_status_downloading
        is DownloadCardState.Paused -> MaterialTheme.colorScheme.onSurfaceVariant to R.string.download_card_status_paused
        is DownloadCardState.Queued -> MaterialTheme.colorScheme.onSurfaceVariant to R.string.download_card_status_queued
        is DownloadCardState.Failed -> MaterialTheme.colorScheme.error to R.string.download_card_status_failed
    }
    StatusIndicator(
        color = color,
        label = stringResource(label),
        labelColor = if (state is DownloadCardState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** The failure reason in words; the HTTP status is the one reason that carries a figure. */
@Composable
internal fun downloadErrorText(error: DownloadError?): String =
    if (error is DownloadError.Http) {
        stringResource(R.string.download_error_http, error.status)
    } else {
        stringResource(downloadErrorRes(error))
    }
