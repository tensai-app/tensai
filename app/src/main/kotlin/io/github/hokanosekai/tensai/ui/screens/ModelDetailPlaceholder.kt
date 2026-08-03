package io.github.hokanosekai.tensai.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Model detail placeholder — specs, quantization, download/delete actions land with TEN-28. */
@Composable
fun ModelDetailPlaceholder(modelId: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "Model: $modelId", style = MaterialTheme.typography.titleMedium)
    }
}
