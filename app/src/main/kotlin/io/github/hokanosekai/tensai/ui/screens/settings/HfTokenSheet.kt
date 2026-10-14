package io.github.hokanosekai.tensai.ui.screens.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hokanosekai.tensai.R
import io.github.hokanosekai.tensai.ui.components.TensaiBottomSheet
import io.github.hokanosekai.tensai.ui.components.TensaiButton
import io.github.hokanosekai.tensai.ui.components.TensaiButtonVariant
import io.github.hokanosekai.tensai.ui.theme.TensaiMono
import io.github.hokanosekai.tensai.ui.theme.TensaiShapes
import io.github.hokanosekai.tensai.ui.theme.tensaiTints

/**
 * Bottom sheet of the Hugging Face token (TEN-144): paste, Save, Cancel, and Remove when one is set.
 * The field is masked (with a show toggle) and starts empty: a stored token is never put back in
 * it, [currentMask] is only the hint.
 *
 * @param onSave runs the save with the trimmed text; the callback gets the outcome, back on the UI.
 *   On success the caller closes the sheet; on a refusal the sheet stays up with the reason.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HfTokenSheet(
    currentMask: String?,
    onSave: (String, (TokenSaveResult) -> Unit) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    TensaiBottomSheet(
        onDismissRequest = onDismiss,
        shape = TensaiShapes.extraLarge.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)),
        containerColor = MaterialTheme.colorScheme.surface,
        scrimColor = MaterialTheme.tensaiTints.scrim,
        // A field and two buttons: full height would leave it floating on an empty screen.
        fullHeight = false,
    ) {
        var value by remember { mutableStateOf("") }
        var shown by remember { mutableStateOf(false) }
        var focused by remember { mutableStateOf(false) }
        var saving by remember { mutableStateOf(false) }
        var failure by remember { mutableStateOf<TokenSaveResult?>(null) }
        val secondary = MaterialTheme.colorScheme.onSurfaceVariant
        val primary = MaterialTheme.colorScheme.primary

        // A typed token is not for screenshots, screen recording or the recents thumbnail: the sheet's
        // window (and the activity's) is secure while it is open, then restored as it was.
        val view = LocalView.current
        DisposableEffect(view) {
            val windows = listOfNotNull((view.parent as? DialogWindowProvider)?.window, view.context.findActivity()?.window)
                .filter { it.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0 }
            windows.forEach { it.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            onDispose { windows.forEach { it.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) } }
        }
        // Leaving the app hides the token again.
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) shown = false
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        Column(modifier = Modifier.navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
            Text(
                text = stringResource(R.string.hf_token_title),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
            )
            Text(
                text = stringResource(R.string.hf_token_body),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = secondary,
            )
            val fieldLabel = stringResource(R.string.hf_token_field)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = 16.dp)
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
                    .border(1.dp, if (focused) primary else Color.Transparent, RoundedCornerShape(14.dp))
                    .padding(start = 16.dp, end = 4.dp),
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = {
                        value = it
                        failure = null
                    },
                    singleLine = true,
                    enabled = !saving,
                    textStyle = TextStyle(fontFamily = TensaiMono, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(primary),
                    visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = fieldLabel }
                        .onFocusChanged { focused = it.isFocused },
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (value.isEmpty()) {
                                Text(
                                    text = currentMask ?: stringResource(R.string.hf_token_hint),
                                    fontFamily = TensaiMono,
                                    fontSize = 14.sp,
                                    color = secondary,
                                    maxLines = 1,
                                )
                            }
                            inner()
                        }
                    },
                )
                IconButton(onClick = { shown = !shown }) {
                    Icon(
                        imageVector = if (shown) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = stringResource(if (shown) R.string.hf_token_hide else R.string.hf_token_show),
                        tint = secondary,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            val status = when {
                saving -> stringResource(R.string.hf_token_checking) to secondary
                failure == TokenSaveResult.Rejected -> stringResource(R.string.hf_token_rejected) to MaterialTheme.colorScheme.error
                failure == TokenSaveResult.Malformed -> stringResource(R.string.hf_token_malformed) to MaterialTheme.colorScheme.error
                failure == TokenSaveResult.StoreFailed -> stringResource(R.string.hf_token_store_failed) to MaterialTheme.colorScheme.error
                else -> null
            }
            if (status != null) {
                Text(
                    text = status.first,
                    fontSize = 13.sp,
                    color = status.second,
                    modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(top = 16.dp),
            ) {
                if (currentMask != null) {
                    TensaiButton(
                        text = stringResource(R.string.hf_token_remove),
                        onClick = {
                            onRemove()
                            onDismiss()
                        },
                        variant = TensaiButtonVariant.Text,
                        contentColor = MaterialTheme.colorScheme.error,
                        enabled = !saving,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                TensaiButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = onDismiss,
                    variant = TensaiButtonVariant.Text,
                )
                TensaiButton(
                    text = stringResource(R.string.hf_token_save),
                    onClick = {
                        saving = true
                        failure = null
                        onSave(value.trim()) { result ->
                            saving = false
                            if (result == TokenSaveResult.Rejected || result == TokenSaveResult.Malformed || result == TokenSaveResult.StoreFailed) failure = result
                        }
                    },
                    enabled = value.isNotBlank() && !saving,
                )
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
