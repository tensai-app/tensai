package io.github.tensaiapp.ui.screens.chat

import io.github.tensaiapp.engine.SamplingConfig
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure model behind [ChatSettingsSheet] (TEN-119): presets, max-length chips, value-pill parsing and
 * the "is everything at its default" test. No Compose type in here so a JVM test can reach all of it.
 */

/** The five knobs a response-style preset sets. Max tokens and stop sequences are not part of a style. */
internal enum class ResponseStyle(val temperature: Float, val topP: Float, val minP: Float, val topK: Int, val repeatPenalty: Float) {
    Precise(0.2f, 0.9f, 0.1f, 20, 1.05f),
    Balanced(0.8f, 0.95f, 0.05f, 40, 1.10f),
    Creative(1.0f, 0.95f, 0.05f, 60, 1.10f),
}

/** Max response length chips, in tokens. Any stored value outside this list simply selects no chip. */
internal val MaxTokenChoices = listOf(512, 1024, 2048, 4096, 8192)

/** Half a step of the finest float slider (0.01): absorbs float noise, never merges two real notches. */
private const val FloatTolerance = 0.005f

/** The preset [config]'s five sampling values match, or `null` for a custom mix. */
internal fun detectStyle(config: SamplingConfig): ResponseStyle? =
    ResponseStyle.entries.firstOrNull { it.matches(config) }

private fun ResponseStyle.matches(c: SamplingConfig): Boolean =
    abs(c.temperature - temperature) < FloatTolerance &&
        abs(c.topP - topP) < FloatTolerance &&
        abs(c.minP - minP) < FloatTolerance &&
        c.topK == topK &&
        abs(c.repeatPenalty - repeatPenalty) < FloatTolerance

internal fun SamplingConfig.withStyle(style: ResponseStyle): SamplingConfig =
    copy(temperature = style.temperature, topP = style.topP, minP = style.minP, topK = style.topK, repeatPenalty = style.repeatPenalty)

/**
 * True when the sheet shows nothing but the app's defaults: [SamplingConfig]'s own values (which
 * equal Balanced at 2048 tokens, see the test pinning that), no stop sequence and,
 * when the model can think, thinking on. Thinking is ignored for a model that cannot, since the
 * switch is disabled there and cannot be what makes Reset available.
 */
internal fun isSheetDefault(
    config: SamplingConfig,
    enableThinking: Boolean,
    supportsThinking: Boolean,
): Boolean {
    val d = SamplingConfig()
    return detectStyle(config) == ResponseStyle.Balanced &&
        config.maxTokens == d.maxTokens &&
        config.stopSequences == d.stopSequences &&
        (!supportsThinking || enableThinking == d.enableThinking)
}

/** "512", "1k", "2k"... for the mono chips. */
internal fun maxTokensLabel(n: Int): String = if (n >= 1024 && n % 1024 == 0) "${n / 1024}k" else n.toString()

/**
 * Commits a value pill's text: parsed, clamped into `[min, max]` (the design clamps rather than
 * rejecting) and rounded to [decimals] digits. `null` when the text is not a number (the caller then
 * reverts to the last value). A comma is read as a decimal point: unambiguous in a single-number
 * field, and what a French keyboard types.
 */
internal fun commitPillText(text: String, min: Float, max: Float, decimals: Int): Float? {
    val n = text.trim().replace(',', '.').toFloatOrNull() ?: return null
    if (n.isNaN() || n.isInfinite()) return null
    val clamped = n.coerceIn(min, max)
    var scale = 1f
    repeat(decimals) { scale *= 10f }
    return (clamped * scale).roundToInt() / scale
}

/** Slider position to value: snapped to [step] and held in `[min, max]`. */
internal fun snapToStep(raw: Float, min: Float, max: Float, step: Float): Float {
    val snapped = min + ((raw - min) / step).roundToInt() * step
    return (snapped.coerceIn(min, max) * 10000f).roundToInt() / 10000f
}

/** A max-length chip is offered only when it is below the loaded model's context; no model, no limit. */
internal fun maxTokenFits(tokens: Int, contextSize: Int?): Boolean = contextSize == null || tokens < contextSize

/** The Sampling card's one-line summary: temperature, top P, min P, top K, repeat penalty. */
internal fun samplingSummary(config: SamplingConfig): String =
    "t ${formatFloatField(config.temperature)} · p ${formatFloatField(config.topP)} · " +
        "m ${formatFloatField(config.minP)} · k ${config.topK} · r ${formatFloatField(config.repeatPenalty)}"
