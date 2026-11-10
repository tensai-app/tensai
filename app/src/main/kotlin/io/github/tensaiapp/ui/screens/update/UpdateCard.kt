package io.github.tensaiapp.ui.screens.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.tensaiapp.R
import io.github.tensaiapp.runtime.RuntimeGraph
import io.github.tensaiapp.runtime.rememberResumeCount
import io.github.tensaiapp.ui.components.MarkdownHeadings
import io.github.tensaiapp.ui.components.MarkdownText
import io.github.tensaiapp.ui.components.TensaiButton
import io.github.tensaiapp.ui.components.TensaiButtonSize
import io.github.tensaiapp.ui.components.TensaiButtonVariant
import io.github.tensaiapp.ui.components.TensaiProgressBar
import io.github.tensaiapp.ui.components.rememberReducedMotion
import io.github.tensaiapp.ui.theme.TensaiMotion
import io.github.tensaiapp.ui.theme.TensaiShapes
import io.github.tensaiapp.ui.theme.tensaiTints
import io.github.tensaiapp.update.UpdateFailure
import io.github.tensaiapp.update.UpdateInstaller
import io.github.tensaiapp.update.UpdateManager
import io.github.tensaiapp.update.UpdateState
import kotlinx.coroutines.flow.getAndUpdate

/**
 * The update card above the chat (TEN-227): first the opt-in question, then, once allowed, whatever
 * [UpdateManager.state] holds. Nothing when there is nothing to say. Each resume tells the manager, so coming back from
 * Android's "Install unknown apps" page carries the update on.
 */
@Composable
fun UpdateCardHost(modifier: Modifier = Modifier, manager: UpdateManager = RuntimeGraph.updateManager) {
    val asksConsent by manager.asksConsent.collectAsState(initial = false)
    val state by manager.state.collectAsState()
    val context = LocalContext.current
    val resumes = rememberResumeCount()
    // Only while this host is composed, so on chat routes: deliberate, the card that asked for the permission or the
    // install lives here, and a confirmation held while away waits for the user to be back on it.
    LaunchedEffect(resumes) {
        manager.onResume()
        UpdateInstaller.pendingConfirmation.getAndUpdate { null }?.let { startConfirmation(context, it) }
    }

    when {
        asksConsent -> UpdateCardFrame(
            eyebrow = stringResource(R.string.update_consent_eyebrow),
            title = stringResource(R.string.update_consent_title),
            modifier = modifier,
            actions = {
                CardButton(stringResource(R.string.update_consent_no), TensaiButtonVariant.Text) { manager.setAllowed(false) }
                CardButton(stringResource(R.string.update_consent_yes), TensaiButtonVariant.Tonal) { manager.setAllowed(true) }
            },
        ) { Body(stringResource(R.string.update_consent_body)) }

        else -> UpdateStateCard(state, manager, modifier)
    }
}

@Composable
private fun UpdateStateCard(state: UpdateState, manager: UpdateManager, modifier: Modifier) {
    val context = LocalContext.current
    when (state) {
        UpdateState.Idle -> Unit
        is UpdateState.Available -> UpdateCardFrame(
            eyebrow = stringResource(R.string.update_available_eyebrow),
            title = stringResource(R.string.update_version_title, state.release.version),
            modifier = modifier,
            actions = {
                CardButton(stringResource(R.string.update_skip), TensaiButtonVariant.Text, manager::skip)
                CardButton(stringResource(R.string.update_later), TensaiButtonVariant.Text, manager::later)
                CardButton(stringResource(R.string.update_install), TensaiButtonVariant.Tonal, manager::update)
            },
        ) {
            if (state.release.notes.isNotBlank()) {
                Box(modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                    MarkdownText(
                        markdown = state.release.notes,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        headings = MarkdownHeadings.Compact,
                    )
                }
            }
        }
        is UpdateState.NeedsPermission -> UpdateCardFrame(
            eyebrow = stringResource(R.string.update_available_eyebrow),
            title = stringResource(R.string.update_version_title, state.release.version),
            modifier = modifier,
            actions = {
                CardButton(stringResource(R.string.update_later), TensaiButtonVariant.Text, manager::later)
                CardButton(stringResource(R.string.update_permission_open), TensaiButtonVariant.Tonal) { openPermissionSettings(context) }
            },
        ) { Body(stringResource(R.string.update_permission_body)) }
        is UpdateState.Downloading -> UpdateCardFrame(
            eyebrow = stringResource(R.string.update_available_eyebrow),
            title = stringResource(R.string.update_downloading, state.release.version),
            modifier = modifier,
        ) {
            TensaiProgressBar(progress = state.fraction, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            Body(stringResource(R.string.update_percent, (state.fraction * 100).toInt()))
        }
        is UpdateState.Installing -> UpdateCardFrame(
            eyebrow = stringResource(R.string.update_available_eyebrow),
            title = stringResource(R.string.update_version_title, state.release.version),
            modifier = modifier,
            actions = { CardButton(stringResource(R.string.action_dismiss), TensaiButtonVariant.Text, manager::later) },
        ) { Body(stringResource(R.string.update_installing)) }
        is UpdateState.Failed -> UpdateCardFrame(
            eyebrow = stringResource(R.string.update_failed_eyebrow),
            title = stringResource(R.string.update_version_title, state.release.version),
            modifier = modifier,
            actions = {
                CardButton(stringResource(R.string.action_dismiss), TensaiButtonVariant.Text, manager::later)
                // A wrong signer stays wrong: no retry for it.
                if (state.reason != UpdateFailure.Signature) {
                    CardButton(stringResource(R.string.update_retry), TensaiButtonVariant.Tonal, manager::update)
                }
            },
        ) {
            Body(
                stringResource(
                    when (state.reason) {
                        UpdateFailure.Download -> R.string.update_failed_download
                        UpdateFailure.Hash -> R.string.update_failed_hash
                        UpdateFailure.Signature -> R.string.update_failed_signature
                        UpdateFailure.Install -> R.string.update_failed_install
                    },
                ),
            )
        }
    }
}

/** Same frame as the tool confirmation card: eyebrow, title, body, then the actions on the right. */
@Composable
private fun UpdateCardFrame(
    eyebrow: String,
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
    body: @Composable () -> Unit,
) {
    val reducedMotion = rememberReducedMotion()
    Surface(
        shape = TensaiShapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.tensaiTints.outline),
        modifier = modifier
            .semantics { liveRegion = LiveRegionMode.Polite }
            .fillMaxWidth()
            .animateContentSize(if (reducedMotion) snap() else tween(TensaiMotion.DurBase)),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp)) {
            Text(
                text = eyebrow,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.04.em,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = title,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.01).em,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { body() }
            // Flowing: three actions in French do not fit one line on a narrow phone.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) { actions() }
        }
    }
}

@Composable
private fun Body(text: String) {
    Text(text = text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun CardButton(text: String, variant: TensaiButtonVariant, onClick: () -> Unit) {
    TensaiButton(text = text, onClick = onClick, variant = variant, size = TensaiButtonSize.Sm)
}

/** Android's install confirmation that came while tensai was in the background, see [UpdateInstaller.pendingConfirmation]. */
private fun startConfirmation(context: Context, confirm: Intent) {
    try {
        context.startActivity(confirm)
    } catch (_: ActivityNotFoundException) {
        // The installer went away: the card still says to confirm, Dismiss clears it.
    }
}

private fun openPermissionSettings(context: Context) {
    try {
        context.startActivity(UpdateInstaller.permissionSettings(context))
    } catch (_: ActivityNotFoundException) {
        // No such page on this build of Android: the card stays, the user can find it in Settings.
    }
}
