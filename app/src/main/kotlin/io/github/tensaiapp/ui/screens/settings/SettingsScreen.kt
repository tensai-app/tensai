package io.github.tensaiapp.ui.screens.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Percent
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Construction
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import io.github.tensaiapp.R
import io.github.tensaiapp.agent.DefaultTierGrants
import io.github.tensaiapp.data.AppLanguage
import io.github.tensaiapp.data.BackendPreference
import io.github.tensaiapp.data.AutoMemoryShare
import io.github.tensaiapp.data.ContextSetting
import io.github.tensaiapp.runtime.rememberResumeCount
import io.github.tensaiapp.ui.components.KanjiGlyph
import io.github.tensaiapp.ui.screens.models.formatFileSize
import io.github.tensaiapp.ui.theme.TensaiMono
import io.github.tensaiapp.ui.theme.TensaiShapes
import io.github.tensaiapp.ui.theme.ThemeMode
import io.github.tensaiapp.ui.theme.tensaiColors
import io.github.tensaiapp.ui.theme.tensaiTints
import kotlinx.coroutines.launch

/**
 * Settings (TEN-38, TEN-117): appearance, inference, storage, about, as Calm Night cards.
 *
 * Everything shown is read from a store through [SettingsViewModel], there is no local state on
 * this screen except dialog visibility. That is what makes a theme or
 * language tap apply immediately (the write comes back through the flow `MainActivity` is
 * collecting) and what makes the values here the same ones the onboarding wrote.
 *
 * @param onNavigateToModels where "Manage models" goes.
 * @param onNavigateToLicenses where the open-source licenses row goes.
 * @param onNavigateToDevice where the "This device" row goes.
 * @param onNavigateToMemory where the "Memory" row goes.
 * @param onNavigateToTools where the "Tools" row goes (TEN-174).
 * @param openTokenSheet true when the screen was opened to set the Hugging Face token (the repo
 *   screen's "Open Settings"): the token sheet opens once, then [onTokenSheetOpened] resets the request.
 */
@Composable
fun SettingsScreen(
    onNavigateToModels: () -> Unit,
    onNavigateToLicenses: () -> Unit,
    onNavigateToChangelog: () -> Unit,
    onNavigateToDevice: () -> Unit,
    onNavigateToMemory: () -> Unit,
    onNavigateToTools: () -> Unit,
    modifier: Modifier = Modifier,
    openTokenSheet: Boolean = false,
    onTokenSheetOpened: () -> Unit = {},
    viewModel: SettingsViewModel = remember { SettingsViewModel() },
    toolsViewModel: ToolsViewModel = remember { ToolsViewModel() },
) {
    val theme by viewModel.themeMode.collectAsState(initial = ThemeMode.System)
    val language by viewModel.language.collectAsState(initial = AppLanguage.System)
    val backend by viewModel.backend.collectAsState(initial = BackendPreference.Auto)
    val contextSetting by viewModel.contextSetting.collectAsState(initial = ContextSetting.Auto)
    val autoShare by viewModel.autoShare.collectAsState(initial = AutoMemoryShare.P50)
    val contextPreviewModel by viewModel.contextPreviewModel.collectAsState(initial = null)
    val storage by viewModel.storage.collectAsState(initial = null)
    val inferenceState by viewModel.inference.collectAsState(initial = InferenceFacts.Unknown)
    val inference = inferenceState
    val tokenMask by viewModel.tokenMask.collectAsState(initial = null)
    val allowMobileData by viewModel.allowMobileData.collectAsState(initial = false)
    val memoryCount by viewModel.memoryCount.collectAsState(initial = 0)
    val changelogDot by viewModel.changelogDot.collectAsState(initial = false)
    val toolGroupsOn by toolsViewModel.groupsOn.collectAsState(initial = ToolGroup.countOn(DefaultTierGrants.granted()))

    val snackbarHostState = remember { SnackbarHostState() }
    val uiScope = rememberCoroutineScope()
    var confirmingClear by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<Picker?>(null) }
    var tokenSheet by remember { mutableStateOf(false) }
    LaunchedEffect(openTokenSheet) {
        if (openTokenSheet) {
            tokenSheet = true
            onTokenSheetOpened()
        }
    }

    // Resolved here, not in the callback below: the purge's continuation is not a composition, so
    // it has no `stringResource` to call, and building the message by pasting a size onto a
    // translated prefix would freeze English word order into every translation.
    val context = LocalContext.current
    val nothingToClear = stringResource(R.string.settings_storage_cleared_nothing)
    val unknown = stringResource(R.string.settings_inference_unknown)
    val newLabel = stringResource(R.string.changelog_new)

    // Read from the system rather than the permission: it also covers API < 33 (always on unless
    // switched off) and the channel-less "turned off in system settings" case. The user changes it
    // in another screen, so it is re-read on every resume.
    val resumes = rememberResumeCount()
    val notificationsEnabled = remember(resumes) { NotificationManagerCompat.from(context).areNotificationsEnabled() }

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

            SectionLabel(stringResource(R.string.settings_group_system))
            SectionCard {
                SettingsRow(
                    icon = Icons.Rounded.Notifications,
                    title = stringResource(R.string.settings_notifications),
                    onClick = { openAppNotificationSettings(context) },
                    trailing = {
                        ValueText(
                            stringResource(
                                if (notificationsEnabled) R.string.notifications_allowed else R.string.settings_notifications_off,
                            ),
                        )
                        Chevron()
                    },
                )
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.PhoneAndroid,
                    title = stringResource(R.string.device_title),
                    onClick = onNavigateToDevice,
                    trailing = { Chevron() },
                )
            }

            SectionLabel(stringResource(R.string.settings_group_downloads))
            SectionCard {
                SettingsRow(
                    icon = Icons.Rounded.Key,
                    title = stringResource(R.string.settings_hf_token),
                    onClick = { tokenSheet = true },
                    trailing = {
                        MonoText(tokenMask ?: stringResource(R.string.settings_hf_token_not_set))
                        Chevron()
                    },
                )
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.CellTower,
                    title = stringResource(R.string.settings_mobile_data),
                    onClick = { picker = Picker.MobileData },
                    trailing = {
                        ValueText(
                            stringResource(
                                if (allowMobileData) R.string.settings_mobile_data_allow else R.string.settings_mobile_data_ask,
                            ),
                        )
                        Chevron()
                    },
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
                    icon = Icons.Rounded.AspectRatio,
                    title = stringResource(R.string.settings_context_setting),
                    onClick = { picker = Picker.Context },
                    trailing = { ValueText(contextSetting.label()); Chevron() },
                )
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.Percent,
                    title = stringResource(R.string.settings_auto_share),
                    supporting = stringResource(R.string.settings_auto_share_explain),
                    onClick = { picker = Picker.AutoShare },
                    trailing = { ValueText(stringResource(R.string.settings_auto_share_value, autoShare.percent)); Chevron() },
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

            SectionLabel(stringResource(R.string.settings_group_agent))
            SectionCard {
                SettingsRow(
                    icon = Icons.Rounded.Construction,
                    title = stringResource(R.string.settings_tools_title),
                    onClick = onNavigateToTools,
                    trailing = {
                        ValueText(stringResource(R.string.settings_tools_summary, toolGroupsOn, ToolGroup.entries.size))
                        Chevron()
                    },
                )
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.Bookmark,
                    title = stringResource(R.string.memory_title),
                    onClick = onNavigateToMemory,
                    trailing = {
                        ValueText(pluralStringResource(R.plurals.memory_facts_count, memoryCount, memoryCount))
                        Chevron()
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
                    onClick = onNavigateToChangelog,
                    trailing = {
                        if (changelogDot) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .background(MaterialTheme.colorScheme.primary, CircleShape)
                                    .semantics { contentDescription = newLabel },
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        MonoText(viewModel.appVersion.ifBlank { unknown })
                        Chevron()
                    },
                )
                RowDivider()
                SettingsRow(
                    icon = Icons.Rounded.Description,
                    title = stringResource(R.string.settings_licenses),
                    onClick = onNavigateToLicenses,
                    trailing = { Chevron() },
                )
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
                Picker.Context -> contextPickerOptions(
                    choices = ContextSetting.entries,
                    selected = contextSetting,
                    global = contextSetting,
                    model = contextPreviewModel,
                    profile = remember(contextPreviewModel) { viewModel.currentProfile() },
                    autoShare = autoShare,
                    onSelect = { choice -> choice?.let(viewModel::setContextSetting) },
                )
                Picker.AutoShare -> AutoMemoryShare.entries.map { share ->
                    PickerOption(
                        title = stringResource(R.string.settings_auto_share_value, share.percent),
                        subtitle = stringResource(
                            when (share) {
                                AutoMemoryShare.P30 -> R.string.settings_auto_share_30
                                AutoMemoryShare.P50 -> R.string.settings_auto_share_50
                                AutoMemoryShare.P70 -> R.string.settings_auto_share_70
                            },
                        ),
                        selected = autoShare == share,
                        onSelect = { viewModel.setAutoShare(share) },
                    )
                }
                Picker.MobileData -> listOf(false, true).map { allow ->
                    PickerOption(
                        title = stringResource(if (allow) R.string.settings_mobile_data_allow else R.string.settings_mobile_data_ask),
                        subtitle = null,
                        selected = allowMobileData == allow,
                        onSelect = { viewModel.setAllowMobileData(allow) },
                    )
                }
            }
            PickerDialog(
                title = stringResource(current.titleRes),
                options = options,
                onDismiss = { picker = null },
                note = if (current == Picker.Context) {
                    listOfNotNull(
                        stringResource(R.string.context_picker_note_global),
                        contextPreviewModel?.let { stringResource(R.string.context_picker_figures_for, it.shownName) },
                    ).joinToString("\n")
                } else {
                    null
                },
            )
        }

        if (tokenSheet) {
            val saved = stringResource(R.string.hf_token_saved)
            val savedUnchecked = stringResource(R.string.hf_token_saved_unchecked)
            HfTokenSheet(
                currentMask = tokenMask,
                onSave = { raw, done ->
                    // The write runs on the application scope; the answer comes back to this screen.
                    viewModel.saveToken(raw) { result ->
                        uiScope.launch {
                            done(result)
                            when (result) {
                                TokenSaveResult.Saved, TokenSaveResult.SavedUnchecked -> {
                                    tokenSheet = false
                                    snackbarHostState.showSnackbar(if (result == TokenSaveResult.Saved) saved else savedUnchecked)
                                }
                                TokenSaveResult.Rejected, TokenSaveResult.Malformed, TokenSaveResult.StoreFailed -> Unit
                            }
                        }
                    }
                },
                onRemove = viewModel::removeToken,
                onDismiss = { tokenSheet = false },
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

/** Opens this app's page in the system notification settings, where it can be turned on or off. */
private fun openAppNotificationSettings(context: Context) {
    val notifications = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    // A non-activity context needs a new task; the composition's context is normally an activity.
    for (intent in listOf(notifications, details)) {
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // Fall through to the app details page.
        }
    }
}

/** The three settings that pick one of a few values: they open a dialog from a design-style row. */
private enum class Picker(val titleRes: Int) {
    Theme(R.string.settings_theme),
    Language(R.string.settings_language),
    Backend(R.string.settings_backend),
    Context(R.string.settings_context_setting),
    AutoShare(R.string.settings_auto_share),
    MobileData(R.string.settings_mobile_data),
}

/**
 * One row of [PickerDialog]. [warning] (TEN-150) is a line under [subtitle] in the error colour, for
 * a choice the device cannot fully honour.
 */
internal data class PickerOption(
    val title: String,
    val subtitle: String?,
    val selected: Boolean,
    val onSelect: () -> Unit,
    val warning: String? = null,
)

/** [note] is a line above the options, for what the whole choice implies (TEN-150). */
@Composable
internal fun PickerDialog(
    title: String,
    options: List<PickerOption>,
    onDismiss: () -> Unit,
    note: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                if (note != null) {
                    Text(
                        text = note,
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
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
                            if (option.warning != null) {
                                Text(
                                    text = option.warning,
                                    fontSize = 12.5.sp,
                                    color = MaterialTheme.colorScheme.error,
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
                    // Stacked over the whole volume: models (primary), the rest of the phone's data
                    // (outline-strong), free (the empty track, warning-tinted when low).
                    val shares = figures?.shares
                    val barDescription = if (figures == null) {
                        stringResource(R.string.settings_storage_models)
                    } else {
                        stringResource(
                            R.string.settings_storage_bar_description,
                            formatFileSize(figures.modelBytes),
                            formatFileSize(figures.freeBytes),
                            formatFileSize(figures.volumeTotalBytes),
                        )
                    }
                    val freeColor = if (shares?.lowFree == true) {
                        MaterialTheme.tensaiColors.warning
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Row(
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(if (shares?.lowFree == true) MaterialTheme.tensaiTints.warning12 else MaterialTheme.colorScheme.surfaceVariant)
                            .semantics(mergeDescendants = true) {
                                role = Role.Image
                                contentDescription = barDescription
                            },
                    ) {
                        if (shares != null) {
                            if (shares.models > 0f) {
                                Box(Modifier.weight(shares.models).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                            }
                            if (shares.other > 0f) {
                                Box(Modifier.weight(shares.other).fillMaxHeight().background(MaterialTheme.tensaiTints.outlineStrong))
                            }
                            if (shares.free > 0f) Spacer(Modifier.weight(shares.free))
                        }
                    }
                    if (figures == null) {
                        Text(
                            text = stringResource(R.string.settings_inference_unknown),
                            fontFamily = TensaiMono,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    } else {
                        // A legend like the Context sheet's breakdown: one dot per segment of the bar.
                        val otherBytes = (figures.volumeTotalBytes - figures.modelBytes - figures.freeBytes).coerceAtLeast(0)
                        Column(modifier = Modifier.padding(top = 8.dp).clearAndSetSemantics {}) {
                            StorageLegendRow(
                                dot = { StorageDot(MaterialTheme.colorScheme.primary) },
                                label = stringResource(R.string.settings_storage_models),
                                value = formatFileSize(figures.modelBytes),
                            )
                            if (figures.volumeTotalBytes > 0) {
                                StorageLegendRow(
                                    dot = { StorageDot(MaterialTheme.tensaiTints.outlineStrong) },
                                    label = stringResource(R.string.settings_storage_legend_other),
                                    value = formatFileSize(otherBytes),
                                )
                            }
                            StorageLegendRow(
                                dot = { StorageDot(null, ring = freeColor) },
                                label = stringResource(R.string.settings_storage_legend_free),
                                value = formatFileSize(figures.freeBytes),
                                color = if (shares?.lowFree == true) freeColor else null,
                            )
                        }
                    }
                    if (figures != null && figures.volumeTotalBytes > 0) {
                        Text(
                            text = stringResource(R.string.settings_storage_volume_total, formatFileSize(figures.volumeTotalBytes)),
                            fontFamily = TensaiMono,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clearAndSetSemantics {},
                        )
                    }
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
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
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
internal fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
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
internal fun RowDivider() {
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

/** One line of the storage legend: dot, label, size in mono on the right, like the Context sheet's parts. */
@Composable
private fun StorageLegendRow(dot: @Composable () -> Unit, label: String, value: String, color: Color? = null) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp)) {
        dot()
        Text(
            text = label,
            fontSize = 13.sp,
            color = color ?: MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(start = 10.dp),
        )
        Text(
            text = value,
            fontFamily = TensaiMono,
            fontSize = 12.sp,
            color = color ?: MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
        )
    }
}

/** 8dp: filled with [fill], or an open ring in [ring] for the free space (the bar's empty track). */
@Composable
private fun StorageDot(fill: Color?, ring: Color = MaterialTheme.tensaiTints.outlineStrong) {
    val modifier = Modifier.size(8.dp).clip(CircleShape)
    if (fill != null) Box(modifier.background(fill)) else Box(modifier.border(1.5.dp, ring, CircleShape))
}

/**
 * The kit's `Row`: 22dp leading icon, 15sp medium title, optional 12.5sp supporting line, trailing
 * slot. Tappable only when [onClick] is set, so read-only facts do not look interactive.
 */
@Composable
internal fun SettingsRow(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    supportingAnnotated: AnnotatedString? = null,
    onClick: (() -> Unit)? = null,
    stateDescription: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .then(if (stateDescription != null) Modifier.semantics { this.stateDescription = stateDescription } else Modifier)
            .padding(horizontal = 16.dp, vertical = 13.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
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
                if (supportingAnnotated != null) {
                    Text(
                        text = supportingAnnotated,
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
        if (content != null) Box(modifier = Modifier.padding(start = 36.dp, top = 10.dp)) { content() }
    }
}

@Composable
internal fun ValueText(text: String) {
    Text(text = text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
}

@Composable
internal fun MonoText(text: String) {
    Text(
        text = text,
        fontFamily = TensaiMono,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End,
    )
}

@Composable
internal fun Chevron(rotation: Float = 0f) {
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

internal val BackendPreference.titleRes: Int
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
