package io.github.hokanosekai.tensai.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Settings placeholder — theme, model defaults, server config land with later issues. */
@Composable
fun SettingsScreenPlaceholder(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "Settings", style = MaterialTheme.typography.titleMedium)
    }
}
