package io.github.hokanosekai.tensai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Raw "Calm Night" palette — dark variant. Not consumed directly, see [TensaiTheme]. */
internal object DarkPalette {
    val Background = Color(0xFF0F0E17)
    val Surface = Color(0xFF1A1825)
    val SurfaceVariant = Color(0xFF242231)
    val Primary = Color(0xFFA78BFA)
    val OnPrimary = Color(0xFF1A1040)
    val OnBackground = Color(0xFFE8E6F0)
    val OnSurfaceVariant = Color(0xFF9B97AD)
    val Success = Color(0xFF7DD3A8)
    val Warning = Color(0xFFF0C36D)
    val Error = Color(0xFFE8837F)
}

/** Raw "Calm Night" palette — light variant. Not consumed directly, see [TensaiTheme]. */
internal object LightPalette {
    val Background = Color(0xFFFAF9FE)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceVariant = Color(0xFFEEEBF6)
    val Primary = Color(0xFF6D4FD0)
    val OnPrimary = Color(0xFFFFFFFF)
    val OnBackground = Color(0xFF1C1A26)
    val OnSurfaceVariant = Color(0xFF5E5A70)
    val Success = Color(0xFF3E9A6C)
    val Warning = Color(0xFFA8761A)
    val Error = Color(0xFFC24F4A)
}

/** Color roles that M3's [androidx.compose.material3.ColorScheme] has no slot for. */
data class TensaiColors(
    val success: Color,
    val warning: Color,
)

/** Alpha-derived tints used for state layers, rings and dividers across the app. */
data class TensaiTints(
    val primary08: Color,
    val primary12: Color,
    val primary16: Color,
    val primary24: Color,
    val primaryRing: Color,
    val success12: Color,
    val warning12: Color,
    val error12: Color,
    val outline: Color,
    val outlineStrong: Color,
    val scrim: Color,
)

internal val LocalTensaiColors = staticCompositionLocalOf<TensaiColors> {
    error("No TensaiColors provided — wrap content in TensaiTheme")
}

internal val LocalTensaiTints = staticCompositionLocalOf<TensaiTints> {
    error("No TensaiTints provided — wrap content in TensaiTheme")
}

val MaterialTheme.tensaiColors: TensaiColors
    @Composable get() = LocalTensaiColors.current

val MaterialTheme.tensaiTints: TensaiTints
    @Composable get() = LocalTensaiTints.current
