package io.github.hokanosekai.tensai.ui.screens.chat

import io.github.hokanosekai.tensai.data.chat.SamplingRanges

/**
 * Text ↔ value conversions for [ChatSettingsSheet]'s editable fields (TEN-35). Kept out of the
 * composable file and free of any Compose type on purpose: this is the only part of the sheet that
 * can be wrong in a way a human reading it wouldn't notice, and it is the only part a JVM unit test
 * can reach.
 */

/** Renders [sequences] one per line — see [stopSequencesFromText] for the round trip's limits. */
internal fun stopSequencesToText(sequences: List<String>): String = sequences.joinToString("\n")

/**
 * Parses a newline-separated stop-sequence field.
 *
 * Lines are taken **verbatim**, not trimmed: leading and trailing spaces are part of a stop
 * sequence and often the whole point of one (`"\nUser:"` versus `"User:"` behave differently). The
 * consequence, and the format's one real limitation, is that a stop sequence *containing* a newline
 * cannot be written here at all — one line is one sequence, and there is no escape. That is a
 * deliberate trade: the alternative is an escaping scheme in a text field the user has no reason to
 * expect one in, for a case (a multi-line stop sequence) that essentially does not occur.
 *
 * `\r\n` is normalized away — some soft keyboards emit it, and a trailing `\r` would otherwise
 * become a silent part of every sequence, matching nothing.
 *
 * Empty lines are dropped rather than kept as empty sequences: an empty stop sequence matches
 * immediately and everywhere, so persisting one would end every generation at its first token. The
 * list is capped at [SamplingRanges.MaxStopSequences], the same cap the decode applies, so the
 * field can never save more than it will read back.
 */
internal fun stopSequencesFromText(text: String): List<String> =
    text.split('\n')
        .map { it.removeSuffix("\r") }
        .filter { it.isNotEmpty() }
        .take(SamplingRanges.MaxStopSequences)

/**
 * Parses a mono numeric field into an [Int] within [range], or `null` when the field doesn't hold
 * one — mid-edit (empty, a lone `-`) or simply out of range. `null` means "don't commit this yet",
 * not "reset to default": the field keeps whatever the user typed and the stored value is left
 * alone until the text becomes valid again.
 */
internal fun parseIntField(text: String, range: IntRange): Int? =
    text.trim().toIntOrNull()?.takeIf { it in range }

/**
 * [parseIntField]'s float counterpart. Only `.` is accepted as the decimal separator, matching what
 * [formatFloatField] writes — parsing the device locale's separator here would mean a field that
 * shows `0.80` and refuses `0,80` on a French keyboard, or vice versa, depending on which side
 * guessed the locale.
 */
internal fun parseFloatField(text: String, range: ClosedFloatingPointRange<Float>): Float? =
    text.trim().toFloatOrNull()?.takeIf { !it.isNaN() && it in range }

/** Renders [value] with [decimals] digits, locale-independently — see [parseFloatField]. */
internal fun formatFloatField(value: Float, decimals: Int = 2): String =
    String.format(java.util.Locale.ROOT, "%.${decimals}f", value)
