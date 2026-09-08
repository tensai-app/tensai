package io.github.hokanosekai.tensai.ui.screens.models

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.hokanosekai.tensai.R
import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.data.models.ModelInUseException
import io.github.hokanosekai.tensai.data.models.ModelRepository
import io.github.hokanosekai.tensai.runtime.residentModel
import io.github.hokanosekai.tensai.runtime.RuntimeGraph
import io.github.hokanosekai.tensai.ui.components.KanjiGlyph
import io.github.hokanosekai.tensai.ui.theme.TensaiShapes
import io.github.hokanosekai.tensai.ui.theme.tensaiTints
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * MIME types to offer in the document picker — GGUF has no registered MIME type of its own.
 * Shared with the onboarding's own import step (TEN-36) so both pickers offer the same thing.
 */
internal val GgufMimeTypes = arrayOf("application/octet-stream", "*/*")

/**
 * Models library — lists imported [io.github.hokanosekai.tensai.data.models.ModelEntity]s and
 * lets the user import a new GGUF file. Chrome (back button, "Models" title) is owned by
 * `TensaiShell`; this composable is body content only.
 *
 * @param onNavigateToStorage where "Free up space" goes when the [StorageLowBanner] is showing —
 *   Settings' storage group, which is the only screen that can release space *without* deleting a
 *   model (saved sessions). Deleting a model is already possible right here, so the banner sends
 *   the user at the option this screen doesn't have.
 */
@Composable
fun ModelsScreen(
    onModelClick: (String) -> Unit,
    onLoadModelClick: (String) -> Unit,
    onNavigateToStorage: () -> Unit,
    modifier: Modifier = Modifier,
    repository: ModelRepository = RuntimeGraph.modelRepository,
) {
    val models by repository.models.collectAsState(initial = null)
    // `remember`-ed because storageUsage() builds a new Flow on each call: collecting the result of
    // the call directly would resubscribe — and re-stat the volume — on every recomposition. It
    // re-emits on its own whenever the model list changes, which is every occasion the free-space
    // reading could have moved because of something this app did.
    val storageUsageFlow = remember(repository) { repository.storageUsage() }
    val storageUsage by storageUsageFlow.collectAsState(initial = null)
    val queueState by RuntimeGraph.inferenceQueue.state.collectAsState()
    // By id, not display name (TEN-80): two quantisations of one model carry the same GGUF
    // name, and marking both "loaded" is a lie about the one that is not.
    val loadedModelId = queueState.residentModel?.modelId

    var importProgress by remember { mutableStateOf<Float?>(null) }
    var importFailure by remember { mutableStateOf<ImportFailure?>(null) }
    var pendingDelete by remember { mutableStateOf<ModelEntity?>(null) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    fun deleteModel(model: ModelEntity) {
        scope.launch {
            try {
                repository.deleteModel(model.id)
            } catch (e: ModelInUseException) {
                snackbarHostState.showSnackbar(e.message ?: "Cannot delete this model.")
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importProgress = 0f
            importFailure = null
            try {
                repository.importModel(uri) { progress -> importProgress = progress }
            } catch (e: CancellationException) {
                // Leaving the screen cancels this scope — that is not a failure to report, and
                // swallowing it would break the cancellation it is carrying.
                throw e
            } catch (e: Exception) {
                // One catch, and it classifies rather than reads (TEN-39). The four per-type
                // catches this replaces all ended in `e.message`, which for a copy failure was the
                // underlying IOException's own words; and an exception the list didn't name — a
                // SAF SecurityException, say — fell through and vanished, leaving the user with a
                // progress bar that stopped and no explanation at all. ImportFailure.of is total,
                // so nothing falls through and nothing brings a stack-trace sentence with it.
                importFailure = ImportFailure.of(e)
            } finally {
                importProgress = null
            }
        }
    }

    fun launchImport() = importLauncher.launch(GgufMimeTypes)

    val currentModels = models
    val currentUsage = storageUsage
    // Recomputed whenever the library or the free-space reading changes, not on every
    // recomposition: unlike the RAM check on each card, this one stats every model file, and
    // `storageUsage()` re-emits with the list anyway.
    val pressure = remember(currentModels, currentUsage) {
        if (currentModels == null || currentUsage == null) {
            StoragePressureState.Ok
        } else {
            StoragePressure.state(currentModels, currentUsage.freeBytes)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        (pressure as? StoragePressureState.Low)?.let { low ->
            StorageLowBanner(
                state = low,
                onFreeUpSpace = onNavigateToStorage,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Box(modifier = Modifier.weight(1f)) {
            when {
                currentModels == null -> Unit // initial Room query hasn't emitted yet.
                currentModels.isEmpty() && importProgress == null -> {
                    EmptyModelsState(onImportClick = ::launchImport, modifier = Modifier.fillMaxSize())
                }
                else -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(currentModels, key = { it.id }) { model ->
                                ModelCard(
                                    model = model,
                                    isLoaded = model.id == loadedModelId,
                                    onClick = { onModelClick(model.id) },
                                    onLoadClick = { onLoadModelClick(model.id) },
                                    onDeleteClick = { fileExists ->
                                        // A missing file has nothing left to lose — delete straight
                                        // away. A present file is a real, unrecoverable loss, so it
                                        // goes through the confirmation dialog below instead.
                                        if (fileExists) pendingDelete = model else deleteModel(model)
                                    },
                                )
                            }
                        }

                        importProgress?.let { progress ->
                            LinearProgressIndicator(
                                progress = { progress },
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }

                        OutlinedButton(
                            onClick = ::launchImport,
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                        ) {
                            Text("Import a GGUF file")
                        }
                    }
                }
            }

            pendingDelete?.let { model ->
                AlertDialog(
                    onDismissRequest = { pendingDelete = null },
                    title = { Text("Delete \"${model.displayName}\"?") },
                    text = { Text("This removes the model file from this device. This can't be undone.") },
                    confirmButton = {
                        TextButton(onClick = {
                            pendingDelete = null
                            deleteModel(model)
                        }) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
                    },
                )
            }

            SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
        }

        // Outside the `when`, so a failed import is reported the same way whether the library was
        // empty (the very first import, the likeliest one to go wrong) or already had models in it.
        importFailure?.let { failure ->
            ImportFailureCard(
                failure = failure,
                onRetry = {
                    importFailure = null
                    launchImport()
                },
                onDismiss = { importFailure = null },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

/**
 * Storage-pressure banner (TEN-39, state 2) — shown when [StoragePressure] finds less free space
 * left than an average model here occupies. Same sober warning treatment as `ChatScreen`'s own
 * banners: a tint, one line of figures, one action, no icon and no dismissal.
 *
 * Not dismissible on purpose, unlike [ImportFailureCard]: an import that failed is over and done
 * with, whereas this is a condition that is still true, and a banner the user can wave away is a
 * banner that stops meaning anything.
 */
@Composable
private fun StorageLowBanner(
    state: StoragePressureState.Low,
    onFreeUpSpace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.tensaiTints.warning12, modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.models_storage_low_title),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(
                        R.string.models_storage_low_body,
                        formatGigabytes(state.freeBytes),
                        formatGigabytes(state.averageModelBytes),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onFreeUpSpace) {
                Text(stringResource(R.string.models_storage_free_up))
            }
        }
    }
}

/**
 * Import failure card (TEN-39, state 3) — replaces the snackbar the four import exceptions used to
 * be shown in.
 *
 * A snackbar was the wrong container for this twice over: it times out, so a failure that happened
 * while the user was looking at the file picker was often gone by the time they looked back; and it
 * has room for one action at most, which the old code didn't use, leaving "try again" to mean
 * "find the import button again yourself". A card stays until it is answered and carries the retry.
 *
 * [body] is chosen by *kind* of failure — see [ImportFailure] for why the throwable's own message
 * never reaches here.
 */
@Composable
private fun ImportFailureCard(
    failure: ImportFailure,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = TensaiShapes.large,
        color = MaterialTheme.tensaiTints.error12,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.import_failed_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = importFailureBody(failure),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.import_failed_dismiss))
                }
                TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.import_failed_retry))
                }
            }
        }
    }
}

/**
 * The one place an [ImportFailure] becomes a sentence — every case from `strings.xml`.
 *
 * `internal` so the onboarding's own import step words its failures identically: two screens
 * offering the same import must not disagree about what went wrong with it.
 */
@Composable
internal fun importFailureBody(failure: ImportFailure): String = when (failure) {
    is ImportFailure.AlreadyImported ->
        stringResource(R.string.import_failed_already_imported, failure.fileName)
    is ImportFailure.NotEnoughSpace -> stringResource(
        R.string.import_failed_no_space,
        formatFileSize(failure.neededBytes),
        formatFileSize(failure.freeBytes),
    )
    is ImportFailure.NotAModelFile ->
        stringResource(R.string.import_failed_not_a_model, failure.fileName)
    is ImportFailure.CopyInterrupted ->
        stringResource(R.string.import_failed_copy_interrupted, failure.fileName)
    ImportFailure.Unknown -> stringResource(R.string.import_failed_unknown)
}

@Composable
private fun EmptyModelsState(onImportClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        KanjiGlyph(size = 72.dp, modifier = Modifier.alpha(0.4f))
        Text(
            text = "No models yet",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = "Import a GGUF file to get started.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        OutlinedButton(onClick = onImportClick, modifier = Modifier.padding(top = 20.dp)) {
            Text("Import a GGUF file")
        }
    }
}
