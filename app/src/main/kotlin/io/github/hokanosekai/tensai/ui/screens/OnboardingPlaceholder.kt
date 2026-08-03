package io.github.hokanosekai.tensai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.hokanosekai.tensai.ui.components.KanjiGlyph

/** First-run onboarding. Real flow (model picker, permissions) lands with TEN-28+. */
@Composable
fun OnboardingPlaceholder(onContinue: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        KanjiGlyph(size = 96.dp, glow = true)
        Spacer(modifier = Modifier.height(24.dp))
        Text(text = "Welcome to tensai", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onContinue) {
            Text("Continue")
        }
    }
}
