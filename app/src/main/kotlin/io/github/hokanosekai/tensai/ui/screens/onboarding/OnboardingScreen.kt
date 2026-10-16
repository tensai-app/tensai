package io.github.hokanosekai.tensai.ui.screens.onboarding

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hokanosekai.tensai.R
import io.github.hokanosekai.tensai.compat.Verdict
import io.github.hokanosekai.tensai.data.AppLanguage
import io.github.hokanosekai.tensai.data.LanguageRepository
import io.github.hokanosekai.tensai.data.OnboardingRepository
import io.github.hokanosekai.tensai.data.ThemeRepository
import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.data.models.ModelRepository
import io.github.hokanosekai.tensai.runtime.RuntimeGraph
import io.github.hokanosekai.tensai.runtime.rememberNotificationAskState
import io.github.hokanosekai.tensai.runtime.rememberNotificationPermissionRequester
import io.github.hokanosekai.tensai.ui.components.KanjiGlyph
import io.github.hokanosekai.tensai.ui.components.TensaiBadge
import io.github.hokanosekai.tensai.ui.components.TensaiBadgeTone
import io.github.hokanosekai.tensai.ui.components.TensaiButton
import io.github.hokanosekai.tensai.ui.components.TensaiButtonSize
import io.github.hokanosekai.tensai.ui.components.TensaiButtonVariant
import io.github.hokanosekai.tensai.ui.components.TensaiChip
import io.github.hokanosekai.tensai.ui.components.TensaiMonoBadge
import io.github.hokanosekai.tensai.ui.components.TensaiProgressBar
import io.github.hokanosekai.tensai.ui.components.rememberReducedMotion
import io.github.hokanosekai.tensai.ui.screens.models.CompatIndicator
import io.github.hokanosekai.tensai.ui.screens.models.DownloadCardState
import io.github.hokanosekai.tensai.ui.screens.models.DownloadStateBody
import io.github.hokanosekai.tensai.ui.screens.models.MobileDataDialog
import io.github.hokanosekai.tensai.ui.screens.models.StorageDialog
import io.github.hokanosekai.tensai.ui.screens.models.GgufMimeTypes
import io.github.hokanosekai.tensai.ui.screens.models.ImportFailure
import io.github.hokanosekai.tensai.ui.screens.models.ModelCard
import io.github.hokanosekai.tensai.ui.screens.models.ModelCardLink
import io.github.hokanosekai.tensai.ui.screens.models.ModelLinkRow
import io.github.hokanosekai.tensai.ui.screens.models.formatFileSize
import io.github.hokanosekai.tensai.ui.screens.models.formatGigabytes
import io.github.hokanosekai.tensai.ui.screens.models.importFailureBody
import io.github.hokanosekai.tensai.ui.screens.models.modelCompatibility
import io.github.hokanosekai.tensai.ui.screens.models.rememberBackendTag
import io.github.hokanosekai.tensai.ui.screens.settings.openLink
import io.github.hokanosekai.tensai.ui.theme.TensaiMono
import io.github.hokanosekai.tensai.ui.theme.TensaiShapes
import io.github.hokanosekai.tensai.ui.theme.ThemeMode
import io.github.hokanosekai.tensai.ui.theme.tensaiTints
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * First-run onboarding (TEN-36, TEN-118): three Calm Night steps, one per [OnboardingStep]. Welcome
 * (theme and language pills), the model (a recommendation drawn from [OnboardingCatalog] that
 * downloads in place, TEN-145, plus the GGUF import as the secondary way) and Ready, which hands off
 * to the load.
 *
 * Which launches land here is [OnboardingGate]'s call. Theme and language write to their stores as
 * soon as a chip is tapped, so the page re-skins under the user's finger. There is no backend
 * choice: it is Auto, and the Models screen is where it can be overridden.
 *
 * @param startStep step to open at: a returning skipper skips the welcome and lands on the model.
 * @param onDone called when the flow ends: with the imported model's id when the user finished it
 *   (the caller sends that to the loading screen, which continues into a first chat), or `null`
 *   when they left without going through Ready (the caller goes to the draft chat).
 * @param onBrowseHuggingFace "Browse Hugging Face" of the other options: the skipped flag is written
 *   exactly as Skip does, then the caller opens the Models screen on Discover. The draft chat sits under it.
 * @param persistScope scope the preference writes run on. Deliberately **not** a
 *   `rememberCoroutineScope()`: "Skip" both persists the flag and navigates away, and a
 *   composition-scoped write would be cancelled by the navigation it triggers. Same reasoning as
 *   [io.github.hokanosekai.tensai.ui.screens.loading.LoadModelViewModel]'s scope.
 */
@Composable
fun OnboardingScreen(
    startStep: OnboardingStep,
    onDone: (modelId: String?) -> Unit,
    modifier: Modifier = Modifier,
    onBrowseHuggingFace: () -> Unit = {},
    modelRepository: ModelRepository = RuntimeGraph.modelRepository,
    themeRepository: ThemeRepository = RuntimeGraph.themeRepository,
    languageRepository: LanguageRepository = RuntimeGraph.languageRepository,
    onboardingRepository: OnboardingRepository = RuntimeGraph.onboardingRepository,
    persistScope: CoroutineScope = RuntimeGraph.applicationScope,
) {
    // The step and the imported model's id survive process death; the entity is re-read from the
    // repository (a ModelEntity is not saveable, and the row is the source of truth anyway).
    var stepOrdinal by rememberSaveable {
        mutableIntStateOf((if (startStep == OnboardingStep.Welcome) OnboardingStep.Welcome else OnboardingStep.Import).ordinal)
    }
    val step = OnboardingStep.entries[stepOrdinal]
    var openPref by remember { mutableStateOf<Pref?>(null) }
    var othersOpen by remember { mutableStateOf(false) }

    var importedId by rememberSaveable { mutableStateOf<String?>(null) }
    // The model came from the in-app download, not from the picker: badge "Downloaded", not "Imported".
    var fromDownload by rememberSaveable { mutableStateOf(false) }
    // The catalog entry picked for download (CatalogModel.key), so a recreated screen finds its row again.
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    var importedEntity by remember { mutableStateOf<ModelEntity?>(null) }
    val models by modelRepository.models.collectAsState(initial = emptyList())
    // Fresh import first (the list can lag the insert by a frame), the list after a restore.
    val imported = importedEntity ?: importedId?.let { id -> models.firstOrNull { it.id == id } }
    var pickerPending by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var importProgress by remember { mutableStateOf<Float?>(null) }
    var importTotalBytes by remember { mutableStateOf<Long?>(null) }
    var importFileName by remember { mutableStateOf("") }
    var importFailure by remember { mutableStateOf<ImportFailure?>(null) }

    val theme by themeRepository.themeMode.collectAsState(initial = ThemeMode.System)
    val language by languageRepository.language.collectAsState(initial = AppLanguage.System)

    val uiScope = rememberCoroutineScope()
    val copying = importProgress != null

    val downloads = remember {
        OnboardingDownloads(scope = uiScope, libraryIds = modelRepository.models.map { list -> list.map { it.id }.toSet() })
    }
    val downloadState by downloads.state.collectAsState()
    val downloadPhase = downloadState.phase
    LaunchedEffect(downloads) { downloads.start() }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pickerPending = false
        if (uri == null || importProgress != null) return@rememberLauncherForActivityResult
        uiScope.launch {
            importProgress = 0f
            importFailure = null
            importTotalBytes = modelRepository.uriSize(uri)
            importFileName = modelRepository.uriDisplayName(uri).orEmpty()
            try {
                val model = modelRepository.importModel(uri) { progress -> importProgress = progress }
                importedEntity = model
                importedId = model.id
                fromDownload = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Same classify-don't-read rule as ModelsScreen's import (TEN-39): this is the
                // *first* import a user ever attempts, so it is the one most likely to fail, and
                // the least useful moment to answer with an IOException's own wording.
                importFailure = ImportFailure.of(e)
            } finally {
                importProgress = null
            }
        }
    }

    // OpenDocument can be launched twice by a double tap before the first picker shows; a second
    // result would start a second concurrent import.
    fun openPicker() {
        if (pickerPending || importProgress != null) return
        pickerPending = true
        importLauncher.launch(GgufMimeTypes)
    }

    // "Browse Hugging Face": leaves the first-run flow the way Skip does (flag persisted), for Discover.
    fun browse() {
        if (finished) return
        finished = true
        persistScope.launch { onboardingRepository.setSkipped(true) }
        onBrowseHuggingFace()
    }

    // One exit only: a double tap must not navigate twice (two chat entries, Loading pushed twice).
    fun finish(modelId: String?) {
        if (finished) return
        finished = true
        onDone(modelId)
    }

    // The same exit from every step: flag persisted, draft chat. Leaving mid-copy would cancel the
    // import, so the Skip button is hidden while one runs.
    fun skip() {
        if (finished) return
        persistScope.launch { onboardingRepository.setSkipped(true) }
        finish(null)
    }

    // Read once, not per recomposition. [OnboardingCatalog.recommend] judges on device class, not on
    // the free memory of the moment, so the pick does not change with what happens to be running.
    val profile = remember { RuntimeGraph.deviceProfiler.profile() }
    val recommendation = remember(profile) { OnboardingCatalog.recommend(profile) }
    val selected = remember(recommendation, selectedKey) {
        (listOf(recommendation.recommended) + recommendation.others).firstOrNull { it.model.key == selectedKey }
    }
    LaunchedEffect(downloads, selected?.model?.key) { downloads.follow(selected?.model) }
    // The finished download becomes "your model", the same state an import leads to.
    LaunchedEffect(downloadPhase) {
        val done = downloadPhase as? OnboardingPhase.Done ?: return@LaunchedEffect
        if (importedId == null) {
            importedId = done.modelId
            fromDownload = true
        }
    }

    // Ready is entered from the model step's Continue, so a model is there. A recreated screen
    // forgets it, and then the step that can produce one is the right place to be.
    val shownStep = if (step == OnboardingStep.Ready && imported == null) OnboardingStep.Import else step

    // Back mid-copy would close the app and cancel the import: swallow it.
    BackHandler(enabled = copying) {}
    BackHandler(enabled = shownStep != OnboardingStep.Welcome && !copying) {
        stepOrdinal = shownStep.ordinal - 1
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp),
        ) {
            Box(modifier = Modifier.widthIn(min = 72.dp), contentAlignment = Alignment.CenterStart) {
                if (shownStep != OnboardingStep.Welcome && !copying) {
                    IconButton(onClick = { stepOrdinal = shownStep.ordinal - 1 }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) { StepDots(shownStep) }
            Box(modifier = Modifier.widthIn(min = 72.dp), contentAlignment = Alignment.CenterEnd) {
                if (shownStep != OnboardingStep.Ready && !copying) {
                    TextButton(onClick = ::skip) {
                        Text(
                            text = stringResource(R.string.onboarding_skip),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        when (shownStep) {
            OnboardingStep.Welcome -> WelcomeStep(
                theme = theme,
                language = language,
                openPref = openPref,
                onTogglePref = { openPref = if (openPref == it) null else it },
                onTheme = { persistScope.launch { themeRepository.setThemeMode(it) } },
                onLanguage = { persistScope.launch { languageRepository.setLanguage(it) } },
                onNext = { stepOrdinal = OnboardingStep.Import.ordinal },
            )
            OnboardingStep.Import -> ModelStep(
                recommendation = recommendation,
                ramLabel = formatGigabytes(profile.totalRamBytes),
                imported = imported,
                importProgress = importProgress,
                importTotalBytes = importTotalBytes,
                importFileName = importFileName,
                importFailure = importFailure,
                fromDownload = fromDownload,
                selected = selected,
                downloadState = downloadState,
                downloadPhase = downloadPhase,
                othersOpen = othersOpen,
                onToggleOthers = { othersOpen = !othersOpen },
                onDownload = { model ->
                    selectedKey = model.key
                    downloads.download(model)
                },
                onPause = downloads::pause,
                onResume = downloads::resume,
                onCancel = downloads::cancel,
                onRetry = downloads::retry,
                onRetryLookup = downloads::retryLookup,
                onBrowse = ::browse,
                onImport = ::openPicker,
                onNext = { stepOrdinal = OnboardingStep.Ready.ordinal },
            )
            OnboardingStep.Ready -> imported?.let { model -> ReadyStep(model = model, onStart = { finish(model.id) }) }
        }
    }

    when (val dialog = downloadState.dialog) {
        null -> Unit
        is OnboardingDialog.MobileData -> MobileDataDialog(
            sizeBytes = dialog.sizeBytes,
            onCancel = downloads::dismissDialog,
            onConfirm = downloads::confirmMobileData,
        )
        // No library to manage yet: the dialog's second button just closes it.
        is OnboardingDialog.Storage -> StorageDialog(
            neededBytes = dialog.neededBytes,
            freeBytes = dialog.freeBytes,
            onCancel = downloads::dismissDialog,
            onManage = downloads::dismissDialog,
        )
    }
}

private enum class Pref { Theme, Language }

/** Small uppercase section label: 12sp bold, 0.06em tracking, text-secondary. */
@Composable
private fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.72.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * One dot per [OnboardingStep], the current one wide and primary, the others small and muted. A
 * single progress node for a screen reader ("Step 2 of 3") rather than three silent boxes.
 */
@Composable
private fun StepDots(current: OnboardingStep, modifier: Modifier = Modifier) {
    val total = OnboardingStep.entries.size
    val description = stringResource(R.string.onboarding_step_progress, current.ordinal + 1, total)
    val reducedMotion = rememberReducedMotion()
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = description
            progressBarRangeInfo = ProgressBarRangeInfo((current.ordinal + 1).toFloat(), 1f..total.toFloat(), total - 2)
        },
    ) {
        OnboardingStep.entries.forEach { entry ->
            val active = entry == current
            val width by animateDpAsState(
                targetValue = if (active) 20.dp else 6.dp,
                animationSpec = if (reducedMotion) snap() else tween(durationMillis = 250),
                label = "stepDotWidth",
            )
            Box(
                modifier = Modifier
                    .width(width)
                    .height(6.dp)
                    .background(
                        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.tensaiTints.outlineStrong,
                        CircleShape,
                    ),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.WelcomeStep(
    theme: ThemeMode,
    language: AppLanguage,
    openPref: Pref?,
    onTogglePref: (Pref) -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onLanguage: (AppLanguage) -> Unit,
    onNext: () -> Unit,
) {
    // Scrolls as one page so a large font scale or a short screen clips nothing; the content is at
    // least as tall as the viewport, which keeps the glyph centered and the actions at the foot.
    BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
        ) {
            Spacer(modifier = Modifier.height(1.dp))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 16.dp),
            ) {
                KanjiGlyph(size = 72.dp, glow = true)
                Text(
                    text = stringResource(R.string.onboarding_welcome_title),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.26).sp,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 20.dp),
                )
                Text(
                    text = stringResource(R.string.onboarding_welcome_body),
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            ) {
                PillRow(gap = 8.dp, modifier = Modifier.fillMaxWidth()) {
                    PrefPill(
                        label = stringResource(R.string.settings_theme),
                        value = stringResource(theme.titleRes),
                        open = openPref == Pref.Theme,
                        onClick = { onTogglePref(Pref.Theme) },
                    )
                    PrefPill(
                        label = stringResource(R.string.settings_language),
                        value = stringResource(language.titleRes),
                        open = openPref == Pref.Language,
                        onClick = { onTogglePref(Pref.Language) },
                    )
                }
                when (openPref) {
                    Pref.Theme -> FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        ThemeMode.entries.forEach { mode ->
                            TensaiChip(
                                label = stringResource(mode.titleRes),
                                selected = theme == mode,
                                icon = mode.icon,
                                onClick = { onTheme(mode) },
                            )
                        }
                    }
                    Pref.Language -> FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        AppLanguage.entries.forEach { entry ->
                            TensaiChip(
                                label = stringResource(entry.titleRes),
                                selected = language == entry,
                                onClick = { onLanguage(entry) },
                            )
                        }
                    }
                    null -> Unit
                }
                TensaiButton(
                    text = stringResource(R.string.onboarding_continue),
                    onClick = onNext,
                    size = TensaiButtonSize.Lg,
                    trailingIcon = Icons.AutoMirrored.Rounded.ArrowForward,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
        }
    }
}

/**
 * The pills on one centered line, never wrapped: a FlowRow put the second pill under the first as
 * soon as "Calm Night" widened the theme one, and an even split cut "Language · System" short when
 * both fit. Each pill gets its natural width when they all fit; past that, the row is shared in
 * proportion to those widths and each pill ellipsizes its value.
 */
@Composable
private fun PillRow(gap: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val gapPx = gap.roundToPx()
        val available = (constraints.maxWidth - gapPx * (measurables.size - 1)).coerceAtLeast(0)
        val wanted = measurables.map { it.maxIntrinsicWidth(constraints.maxHeight) }
        val total = wanted.sum()
        val placeables = measurables.mapIndexed { i, measurable ->
            val width = if (total <= available) wanted[i] else (available.toLong() * wanted[i] / total).toInt()
            measurable.measure(Constraints(maxWidth = width))
        }
        val rowWidth = placeables.sumOf { it.width } + gapPx * (placeables.size - 1)
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(constraints.maxWidth, height) {
            var x = ((constraints.maxWidth - rowWidth) / 2).coerceAtLeast(0)
            placeables.forEach {
                it.placeRelative(x, (height - it.height) / 2)
                x += it.width + gapPx
            }
        }
    }
}

/** "Theme · System": 36dp pill, the value in mono, the chevron turning while its chips are out. */
@Composable
private fun PrefPill(label: String, value: String, open: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val state = stringResource(if (open) R.string.onboarding_pref_expanded else R.string.onboarding_pref_collapsed)
    val onSurface = MaterialTheme.colorScheme.onSurface
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.Transparent,
        border = BorderStroke(1.dp, if (open) MaterialTheme.colorScheme.primary else MaterialTheme.tensaiTints.outlineStrong),
        modifier = modifier.minimumInteractiveComponentSize().semantics { stateDescription = state },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.heightIn(min = 36.dp).padding(start = 12.dp, end = 8.dp),
        ) {
            Text(
                text = buildAnnotatedString {
                    append("$label · ")
                    withStyle(SpanStyle(fontFamily = TensaiMono, fontSize = 12.sp, color = onSurface)) { append(value) }
                },
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Icon(
                imageVector = Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp).rotate(if (open) 180f else 0f),
            )
        }
    }
}

@Composable
private fun ColumnScope.ModelStep(
    recommendation: Recommendation,
    ramLabel: String,
    imported: ModelEntity?,
    importProgress: Float?,
    importTotalBytes: Long?,
    importFileName: String,
    importFailure: ImportFailure?,
    fromDownload: Boolean,
    selected: RatedModel?,
    downloadState: OnboardingDownloadState,
    downloadPhase: OnboardingPhase,
    othersOpen: Boolean,
    onToggleOthers: () -> Unit,
    onDownload: (CatalogModel) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onRetryLookup: () -> Unit,
    onBrowse: () -> Unit,
    onImport: () -> Unit,
    onNext: () -> Unit,
) {
    val context = LocalContext.current
    val copying = importProgress != null
    // The selected entry takes the recommended card's place, and the recommended one joins the others.
    val shown = selected ?: recommendation.recommended
    val rest = (listOf(recommendation.recommended) + recommendation.others).filter { it.model.key != shown.model.key }
        .sortedBy { it.model.facts.paramCount }
    val resolving = downloadPhase is OnboardingPhase.Resolving
    val active = downloadPhase as? OnboardingPhase.Active
    val failed = active?.card is DownloadCardState.Failed
    val inProgress = resolving || (active != null && !failed)
    val lookupFailed = downloadState.lookup == OnboardingLookup.Offline || downloadState.lookup == OnboardingLookup.Failed
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, top = 16.dp, end = 20.dp, bottom = 8.dp),
    ) {
        Column {
            Text(
                text = stringResource(R.string.onboarding_model_title),
                fontSize = 22.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.22).sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.onboarding_import_body),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        if (importFailure != null) {
            ErrorCard(
                message = if (importFailure is ImportFailure.NotAModelFile) {
                    stringResource(R.string.onboarding_import_error)
                } else {
                    importFailureBody(importFailure)
                },
                actionLabel = stringResource(R.string.onboarding_try_another),
                onAction = onImport,
            )
        }
        if (imported == null && lookupFailed) {
            ErrorCard(
                message = stringResource(
                    if (downloadState.lookup == OnboardingLookup.Offline) R.string.onboarding_download_offline else R.string.onboarding_download_unavailable,
                ),
                actionLabel = stringResource(R.string.repo_retry),
                onAction = onRetryLookup,
            )
        }
        if (imported == null && downloadState.notAllowed) {
            ErrorCard(message = stringResource(R.string.repo_not_allowed), actionLabel = null, onAction = {})
        }

        when {
            imported != null -> {
                Eyebrow(stringResource(R.string.onboarding_your_model))
                val importedCompatibility = modelCompatibility(imported)
                ModelCard(
                    model = imported,
                    // One check, remembered per model and preferences, see modelCompatibility's KDoc.
                    compatibility = importedCompatibility,
                    backend = rememberBackendTag(importedCompatibility),
                    noAction = true,
                    imported = !fromDownload,
                    downloaded = fromDownload,
                )
            }
            copying -> {
                Eyebrow(stringResource(R.string.onboarding_importing))
                CopyingCard(importFileName, importProgress ?: 0f, importTotalBytes)
            }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val isRecommended = shown.model.key == recommendation.recommended.model.key
                    Eyebrow(
                        stringResource(
                            when {
                                inProgress || failed -> R.string.onboarding_downloading
                                !isRecommended -> R.string.onboarding_selected
                                shown.compatibility.verdict == Verdict.RunsWell -> R.string.onboarding_recommended
                                else -> R.string.onboarding_smallest
                            },
                        ),
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    TensaiMonoBadge(stringResource(R.string.onboarding_ram_badge, ramLabel))
                }
                if (downloadPhase is OnboardingPhase.Idle) {
                    ModelCard(
                        model = shown.model.toDisplayEntity(),
                        compatibility = shown.compatibility,
                        backend = rememberBackendTag(shown.compatibility),
                        link = ModelCardLink(shown.model.linkLabel, shown.model.linkUrl),
                        noAction = true,
                    )
                } else {
                    DownloadingCard(
                        model = shown.model,
                        phase = downloadPhase,
                        onPause = onPause,
                        onResume = onResume,
                        onCancel = onCancel,
                        onRetry = onRetry,
                        onOpenPage = { openLink(context, shown.model.linkUrl) },
                    )
                }
                if (rest.isNotEmpty()) {
                    TextButton(onClick = onToggleOthers) {
                        Text(
                            text = stringResource(if (othersOpen) R.string.onboarding_options_hide else R.string.onboarding_options_show),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Icon(
                            imageVector = if (othersOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.padding(start = 6.dp).size(18.dp),
                        )
                    }
                }
                if (othersOpen) {
                    CardSurface {
                        rest.forEach { rated ->
                            OtherModelRow(rated, downloadEnabled = !resolving, onDownload = { onDownload(rated.model) })
                            Divider()
                        }
                        TextButton(onClick = onBrowse, modifier = Modifier.padding(horizontal = 8.dp)) {
                            Text(
                                text = stringResource(R.string.onboarding_browse_hf),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.padding(start = 6.dp).size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 20.dp),
    ) {
        when {
            imported != null -> TensaiButton(
                text = stringResource(R.string.onboarding_continue),
                onClick = onNext,
                size = TensaiButtonSize.Lg,
                trailingIcon = Icons.AutoMirrored.Rounded.ArrowForward,
                modifier = Modifier.fillMaxWidth(),
            )
            // Pending download: Continue is there but locked until the model is registered.
            inProgress || failed -> TensaiButton(
                text = stringResource(R.string.onboarding_continue),
                onClick = onNext,
                size = TensaiButtonSize.Lg,
                trailingIcon = Icons.AutoMirrored.Rounded.ArrowForward,
                enabled = false,
                modifier = Modifier.fillMaxWidth(),
            )
            else -> TensaiButton(
                text = stringResource(R.string.repo_download, formatFileSize(shown.model.facts.sizeBytes)),
                onClick = { onDownload(shown.model) },
                size = TensaiButtonSize.Lg,
                icon = Icons.Rounded.Download,
                enabled = !copying,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (imported == null && !inProgress) {
            TextButton(onClick = onImport, enabled = !copying) {
                Icon(
                    imageVector = Icons.Rounded.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp).size(18.dp),
                )
                Text(
                    text = stringResource(R.string.models_import),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * The selected entry while its download exists: name, link, quant and size, then what the state offers
 * ([DownloadStateBody]: bytes, speed, time left, Pause / Cancel, or the reason and Retry).
 */
@Composable
private fun DownloadingCard(
    model: CatalogModel,
    phase: OnboardingPhase,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onOpenPage: () -> Unit,
) {
    CardSurface {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = model.name,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                lineHeight = 22.sp,
            )
            ModelLinkRow(ModelCardLink(model.linkLabel, model.linkUrl), modifier = Modifier.padding(top = 2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 12.dp)) {
                TensaiMonoBadge(model.quantLabel)
                TensaiMonoBadge(formatFileSize(model.facts.sizeBytes))
            }
            Column(modifier = Modifier.padding(top = 16.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                when (phase) {
                    is OnboardingPhase.Active -> DownloadStateBody(
                        state = phase.card,
                        onOpen = onOpenPage,
                        onPause = { onPause(phase.id) },
                        onResume = { onResume(phase.id) },
                        onCancel = { onCancel(phase.id) },
                        onRetry = { onRetry(phase.id) },
                    )
                    else -> {
                        Text(
                            text = stringResource(R.string.onboarding_download_preparing),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(bottom = 10.dp),
                        )
                        TensaiProgressBar(progress = null)
                    }
                }
            }
        }
    }
}

/** Hairline-bordered surface card, the kit's `onbCard`. */
@Composable
private fun CardSurface(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        shape = TensaiShapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.tensaiTints.outline),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column { content() }
    }
}

@Composable
private fun Divider() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.tensaiTints.outline))
}

/** Name and verdict on one line, quant, size and the Hugging Face link on the next, a Download button at the end. */
@Composable
private fun OtherModelRow(rated: RatedModel, downloadEnabled: Boolean, onDownload: () -> Unit) {
    val model = rated.model
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = model.name,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                CompatIndicator(rated.compatibility)
            }
            Text(
                text = "${model.quantLabel} · ${formatFileSize(model.facts.sizeBytes)}",
                fontFamily = TensaiMono,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ModelLinkRow(ModelCardLink(model.linkLabel, model.linkUrl))
        }
        TensaiButton(
            text = stringResource(R.string.onboarding_download),
            onClick = onDownload,
            variant = TensaiButtonVariant.Tonal,
            size = TensaiButtonSize.Sm,
            enabled = downloadEnabled,
        )
    }
}

/** Inline failure card with an optional action (a retry, or "Try another file" for a bad import). */
@Composable
private fun ErrorCard(message: String, actionLabel: String?, onAction: () -> Unit) {
    val error = MaterialTheme.colorScheme.error
    Surface(
        shape = TensaiShapes.large,
        color = MaterialTheme.tensaiTints.error12,
        border = BorderStroke(1.dp, error.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Assertive },
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = if (actionLabel != null) 6.dp else 14.dp),
        ) {
            Icon(imageVector = Icons.Rounded.Error, contentDescription = null, tint = error, modifier = Modifier.size(22.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = message,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (actionLabel != null) {
                    TextButton(onClick = onAction) {
                        Text(
                            text = actionLabel,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = error,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The copy in flight: file name, bar and "Copying model · 0.4 / 1.2 GB". Real bytes, the same
 * figures and strings as the Models screen's [io.github.hokanosekai.tensai.ui.screens.models.ImportProgress];
 * indeterminate and unit-less when the provider reports no size.
 */
@Composable
private fun CopyingCard(fileName: String, progress: Float, totalBytes: Long?) {
    val known = totalBytes != null && totalBytes > 0
    CardSurface {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(18.dp).semantics(mergeDescendants = true) {},
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    imageVector = Icons.Rounded.Description,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    text = fileName,
                    fontFamily = TensaiMono,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
            TensaiProgressBar(if (known) progress else null)
            Text(
                text = if (known && totalBytes != null) {
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
}

@Composable
private fun ColumnScope.ReadyStep(model: ModelEntity, onStart: () -> Unit) {
    val compatibility = modelCompatibility(model)
    BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
        Column(
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                KanjiGlyph(size = 64.dp, glow = true)
                Text(
                    text = stringResource(R.string.onboarding_ready_title, model.shownName),
                    fontSize = 22.sp,
                    lineHeight = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.22).sp,
                    color = MaterialTheme.colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 18.dp),
                )
                Text(
                    text = stringResource(R.string.onboarding_ready_body),
                    fontSize = 14.5.sp,
                    lineHeight = 21.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            CardSurface {
                SummaryRow(stringResource(R.string.onboarding_summary_model)) {
                    Text(
                        text = model.shownName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.End,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Divider()
                SummaryRow(stringResource(R.string.onboarding_summary_size)) {
                    Text(
                        text = formatFileSize(model.sizeBytes),
                        fontFamily = TensaiMono,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                Divider()
                SummaryRow(stringResource(R.string.onboarding_summary_compatibility)) { compatibility?.let { CompatIndicator(it) } }
                Divider()
                SummaryRow(stringResource(R.string.onboarding_summary_backend)) {
                    Text(
                        text = stringResource(R.string.settings_backend_auto),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            NotificationsCard()
        }
    }
    TensaiButton(
        text = stringResource(R.string.onboarding_start_chatting),
        onClick = onStart,
        size = TensaiButtonSize.Lg,
        icon = Icons.Rounded.PlayArrow,
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, top = 8.dp, end = 20.dp, bottom = 24.dp),
    )
}

/**
 * Optional "Notifications" offer under the summary (TEN-127): the one place the permission dialog
 * is explained before it appears. Non-blocking, "Start chatting" works either way. Shown only
 * while [rememberNotificationAskState] says an ask is still possible, and kept on screen as
 * "Allowed" once the user grants; a refusal (or an install that already asked) leaves nothing.
 */
@Composable
private fun NotificationsCard() {
    val askState = rememberNotificationAskState()
    val requestPermission = rememberNotificationPermissionRequester()
    val scope = rememberCoroutineScope()
    // The state flips to "can't ask" the moment the ask is spent, so remember the offer was made
    // for the "Allowed" confirmation to have a card to appear on after the dialog closes.
    var offered by rememberSaveable { mutableStateOf(false) }
    // Spent on the tap and cleared when the state re-reads (the dialog closing resumes the host).
    var tapped by remember { mutableStateOf(false) }
    LaunchedEffect(askState) { tapped = false }
    LaunchedEffect(askState?.canAsk) { if (askState?.canAsk == true) offered = true }
    val granted = askState?.granted == true
    if (askState == null || !(askState.canAsk || (offered && granted))) return
    CardSurface {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 12.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Notifications,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_notifications),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.onboarding_notifications_body),
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (granted) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.notifications_allowed),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                // Tonal pill, the same primary-16 button Settings uses for "Manage models".
                Button(
                    onClick = {
                        tapped = true
                        // An exception here must not turn an optional card into a crash.
                        scope.launch { runCatching { requestPermission() } }
                    },
                    enabled = !tapped,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.tensaiTints.primary16,
                        contentColor = MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier.height(36.dp),
                ) {
                    Text(
                        text = stringResource(R.string.notifications_allow),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        value()
    }
}

private val ThemeMode.titleRes: Int
    get() = when (this) {
        ThemeMode.System -> R.string.settings_theme_system
        ThemeMode.Dark -> R.string.settings_theme_dark
        ThemeMode.Light -> R.string.settings_theme_light
    }

private val ThemeMode.icon
    get() = when (this) {
        ThemeMode.System -> Icons.Rounded.BrightnessAuto
        ThemeMode.Dark -> Icons.Rounded.DarkMode
        ThemeMode.Light -> Icons.Rounded.LightMode
    }

private val AppLanguage.titleRes: Int
    get() = when (this) {
        AppLanguage.System -> R.string.settings_language_system
        AppLanguage.English -> R.string.settings_language_english
        AppLanguage.French -> R.string.settings_language_french
    }
