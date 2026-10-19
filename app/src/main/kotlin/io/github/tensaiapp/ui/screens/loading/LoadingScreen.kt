package io.github.tensaiapp.ui.screens.loading

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.foundation.layout.size
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import io.github.tensaiapp.engine.LoadStage
import io.github.tensaiapp.runtime.LoadProgressMap
import io.github.tensaiapp.ui.components.TensaiButton
import io.github.tensaiapp.ui.components.TensaiButtonSize
import io.github.tensaiapp.ui.components.TensaiButtonVariant
import io.github.tensaiapp.ui.components.TensaiDialog
import io.github.tensaiapp.ui.components.TensaiDialogAction
import io.github.tensaiapp.ui.components.TensaiProgressBar
import io.github.tensaiapp.ui.theme.tensaiColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.tensaiapp.R
import io.github.tensaiapp.compat.Compatibility
import io.github.tensaiapp.compat.Verdict
import io.github.tensaiapp.data.models.ModelEntity
import io.github.tensaiapp.runtime.rememberNotificationAskLauncher
import io.github.tensaiapp.runtime.rememberNotificationAskState
import io.github.tensaiapp.ui.components.KanjiGlyph
import io.github.tensaiapp.ui.screens.models.compatExplanation
import io.github.tensaiapp.ui.theme.TensaiMono

/**
 * Loading screen for the `loading/{modelId}` route (TEN-30) — replaces the old fake looping-glyph
 * placeholder with the real [LoadModelViewModel]-driven flow.
 *
 * @param onLoaded called once when the load succeeds — the caller navigates to chat.
 * @param onBack called for both the explicit "Continue in background" button and the system back
 *   gesture (see the [BackHandler] below) — the load itself is never cancelled by either, since it
 *   runs on `RuntimeGraph.applicationScope` (see [LoadModelViewModel]'s KDoc), not this
 *   composable's scope.
 * @param onSeeSmallerModels called from the WontFit/OOM dialogs — the caller navigates back to
 *   the Models list.
 */
@Composable
fun LoadingScreen(
    modelId: String,
    onLoaded: () -> Unit,
    onBack: () -> Unit,
    onSeeSmallerModels: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LoadModelViewModel = remember(modelId) { LoadModelViewModel(modelId) },
) {
    val uiState by viewModel.state.collectAsState()
    val askState = rememberNotificationAskState()
    val launchNotificationAsk = rememberNotificationAskLauncher()

    // The ask used to fire here on every load, a bare dialog over the loading screen (TEN-127). It
    // now rides on "Continue in background", the moment the notification starts to matter. At most
    // one dialog per install: see [NotificationPermissionGate]. The state was read on entry so the
    // tap does no I/O: the dialog launches synchronously, the claim persists on the application
    // scope, and navigation follows at once. An unread state (null) just navigates. The guard makes
    // a second tap a no-op, so a double tap can't navigate twice either.
    var continued by remember { mutableStateOf(false) }
    val continueInBackground: () -> Unit = {
        if (!continued) {
            continued = true
            runCatching { launchNotificationAsk(askState) }
            onBack()
        }
    }

    // start() is not a suspend function and runs the attempt on RuntimeGraph.applicationScope (see
    // [LoadModelViewModel]), so leaving the screen can't cancel the load.
    LaunchedEffect(modelId) { viewModel.start() }

    LaunchedEffect(uiState) {
        if (uiState is LoadUiState.Success) onLoaded()
    }

    BackHandler(onBack = onBack)

    Box(
        modifier = modifier
            .fillMaxSize()
            // Opaque first: the route fades in over the previous screen, and the translucent wash
            // alone let the Models list show through the first frames.
            .background(MaterialTheme.colorScheme.background)
            .loadingBackdrop(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center,
    ) {
        when (val state = uiState) {
            is LoadUiState.Preparing -> KanjiGlyph(size = 96.dp, glow = true)

            is LoadUiState.ModelNotFound -> ModelNotFoundContent(onBack = onBack)

            is LoadUiState.FileMissing -> FileMissingDialog(model = state.model, onClose = onBack)

            is LoadUiState.Blocked -> BlockedDialog(
                model = state.model,
                compatibility = state.compatibility,
                onLoadAnyway = viewModel::loadAnyway,
                onSeeSmallerModels = onSeeSmallerModels,
            )

            is LoadUiState.InProgress -> LoadingContent(
                state = state,
                onContinueInBackground = continueInBackground,
            )

            is LoadUiState.Success -> Unit // LaunchedEffect above navigates away immediately.

            is LoadUiState.OomFailed -> OomDialog(
                model = state.model,
                onSeeSmallerModels = onSeeSmallerModels,
                onClose = onBack,
            )

            is LoadUiState.GenericFailed -> GenericFailDialog(
                failure = state.failure,
                detail = state.detail,
                onRetry = viewModel::retry,
                onRetryOnCpu = viewModel::retryOnCpu,
                canRetryOnCpu = state.canRetryOnCpu,
                onClose = onBack,
            )
        }
    }
}

/**
 * The loading screen proper (TEN-123): glyph revealed by the overall progress, model name, a
 * "<stage> · NN%" status line and the bar, with "Continue in background" tucked under it. Every
 * number comes from [LoadUiState.InProgress], which mirrors the engine's real stages; the bar
 * pulses (rather than moving) while a stage has no progress of its own.
 */
@Composable
private fun LoadingContent(
    state: LoadUiState.InProgress,
    onContinueInBackground: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Weights progress that stops moving for a second (page faults on a cold mmap, a slow flash
    // read) is "working, no news", not "stuck at 40%": pulse until the next value arrives.
    var stalled by remember { mutableStateOf(false) }
    LaunchedEffect(state.stage, state.weightsProgress) {
        stalled = false
        delay(WEIGHTS_STALL_MILLIS)
        stalled = true
    }
    val pulsing = LoadProgressMap.isIndeterminate(state.stage, state.weightsProgress) ||
        (stalled && state.stage == LoadStage.Weights)
    val status = stringResource(
        R.string.loading_status,
        stageLabel(state.stage, state.backendLabel),
        state.percent,
    )
    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        KanjiGlyph(size = 150.dp, progress = state.progress, glow = true)
        Text(
            text = state.model.shownName,
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 32.dp),
        )
        Text(
            text = status,
            fontFamily = TensaiMono,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // The bar below carries the same sentence as its state description.
            modifier = Modifier.padding(top = 6.dp).clearAndSetSemantics {},
        )
        // TalkBack: announce stage changes only (the label, no percent), through a 1dp live region.
        // The percent ticks far too often to be read out; the bar exposes it on focus instead.
        val stageText = stageLabel(state.stage, state.backendLabel)
        Box(
            modifier = Modifier.size(1.dp).semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = stageText
            },
        )
        TensaiProgressBar(
            progress = state.progress,
            pulse = pulsing,
            modifier = Modifier
                .padding(top = 24.dp)
                .widthIn(max = 240.dp)
                .semantics { stateDescription = status },
        )
        TensaiButton(
            text = stringResource(R.string.loading_continue_in_background),
            onClick = onContinueInBackground,
            variant = TensaiButtonVariant.Text,
            size = TensaiButtonSize.Sm,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

private const val WEIGHTS_STALL_MILLIS = 1_000L

@Composable
private fun stageLabel(stage: LoadStage, backendLabel: String): String = when (stage) {
    LoadStage.Preparing -> stringResource(R.string.loading_stage_preparing)
    LoadStage.Weights -> stringResource(R.string.loading_stage_weights)
    LoadStage.Context -> stringResource(R.string.loading_stage_context)
    LoadStage.Warmup -> stringResource(R.string.loading_stage_warmup, backendLabel)
    LoadStage.Ready -> stringResource(R.string.loading_stage_ready)
}

/** Radial wash behind the loading screen: primary at 10% fading out by 60% of a 120% x 90% ellipse centered at 50% / 30%. */
private fun Modifier.loadingBackdrop(color: Color): Modifier = drawBehind {
    val center = Offset(size.width * 0.5f, size.height * 0.3f)
    val rx = size.width * 1.2f
    val ry = size.height * 0.9f
    val brush = Brush.radialGradient(
        0f to color,
        0.6f to Color.Transparent,
        1f to Color.Transparent,
        center = center,
        radius = rx,
    )
    // The brush is circular; squashing it vertically turns the circle into the design's ellipse.
    withTransform({ scale(1f, ry / rx, pivot = center) }) {
        drawRect(brush, topLeft = Offset(center.x - rx, center.y - rx), size = Size(rx * 2f, rx * 2f))
    }
}

@Composable
private fun ModelNotFoundContent(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.model_removed), style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = onBack, modifier = Modifier.padding(top = 12.dp)) {
            Text(stringResource(R.string.action_back))
        }
    }
}

@Composable
private fun FileMissingDialog(model: ModelEntity, onClose: () -> Unit) {
    TensaiDialog(
        onDismissRequest = onClose,
        icon = Icons.Default.FolderOff,
        iconTint = MaterialTheme.tensaiColors.warning,
        title = stringResource(R.string.model_file_missing),
        body = stringResource(R.string.loading_file_missing_body, model.shownName),
        actions = listOf(TensaiDialogAction(stringResource(R.string.loading_back_to_models), onClose)),
    )
}

/**
 * The [Verdict.WontFit] confirmation (TEN-39, state 4 — the model card's own red light is the
 * other half). Its numbers come from [compatExplanation], the same one sentence the card printed a
 * tap earlier, off the same [Compatibility] instance the block was decided from — so the figure
 * that stopped the load and the figure shown for it cannot disagree.
 */
@Composable
private fun BlockedDialog(
    model: ModelEntity,
    compatibility: Compatibility,
    onLoadAnyway: () -> Unit,
    onSeeSmallerModels: () -> Unit,
) {
    TensaiDialog(
        onDismissRequest = onSeeSmallerModels,
        icon = Icons.Default.Warning,
        iconTint = MaterialTheme.tensaiColors.warning,
        title = stringResource(R.string.loading_blocked_title, model.shownName),
        body = compatExplanation(compatibility),
        actions = listOf(
            TensaiDialogAction(stringResource(R.string.loading_see_smaller_models), onSeeSmallerModels),
            TensaiDialogAction(stringResource(R.string.model_compat_load_anyway), onLoadAnyway),
        ),
    )
}

@Composable
private fun OomDialog(model: ModelEntity, onSeeSmallerModels: () -> Unit, onClose: () -> Unit) {
    TensaiDialog(
        onDismissRequest = onClose,
        icon = Icons.Default.Memory,
        iconTint = MaterialTheme.colorScheme.error,
        title = stringResource(R.string.loading_oom_title),
        body = stringResource(R.string.loading_oom_body, model.shownName),
        actions = listOf(
            TensaiDialogAction(stringResource(R.string.action_close), onClose),
            TensaiDialogAction(stringResource(R.string.loading_see_smaller_models), onSeeSmallerModels),
        ),
    )
}

/**
 * Every load failure that isn't out-of-memory. The body is chosen by [LoadFailure] — see
 * [LoadUiState.GenericFailed] for what used to be printed here instead.
 */
@Composable
private fun GenericFailDialog(
    failure: LoadFailure,
    detail: String?,
    onRetry: () -> Unit,
    onRetryOnCpu: () -> Unit,
    canRetryOnCpu: Boolean,
    onClose: () -> Unit,
) {
    TensaiDialog(
        onDismissRequest = onClose,
        icon = Icons.Default.ErrorOutline,
        iconTint = MaterialTheme.colorScheme.error,
        title = stringResource(R.string.load_failed_title),
        body = when {
            failure == LoadFailure.UnsupportedArchitecture && !detail.isNullOrBlank() ->
                stringResource(R.string.load_failed_unsupported, detail)
            else -> stringResource(
                when (failure) {
                    LoadFailure.UnreadableModel, LoadFailure.UnsupportedArchitecture -> R.string.load_failed_unreadable
                    LoadFailure.EngineBusy -> R.string.load_failed_busy
                    LoadFailure.IncompleteModel -> R.string.load_failed_incomplete
                    LoadFailure.Unknown -> R.string.load_failed_unknown
                },
            )
        },
        actions = buildList {
            add(TensaiDialogAction(stringResource(R.string.action_close), onClose))
            // The same file fails the same way: a retry is a dead button (TEN-167).
            if (!failure.isDeterministic) {
                if (canRetryOnCpu) {
                    add(TensaiDialogAction(stringResource(R.string.loading_retry_on_cpu), onRetryOnCpu))
                }
                add(TensaiDialogAction(stringResource(R.string.loading_retry), onRetry))
            }
        },
    )
}

