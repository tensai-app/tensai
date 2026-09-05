package io.github.hokanosekai.tensai.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.hokanosekai.tensai.R
import io.github.hokanosekai.tensai.data.AppLanguage
import io.github.hokanosekai.tensai.data.BackendPreference
import io.github.hokanosekai.tensai.ui.components.KanjiGlyph
import io.github.hokanosekai.tensai.ui.screens.models.formatFileSize
import io.github.hokanosekai.tensai.ui.theme.TensaiMono
import io.github.hokanosekai.tensai.ui.theme.TensaiShapes
import io.github.hokanosekai.tensai.ui.theme.ThemeMode
import io.github.hokanosekai.tensai.ui.theme.tensaiTints
import kotlinx.coroutines.launch

/**
 * Settings (TEN-38): appearance, inference defaults, storage, about.
 *
 * Everything shown is read from a store through [SettingsViewModel] — there is no local state on
 * this screen except the confirmation dialog's own visibility. That is what makes a theme or
 * language tap apply immediately (the write comes back through the flow `MainActivity` is
 * collecting) and what makes the values here the same ones the onboarding wrote.
 *
 * @param onNavigateToModels where "Manage models" goes.
 */
@Composable
fun SettingsScreen(
    onNavigateToModels: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = remember { SettingsViewModel() },
) {
    val theme by viewModel.themeMode.collectAsState(initial = ThemeMode.System)
    val language by viewModel.language.collectAsState(initial = AppLanguage.System)
    val backend by viewModel.backend.collectAsState(initial = BackendPreference.Auto)
    val storage by viewModel.storage.collectAsState(initial = null)
    val inferenceState by viewModel.inference.collectAsState(initial = InferenceFacts.Unknown)
    val inference = inferenceState

    val snackbarHostState = remember { SnackbarHostState() }
    val uiScope = rememberCoroutineScope()
    var confirmingClear by remember { mutableStateOf(false) }

    // Resolved here, not in the callback below: the purge's continuation is not a composition, so
    // it has no `stringResource` to call — and building the message by pasting a size onto a
    // translated prefix would freeze English word order into every translation.
    val context = LocalContext.current
    val nothingToClear = stringResource(R.string.settings_storage_cleared_nothing)

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            GroupHeader(stringResource(R.string.settings_group_appearance))

            SettingLabel(stringResource(R.string.settings_theme))
            ThemeMode.entries.forEach { mode ->
                ChoiceRow(
                    title = stringResource(mode.titleRes),
                    subtitle = stringResource(mode.subtitleRes),
                    selected = theme == mode,
                    onClick = { viewModel.setThemeMode(mode) },
                )
            }

            SettingLabel(stringResource(R.string.settings_language))
            AppLanguage.entries.forEach { entry ->
                ChoiceRow(
                    title = stringResource(entry.titleRes),
                    subtitle = null,
                    selected = language == entry,
                    onClick = { viewModel.setLanguage(entry) },
                )
            }

            GroupHeader(stringResource(R.string.settings_group_inference))

            SettingLabel(stringResource(R.string.settings_backend))
            BackendPreference.entries.forEach { preference ->
                ChoiceRow(
                    title = stringResource(preference.titleRes),
                    subtitle = stringResource(preference.subtitleRes),
                    selected = backend == preference,
                    onClick = { viewModel.setBackend(preference) },
                )
            }

            ValueRow(
                label = stringResource(R.string.settings_context_size),
                value = when (inference) {
                    is InferenceFacts.InUse ->
                        stringResource(R.string.settings_context_size_value, inference.nCtx)
                    is InferenceFacts.Planned ->
                        stringResource(R.string.settings_context_size_value, inference.nCtx)
                    InferenceFacts.Unknown -> stringResource(R.string.settings_inference_unknown)
                },
                caption = inference.caption(),
            )

            ValueRow(
                label = stringResource(R.string.settings_kv_cache),
                value = when (inference) {
                    is InferenceFacts.InUse -> stringResource(
                        R.string.settings_kv_cache_value,
                        inference.kvCacheType.name.lowercase(),
                        stringResource(R.string.settings_inference_chosen_auto),
                    )
                    is InferenceFacts.Planned -> stringResource(
                        R.string.settings_kv_cache_value,
                        inference.kvCacheType.name.lowercase(),
                        stringResource(R.string.settings_inference_chosen_auto),
                    )
                    InferenceFacts.Unknown -> stringResource(R.string.settings_inference_unknown)
                },
                caption = null,
            )

            GroupHeader(stringResource(R.string.settings_group_storage))

            StorageSection(
                figures = storage,
                onManageModels = onNavigateToModels,
                onClearClick = { confirmingClear = true },
            )

            GroupHeader(stringResource(R.string.settings_group_about))

            ValueRow(
                label = stringResource(R.string.settings_version),
                value = viewModel.appVersion.ifBlank { stringResource(R.string.settings_inference_unknown) },
                caption = null,
            )

            SettingLabel(stringResource(R.string.settings_licenses))
            ThirdPartyLicenses.forEach { entry ->
                LicenseRow(entry)
            }

            Footer()
        }

        if (confirmingClear) {
            AlertDialog(
                onDismissRequest = { confirmingClear = false },
                title = { Text(stringResource(R.string.settings_storage_clear_title)) },
                text = { Text(stringResource(R.string.settings_storage_clear_body)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmingClear = false
                        viewModel.clearSessions { report ->
                            // The purge itself runs on the application scope so navigating away
                            // can't cut it short; only this message is tied to the screen still
                            // being here to show it.
                            uiScope.launch {
                                snackbarHostState.showSnackbar(
                                    if (report.freedBytes > 0) {
                                        context.getString(
                                            R.string.settings_storage_cleared,
                                            formatFileSize(report.freedBytes),
                                        )
                                    } else {
                                        nothingToClear
                                    },
                                )
                            }
                        }
                    }) {
                        Text(
                            text = stringResource(R.string.settings_storage_clear_confirm),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { confirmingClear = false }) {
                        Text(stringResource(R.string.settings_storage_clear_cancel))
                    }
                },
            )
        }

        SnackbarHost(hostState = snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun StorageSection(
    figures: StorageFigures?,
    onManageModels: () -> Unit,
    onClearClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.settings_storage_models),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (figures == null) {
                    stringResource(R.string.settings_inference_unknown)
                } else {
                    stringResource(
                        R.string.settings_storage_models_usage,
                        formatFileSize(figures.modelBytes),
                        formatFileSize(figures.volumeTotalBytes),
                    )
                },
                fontFamily = TensaiMono,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LinearProgressIndicator(
            progress = { figures?.modelFraction ?: 0f },
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        TextButton(onClick = onManageModels, modifier = Modifier.padding(top = 4.dp)) {
            Text(stringResource(R.string.settings_storage_manage_models))
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.settings_storage_sessions),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = if (figures == null || figures.sessionCount == 0) {
                        stringResource(R.string.settings_storage_sessions_empty)
                    } else {
                        pluralStringResource(
                            R.plurals.settings_storage_sessions_usage,
                            figures.sessionCount,
                            formatFileSize(figures.sessionBytes),
                            figures.sessionCount,
                        )
                    },
                    fontFamily = TensaiMono,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Deliberately not disabled on a zero count: the purge also sweeps state files no row
            // points at (see SessionRepository.purgeAll), and those are invisible to the count
            // above — disabling on it would make the one action that can reclaim them
            // unreachable in exactly the case they exist. It reports "nothing to clear" when
            // there genuinely was nothing.
            TextButton(onClick = onClearClick, enabled = figures != null) {
                Text(
                    text = stringResource(R.string.settings_storage_clear),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        Text(
            text = stringResource(R.string.settings_storage_sessions_explainer),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun LicenseRow(entry: ThirdPartyLicense, modifier: Modifier = Modifier) {
    Surface(
        shape = TensaiShapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(text = entry.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = stringResource(R.string.settings_license_line, entry.license, entry.author),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                text = entry.url,
                fontFamily = TensaiMono,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** The 天 sign-off closing the screen — the same glyph Home watermarks with, quiet and centred. */
@Composable
private fun Footer(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(top = 32.dp, bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        KanjiGlyph(size = 32.dp, tint = MaterialTheme.tensaiTints.primaryRing)
    }
}

@Composable
private fun GroupHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 28.dp, bottom = 4.dp),
    )
}

@Composable
private fun SettingLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(top = 16.dp),
    )
}

/** A label and a monospaced value — the shape of every read-only row on this screen. */
@Composable
private fun ValueRow(label: String, value: String, caption: String?, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                text = value,
                fontFamily = TensaiMono,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
            )
        }
        if (caption != null) {
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** Selectable row — same affordance as the onboarding's `ChoiceCard`, sized for a settings list. */
@Composable
private fun ChoiceRow(
    title: String,
    subtitle: String?,
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
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** Caption under the context-size row — says whether the numbers are live or a plan. */
@Composable
private fun InferenceFacts.caption(): String = when (this) {
    is InferenceFacts.InUse -> stringResource(R.string.settings_inference_in_use, modelName)
    is InferenceFacts.Planned -> stringResource(R.string.settings_inference_planned, modelName)
    InferenceFacts.Unknown -> stringResource(R.string.settings_inference_no_model)
}

private val ThemeMode.titleRes: Int
    get() = when (this) {
        ThemeMode.System -> R.string.settings_theme_system
        ThemeMode.Dark -> R.string.settings_theme_dark
        ThemeMode.Light -> R.string.settings_theme_light
    }

private val ThemeMode.subtitleRes: Int
    get() = when (this) {
        ThemeMode.System -> R.string.settings_theme_system_subtitle
        ThemeMode.Dark -> R.string.settings_theme_dark_subtitle
        ThemeMode.Light -> R.string.settings_theme_light_subtitle
    }

private val AppLanguage.titleRes: Int
    get() = when (this) {
        AppLanguage.System -> R.string.settings_language_system
        AppLanguage.English -> R.string.settings_language_english
        AppLanguage.French -> R.string.settings_language_french
    }

private val BackendPreference.titleRes: Int
    get() = when (this) {
        BackendPreference.Auto -> R.string.settings_backend_auto
        BackendPreference.Cpu -> R.string.settings_backend_cpu
        BackendPreference.Gpu -> R.string.settings_backend_gpu
    }

private val BackendPreference.subtitleRes: Int
    get() = when (this) {
        BackendPreference.Auto -> R.string.settings_backend_auto_subtitle
        BackendPreference.Cpu -> R.string.settings_backend_cpu_subtitle
        BackendPreference.Gpu -> R.string.settings_backend_gpu_subtitle
    }
