package io.github.hokanosekai.tensai.ui.components

import android.graphics.Matrix
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.PathParser
import io.github.hokanosekai.tensai.ui.theme.TensaiMotion
import io.github.hokanosekai.tensai.ui.theme.TensaiTheme
import io.github.hokanosekai.tensai.ui.theme.ThemeMode
import io.github.hokanosekai.tensai.ui.theme.tensaiTints

/**
 * Raw path data for the brand glyph (天), extracted from
 * `res/drawable/ic_launcher_foreground.xml` so it can be redrawn and animated without
 * depending on the vector drawable resource at runtime.
 */
private const val GlyphPathData =
    "M861 799Q861 799 870.0 792.5Q879 786 892.5 775.0Q906 764 921.5 751.0Q937 738 950 725Q948 718 " +
        "941.5 714.0Q935 710 925 710H64L57 740H813ZM828 509Q828 509 836.5 502.5Q845 496 859.0 485.0Q873 " +
        "474 887.5 461.5Q902 449 915 436Q911 421 890 421H96L89 451H780ZM528 441Q540 378 567.0 314.0Q594 " +
        "250 644.0 188.5Q694 127 772.0 72.5Q850 18 964 -27L962 -38Q940 -40 925.0 -48.0Q910 -56 904 " +
        "-80Q795 -30 723.5 31.5Q652 93 608.5 161.5Q565 230 542.5 300.5Q520 371 509 437ZM512 739Q511 647 " +
        "507.5 563.0Q504 479 491.5 403.0Q479 327 451.0 259.5Q423 192 373.0 132.5Q323 73 245.5 21.0Q168 " +
        "-31 55 -74L41 -56Q166 0 244.0 64.5Q322 129 364.5 203.5Q407 278 425.0 362.0Q443 446 446.5 " +
        "540.0Q450 634 451 739Z"

// Group transform applied to [GlyphPathData] by the source vector drawable — same
// pivot/scale/translate, replicated here via android.graphics.Matrix so the glyph renders
// identically once redrawn as a Compose Path.
private const val GlyphPivotX = 502.5f
private const val GlyphPivotY = 359.5f
private const val GlyphScaleX = 0.20712604488808262f
private const val GlyphScaleY = -0.20712604488808262f
private const val GlyphTranslateX = -286.5f
private const val GlyphTranslateY = -143.5f

/** Side of the square viewport the transformed path sits in (matches the vector drawable). */
private const val GlyphViewportSize = 432f

private fun buildGlyphPath(): Path {
    val androidPath = PathParser.createPathFromPathData(GlyphPathData)
    val matrix = Matrix().apply {
        postTranslate(-GlyphPivotX, -GlyphPivotY)
        postScale(GlyphScaleX, GlyphScaleY)
        postTranslate(GlyphTranslateX + GlyphPivotX, GlyphTranslateY + GlyphPivotY)
    }
    androidPath.transform(matrix)
    return androidPath.asComposePath()
}

/**
 * The 天 brand glyph, redrawn as a Compose [Path] so it can be tinted, revealed progressively
 * and lit up with a glow — none of which a static vector drawable resource supports.
 *
 * @param progress null draws the glyph in full; 0..1 clips it from the top down, approximating
 * the stroke order of the character.
 * @param glow behind the glyph, a soft halo in [tint] — reserve for AI-active moments.
 */
@Composable
fun KanjiGlyph(
    size: Dp,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    progress: Float? = null,
    glow: Boolean = false,
) {
    val glyphPath = remember { buildGlyphPath() }
    val glowAlpha = MaterialTheme.tensaiTints.primaryRing.alpha

    val context = LocalContext.current
    val animatorDurationScale = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    val animatedProgress by animateFloatAsState(
        targetValue = progress ?: 1f,
        animationSpec = if (animatorDurationScale == 0f) {
            snap()
        } else {
            tween(durationMillis = TensaiMotion.DurFast, easing = TensaiMotion.EasingStandard)
        },
        label = "kanjiGlyphProgress",
    )

    Canvas(
        modifier = modifier
            .size(size)
            .clearAndSetSemantics {},
    ) {
        val scaleFactor = this.size.width / GlyphViewportSize

        if (glow) {
            val glowRadius = this.size.width * 1.4f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(tint.copy(alpha = glowAlpha), tint.copy(alpha = 0f)),
                    center = center,
                    radius = glowRadius,
                ),
                radius = glowRadius,
                center = center,
            )
        }

        scale(scaleFactor, pivot = Offset.Zero) {
            clipRect(
                left = 0f,
                top = 0f,
                right = GlyphViewportSize,
                bottom = GlyphViewportSize * animatedProgress,
            ) {
                drawPath(path = glyphPath, color = tint)
            }
        }
    }
}

@Composable
private fun KanjiGlyphGallery() {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            modifier = Modifier.padding(24.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            KanjiGlyph(size = 64.dp)
            KanjiGlyph(size = 64.dp, progress = 0.6f)
            KanjiGlyph(size = 64.dp, glow = true)
            KanjiGlyph(size = 64.dp, tint = Color.White)
        }
    }
}

@Preview(name = "KanjiGlyph — Dark", showBackground = true)
@Composable
private fun KanjiGlyphPreviewDark() {
    TensaiTheme(themeMode = ThemeMode.Dark) {
        KanjiGlyphGallery()
    }
}

@Preview(name = "KanjiGlyph — Light", showBackground = true)
@Composable
private fun KanjiGlyphPreviewLight() {
    TensaiTheme(themeMode = ThemeMode.Light) {
        KanjiGlyphGallery()
    }
}
