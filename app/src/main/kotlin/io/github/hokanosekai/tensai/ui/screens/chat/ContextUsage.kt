package io.github.hokanosekai.tensai.ui.screens.chat

import java.util.Locale
import kotlin.math.roundToInt

/**
 * What the chat header's context ring shows (TEN-160): the loaded model's KV-cache fill, or
 * nothing to fill at all.
 */
sealed interface ContextUsageState {
    /** No model is resident: the ring is a dashed, empty track. */
    data object NoModel : ContextUsageState

    /** [used] tokens of the cache that belong to this conversation, out of a window of [length]. */
    data class Loaded(val used: Int, val length: Int) : ContextUsageState
}

/** Arc color of the context ring, picked by [contextTone]. */
enum class ContextTone { Primary, Warning, Error }

private const val WarningFraction = 0.7f
private const val ErrorFraction = 0.9f

/** [ContextUsageState.Loaded] as a 0..1 fraction of the window, `0` for an empty or unsized one. */
internal fun ContextUsageState.Loaded.fraction(): Float =
    if (length <= 0) 0f else (used.toFloat() / length).coerceIn(0f, 1f)

/** Primary below 70 %, warning up to and including 90 %, error above. */
internal fun contextTone(fraction: Float): ContextTone = when {
    fraction < WarningFraction -> ContextTone.Primary
    fraction <= ErrorFraction -> ContextTone.Warning
    else -> ContextTone.Error
}

/** Whole percent of the window in use, for the content description. */
internal fun contextPercent(used: Int, length: Int): Int =
    if (length <= 0) 0 else (used * 100f / length).roundToInt().coerceIn(0, 100)

/** `512` below a thousand, one decimal of thousands above: `11234` is `11.2k`. */
internal fun formatTokens(tokens: Int): String =
    if (tokens < 1000) tokens.toString() else String.format(Locale.ROOT, "%.1fk", tokens / 1000f)

/** A window size in KiB-tokens, rounded: `32768` is `32k`. Below 1024 the plain number. */
internal fun formatContextLength(length: Int): String =
    if (length < 1024) length.toString() else "${(length / 1024f).roundToInt()}k"

/** What [ChatScreen] hands the shell's top bar to draw the ring with. */
data class ContextRingState(val usage: ContextUsageState = ContextUsageState.NoModel, val busy: Boolean = false)
