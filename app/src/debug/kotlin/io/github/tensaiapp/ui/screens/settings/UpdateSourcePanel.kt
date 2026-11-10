package io.github.tensaiapp.ui.screens.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.tensaiapp.R
import io.github.tensaiapp.runtime.RuntimeGraph
import kotlinx.coroutines.launch

/**
 * Debug builds only (this source set, the release one has an empty stub), under Settings' DEBUG section: the releases-API
 * URL the update check of this build asks (TEN-227), and Check now. Unset, a debug build never checks: it is signed with
 * the debug key, so only a test release built the same way can install over it. Check now still needs the opt-in.
 */
@Composable
internal fun UpdateSourcePanel() {
    val preferences = RuntimeGraph.updatePreferences
    val url by preferences.debugSourceUrl.collectAsState(initial = null)
    var editing by remember { mutableStateOf(false) }

    Spacer(Modifier.height(12.dp))
    SectionCard {
        SettingsRow(
            icon = Icons.Rounded.Link,
            title = stringResource(R.string.debug_update_source),
            supporting = url ?: stringResource(R.string.debug_update_source_unset),
            onClick = { editing = true },
            trailing = { Chevron() },
        )
        RowDivider()
        SettingsRow(
            icon = Icons.Rounded.Refresh,
            title = stringResource(R.string.debug_update_check_now),
            supporting = stringResource(R.string.debug_update_check_now_sub),
            onClick = { RuntimeGraph.applicationScope.launch { runCatching { RuntimeGraph.updateManager.checkIfDue(force = true) } } },
        )
    }

    if (editing) {
        var draft by remember { mutableStateOf(url.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(stringResource(R.string.debug_update_source)) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    placeholder = { Text("https://api.github.com/repos/<owner>/<repo>/releases/latest") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    editing = false
                    RuntimeGraph.applicationScope.launch { preferences.setDebugSourceUrl(draft) }
                }) { Text(stringResource(R.string.debug_update_source_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}
