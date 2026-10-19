package io.github.tensaiapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color

/** User-facing theme preference. [System] follows the OS day/night setting. */
enum class ThemeMode { System, Dark, Light }

private fun darkScheme() = darkColorScheme(
    primary = DarkPalette.Primary,
    onPrimary = DarkPalette.OnPrimary,
    background = DarkPalette.Background,
    onBackground = DarkPalette.OnBackground,
    surface = DarkPalette.Surface,
    onSurface = DarkPalette.OnBackground,
    surfaceVariant = DarkPalette.SurfaceVariant,
    onSurfaceVariant = DarkPalette.OnSurfaceVariant,
    error = DarkPalette.Error,
    onError = Color(0xFF3A0F0D),
)

private fun lightScheme() = lightColorScheme(
    primary = LightPalette.Primary,
    onPrimary = LightPalette.OnPrimary,
    background = LightPalette.Background,
    onBackground = LightPalette.OnBackground,
    surface = LightPalette.Surface,
    onSurface = LightPalette.OnBackground,
    surfaceVariant = LightPalette.SurfaceVariant,
    onSurfaceVariant = LightPalette.OnSurfaceVariant,
    error = LightPalette.Error,
    onError = Color.White,
)

/**
 * Whether this preference renders the app dark right now. Shared with `MainActivity`, which needs
 * the same answer for the system bars' icon colour (TEN-99) and must not drift from the scheme.
 */
@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Dark -> true
    ThemeMode.Light -> false
}

/**
 * Violet is an accent spice: actions, selection, AI-active states, the brand glyph.
 * Never a large surface fill.
 */
@Composable
fun TensaiTheme(
    themeMode: ThemeMode = ThemeMode.System,
    content: @Composable () -> Unit,
) {
    val isDark = themeMode.isDark()

    val colorScheme = if (isDark) darkScheme() else lightScheme()

    val tensaiColors = if (isDark) {
        TensaiColors(success = DarkPalette.Success, warning = DarkPalette.Warning)
    } else {
        TensaiColors(success = LightPalette.Success, warning = LightPalette.Warning)
    }

    val tensaiTints = TensaiTints(
        primary08 = colorScheme.primary.copy(alpha = 0.08f),
        primary12 = colorScheme.primary.copy(alpha = 0.12f),
        primary16 = colorScheme.primary.copy(alpha = 0.16f),
        primary24 = colorScheme.primary.copy(alpha = 0.24f),
        primaryRing = colorScheme.primary.copy(alpha = if (isDark) 0.45f else 0.40f),
        success12 = tensaiColors.success.copy(alpha = 0.13f),
        warning12 = tensaiColors.warning.copy(alpha = 0.13f),
        error12 = colorScheme.error.copy(alpha = 0.13f),
        outline = colorScheme.onBackground.copy(alpha = 0.10f),
        outlineStrong = colorScheme.onBackground.copy(alpha = if (isDark) 0.18f else 0.16f),
        scrim = if (isDark) Color(0xFF06050C).copy(alpha = 0.62f) else Color(0xFF1C1A26).copy(alpha = 0.38f),
    )

    CompositionLocalProvider(
        LocalTensaiColors provides tensaiColors,
        LocalTensaiTints provides tensaiTints,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = TensaiTypography,
            shapes = TensaiShapes,
            content = content,
        )
    }
}
