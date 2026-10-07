package io.github.hokanosekai.tensai.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
 * Settings (TEN-38, TEN-117): appearance, inference, storage, about, as Calm Night cards.
 *
 * Everything shown is read from a store through [SettingsViewModel], there is no local state on
 * this screen except dialog visibility and the licenses row being expanded. That is what makes a theme or
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
    var picker by remember { mutableStateOf<Picker?>(null) }
    var licensesOpen by remember { mutableStateOf(false) }

    // Resolved here, not in the callback below: the purge's continuation is not a composition, so
    // it has no `stringResource` to call, and building the message by pasting a size onto a
    // translated prefix would freeze English word order into every translation.
    val context = LocalContext.current
    val nothingToClear = stringResource(R.string.settings_storage_cleared_nothing)
    val unknown = stringResource(R.string.settings_inference_unknown)

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            SectionLabel(stringResource(R.string.settings_group_appearance))
            SectionCard {
                SettingsRow(
                    icon = Icons.Rounded.DarkMode,
                    title = stringResource(R.string.settings_theme),
                    onClick = { picker = Picker.Theme },
                    trailing = { ValueText(stringResource(theme.titleRes)); Chevron() },
                )
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.Translate,
                    title = stringResource(R.string.settings_language),
                    onClick = { picker = Picker.Language },
                    trailing = { ValueText(stringResource(language.titleRes)); Chevron() },
                )
            }

            SectionLabel(stringResource(R.string.settings_group_inference))
            SectionCard {
                SettingsRow(
                    icon = Icons.Rounded.Tune,
                    title = stringResource(R.string.settings_backend),
                    onClick = { picker = Picker.Backend },
                    trailing = { ValueText(stringResource(backend.titleRes)); Chevron() },
                )
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.Straighten,
                    title = stringResource(R.string.settings_context_size),
                    supporting = inference.caption(),
                    trailing = {
                        MonoText(
                            when (inference) {
                                is InferenceFacts.InUse ->
                                    pluralStringResource(R.plurals.settings_context_size_value, inference.nCtx, inference.nCtx)
                                is InferenceFacts.Planned ->
                                    pluralStringResource(R.plurals.settings_context_size_value, inference.nCtx, inference.nCtx)
                                InferenceFacts.Unknown -> unknown
                            },
                        )
                    },
                )
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.Memory,
                    title = stringResource(R.string.settings_kv_cache),
                    trailing = {
                        MonoText(
                            when (inference) {
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
                                InferenceFacts.Unknown -> unknown
                            },
                        )
                    },
                )
            }

            SectionLabel(stringResource(R.string.settings_group_storage))
            StorageSection(
                figures = storage,
                onManageModels = onNavigateToModels,
                onClearClick = { confirmingClear = true },
            )

            SectionLabel(stringResource(R.string.settings_group_about))
            SectionCard {
                SettingsRow(
                    icon = Icons.Rounded.Info,
                    title = stringResource(R.string.settings_version),
                    trailing = { MonoText(viewModel.appVersion.ifBlank { unknown }) },
                )
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.Description,
                    title = stringResource(R.string.settings_licenses),
                    onClick = { licensesOpen = !licensesOpen },
                    stateDescription = stringResource(
                        if (licensesOpen) R.string.settings_licenses_expanded else R.string.settings_licenses_collapsed,
                    ),
                    trailing = { Chevron(rotation = if (licensesOpen) 90f else 0f) },
                )
                if (licensesOpen) {
                    ThirdPartyLicenses.forEach { entry ->
                        RowDivider()
                        LicenseRow(entry)
                    }
                }
            }

            Footer()
        }

        picker?.let { current ->
            val options: List<PickerOption> = when (current) {
                Picker.Theme -> ThemeMode.entries.map { mode ->
                    PickerOption(
                        title = stringResource(mode.titleRes),
                        subtitle = stringResource(mode.subtitleRes),
                        selected = theme == mode,
                        onSelect = { viewModel.setThemeMode(mode) },
                    )
                }
                Picker.Language -> AppLanguage.entries.map { entry ->
                    PickerOption(
                        title = stringResource(entry.titleRes),
                        subtitle = null,
                        selected = language == entry,
                        onSelect = { viewModel.setLanguage(entry) },
                    )
                }
                Picker.Backend -> BackendPreference.entries.map { preference ->
                    PickerOption(
                        title = stringResource(preference.titleRes),
                        subtitle = stringResource(preference.subtitleRes),
                        selected = backend == preference,
                        onSelect = { viewModel.setBackend(preference) },
                    )
                }
            }
            PickerDialog(
                title = stringResource(current.titleRes),
                options = options,
                onDismiss = { picker = null },
            )
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

/** The three settings that pick one of a few values: they open a dialog from a design-style row. */
private enum class Picker(val titleRes: Int) {
    Theme(R.string.settings_theme),
    Language(R.string.settings_language),
    Backend(R.string.settings_backend),
}

private data class PickerOption(
    val title: String,
    val subtitle: String?,
    val selected: Boolean,
    val onSelect: () -> Unit,
)

@Composable
private fun PickerDialog(
    title: String,
    options: List<PickerOption>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { option ->
                    // Applied on tap, and the dialog closes: the write comes back through the
                    // store's flow, so the row behind already shows the new value.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = option.selected,
                                role = Role.RadioButton,
                                onClick = {
                                    option.onSelect()
                                    onDismiss()
                                },
                            )
                            .heightIn(min = 48.dp)
                            .padding(vertical = 10.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = option.title,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (option.selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            if (option.subtitle != null) {
                                Text(
                                    text = option.subtitle,
                                    fontSize = 12.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                        if (option.selected) {
                            Box(
                                modifier = Modifier
                                    .padding(start = 12.dp)
                                    .size(8.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_storage_clear_cancel)) }
        },
    )
}

@Composable
private fun StorageSection(
    figures: StorageFigures?,
    onManageModels: () -> Unit,
    onClearClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(modifier = modifier) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Top) {
                RowIcon(Icons.Rounded.Inventory2)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_storage_models),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    // The kit's ProgressBar: 6dp pill track in surface-variant, primary fill.
                    Box(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth()
                            .height(6.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                    ) {
                        val fraction = (figures?.modelFraction ?: 0f).coerceIn(0f, 1f)
                        if (fraction > 0f) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(fraction)
                                    .height(6.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape),
                            )
                        }
                    }
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
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            // Tonal button (kit: primary-16 fill, primary text, pill, 36dp), aligned with the text
            // column: 22dp icon + 14dp gap.
            Button(
                onClick = onManageModels,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.tensaiTints.primary16,
                    contentColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.padding(start = 36.dp, top = 12.dp).height(36.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_storage_manage_models),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        RowDivider()
        // Deliberately not disabled on a zero count: the purge also sweeps state files no row
        // points at (see SessionRepository.purgeAll), and those are invisible to the count
        // above. Disabling on it would make the one action that can reclaim them unreachable in
        // exactly the case they exist. It reports "nothing to clear" when there genuinely was
        // nothing.
        SettingsRow(
            icon = Icons.Rounded.History,
            title = stringResource(R.string.settings_storage_sessions),
            supporting = if (figures == null || figures.sessionCount == 0) {
                stringResource(R.string.settings_storage_sessions_empty)
            } else {
                pluralStringResource(
                    R.plurals.settings_storage_sessions_usage,
                    figures.sessionCount,
                    formatFileSize(figures.sessionBytes),
                    figures.sessionCount,
                )
            },
            trailing = {
                TextButton(
                    onClick = onClearClick,
                    enabled = figures != null,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    ),
                ) {
                    Text(
                        text = stringResource(R.string.settings_storage_clear),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            },
        )
        RowDivider()
        Text(
            text = stringResource(R.string.settings_storage_sessions_explainer),
            fontSize = 12.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/** One open-source dependency, shown as a row (no icon) when the licenses row is expanded. */
@Composable
private fun LicenseRow(entry: ThirdPartyLicense, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(start = 52.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
        Text(
            text = entry.name,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.settings_license_line, entry.license, entry.author),
            fontSize = 12.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp),
        )
        Text(
            text = entry.url,
            fontFamily = TensaiMono,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** The 天 sign-off closing the screen: the glyph at half opacity over the tagline, centred. */
@Composable
private fun Footer(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        KanjiGlyph(size = 30.dp, modifier = Modifier.alpha(0.5f))
        Text(
            text = stringResource(R.string.settings_footer),
            fontSize = 12.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Eyebrow above a card: 12sp bold uppercase, 0.06em tracking, text-secondary. */
@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.72.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, end = 4.dp, top = 18.dp, bottom = 8.dp),
    )
}

/** Card: surface fill, 1dp outline hairline, 20dp radius, content clipped to the shape. */
@Composable
private fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
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
private fun RowDivider() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.tensaiTints.outline))
}

@Composable
private fun RowIcon(icon: ImageVector) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(22.dp),
    )
}

/**
 * The kit's `Row`: 22dp leading icon, 15sp medium title, optional 12.5sp supporting line, trailing
 * slot. Tappable only when [onClick] is set, so read-only facts do not look interactive.
 */
@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    onClick: (() -> Unit)? = null,
    stateDescription: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .then(if (stateDescription != null) Modifier.semantics { this.stateDescription = stateDescription } else Modifier)
            .padding(horizontal = 16.dp, vertical = 13.dp),
    ) {
        RowIcon(icon)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (supporting != null) {
                Text(
                    text = supporting,
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (trailing != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                trailing()
            }
        }
    }
}

@Composable
private fun ValueText(text: String) {
    Text(text = text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
}

@Composable
private fun MonoText(text: String) {
    Text(
        text = text,
        fontFamily = TensaiMono,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End,
    )
}

@Composable
private fun Chevron(rotation: Float = 0f) {
    Icon(
        imageVector = if (rotation == 0f) Icons.Rounded.ChevronRight else Icons.Rounded.ExpandMore,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
    )
}

/** Caption under the context-size row: says whether the numbers are live or a plan. */
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
