package io.github.tensaiapp.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

@Composable
private fun Swatch(name: String, color: Color) {
    Column {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(color, TensaiShapes.medium),
        )
        Text(text = name, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ShapeSample(name: String, shape: androidx.compose.ui.graphics.Shape) {
    Column {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(MaterialTheme.colorScheme.primary, shape),
        )
        Text(text = name, style = MaterialTheme.typography.labelSmall)
    }
}

/** Visual reference for the Calm Night theme — colors, tints, type and shapes. */
@Composable
fun ThemeGallery() {
    val colors = MaterialTheme.colorScheme
    val tensaiColors = MaterialTheme.tensaiColors
    val tints = MaterialTheme.tensaiTints

    Surface(color = colors.background, contentColor = colors.onBackground) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Text(text = "Colors", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Swatch("background", colors.background)
                Swatch("surface", colors.surface)
                Swatch("surfaceVariant", colors.surfaceVariant)
                Swatch("primary", colors.primary)
                Swatch("success", tensaiColors.success)
                Swatch("warning", tensaiColors.warning)
                Swatch("error", colors.error)
            }

            Text(text = "Tints", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Swatch("primary08", tints.primary08)
                Swatch("primary12", tints.primary12)
                Swatch("primary16", tints.primary16)
                Swatch("primary24", tints.primary24)
                Swatch("primaryRing", tints.primaryRing)
                Swatch("outline", tints.outline)
                Swatch("outlineStrong", tints.outlineStrong)
            }

            Text(text = "Typography", style = MaterialTheme.typography.titleMedium)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "Display 天才", style = MaterialTheme.typography.displaySmall)
                Text(text = "Body — the quick brown fox", style = MaterialTheme.typography.bodyLarge)
                Text(text = "12.4 tok/s", style = MaterialTheme.typography.bodyMedium.copy(fontFamily = TensaiMono))
            }

            Text(text = "Shapes", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ShapeSample("extraSmall", TensaiShapes.extraSmall)
                ShapeSample("small", TensaiShapes.small)
                ShapeSample("medium", TensaiShapes.medium)
                ShapeSample("large", TensaiShapes.large)
                ShapeSample("extraLarge", TensaiShapes.extraLarge)
            }
        }
    }
}

@Preview(name = "Calm Night — Dark", showBackground = true)
@Composable
private fun ThemeGalleryDarkPreview() {
    TensaiTheme(themeMode = ThemeMode.Dark) {
        ThemeGallery()
    }
}

@Preview(name = "Calm Night — Light", showBackground = true)
@Composable
private fun ThemeGalleryLightPreview() {
    TensaiTheme(themeMode = ThemeMode.Light) {
        ThemeGallery()
    }
}
