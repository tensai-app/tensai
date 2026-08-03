package io.github.hokanosekai.tensai.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** STUB model list — three fake entries until the real model repository lands (TEN-28). */
private val stubModelIds = listOf("stub-model-1", "stub-model-2", "stub-model-3")

@Composable
fun ModelsScreenPlaceholder(onModelClick: (String) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(stubModelIds) { modelId ->
            Text(
                text = modelId,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onModelClick(modelId) }
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            )
        }
    }
}
