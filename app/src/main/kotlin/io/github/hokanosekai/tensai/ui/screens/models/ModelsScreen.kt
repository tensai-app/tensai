package io.github.hokanosekai.tensai.ui.screens.models

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import io.github.hokanosekai.tensai.data.models.DuplicateModelException
import io.github.hokanosekai.tensai.data.models.InsufficientStorageException
import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.data.models.ModelImportException
import io.github.hokanosekai.tensai.data.models.ModelInUseException
import io.github.hokanosekai.tensai.data.models.ModelMetadataException
import io.github.hokanosekai.tensai.data.models.ModelRepository
import io.github.hokanosekai.tensai.runtime.QueueState
import io.github.hokanosekai.tensai.runtime.RuntimeGraph
import io.github.hokanosekai.tensai.ui.components.KanjiGlyph
import kotlinx.coroutines.launch

/** MIME types to offer in the document picker — GGUF has no registered MIME type of its own. */
private val GgufMimeTypes = arrayOf("application/octet-stream", "*/*")

/**
 * Models library — lists imported [io.github.hokanosekai.tensai.data.models.ModelEntity]s and
 * lets the user import a new GGUF file. Chrome (back button, "Models" title) is owned by
 * `TensaiShell`; this composable is body content only.
 */
@Composable
fun ModelsScreen(
    onModelClick: (String) -> Unit,
    onLoadModelClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    repository: ModelRepository = RuntimeGraph.modelRepository,
) {
    val models by repository.models.collectAsState(initial = null)
    val queueState by RuntimeGraph.inferenceQueue.state.collectAsState()
    val loadedModelName = (queueState as? QueueState.ModelReady)?.info?.name

    var importProgress by remember { mutableStateOf<Float?>(null) }
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
            try {
                repository.importModel(uri) { progress -> importProgress = progress }
            } catch (e: DuplicateModelException) {
                snackbarHostState.showSnackbar(e.message ?: "This model is already imported.")
            } catch (e: InsufficientStorageException) {
                snackbarHostState.showSnackbar(e.message ?: "Not enough free storage.")
            } catch (e: ModelMetadataException) {
                snackbarHostState.showSnackbar(e.message ?: "Could not read this file's metadata.")
            } catch (e: ModelImportException) {
                snackbarHostState.showSnackbar(e.message ?: "Import failed.")
            } finally {
                importProgress = null
            }
        }
    }

    fun launchImport() = importLauncher.launch(GgufMimeTypes)

    Box(modifier = modifier.fillMaxSize()) {
        val currentModels = models
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
                                isLoaded = model.displayName == loadedModelName,
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
