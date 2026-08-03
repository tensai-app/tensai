package io.github.hokanosekai.tensai.ui.screens.loading

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.runtime.rememberNotificationPermissionRequester
import io.github.hokanosekai.tensai.ui.components.KanjiGlyph
import io.github.hokanosekai.tensai.ui.theme.TensaiMono
import java.util.Locale

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
    val requestNotificationPermission = rememberNotificationPermissionRequester()

    LaunchedEffect(modelId) {
        // Per the ticket: requested once, right before the first loadModel() call in a session —
        // not blocking, a refusal just means no persistent notification later.
        requestNotificationPermission()
        viewModel.start()
    }

    LaunchedEffect(uiState) {
        if (uiState is LoadUiState.Success) onLoaded()
    }

    BackHandler(onBack = onBack)

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val state = uiState) {
            is LoadUiState.Preparing -> KanjiGlyph(size = 96.dp, glow = true)

            is LoadUiState.ModelNotFound -> ModelNotFoundContent(onBack = onBack)

            is LoadUiState.Blocked -> BlockedDialog(
                model = state.model,
                explanation = state.compatibility.explanation,
                onLoadAnyway = viewModel::loadAnyway,
                onSeeSmallerModels = onSeeSmallerModels,
            )

            is LoadUiState.InProgress -> LoadingContent(
                model = state.model,
                progress = state.progress,
                onContinueInBackground = onBack,
            )

            is LoadUiState.Success -> Unit // LaunchedEffect above navigates away immediately.

            is LoadUiState.OomFailed -> OomDialog(
                model = state.model,
                onSeeSmallerModels = onSeeSmallerModels,
                onClose = onBack,
            )

            is LoadUiState.GenericFailed -> GenericFailDialog(
                message = state.message,
                onRetry = viewModel::retry,
                onRetryOnCpu = viewModel::retryOnCpu,
                onClose = onBack,
            )
        }
    }
}

@Composable
private fun LoadingContent(
    model: ModelEntity,
    progress: Float,
    onContinueInBackground: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.weight(1f))

        KanjiGlyph(size = 150.dp, progress = progress, glow = true)
        Text(
            text = model.displayName,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            text = "loading to memory · ${formatGb((progress * model.sizeBytes).toLong())} / " +
                "${formatGb(model.sizeBytes)} GB",
            fontFamily = TensaiMono,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )

        Spacer(modifier = Modifier.weight(1f))

        TextButton(onClick = onContinueInBackground) {
            Text("Continue in background")
        }
    }
}

@Composable
private fun ModelNotFoundContent(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("This model was removed.", style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = onBack, modifier = Modifier.padding(top = 12.dp)) {
            Text("Back")
        }
    }
}

@Composable
private fun BlockedDialog(
    model: ModelEntity,
    explanation: String,
    onLoadAnyway: () -> Unit,
    onSeeSmallerModels: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onSeeSmallerModels,
        title = { Text("\"${model.displayName}\" probably won't fit") },
        text = { Text(explanation) },
        confirmButton = {
            TextButton(onClick = onLoadAnyway) { Text("Load anyway") }
        },
        dismissButton = {
            TextButton(onClick = onSeeSmallerModels) { Text("See smaller models") }
        },
    )
}

@Composable
private fun OomDialog(model: ModelEntity, onSeeSmallerModels: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Not enough memory") },
        text = { Text("${model.displayName} couldn't fit in memory. Try a smaller quant.") },
        confirmButton = {
            TextButton(onClick = onSeeSmallerModels) { Text("See smaller models") }
        },
        dismissButton = {
            TextButton(onClick = onClose) { Text("Close") }
        },
    )
}

@Composable
private fun GenericFailDialog(
    message: String,
    onRetry: () -> Unit,
    onRetryOnCpu: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Couldn't load this model") },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onRetry) { Text("Retry") }
        },
        dismissButton = {
            // Simple chip-row equivalent for the CPU-retry override — two text actions rather
            // than a dedicated FilterChip row, to keep this dialog's default M3 layout.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onRetryOnCpu) { Text("Retry on CPU") }
                TextButton(onClick = onClose) { Text("Close") }
            }
        },
    )
}

/** e.g. 380_000_000L -> "0.4" — always GB, no MB fallback (see the "X.X / Y.Y GB" line's spec). */
private fun formatGb(bytes: Long): String =
    String.format(Locale.US, "%.1f", bytes / (1024.0 * 1024.0 * 1024.0))
