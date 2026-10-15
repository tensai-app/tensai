package io.github.hokanosekai.tensai.ui.screens.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hokanosekai.tensai.R
import io.github.hokanosekai.tensai.compat.CompatibilityChecker
import io.github.hokanosekai.tensai.data.BackendPreference
import io.github.hokanosekai.tensai.runtime.RuntimeGraph
import io.github.hokanosekai.tensai.ui.components.KanjiGlyph
import io.github.hokanosekai.tensai.ui.components.TensaiBottomSheet
import io.github.hokanosekai.tensai.ui.components.TensaiButton
import io.github.hokanosekai.tensai.ui.components.TensaiButtonVariant
import io.github.hokanosekai.tensai.ui.screens.models.StatusIndicator
import io.github.hokanosekai.tensai.ui.screens.models.backendTag
import io.github.hokanosekai.tensai.ui.screens.models.cardFacts
import io.github.hokanosekai.tensai.ui.screens.models.compatLabel
import io.github.hokanosekai.tensai.ui.screens.models.formatGigabytes
import io.github.hokanosekai.tensai.ui.screens.models.monoFigures
import io.github.hokanosekai.tensai.ui.screens.models.verdictColor
import io.github.hokanosekai.tensai.ui.theme.TensaiMono
import io.github.hokanosekai.tensai.ui.theme.tensaiTints

/**
 * The "Select model" sheet behind the input pill's model chip (TEN-163): the models on the phone,
 * loaded one first, then the shortcuts to the Models and Discover screens.
 *
 * Reads the library, the verdicts and the backend itself, like the Models list, so the figures are
 * the ones the cards show. Nothing is loaded here: [onPick] hands the model to the load screen, which owns the steps, the retries and the cancel.
 *
 * @param loadedModelId the resident model's id (not its name: two quantisations share one).
 * @param busy see [pickerBusy]; locks the model rows, not the shortcuts.
 * @param onImport the empty state's "Import a GGUF file".
 */
@Composable
fun ModelPickerSheet(
    loadedModelId: String?,
    busy: PickerBusy?,
    onPick: (modelId: String) -> Unit,
    onManage: () -> Unit,
    onDiscover: () -> Unit,
    onImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val models by RuntimeGraph.modelRepository.models.collectAsState(initial = null)
    val contextPrefs by remember { RuntimeGraph.contextRepository.prefs }.collectAsState(initial = null)
    val backendPreference by RuntimeGraph.backendRepository.preference.collectAsState(initial = null)
    val profile = remember { RuntimeGraph.deviceProfiler.profile() }

    val rows = remember(models, contextPrefs, backendPreference, loadedModelId) {
        val library = models ?: return@remember null
        val prefs = contextPrefs ?: return@remember null
        // Held back until DataStore delivers it, like the Models list: no row shows a guessed backend.
        val backendPref = backendPreference ?: return@remember null
        orderPickerRows(
            library.map { model ->
                val compat = CompatibilityChecker.check(model, profile, prefs.setting, prefs.autoShare)
                PickerRow(
                    id = model.id,
                    name = model.shownName,
                    facts = cardFacts(model.displayQuant, model.sizeBytes, backendTag(compat, backendPref, profile.hasVulkan, profile.hasOpenCl)),
                    verdict = compat.verdict,
                    loaded = model.id == loadedModelId,
                    needsBytes = compat.estimatedBytes,
                    phoneBytes = compat.deviceRamBytes,
                )
            },
        )
    }

    TensaiBottomSheet(
        onDismissRequest = onDismiss,
        shape = SheetShape,
        containerColor = MaterialTheme.colorScheme.background,
        scrimColor = MaterialTheme.tensaiTints.scrim,
        dragHandle = { SheetDragHandle() },
        fullHeight = true,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.picker_close),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                text = stringResource(R.string.picker_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        when {
            rows == null -> Unit // library or preferences still loading.
            rows.isEmpty() -> EmptyPicker(onDiscover = onDiscover, onImport = onImport)
            else -> PickerList(rows, busy, onPick, onManage, onDiscover, onDismiss)
        }
    }
}

@Composable
private fun PickerList(
    rows: List<PickerRow>,
    busy: PickerBusy?,
    onPick: (String) -> Unit,
    onManage: () -> Unit,
    onDiscover: () -> Unit,
    onDismiss: () -> Unit,
) {
    // The row whose "Needs X, phone has Y" is open. One at a time, and gone with the sheet.
    var confirmId by rememberSaveable { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp)
            .navigationBarsPadding()
            .padding(bottom = 28.dp),
    ) {
        if (busy != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.HourglassEmpty,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(if (busy == PickerBusy.Loading) R.string.picker_wait_load else R.string.picker_wait_reply),
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SheetCard {
            Column {
                rows.forEachIndexed { index, row ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.tensaiTints.outline)
                    PickerModelRow(
                        row = row,
                        busy = busy,
                        confirming = confirmId == row.id,
                        onTap = {
                            when (pickerTap(row, busy)) {
                                PickerTap.Ignore -> Unit
                                PickerTap.Close -> onDismiss()
                                PickerTap.ToggleConfirm -> confirmId = if (confirmId == row.id) null else row.id
                                PickerTap.Pick -> onPick(row.id)
                            }
                        },
                        onLoadAnyway = { if (pickerLoadAnyway(row, busy) == PickerTap.Pick) onPick(row.id) },
                    )
                }
            }
        }
        Column(modifier = Modifier.padding(top = 12.dp)) {
            SheetCard {
                Column {
                    ShortcutRow(Icons.AutoMirrored.Filled.ViewList, stringResource(R.string.picker_manage), onManage)
                    HorizontalDivider(color = MaterialTheme.tensaiTints.outline)
                    ShortcutRow(Icons.Filled.TravelExplore, stringResource(R.string.picker_discover), onDiscover)
                }
            }
        }
    }
}

@Composable
private fun PickerModelRow(
    row: PickerRow,
    busy: PickerBusy?,
    confirming: Boolean,
    onTap: () -> Unit,
    onLoadAnyway: () -> Unit,
) {
    val dim = if (row.wontFit) 0.5f else 1f
    Column(modifier = Modifier.alpha(if (busy != null) 0.5f else 1f)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = busy == null, role = Role.Button, onClick = onTap)
                .padding(horizontal = 16.dp, vertical = 13.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = row.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).alpha(dim),
                )
                if (row.loaded) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = stringResource(R.string.picker_loaded),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                } else if (row.verdict != null) {
                    StatusIndicator(color = verdictColor(row.verdict), label = compatLabel(row.verdict))
                }
            }
            row.facts?.let { facts ->
                Text(
                    text = facts,
                    fontFamily = TensaiMono,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp).alpha(dim),
                )
            }
        }
        if (row.wontFit && confirming && busy == null) {
            val needs = formatGigabytes(row.needsBytes)
            val has = formatGigabytes(row.phoneBytes)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(start = 16.dp, end = 10.dp, bottom = 10.dp),
            ) {
                Text(
                    text = monoFigures(stringResource(R.string.model_compat_needs, needs, has), listOf(needs, has)),
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onLoadAnyway) { Text(stringResource(R.string.model_compat_load_anyway)) }
            }
        }
    }
}

@Composable
private fun ShortcutRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(
            text = label,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Icons.Filled.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun EmptyPicker(onDiscover: () -> Unit, onImport: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 48.dp),
    ) {
        KanjiGlyph(size = 96.dp, modifier = Modifier.alpha(0.18f))
        Text(
            text = stringResource(R.string.models_empty_title),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(top = 10.dp),
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 14.dp)) {
            TensaiButton(
                text = stringResource(R.string.picker_discover),
                onClick = onDiscover,
                variant = TensaiButtonVariant.Tonal,
                icon = Icons.Filled.TravelExplore,
            )
            TensaiButton(
                text = stringResource(R.string.models_import),
                onClick = onImport,
                variant = TensaiButtonVariant.Text,
                icon = Icons.Filled.FolderOpen,
            )
        }
    }
}
