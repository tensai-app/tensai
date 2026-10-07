package io.github.hokanosekai.tensai.ui.screens.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hokanosekai.tensai.R
import io.github.hokanosekai.tensai.data.BackendPreference
import io.github.hokanosekai.tensai.data.BackendRepository
import io.github.hokanosekai.tensai.data.OnboardingRepository
import io.github.hokanosekai.tensai.data.ThemeRepository
import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.data.models.ModelRepository
import io.github.hokanosekai.tensai.runtime.RuntimeGraph
import io.github.hokanosekai.tensai.ui.components.KanjiGlyph
import io.github.hokanosekai.tensai.ui.components.TensaiChip
import io.github.hokanosekai.tensai.ui.components.TensaiMonoBadge
import io.github.hokanosekai.tensai.ui.screens.models.CompatIndicator
import io.github.hokanosekai.tensai.ui.screens.models.GgufMimeTypes
import io.github.hokanosekai.tensai.ui.screens.models.ImportFailure
import io.github.hokanosekai.tensai.ui.screens.models.compatExplanation
import io.github.hokanosekai.tensai.ui.screens.models.formatFileSize
import io.github.hokanosekai.tensai.ui.screens.models.formatGigabytes
import io.github.hokanosekai.tensai.ui.screens.models.importFailureBody
import io.github.hokanosekai.tensai.ui.screens.models.modelCompatibility
import io.github.hokanosekai.tensai.ui.theme.TensaiMono
import io.github.hokanosekai.tensai.ui.theme.TensaiShapes
import io.github.hokanosekai.tensai.ui.theme.ThemeMode
import io.github.hokanosekai.tensai.ui.theme.tensaiTints
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A GGUF worth suggesting to someone with an empty device. Downloaded on a computer and copied
 * over: V1 imports from local storage only, there is no in-app download (that's TEN-45, V1.1).
 * Sizes are the Q4_K_M builds, rounded; kept small on purpose, since the measured table these
 * are drawn from ([io.github.hokanosekai.tensai.compat.CompatibilityChecker]'s KDoc) puts the
 * usable ceiling on this device class around 4B params.
 */
private data class ModelSuggestion(val name: String, val size: String, val url: String)

private val Suggestions = listOf(
    ModelSuggestion("Qwen2.5 1.5B Instruct · Q4_K_M", "~1.0 GB", "huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF"),
    ModelSuggestion("Llama 3.2 3B Instruct · Q4_K_M", "~2.0 GB", "huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF"),
    ModelSuggestion("Qwen2.5 0.5B Instruct · Q4_K_M", "~0.4 GB", "huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF"),
)


/**
 * First-run onboarding (TEN-36, TEN-118): one Calm Night page. Welcome header, the model to start
 * from (import from local storage, since there is no in-app download before V1.1), backend and
 * theme chips, and a full-width action that hands off to the load.
 *
 * Which launches land here is [OnboardingGate]'s call. The page is a single scroll, so
 * [startStep] no longer picks a screen: a returning skipper sees the same page, with the same
 * Skip. Backend and theme write to their stores as soon as a chip is tapped.
 *
 * @param startStep kept for the gate's contract; the layout shows every step at once.
 * @param onDone called when the flow ends: with the imported model's id when the user finished it
 *   (the caller sends that to the loading screen, which continues into a first chat), or `null`
 *   when they left without a model (the caller goes Home).
 * @param persistScope scope the preference writes run on. Deliberately **not** a
 *   `rememberCoroutineScope()`: "Skip" both persists the flag and navigates away, and a
 *   composition-scoped write would be cancelled by the navigation it triggers. Same reasoning as
 *   [io.github.hokanosekai.tensai.ui.screens.loading.LoadModelViewModel]'s scope.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(
    startStep: OnboardingStep,
    onDone: (modelId: String?) -> Unit,
    modifier: Modifier = Modifier,
    modelRepository: ModelRepository = RuntimeGraph.modelRepository,
    themeRepository: ThemeRepository = RuntimeGraph.themeRepository,
    backendRepository: BackendRepository = RuntimeGraph.backendRepository,
    onboardingRepository: OnboardingRepository = RuntimeGraph.onboardingRepository,
    persistScope: CoroutineScope = RuntimeGraph.applicationScope,
) {
    var imported by remember { mutableStateOf<ModelEntity?>(null) }
    var importProgress by remember { mutableStateOf<Float?>(null) }
    var importFailure by remember { mutableStateOf<ImportFailure?>(null) }

    val backend by backendRepository.preference.collectAsState(initial = BackendPreference.Auto)
    val theme by themeRepository.themeMode.collectAsState(initial = ThemeMode.System)

    val uiScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        uiScope.launch {
            importProgress = 0f
            try {
                imported = modelRepository.importModel(uri) { progress -> importProgress = progress }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Same classify-don't-read rule as ModelsScreen's import (TEN-39): this is the
                // *first* import a user ever attempts, so it is the one most likely to fail, and
                // the least useful moment to answer with an IOException's own wording. Kept as a
                // snackbar rather than promoted to ModelsScreen's card: the import button is
                // still right there in the model card, unchanged, so the retry is one tap.
                importFailure = ImportFailure.of(e)
            } finally {
                importProgress = null
            }
        }
    }

    // Resolved in composition (stringResource needs it) and shown from an effect, since
    // showSnackbar suspends. Cleared only AFTER it has been shown: clearing first changes the
    // effect's key and cancels the snackbar before it appears. Clearing at the end still lets
    // the same failure twice in a row be announced twice.
    val importFailureMessage = importFailure?.let { importFailureBody(it) }
    LaunchedEffect(importFailureMessage) {
        val message = importFailureMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        importFailure = null
    }

    fun skip() {
        persistScope.launch { onboardingRepository.setSkipped(true) }
        onDone(null)
    }

    // Total RAM does not change under the page, so it is read once, not per recomposition.
    val ramLabel = remember { formatGigabytes(RuntimeGraph.deviceProfiler.profile().totalRamBytes) }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                if (imported == null) {
                    // Leaving mid-copy would cancel the import, so Skip waits for it.
                    TextButton(onClick = ::skip, enabled = importProgress == null) { Text(stringResource(R.string.onboarding_skip)) }
                } else {
                    Spacer(modifier = Modifier.height(48.dp))
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                ) {
                    KanjiGlyph(size = 72.dp, glow = true)
                    Text(
                        text = stringResource(R.string.onboarding_welcome_title),
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.26).sp,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                    Text(
                        text = stringResource(R.string.onboarding_welcome_body),
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
                ) {
                    Eyebrow(stringResource(R.string.onboarding_recommended))
                    TensaiMonoBadge(stringResource(R.string.onboarding_ram_badge, ramLabel))
                }

                ModelCard(
                    imported = imported,
                    importProgress = importProgress,
                    onImportClick = { importLauncher.launch(GgufMimeTypes) },
                )

                Eyebrow(stringResource(R.string.onboarding_backend), modifier = Modifier.padding(top = 22.dp, bottom = 10.dp))
                // Backend writes to the same store Settings reads (one answer per preference).
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TensaiChip(
                        label = stringResource(R.string.settings_backend_auto),
                        selected = backend == BackendPreference.Auto,
                        icon = Icons.Rounded.AutoAwesome,
                        onClick = { persistScope.launch { backendRepository.setPreference(BackendPreference.Auto) } },
                    )
                    TensaiChip(
                        label = stringResource(R.string.settings_backend_gpu),
                        selected = backend == BackendPreference.Gpu,
                        icon = Icons.Rounded.Bolt,
                        onClick = { persistScope.launch { backendRepository.setPreference(BackendPreference.Gpu) } },
                    )
                    TensaiChip(
                        label = stringResource(R.string.settings_backend_cpu),
                        selected = backend == BackendPreference.Cpu,
                        icon = Icons.Rounded.Memory,
                        onClick = { persistScope.launch { backendRepository.setPreference(BackendPreference.Cpu) } },
                    )
                }

                Eyebrow(stringResource(R.string.settings_theme), modifier = Modifier.padding(top = 22.dp, bottom = 10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TensaiChip(
                        label = stringResource(R.string.settings_theme_system),
                        selected = theme == ThemeMode.System,
                        icon = Icons.Rounded.BrightnessAuto,
                        onClick = { persistScope.launch { themeRepository.setThemeMode(ThemeMode.System) } },
                    )
                    TensaiChip(
                        label = stringResource(R.string.settings_theme_dark),
                        selected = theme == ThemeMode.Dark,
                        icon = Icons.Rounded.DarkMode,
                        onClick = { persistScope.launch { themeRepository.setThemeMode(ThemeMode.Dark) } },
                    )
                    TensaiChip(
                        label = stringResource(R.string.settings_theme_light),
                        selected = theme == ThemeMode.Light,
                        icon = Icons.Rounded.LightMode,
                        onClick = { persistScope.launch { themeRepository.setThemeMode(ThemeMode.Light) } },
                    )
                }

                // With a model in: straight into its load (and a first chat). Without one, this is
                // the same exit as Skip: Home, flag persisted.
                Button(
                    onClick = { if (imported != null) onDone(imported?.id) else skip() },
                    enabled = importProgress == null,
                    modifier = Modifier.fillMaxWidth().padding(top = 28.dp).height(52.dp),
                ) {
                    Text(
                        text = stringResource(
                            if (imported != null) R.string.onboarding_start_chatting else R.string.onboarding_get_started,
                        ),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 8.dp).size(20.dp),
                    )
                }
            }
        }

        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/** Small uppercase section label: 13sp semibold, 0.04em tracking, text-secondary. */
@Composable
private fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.52.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * The model card. Empty device: the starting-point suggestions, what to do with them, and the
 * import action (SAF picker). Once a file is in: its name, size and the traffic light for it on
 * this device, reusing the Models screen's own [CompatIndicator]/[modelCompatibility] rather than
 * a second opinion of its own.
 */
@Composable
private fun ModelCard(
    imported: ModelEntity?,
    importProgress: Float?,
    onImportClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = TensaiShapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.tensaiTints.outline),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            if (imported == null) {
                Suggestions.forEachIndexed { index, suggestion ->
                    if (index > 0) {
                        Box(
                            modifier = Modifier
                                .padding(vertical = 10.dp)
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(MaterialTheme.tensaiTints.outline),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            text = suggestion.name,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        TensaiMonoBadge(suggestion.size)
                    }
                    Text(
                        text = suggestion.url,
                        fontFamily = TensaiMono,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }

                Text(
                    text = stringResource(R.string.onboarding_import_body),
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 14.dp),
                )

                if (importProgress != null) {
                    Box(
                        modifier = Modifier
                            .padding(top = 16.dp)
                            .fillMaxWidth()
                            .height(6.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(importProgress.coerceIn(0f, 1f))
                                .height(6.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape),
                        )
                    }
                    Text(
                        text = stringResource(R.string.onboarding_copying),
                        fontFamily = TensaiMono,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else {
                    Button(onClick = onImportClick, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.FileOpen,
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
            } else {
                Text(
                    text = imported.displayName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                TensaiMonoBadge(formatFileSize(imported.sizeBytes), modifier = Modifier.padding(top = 10.dp))
                // One check for both rows, see CompatIndicator's KDoc for why this is read once.
                val compatibility = modelCompatibility(imported)
                CompatIndicator(compatibility, modifier = Modifier.padding(top = 12.dp))
                Text(
                    text = compatExplanation(compatibility),
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
