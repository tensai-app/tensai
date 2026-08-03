package io.github.hokanosekai.tensai.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.hokanosekai.tensai.ui.components.KanjiGlyph
import io.github.hokanosekai.tensai.ui.theme.TensaiMotion

/** Loading — fake looping glyph reveal. Real progress wiring lands with the model loader. */
@Composable
fun LoadingScreenPlaceholder(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "loadingGlyph")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = TensaiMotion.DurGlyph, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "loadingGlyphProgress",
    )

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        KanjiGlyph(size = 96.dp, progress = progress, glow = true)
    }
}
