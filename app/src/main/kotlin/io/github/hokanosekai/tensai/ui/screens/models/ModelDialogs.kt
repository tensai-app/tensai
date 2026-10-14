package io.github.hokanosekai.tensai.ui.screens.models

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import io.github.hokanosekai.tensai.ui.components.TensaiBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hokanosekai.tensai.R
import io.github.hokanosekai.tensai.ui.components.TensaiDialog
import io.github.hokanosekai.tensai.ui.components.TensaiDialogAction
import io.github.hokanosekai.tensai.ui.screens.settings.SettingsRow
import io.github.hokanosekai.tensai.ui.theme.TensaiShapes
import io.github.hokanosekai.tensai.ui.theme.tensaiTints

/**
 * Delete confirmation, shared by the library and the detail screen: centered, radius 28, error
 * delete icon, centered title and body, text buttons Cancel / Delete (error).
 */
@Composable
internal fun DeleteModelDialog(modelName: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    TensaiDialog(
        onDismissRequest = onCancel,
        icon = Icons.Default.Delete,
        iconTint = MaterialTheme.colorScheme.error,
        title = stringResource(R.string.model_delete_confirm_title, modelName),
        body = stringResource(R.string.model_delete_confirm_body),
        actions = listOf(
            TensaiDialogAction(stringResource(R.string.action_cancel), onCancel),
            TensaiDialogAction(stringResource(R.string.model_delete_confirm), onConfirm, destructive = true),
        ),
    )
}

/**
 * "+" sheet (TEN-138): "From Hugging Face" opens the Discover tab on its search field,
 * "From this phone" runs the existing GGUF import.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddModelSheet(onDismiss: () -> Unit, onBrowseHuggingFace: () -> Unit, onImportFromPhone: () -> Unit) {
    TensaiBottomSheet(
        onDismissRequest = onDismiss,
        shape = TensaiShapes.extraLarge.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)),
        containerColor = MaterialTheme.colorScheme.surface,
        scrimColor = MaterialTheme.tensaiTints.scrim,
        // A two-row action sheet: full height would leave it floating on an empty screen.
        fullHeight = false,
    ) {
        Column(modifier = Modifier.navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
            Text(
                text = stringResource(R.string.models_add),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            )
            SettingsRow(
                icon = Icons.Default.TravelExplore,
                title = stringResource(R.string.models_add_hf_title),
                supporting = stringResource(R.string.models_add_hf_body),
                onClick = {
                    onDismiss()
                    onBrowseHuggingFace()
                },
            )
            SettingsRow(
                icon = Icons.Default.FolderOpen,
                title = stringResource(R.string.models_add_phone_title),
                supporting = stringResource(R.string.models_add_phone_body),
                onClick = {
                    onDismiss()
                    onImportFromPhone()
                },
            )
        }
    }
}
