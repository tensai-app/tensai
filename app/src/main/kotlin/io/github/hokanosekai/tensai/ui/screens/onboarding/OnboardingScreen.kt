package io.github.hokanosekai.tensai.ui.screens.onboarding

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.hokanosekai.tensai.data.BackendPreference
import io.github.hokanosekai.tensai.data.BackendRepository
import io.github.hokanosekai.tensai.data.OnboardingRepository
import io.github.hokanosekai.tensai.data.ThemeRepository
import io.github.hokanosekai.tensai.data.models.DuplicateModelException
import io.github.hokanosekai.tensai.data.models.InsufficientStorageException
import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.data.models.ModelImportException
import io.github.hokanosekai.tensai.data.models.ModelMetadataException
import io.github.hokanosekai.tensai.data.models.ModelRepository
import io.github.hokanosekai.tensai.runtime.RuntimeGraph
import io.github.hokanosekai.tensai.ui.components.KanjiGlyph
import io.github.hokanosekai.tensai.ui.screens.models.CompatIndicator
import io.github.hokanosekai.tensai.ui.screens.models.GgufMimeTypes
import io.github.hokanosekai.tensai.ui.screens.models.formatFileSize
import io.github.hokanosekai.tensai.ui.screens.models.modelCompatibility
import io.github.hokanosekai.tensai.ui.theme.TensaiMono
import io.github.hokanosekai.tensai.ui.theme.TensaiShapes
import io.github.hokanosekai.tensai.ui.theme.ThemeMode
import io.github.hokanosekai.tensai.ui.theme.tensaiTints
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A GGUF worth suggesting to someone with an empty device. Downloaded on a computer and copied
 * over — V1 imports from local storage only, there is no in-app download (that's TEN-45, V1.1).
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
 * First-run onboarding (TEN-36): welcome → import a GGUF → backend/theme → hand off to the load,
 * so a blank device reaches its first chat without leaving the flow.
 *
 * Which launches land here — and at which [OnboardingStep] — is [OnboardingGate]'s call, not this
 * composable's; it renders whatever step it's handed and walks forward from there. Every step is
 * skippable except the last (by then a model is imported and there is nothing left to skip).
 *
 * @param startStep step to open at — see [OnboardingGate.entry].
 * @param onDone called when the flow ends: with the imported model's id when the user finished it
 *   (the caller sends that to the loading screen, which continues into a first chat), or `null`
 *   when they skipped out (the caller goes Home).
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
    modelRepository: ModelRepository = RuntimeGraph.modelRepository,
    themeRepository: ThemeRepository = RuntimeGraph.themeRepository,
    backendRepository: BackendRepository = RuntimeGraph.backendRepository,
    onboardingRepository: OnboardingRepository = RuntimeGraph.onboardingRepository,
    persistScope: CoroutineScope = RuntimeGraph.applicationScope,
) {
    var step by remember { mutableStateOf(startStep) }
    var imported by remember { mutableStateOf<ModelEntity?>(null) }
    var importProgress by remember { mutableStateOf<Float?>(null) }

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

    fun skip() {
        persistScope.launch { onboardingRepository.setSkipped(true) }
        onDone(null)
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (step != OnboardingStep.Ready) {
                    TextButton(onClick = ::skip) { Text("Skip") }
                } else {
                    Spacer(modifier = Modifier.height(48.dp))
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (step) {
                    OnboardingStep.Welcome -> WelcomeStep(onContinue = { step = OnboardingStep.Import })

                    OnboardingStep.Import -> ImportStep(
                        imported = imported,
                        importProgress = importProgress,
                        onImportClick = { importLauncher.launch(GgufMimeTypes) },
                        onContinue = { step = OnboardingStep.Preferences },
                    )

                    OnboardingStep.Preferences -> PreferencesStep(
                        backend = backend,
                        theme = theme,
                        onBackendChange = { persistScope.launch { backendRepository.setPreference(it) } },
                        onThemeChange = { persistScope.launch { themeRepository.setThemeMode(it) } },
                        onContinue = { step = OnboardingStep.Ready },
                    )

                    OnboardingStep.Ready -> ReadyStep(
                        model = imported,
                        onStart = { onDone(imported?.id) },
                    )
                }
            }

            StepDots(current = step, modifier = Modifier.align(Alignment.CenterHorizontally))
        }

        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun WelcomeStep(onContinue: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        KanjiGlyph(size = 96.dp, glow = true)
        Text(
            text = "tensai",
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 24.dp),
        )
        Text(
            text = "A local AI agent, running fully on your phone. Nothing leaves the device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        Button(onClick = onContinue, modifier = Modifier.padding(top = 32.dp)) {
            Text("Get started")
        }
    }
}

/**
 * The import step: what a GGUF is and where to get one, the SAF picker, and — once a file is in —
 * the traffic light for it on this device, reusing the Models screen's own
 * [CompatIndicator]/[modelCompatibility] rather than a second opinion of its own.
 */
@Composable
private fun ImportStep(
    imported: ModelEntity?,
    importProgress: Float?,
    onImportClick: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(text = "Bring a model", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "tensai runs GGUF models straight off your storage. Download one on your " +
                "computer, copy it to the phone, then pick it here.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )

        Text(
            text = "Good starting points",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 20.dp),
        )
        Suggestions.forEach { suggestion ->
            Surface(
                shape = TensaiShapes.large,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = suggestion.name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = suggestion.size,
                            fontFamily = TensaiMono,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = suggestion.url,
                        fontFamily = TensaiMono,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        if (importProgress != null) {
            LinearProgressIndicator(
                progress = { importProgress },
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
            )
            Text(
                text = "Copying into tensai…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        if (imported != null) {
            Surface(
                shape = TensaiShapes.large,
                color = MaterialTheme.tensaiTints.primary12,
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(text = imported.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = formatFileSize(imported.sizeBytes),
                        fontFamily = TensaiMono,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    CompatIndicator(imported, modifier = Modifier.padding(top = 10.dp))
                    Text(
                        text = modelCompatibility(imported).explanation,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                Text("Continue")
            }
        } else {
            OutlinedButton(
                onClick = onImportClick,
                enabled = importProgress == null,
                modifier = Modifier.fillMaxWidth().padding(top = 20.dp),
            ) {
                Text("Import a GGUF file")
            }
        }
    }
}

@Composable
private fun PreferencesStep(
    backend: BackendPreference,
    theme: ThemeMode,
    onBackendChange: (BackendPreference) -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(text = "How should it run?", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "You can change both later, in Settings.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        // Both choices write to the same stores Settings reads and writes (TEN-38's
        // `SettingsViewModel` takes the very same [ThemeRepository]/[BackendRepository] this step
        // does, through `RuntimeGraph`) — there is one answer per preference in this app, not one
        // per screen that offers it.
        Text(text = "Backend", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 24.dp))
        ChoiceCard(
            title = "Auto",
            subtitle = "Recommended — picks CPU or GPU per model",
            selected = backend == BackendPreference.Auto,
            onClick = { onBackendChange(BackendPreference.Auto) },
        )
        ChoiceCard(
            title = "CPU",
            subtitle = "Fastest for most dense models on this class of phone",
            selected = backend == BackendPreference.Cpu,
            onClick = { onBackendChange(BackendPreference.Cpu) },
        )
        ChoiceCard(
            title = "GPU",
            subtitle = "Vulkan offload, when the device has it",
            selected = backend == BackendPreference.Gpu,
            onClick = { onBackendChange(BackendPreference.Gpu) },
        )

        Text(text = "Theme", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 24.dp))
        ChoiceCard(
            title = "System",
            subtitle = "Follows your phone's day/night setting",
            selected = theme == ThemeMode.System,
            onClick = { onThemeChange(ThemeMode.System) },
        )
        ChoiceCard(
            title = "Calm Night",
            subtitle = "Dark, always",
            selected = theme == ThemeMode.Dark,
            onClick = { onThemeChange(ThemeMode.Dark) },
        )
        ChoiceCard(
            title = "Light",
            subtitle = "Light, always",
            selected = theme == ThemeMode.Light,
            onClick = { onThemeChange(ThemeMode.Light) },
        )

        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
            Text("Continue")
        }
    }
}

@Composable
private fun ReadyStep(model: ModelEntity?, onStart: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        KanjiGlyph(size = 96.dp, glow = true)
        Text(
            text = model?.let { "${it.displayName} is ready" } ?: "You're set",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 24.dp),
        )
        Text(
            text = "Loading a model takes a few seconds — it stays in memory afterwards.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
        Button(onClick = onStart, modifier = Modifier.padding(top = 32.dp)) {
            Text("Start chatting")
        }
    }
}

/** Selectable card — the shape every choice in [PreferencesStep] uses. */
@Composable
private fun ChoiceCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = TensaiShapes.large,
        color = if (selected) MaterialTheme.tensaiTints.primary12 else MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** One dot per [OnboardingStep], filled up to [current] — grows on its own as steps are added. */
@Composable
private fun StepDots(current: OnboardingStep, modifier: Modifier = Modifier) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.padding(top = 16.dp),
    ) {
        OnboardingStep.entries.forEach { entry ->
            val color = if (entry.ordinal <= current.ordinal) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.tensaiTints.primaryRing
            }
            Box(modifier = Modifier.size(6.dp).background(color, CircleShape))
        }
    }
}
