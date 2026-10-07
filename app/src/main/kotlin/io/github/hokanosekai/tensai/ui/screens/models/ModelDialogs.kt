package io.github.hokanosekai.tensai.ui.screens.models

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hokanosekai.tensai.R
import io.github.hokanosekai.tensai.ui.components.TensaiButton
import io.github.hokanosekai.tensai.ui.components.TensaiButtonSize
import io.github.hokanosekai.tensai.ui.components.TensaiButtonVariant
import io.github.hokanosekai.tensai.ui.screens.settings.SettingsRow
import io.github.hokanosekai.tensai.ui.theme.TensaiShapes
import io.github.hokanosekai.tensai.ui.theme.tensaiTints

/**
 * Delete confirmation, shared by the library and the detail screen: centered, radius 28, error
 * delete icon, centered title and body, text buttons Cancel / Delete (error).
 */
@Composable
internal fun DeleteModelDialog(modelName: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        shape = TensaiShapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        icon = { Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(26.dp)) },
        iconContentColor = MaterialTheme.colorScheme.error,
        title = {
            Text(
                text = stringResource(R.string.model_delete_confirm_title, modelName),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        text = {
            Text(
                text = stringResource(R.string.model_delete_confirm_body),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        dismissButton = {
            TensaiButton(
                text = stringResource(R.string.action_cancel),
                onClick = onCancel,
                variant = TensaiButtonVariant.Text,
                size = TensaiButtonSize.Sm,
            )
        },
        confirmButton = {
            TensaiButton(
                text = stringResource(R.string.model_delete_confirm),
                onClick = onConfirm,
                variant = TensaiButtonVariant.Text,
                size = TensaiButtonSize.Sm,
                contentColor = MaterialTheme.colorScheme.error,
            )
        },
    )
}

/**
 * "+" sheet: import from this phone (the existing picker), or Hugging Face, shown disabled with a
 * "Coming soon" badge until that exists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddModelSheet(onDismiss: () -> Unit, onImportFromPhone: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = TensaiShapes.extraLarge.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)),
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        scrimColor = MaterialTheme.tensaiTints.scrim,
    ) {
        Column(modifier = Modifier.navigationBarsPadding().padding(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
            Text(
                text = stringResource(R.string.models_add),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
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
            Box(modifier = Modifier.semantics(mergeDescendants = true) { disabled() }) {
                SettingsRow(
                    icon = Icons.Default.Download,
                    title = stringResource(R.string.models_add_hf_title),
                    supporting = stringResource(R.string.models_add_hf_body),
                    modifier = Modifier.alpha(0.38f),
                )
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.models_coming_soon),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}
