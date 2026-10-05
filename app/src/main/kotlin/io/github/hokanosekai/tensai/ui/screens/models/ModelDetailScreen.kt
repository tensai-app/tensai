package io.github.hokanosekai.tensai.ui.screens.models

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.hokanosekai.tensai.R
import io.github.hokanosekai.tensai.data.models.ModelInUseException
import io.github.hokanosekai.tensai.data.models.ModelRepository
import io.github.hokanosekai.tensai.runtime.RuntimeGraph
import io.github.hokanosekai.tensai.ui.theme.TensaiMono
import io.github.hokanosekai.tensai.ui.theme.tensaiTints
import kotlinx.coroutines.launch

/**
 * Model detail — metadata, load/delete actions. Chrome (back button, title) is owned by
 * `TensaiShell`; this composable is body content only.
 */
@Composable
fun ModelDetailScreen(
    modelId: String,
    onLoadClick: (String) -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
    repository: ModelRepository = RuntimeGraph.modelRepository,
) {
    val models by repository.models.collectAsState(initial = null)
    val model = models?.find { it.id == modelId }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(deleteError) {
        deleteError?.let {
            snackbarHostState.showSnackbar(it)
            deleteError = null
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            models == null -> Unit // initial Room query hasn't emitted yet.
            model == null -> {
                Text(
                    text = stringResource(R.string.model_removed),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(20.dp),
                ) {
                    Text(text = model.displayName, style = MaterialTheme.typography.headlineSmall)

                    val fileExists = rememberFileExists(model.filePath)
                    val compatibility = modelCompatibility(model)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        CompatDot(compatibility.verdict)
                        Text(
                            text = compatLabel(compatibility.verdict),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // The file can go missing without this row's compatibility check noticing —
                    // it's estimated from the persisted GGUF metadata, not the file itself — so
                    // this is checked and surfaced separately.
                    if (!fileExists) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            Box(modifier = Modifier.size(8.dp).background(MaterialTheme.colorScheme.error, CircleShape))
                            Text(
                                text = stringResource(R.string.model_detail_file_missing),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Column(modifier = Modifier.padding(top = 20.dp)) {
                        MetadataRow(stringResource(R.string.model_detail_architecture), model.architecture)
                        MetadataRow(stringResource(R.string.model_detail_parameters), formatParamCount(model.paramCount))
                        MetadataRow(stringResource(R.string.model_detail_quantization), model.quantLabel)
                        MetadataRow(
                            stringResource(R.string.model_detail_context_length),
                            pluralStringResource(
                                R.plurals.model_detail_context_length_value,
                                model.contextLength.toInt(),
                                model.contextLength,
                            ),
                        )
                        MetadataRow(stringResource(R.string.model_detail_file_size), formatFileSize(model.sizeBytes))
                        MetadataRow(
                            stringResource(R.string.model_detail_thinking),
                            stringResource(
                                if (model.supportsThinking) R.string.model_detail_supported else R.string.model_detail_not_supported,
                            ),
                        )
                        MetadataRow(stringResource(R.string.model_detail_recommended_backend), compatibility.recommendedBackend.name)
                        WrappingMetadataRow(stringResource(R.string.model_detail_estimated_memory), compatExplanation(compatibility))
                    }

                    Button(
                        onClick = { onLoadClick(model.id) },
                        enabled = fileExists,
                        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    ) {
                        Text(stringResource(R.string.model_load))
                    }

                    TextButton(
                        onClick = { showDeleteConfirm = true },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    ) {
                        Text(stringResource(R.string.model_delete))
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 24.dp),
                        color = MaterialTheme.tensaiTints.outline,
                    )

                    // TODO(TEN-34): list this model's saved sessions here.
                    Text(text = stringResource(R.string.model_detail_saved_sessions), style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = stringResource(R.string.model_detail_coming_soon),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                if (showDeleteConfirm) {
                    AlertDialog(
                        onDismissRequest = { showDeleteConfirm = false },
                        title = { Text(stringResource(R.string.model_delete_confirm_title, model.displayName)) },
                        text = { Text(stringResource(R.string.model_delete_confirm_body)) },
                        confirmButton = {
                            TextButton(onClick = {
                                showDeleteConfirm = false
                                scope.launch {
                                    try {
                                        repository.deleteModel(model.id)
                                        onDeleted()
                                    } catch (e: ModelInUseException) {
                                        deleteError = context.getString(R.string.model_delete_in_use, model.displayName)
                                    }
                                }
                            }) {
                                Text(stringResource(R.string.model_delete_confirm), color = MaterialTheme.colorScheme.error)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.action_cancel)) }
                        },
                    )
                }
            }
        }

        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun MetadataRow(label: String, value: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        Text(
            text = value,
            fontFamily = TensaiMono,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

/**
 * Like [MetadataRow], but stacks [value] below [label] instead of side-by-side — for values too
 * long to share a line at any reasonable width, e.g. [compatExplanation]'s sentence.
 */
@Composable
private fun WrappingMetadataRow(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            fontFamily = TensaiMono,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
