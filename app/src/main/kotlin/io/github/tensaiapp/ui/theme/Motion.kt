package io.github.tensaiapp.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing

/** Duration and easing tokens shared by every animation in the app. */
object TensaiMotion {
    const val DurFast = 150
    const val DurBase = 200
    const val DurSlow = 250
    const val DurGlyph = 2400

    val EasingStandard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EasingEmphasizedDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EasingAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
}
