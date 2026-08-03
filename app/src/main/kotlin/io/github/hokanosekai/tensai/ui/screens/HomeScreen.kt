package io.github.hokanosekai.tensai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import io.github.hokanosekai.tensai.ui.components.KanjiGlyph
import io.github.hokanosekai.tensai.ui.theme.TensaiShapes

/**
 * Home — greeting, glyph watermark, and a non-functional input pill.
 * Real layout/copy refinement lands with TEN-37, real input with the chat feature.
 */
@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize()) {
        KanjiGlyph(
            size = 220.dp,
            modifier = Modifier
                .align(Alignment.Center)
                .alpha(0.08f),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = "Yo.", style = MaterialTheme.typography.headlineLarge)

            Surface(
                shape = TensaiShapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Ask tensai anything…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
        }
    }
}
