package io.github.hokanosekai.tensai.ui.screens.models

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hokanosekai.tensai.R
import io.github.hokanosekai.tensai.compat.CompatibilityChecker
import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.data.models.ModelInUseException
import io.github.hokanosekai.tensai.data.models.ModelRepository
import io.github.hokanosekai.tensai.runtime.residentModel
import io.github.hokanosekai.tensai.runtime.RuntimeGraph
import io.github.hokanosekai.tensai.ui.components.KanjiGlyph
import io.github.hokanosekai.tensai.ui.components.TensaiButton
import io.github.hokanosekai.tensai.ui.components.TensaiButtonSize
import io.github.hokanosekai.tensai.ui.components.TensaiButtonVariant
import io.github.hokanosekai.tensai.ui.components.TensaiChip
import io.github.hokanosekai.tensai.ui.components.TensaiProgressBar
import io.github.hokanosekai.tensai.ui.theme.TensaiMono
import io.github.hokanosekai.tensai.ui.theme.TensaiShapes
import io.github.hokanosekai.tensai.ui.theme.tensaiColors
import io.github.hokanosekai.tensai.ui.theme.tensaiTints
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
 * @param importRequests taps on the shell top bar's "+" (TEN-108). Each one opens the picker,
 *   exactly as the empty state's button and the failure dialog's "Try again" do.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelsScreen(
    onModelClick: (String) -> Unit,
    onLoadModelClick: (String) -> Unit,
    onNavigateToStorage: () -> Unit,
    modifier: Modifier = Modifier,
    importRequests: Flow<Unit> = emptyFlow(),
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
    var importTotalBytes by remember { mutableStateOf<Long?>(null) }
    var showAddSheet by remember { mutableStateOf(false) }
    // Bumped after a successful import: a relink can leave the row structurally equal (same id, same
    // path), so nothing else would make the file-existence pass run again.
    var refreshTick by remember { mutableIntStateOf(0) }
    var importFailure by remember { mutableStateOf<ImportFailure?>(null) }
    var pendingDelete by remember { mutableStateOf<ModelEntity?>(null) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    fun deleteModel(model: ModelEntity) {
        scope.launch {
            try {
                repository.deleteModel(model.id)
            } catch (e: ModelInUseException) {
                snackbarHostState.showSnackbar(context.getString(R.string.model_delete_in_use, model.displayName))
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importProgress = 0f
            importTotalBytes = repository.uriSize(uri)
            importFailure = null
            try {
                repository.importModel(uri) { progress -> importProgress = progress }
                refreshTick++
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

    LaunchedEffect(importRequests) {
        importRequests.collect { showAddSheet = true }
    }

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

    // Verdicts are computed here, once per model, so the filter and the badge on each card read the
    // same answer (they used to be computed per card). File existence is a stat() per model, done
    // off the main thread; until it lands every file counts as present, as `rememberFileExists` did.
    // Not keyed: the map survives a library change (no blank list, no scroll jump); the effect
    // below refreshes it. remember + LaunchedEffect rather than a keyed produceState: lint's
    // ProduceStateDoesNotAssignValue misreads the keyed overloads.
    val seenTick = remember { intArrayOf(0) }
    var existence by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    LaunchedEffect(currentModels, refreshTick) {
        val list = currentModels ?: return@LaunchedEffect
        // A tick means "the files changed under unchanged rows": drop the old answers so no card
        // renders from a stale one. A plain library change keeps them (no blank list, no scroll jump).
        if (seenTick[0] != refreshTick) {
            seenTick[0] = refreshTick
            existence = emptyMap()
        }
        existence = withContext(Dispatchers.IO) { list.associate { it.id to File(it.filePath).exists() } }
    }
    // Until every id has been statted the list is held back (no card, no filter result): a card
    // cannot say "Load model" for a frame and then flip to "File missing".
    val existenceReady = currentModels?.all { it.id in existence } == true
    // `remember`-ed on the library and the existence map so the list does not reshuffle under the
    // user's finger when free RAM twitches; a library change re-reads the device profile.
    val verdicts = remember(currentModels, existence, existenceReady) {
        if (!existenceReady) return@remember emptyMap()
        val profile = RuntimeGraph.deviceProfiler.profile()
        currentModels.orEmpty().associate { model ->
            model.id to if (existence[model.id] == true) CompatibilityChecker.check(model, profile) else null
        }
    }
    val hasVulkan = remember { RuntimeGraph.deviceProfiler.profile().hasVulkan }
    var selectedFilter by rememberSaveable { mutableStateOf(ModelFilter.All) }
    // A saved Vulkan selection on a device that no longer offers the chip falls back to All.
    val filter = if (selectedFilter in availableFilters(hasVulkan)) selectedFilter else ModelFilter.All
    val shown = filterModels(currentModels.orEmpty(), verdicts, filter)

    Column(modifier = modifier.fillMaxSize()) {
        // At the top, right under the bar whose "+" started it (TEN-108), and outside the `when`
        // so it shows whether or not the library was empty when the import began.
        importProgress?.let { progress ->
            ImportProgress(progress, importTotalBytes)
        }

        if (!currentModels.isNullOrEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, top = 4.dp, end = 20.dp, bottom = 12.dp)
                    .selectableGroup(),
            ) {
                availableFilters(hasVulkan).forEach { option ->
                    TensaiChip(
                        label = stringResource(option.labelRes),
                        selected = option == filter,
                        onClick = { selectedFilter = option },
                    )
                }
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when {
                currentModels == null -> Unit // initial Room query hasn't emitted yet.
                currentModels.isEmpty() && importProgress == null -> {
                    EmptyModelsState(onImportClick = ::launchImport, modifier = Modifier.fillMaxSize())
                }
                !existenceReady -> Unit // stat() pass still running, see above.
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        (pressure as? StoragePressureState.Low)?.let { low ->
                            item(key = "storage") {
                                StorageLowBanner(state = low, onFreeUpSpace = onNavigateToStorage)
                            }
                        }
                        if (shown.isEmpty() && currentModels.isNotEmpty()) {
                            item(key = "filter-empty") {
                                Text(
                                    text = stringResource(
                                        if (filter == ModelFilter.Vulkan) R.string.models_filter_empty_vulkan
                                        else R.string.models_filter_empty_cpu,
                                    ),
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 64.dp),
                                )
                            }
                        }
                        items(shown, key = { it.id }) { model ->
                            ModelCard(
                                model = model,
                                fileExists = existence[model.id] == true,
                                compatibility = verdicts[model.id],
                                isLoaded = model.id == loadedModelId,
                                onClick = { onModelClick(model.id) },
                                onLoadClick = { onLoadModelClick(model.id) },
                                onDetailsClick = { onModelClick(model.id) },
                                onUnloadClick = {
                                    // applicationScope like the notification action: the unload may
                                    // save a session first and must outlive this screen.
                                    RuntimeGraph.applicationScope.launch {
                                        unloadResidentModel(RuntimeGraph.inferenceQueue)
                                    }
                                },
                                onDeleteClick = { fileExists ->
                                    // A missing file has nothing left to lose: delete straight
                                    // away. A present file is a real, unrecoverable loss, so it
                                    // goes through the confirmation dialog below instead.
                                    if (fileExists) pendingDelete = model else deleteModel(model)
                                },
                            )
                        }
                    }
                }
            }

            pendingDelete?.let { model ->
                DeleteModelDialog(
                    modelName = model.displayName,
                    onCancel = { pendingDelete = null },
                    onConfirm = {
                        pendingDelete = null
                        deleteModel(model)
                    },
                )
            }

            if (showAddSheet) {
                AddModelSheet(onDismiss = { showAddSheet = false }, onImportFromPhone = ::launchImport)
            }

            SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
        }

        // Outside the `when`, so a failed import is reported the same way whether the library was
        // empty (the very first import, the likeliest one to go wrong) or already had models in it.
        importFailure?.let { failure ->
            ImportFailureDialog(
                failure = failure,
                onRetry = {
                    importFailure = null
                    launchImport()
                },
                onDismiss = { importFailure = null },
            )
        }
    }
}

/**
 * Import progress under the top bar: rounded lavender bar and a mono line "Copying model · 1.2 / 2.1 GB"
 * (just "Copying model" when the provider did not report a size).
 */
@Composable
internal fun ImportProgress(progress: Float, totalBytes: Long?, modifier: Modifier = Modifier) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.padding(start = 20.dp, top = 2.dp, end = 20.dp, bottom = 14.dp),
    ) {
        TensaiProgressBar(if (totalBytes != null && totalBytes > 0) progress else null)
        Text(
            text = if (totalBytes != null && totalBytes > 0) {
                stringResource(
                    R.string.models_import_progress,
                    formatGigabytes((totalBytes * progress.coerceIn(0f, 1f)).toLong()),
                    formatGigabytes(totalBytes),
                )
            } else {
                stringResource(R.string.models_import_progress_unknown)
            },
            fontFamily = TensaiMono,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Storage-pressure banner (TEN-39, state 2) — shown when [StoragePressure] finds less free space
 * left than an average model here occupies. First item of the list (it scrolls away with it):
 * warning-tinted card, one line of figures (set in mono), one action.
 *
 * Not dismissible on purpose, unlike [ImportFailureDialog]: an import that failed is over and done
 * with, whereas this is a condition that is still true, and a banner the user can wave away is a
 * banner that stops meaning anything.
 */
@Composable
private fun StorageLowBanner(
    state: StoragePressureState.Low,
    onFreeUpSpace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val warning = MaterialTheme.tensaiColors.warning
    val free = formatGigabytes(state.freeBytes)
    val average = formatGigabytes(state.averageModelBytes)
    val body = monoFigures(stringResource(R.string.models_storage_low_body, free, average), listOf(free, average))
    Surface(
        shape = TensaiShapes.large,
        color = MaterialTheme.tensaiTints.warning12,
        border = BorderStroke(1.dp, warning.copy(alpha = 0.4f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.SdStorage,
                contentDescription = null,
                tint = warning,
                modifier = Modifier.size(22.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.models_storage_low_title),
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = body,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TensaiButton(
                    text = stringResource(R.string.models_storage_free_up),
                    onClick = onFreeUpSpace,
                    variant = TensaiButtonVariant.Tonal,
                    size = TensaiButtonSize.Sm,
                    icon = Icons.Default.DeleteSweep,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

/** [text] with each of [figures] (first occurrence not already claimed) set in the mono face. */
internal fun monoFigures(text: String, figures: List<String>): AnnotatedString = buildAnnotatedString {
    append(text)
    val claimed = mutableListOf<IntRange>()
    for (figure in figures) {
        var from = 0
        while (true) {
            val start = text.indexOf(figure, from)
            if (start < 0) break
            val range = start until start + figure.length
            if (claimed.none { it.first <= range.last && range.first <= it.last }) {
                claimed += range
                addStyle(SpanStyle(fontFamily = TensaiMono), range.first, range.last + 1)
                break
            }
            from = start + 1
        }
    }
}

/**
 * Import failure dialog (TEN-39 state 3, a dialog since TEN-108). Replaced a snackbar first: a
 * snackbar times out, so a failure that happened while the user was looking at the file picker was
 * often gone by the time they looked back, and it has room for one action at most. Then a card
 * under the import button, which went with that button: a centered dialog is where the eye lands
 * coming back from the picker, and it still stays until answered and carries the retry.
 *
 * [failure]'s body is chosen by *kind* of failure: see [ImportFailure] for why the throwable's own
 * message never reaches here.
 */
@Composable
internal fun ImportFailureDialog(
    failure: ImportFailure,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_failed_title)) },
        text = { Text(importFailureBody(failure)) },
        confirmButton = {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.import_failed_retry)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.import_failed_dismiss)) }
        },
    )
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
        KanjiGlyph(size = 64.dp)
        Text(
            text = stringResource(R.string.models_empty_title),
            fontSize = 20.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 20.dp),
        )
        Text(
            text = stringResource(R.string.models_empty_body),
            fontSize = 14.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
        TensaiButton(
            text = stringResource(R.string.models_import),
            onClick = onImportClick,
            variant = TensaiButtonVariant.Outlined,
            icon = Icons.Default.FolderOpen,
            modifier = Modifier.padding(top = 20.dp),
        )
    }
}
