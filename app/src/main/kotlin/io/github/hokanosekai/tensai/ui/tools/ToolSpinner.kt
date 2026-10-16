package io.github.hokanosekai.tensai.ui.tools

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.hokanosekai.tensai.ui.components.rememberReducedMotion

/** The ring of a running call: a primary arc turning, or a still quarter ring under "remove animations". */
@Composable
fun ToolSpinner(size: Dp = 14.dp, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    if (rememberReducedMotion()) {
        CircularProgressIndicator(progress = { 0.25f }, modifier = modifier.size(size), color = color, trackColor = track, strokeWidth = 2.dp)
    } else {
        CircularProgressIndicator(modifier = modifier.size(size), color = color, trackColor = track, strokeWidth = 2.dp)
    }
}
