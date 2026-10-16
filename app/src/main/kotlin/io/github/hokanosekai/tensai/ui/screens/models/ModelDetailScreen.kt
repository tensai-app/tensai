package io.github.hokanosekai.tensai.ui.screens.models

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Eject
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hokanosekai.tensai.R
import io.github.hokanosekai.tensai.compat.CompatibilityChecker
import io.github.hokanosekai.tensai.compat.Verdict
import io.github.hokanosekai.tensai.data.ContextPrefs
import io.github.hokanosekai.tensai.data.ContextSetting
import io.github.hokanosekai.tensai.data.models.ModelInUseException
import io.github.hokanosekai.tensai.data.models.ModelOrigin
import io.github.hokanosekai.tensai.data.models.ModelRepository
import io.github.hokanosekai.tensai.runtime.LoadPlan
import io.github.hokanosekai.tensai.runtime.LoadPolicy
import io.github.hokanosekai.tensai.runtime.RuntimeGraph
import io.github.hokanosekai.tensai.runtime.residentModel
import io.github.hokanosekai.tensai.ui.components.TensaiBadge
import io.github.hokanosekai.tensai.ui.components.TensaiButton
import io.github.hokanosekai.tensai.ui.components.TensaiButtonSize
import io.github.hokanosekai.tensai.ui.components.TensaiButtonVariant
import io.github.hokanosekai.tensai.ui.components.TensaiProgressBar
import io.github.hokanosekai.tensai.ui.screens.settings.Chevron
import io.github.hokanosekai.tensai.ui.screens.settings.MonoText
import io.github.hokanosekai.tensai.ui.screens.settings.PickerDialog
import io.github.hokanosekai.tensai.ui.screens.settings.RowDivider
import io.github.hokanosekai.tensai.ui.screens.settings.SectionCard
import io.github.hokanosekai.tensai.ui.screens.settings.SectionLabel
import io.github.hokanosekai.tensai.ui.screens.settings.SettingsRow
import io.github.hokanosekai.tensai.ui.screens.settings.ValueText
import io.github.hokanosekai.tensai.ui.screens.settings.contextPickerOptions
import io.github.hokanosekai.tensai.ui.screens.settings.label
import io.github.hokanosekai.tensai.ui.screens.settings.openLink
import io.github.hokanosekai.tensai.ui.theme.TensaiShapes
import io.github.hokanosekai.tensai.ui.theme.TensaiMono
import io.github.hokanosekai.tensai.ui.theme.tensaiTints
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Model detail (TEN-122): header block, MODEL / ON THIS DEVICE / SAVED SESSIONS cards, actions.
 * Chrome (back button, title) is owned by `TensaiShell`; this composable is body content only.
 *
 * Re-import of a missing file goes through the same picker as the library. When the picked file is
 * the one this row describes (same name and size), `ModelImporter` relinks the row to the new copy:
 * same id, so saved sessions stay attached, and a failed import changes nothing.
 */
@Composable
fun ModelDetailScreen(
    modelId: String,
    onLoadClick: (String) -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
    onManageModels: () -> Unit = {},
    onOpenRepo: (String, String?) -> Unit = { _, _ -> },
    repository: ModelRepository = RuntimeGraph.modelRepository,
) {
    val models by repository.models.collectAsState(initial = null)
    val model = models?.find { it.id == modelId }
    val queueState by RuntimeGraph.inferenceQueue.state.collectAsState()
    val isLoaded = queueState.residentModel?.modelId == modelId
    val allSessions by RuntimeGraph.sessionRepository.observeSessions().collectAsState(initial = emptyList())
    val sessions = remember(allSessions, modelId) { sessionsOfModel(modelId, allSessions) }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showContextPicker by remember { mutableStateOf(false) }
    var showChatTemplatePicker by remember { mutableStateOf(false) }
    // Null until DataStore delivers the preferences: the body is held back until then, like the backend
    // preference, so no row shows a verdict priced from guessed defaults.
    val contextPrefs by remember { RuntimeGraph.contextRepository.prefs }.collectAsState(initial = null)
    // Bumped after a successful import: a relink keeps the id and may keep the path, so the row can
    // be structurally equal and nothing else would re-run the file-existence check.
    var refreshTick by remember { mutableIntStateOf(0) }
    var importProgress by remember { mutableStateOf<Float?>(null) }
    var importTotalBytes by remember { mutableStateOf<Long?>(null) }
    var importFailure by remember { mutableStateOf<ImportFailure?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val clearDescription = stringResource(R.string.model_detail_sessions_clear_desc)
    // TEN-143: "Download again" goes through the same queue and the same dialogs as the repo screen.
    val downloads = remember { ModelDownloads(scope) }
    LaunchedEffect(downloads) { downloads.start() }
    val downloadsState by downloads.state.collectAsState()
    val origin = model?.origin
    val activeDownload = origin?.let { ModelSource.activeDownload(it, downloadsState.cards) }

    // Null until the stat() has answered: nothing is drawn before then, so a model never shows
    // "Load model" for a frame and then flips to "File missing".
    // remember + LaunchedEffect rather than a keyed produceState: lint's
    // ProduceStateDoesNotAssignValue misreads the keyed overloads.
    var fileExists by remember(model?.filePath, refreshTick) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(model?.filePath, refreshTick) {
        val path = model?.filePath ?: return@LaunchedEffect
        fileExists = withContext(Dispatchers.IO) { File(path).exists() }
    }
    // The download that was running is over (done, or cancelled): look at the file again. A relink keeps
    // the id and usually the path, so the row can come back equal and nothing else would re-stat it.
    var wasDownloading by remember { mutableStateOf(false) }
    LaunchedEffect(activeDownload != null) {
        if (wasDownloading && activeDownload == null) refreshTick++
        wasDownloading = activeDownload != null
    }

    suspend fun removeModel(name: String): Boolean = try {
        repository.deleteModel(modelId)
        true
    } catch (e: ModelInUseException) {
        snackbarHostState.showSnackbar(context.getString(R.string.model_delete_in_use, name))
        false
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importProgress = 0f
            importFailure = null
            importTotalBytes = repository.uriSize(uri)
            try {
                repository.importModel(uri) { importProgress = it }
                refreshTick++
                // Same file: the importer relinked this row (same id, sessions kept) and the
                // screen updates in place.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                importFailure = ImportFailure.of(e)
            } finally {
                importProgress = null
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            importProgress?.let { ImportProgress(it, importTotalBytes) }
            when {
                models == null -> Unit // initial Room query hasn't emitted yet.
                model == null -> {
                    if (importProgress == null) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            Text(
                                text = stringResource(R.string.model_removed),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.align(Alignment.Center),
                            )
                        }
                    }
                }
                fileExists == null || contextPrefs == null -> Unit
                else -> {
                    val prefs = checkNotNull(contextPrefs)
                    val globalContext = prefs.setting
                    val exists = fileExists == true
                    // One preference collector for the whole screen; null until loaded, so no wrong backend shows.
                    val profile = remember(model) { RuntimeGraph.deviceProfiler.profile() }
                    // One compute: the verdict, the estimate and the planned context all come from the same plan.
                    val compatibility = remember(model, profile, prefs) {
                        CompatibilityChecker.check(model, profile, prefs.setting, prefs.autoShare)
                    }
                    val plannedCtx = compatibility.plannedCtx
                    val backend = rememberBackendTag(compatibility)
                    val wontFit = compatibility.verdict == Verdict.WontFit && !isLoaded
                    val blocked = compatibility.verdict.isBlocked && !isLoaded
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(start = 16.dp, end = 16.dp, bottom = 28.dp),
                    ) {
                        if (!exists) {
                            MissingFileCard(
                                canDownloadAgain = origin != null,
                                download = activeDownload,
                                notAllowed = downloadsState.notAllowed,
                                onDownloadAgain = { ModelSource.again(model)?.let(downloads::again) },
                                onReimport = { importLauncher.launch(GgufMimeTypes) },
                                onPause = downloads::pause,
                                onResume = downloads::resume,
                                onCancel = downloads::cancel,
                                onRetry = downloads::retry,
                                onOpenRepo = { origin?.let { onOpenRepo(it.repo, it.file) } },
                            )
                        }

                        Column(modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp)) {
                            Text(
                                text = model.shownName,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold,
                                lineHeight = 26.sp,
                            )
                            Text(
                                text = formatParamCount(model.paramCount),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.padding(top = 10.dp),
                            ) {
                                ModelStatus(compatibility, exists, fontSize = 12.5f)
                                if (isLoaded) TensaiBadge(stringResource(R.string.model_loaded_badge))
                            }
                            CardFacts(cardFacts(model.displayQuant, model.sizeBytes, if (exists) backend else null), modifier = Modifier.padding(top = 8.dp))
                            CardChips(cardChips(tools = model.toolsAvailable, thinking = model.thinkingAvailable, noTemplate = !model.hasChatTemplate), modifier = Modifier.padding(top = 12.dp))
                            if (wontFit && exists) WontFitLine(compatibility, modifier = Modifier.padding(top = 8.dp))
                            if (blocked && exists) BlockedLine(compatibility, modifier = Modifier.padding(top = 8.dp))
                        }

                        SectionLabel(stringResource(R.string.model_detail_section_model))
                        SectionCard {
                            SettingsRow(Icons.Default.AccountTree, stringResource(R.string.model_detail_architecture), trailing = { MonoText(model.architecture) })
                            RowDivider()
                            SettingsRow(Icons.Default.Hub, stringResource(R.string.model_detail_parameters), trailing = { MonoText(formatParamCount(model.paramCount)) })
                            RowDivider()
                            SettingsRow(Icons.Default.Compress, stringResource(R.string.model_detail_quantization), trailing = { MonoText(model.displayQuant) })
                            RowDivider()
                            SettingsRow(Icons.Default.Storage, stringResource(R.string.model_detail_file_size), trailing = { MonoText(formatFileSize(model.sizeBytes)) })
                            RowDivider()
                            val trained = if (model.contextLength > 0) groupedNumber(model.contextLength) else null
                            SettingsRow(
                                icon = Icons.Default.Straighten,
                                title = stringResource(R.string.model_detail_context_length),
                                supportingAnnotated = trained?.let {
                                    monoFigures(stringResource(R.string.model_detail_context_trained, it), listOf(it))
                                },
                                trailing = { MonoText(stringResource(R.string.model_detail_context_used, groupedNumber(plannedCtx.toLong()))) },
                            )
                            RowDivider()
                            SettingsRow(
                                icon = Icons.Default.Tune,
                                title = stringResource(R.string.model_detail_context_setting),
                                onClick = { showContextPicker = true },
                                trailing = {
                                    ValueText(
                                        model.contextSettingOverride?.label()
                                            ?: stringResource(R.string.context_option_global_value, globalContext.label()),
                                    )
                                    Chevron()
                                },
                            )
                            RowDivider()
                            SettingsRow(
                                icon = Icons.Default.Tune,
                                title = stringResource(R.string.model_detail_chat_template_setting),
                                onClick = { showChatTemplatePicker = true },
                                trailing = {
                                    ValueText(model.chatTemplateChoice?.label() ?: stringResource(R.string.chat_template_option_auto))
                                    Chevron()
                                },
                            )
                            if (origin != null) {
                                RowDivider()
                                SourceRow(origin = origin)
                            }
                        }

                        SectionLabel(stringResource(R.string.model_detail_section_capabilities))
                        SectionCard {
                            SettingsRow(
                                icon = Icons.Default.Psychology,
                                title = stringResource(R.string.model_detail_thinking),
                                trailing = {
                                    ValueText(
                                        stringResource(
                                            if (model.thinkingAvailable) R.string.model_detail_supported else R.string.model_detail_not_supported,
                                        ),
                                    )
                                },
                            )
                            RowDivider()
                            SettingsRow(
                                icon = Icons.Default.Construction,
                                title = stringResource(R.string.model_detail_tools),
                                trailing = {
                                    ValueText(
                                        stringResource(
                                            if (model.toolsAvailable) R.string.model_detail_supported else R.string.model_detail_not_supported,
                                        ),
                                    )
                                },
                            )
                            RowDivider()
                            SettingsRow(
                                icon = Icons.Default.Description,
                                title = stringResource(R.string.model_detail_chat_template),
                                trailing = { ValueText(chatTemplateStatus(model)) },
                            )
                        }

                        SectionLabel(stringResource(R.string.model_detail_section_device))
                        SectionCard {
                            SettingsRow(
                                icon = Icons.Default.Memory,
                                title = stringResource(R.string.model_detail_estimated_memory),
                                trailing = { MonoText(formatGigabytes(compatibility.estimatedBytes)) },
                            ) {
                                Column {
                                    val available = profile.availableRamBytes
                                    TensaiProgressBar(
                                        progress = if (available > 0) (compatibility.estimatedBytes.toFloat() / available) else 1f,
                                        height = 4.dp,
                                    )
                                    Text(
                                        text = stringResource(R.string.model_detail_ram_available, formatGigabytes(available)),
                                        fontFamily = TensaiMono,
                                        fontSize = 11.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 6.dp),
                                    )
                                }
                            }
                            if (backend != null) {
                                RowDivider()
                                SettingsRow(
                                    icon = Icons.Default.Bolt,
                                    title = stringResource(R.string.model_detail_backend),
                                    trailing = {
                                        val cpuCores = profile.cpuCoreCount.takeIf { it > 0 }?.let { stringResource(R.string.model_detail_cpu_cores, it) }
                                        ValueText(
                                            backendHardware(
                                                backend,
                                                vulkanDevice = profile.vulkanDeviceName,
                                                openClDevice = profile.openClDeviceName,
                                                cpuCores = cpuCores,
                                            ),
                                        )
                                    },
                                )
                            }
                            RowDivider()
                            FileLocationRow(path = model.filePath)
                        }

                        SectionLabel(stringResource(R.string.model_detail_saved_sessions))
                        SectionCard {
                            if (sessions != null) {
                                SettingsRow(
                                    icon = Icons.Default.History,
                                    title = stringResource(R.string.model_detail_saved_sessions),
                                    supportingAnnotated = buildAnnotatedString {
                                        pushStyle(SpanStyle(fontFamily = TensaiMono))
                                        append("${sessions.count} · ${formatFileSize(sessions.bytes)}")
                                        pop()
                                    },
                                    trailing = {
                                        TensaiButton(
                                            text = stringResource(R.string.model_detail_sessions_clear),
                                            onClick = {
                                                // applicationScope: the delete holds the session mutex and must finish
                                                // even if the user leaves right after tapping.
                                                RuntimeGraph.applicationScope.launch {
                                                    RuntimeGraph.sessionRepository.deleteForModel(modelId)
                                                }
                                            },
                                            variant = TensaiButtonVariant.Text,
                                            size = TensaiButtonSize.Sm,
                                            contentColor = MaterialTheme.colorScheme.error,
                                            modifier = Modifier.semantics { contentDescription = clearDescription },
                                        )
                                    },
                                )
                            } else {
                                SettingsRow(Icons.Default.History, stringResource(R.string.model_detail_sessions_none))
                            }
                        }

                        Column(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(top = 28.dp),
                        ) {
                            // Same selection as the card; a missing, unloaded file has only the
                            // "Remove from library" button below.
                            val action = modelAction(isLoaded, exists, wontFit, blocked)
                            if (action != ModelAction.Remove) {
                                ModelActionButton(
                                    action = action,
                                    onLoad = { onLoadClick(model.id) },
                                    onUnload = {
                                        // applicationScope like the library and the notification:
                                        // the unload may save a session first and must outlive this screen.
                                        RuntimeGraph.applicationScope.launch {
                                            unloadResidentModel(RuntimeGraph.inferenceQueue)
                                        }
                                    },
                                    onRemove = {},
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            TensaiButton(
                                text = stringResource(if (exists) R.string.model_delete else R.string.model_detail_remove_from_library),
                                onClick = {
                                    // A missing file has nothing left to lose: no confirmation.
                                    if (exists) {
                                        showDeleteConfirm = true
                                    } else {
                                        scope.launch { if (removeModel(model.shownName)) onDeleted() }
                                    }
                                },
                                variant = TensaiButtonVariant.Text,
                                enabled = !isLoaded,
                                contentColor = MaterialTheme.colorScheme.error,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (isLoaded) {
                                Text(
                                    text = stringResource(R.string.model_detail_unload_first),
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }

                    if (showContextPicker) {
                        // A fixed size at or above the trained context is not offered: it would be Max, or a
                        // context the model was never trained for. An unknown trained context plans as 4096.
                        val trainedContext = if (model.contextLength > 0) model.contextLength else 4_096L
                        PickerDialog(
                            title = stringResource(R.string.model_detail_context_length),
                            options = contextPickerOptions(
                                choices = listOf<ContextSetting?>(null) + ContextSetting.offeredFor(trainedContext),
                                selected = model.contextSettingOverride,
                                global = globalContext,
                                model = model,
                                profile = profile,
                                autoShare = prefs.autoShare,
                                onSelect = { choice ->
                                    // applicationScope: a one-statement write that must land even if the user leaves.
                                    RuntimeGraph.applicationScope.launch { repository.setContextSetting(model.id, choice) }
                                },
                            ),
                            onDismiss = { showContextPicker = false },
                            note = stringResource(R.string.context_picker_note_model),
                        )
                    }

                    if (showChatTemplatePicker) {
                        PickerDialog(
                            title = stringResource(R.string.model_detail_chat_template),
                            options = chatTemplatePickerOptions(
                                selected = model.chatTemplateChoice,
                                onSelect = { choice ->
                                    RuntimeGraph.applicationScope.launch { repository.setChatTemplate(model.id, choice) }
                                },
                            ),
                            onDismiss = { showChatTemplatePicker = false },
                            note = stringResource(R.string.chat_template_picker_note),
                        )
                    }

                    if (showDeleteConfirm) {
                        DeleteModelDialog(
                            modelName = model.shownName,
                            onCancel = { showDeleteConfirm = false },
                            onConfirm = {
                                showDeleteConfirm = false
                                scope.launch { if (removeModel(model.shownName)) onDeleted() }
                            },
                        )
                    }
                }
            }
        }

        importFailure?.let { failure ->
            ImportFailureDialog(
                failure = failure,
                onRetry = {
                    importFailure = null
                    importLauncher.launch(GgufMimeTypes)
                },
                onDismiss = { importFailure = null },
            )
        }

        when (val dialog = downloadsState.dialog) {
            null -> Unit
            is DownloadsDialog.MobileData -> MobileDataDialog(
                sizeBytes = dialog.sizeBytes,
                onCancel = downloads::dismissDialog,
                onConfirm = downloads::confirmMobileData,
            )
            is DownloadsDialog.Storage -> StorageDialog(
                neededBytes = dialog.neededBytes,
                freeBytes = dialog.freeBytes,
                onCancel = downloads::dismissDialog,
                onManage = {
                    downloads.dismissDialog()
                    onManageModels()
                },
            )
        }

        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * "Source" row (TEN-143): `hf.co/<repo> · <quant>` in mono, an open-in-new icon, and a tap that opens
 * the repo page. Without a usable page URL (a repo id the client refuses) it is a plain fact.
 */
@Composable
private fun SourceRow(origin: ModelOrigin) {
    val context = LocalContext.current
    val label = ModelSource.label(origin)
    val url = remember(origin.repo) { runCatching { RuntimeGraph.hfClient.repoPageUrl(origin.repo) }.getOrNull() }
    val description = stringResource(R.string.licenses_open_url, label)
    SettingsRow(
        icon = Icons.Default.Link,
        title = stringResource(R.string.model_detail_source),
        supportingAnnotated = buildAnnotatedString {
            pushStyle(SpanStyle(fontFamily = TensaiMono))
            append(label)
            pop()
        },
        onClick = url?.let { page -> { openLink(context, page) } },
        modifier = if (url != null) Modifier.semantics { contentDescription = description } else Modifier,
        trailing = if (url != null) {
            {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        } else {
            null
        },
    )
}

/**
 * Error card at the top of a model whose file is gone. Without an origin: the text and a tonal
 * "Re-import". With one (TEN-143): "Download again" next to "Re-import", and while that download is in
 * the queue its progress instead of the buttons ([download], the same states as a card of On device).
 */
@Composable
private fun MissingFileCard(
    canDownloadAgain: Boolean,
    download: DownloadCardModel?,
    notAllowed: Boolean,
    onDownloadAgain: () -> Unit,
    onReimport: () -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onOpenRepo: () -> Unit,
) {
    val error = MaterialTheme.colorScheme.error
    Surface(
        shape = TensaiShapes.large,
        color = MaterialTheme.tensaiTints.error12,
        border = BorderStroke(1.dp, error.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 16.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Icon(Icons.Default.Error, contentDescription = null, tint = error, modifier = Modifier.size(22.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        if (canDownloadAgain) R.string.model_detail_file_missing_origin else R.string.model_detail_file_missing,
                    ),
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                if (notAllowed) {
                    Text(
                        text = stringResource(R.string.repo_not_allowed),
                        fontSize = 12.5.sp,
                        color = error,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (download != null) {
                    DownloadStateBody(
                        state = download.state,
                        onOpen = onOpenRepo,
                        onPause = { onPause(download.id) },
                        onResume = { onResume(download.id) },
                        onCancel = { onCancel(download.id) },
                        onRetry = { onRetry(download.id) },
                        modifier = Modifier.padding(top = 12.dp),
                    )
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 10.dp)) {
                        if (canDownloadAgain) {
                            TensaiButton(
                                text = stringResource(R.string.model_detail_download_again),
                                onClick = onDownloadAgain,
                                variant = TensaiButtonVariant.Tonal,
                                size = TensaiButtonSize.Sm,
                                icon = Icons.Default.Download,
                            )
                        }
                        TensaiButton(
                            text = stringResource(R.string.model_detail_reimport),
                            onClick = onReimport,
                            variant = if (canDownloadAgain) TensaiButtonVariant.Outlined else TensaiButtonVariant.Tonal,
                            size = TensaiButtonSize.Sm,
                            icon = Icons.Default.FolderOpen,
                        )
                    }
                }
            }
        }
    }
}

/** Path with the directory shrinking first (ellipsis) and the file name kept whole, plus a copy button. */
@Composable
private fun FileLocationRow(path: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1400)
            copied = false
        }
    }
    val file = File(path)
    val dir = file.parent?.let { "$it/" }.orEmpty()
    SettingsRow(
        icon = Icons.Default.Folder,
        title = stringResource(R.string.model_detail_file_location),
        trailing = {
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(path))
                    copied = true
                },
            ) {
                Icon(
                    imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                    contentDescription = stringResource(if (copied) R.string.model_detail_path_copied else R.string.model_detail_copy_path),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = dir,
                fontFamily = TensaiMono,
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = file.name,
                fontFamily = TensaiMono,
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 32768 -> "32,768", fixed to US grouping like every other mono figure on these screens. */
private fun groupedNumber(value: Long): String = String.format(Locale.US, "%,d", value)
