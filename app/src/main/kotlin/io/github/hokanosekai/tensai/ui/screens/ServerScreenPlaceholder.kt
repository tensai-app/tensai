package io.github.hokanosekai.tensai.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Server placeholder — remote/OpenAI-compatible endpoint config, marked "V2" in the drawer. */
@Composable
fun ServerScreenPlaceholder(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "Server", style = MaterialTheme.typography.titleMedium)
    }
}
