package io.github.hokanosekai.tensai.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Chat placeholder — the real conversation view (streaming, messages, input) lands with a later issue. */
@Composable
fun ChatScreenPlaceholder(conversationId: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = "Chat: $conversationId", style = MaterialTheme.typography.titleMedium)
    }
}
