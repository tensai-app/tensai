package io.github.tensaiapp.ui.screens.models

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.tensaiapp.R
import io.github.tensaiapp.compat.Compatibility
import io.github.tensaiapp.compat.Verdict
import java.util.Locale
import kotlin.math.roundToInt

/** e.g. 7_000_000_000L -> "7B params", 1_100_000_000L -> "1.1B params". */
@Composable
internal fun formatParamCount(paramCount: Long): String {
    val billions = paramCount / 1_000_000_000.0
    val label = if (billions >= 10 || billions == billions.roundToInt().toDouble()) {
        "${billions.roundToInt()}B"
    } else {
        "%.1fB".format(billions)
    }
    return stringResource(R.string.model_param_count, label)
}

/** e.g. 4_400_000_000L -> "4.4 GB". */
internal fun formatFileSize(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    if (gb >= 0.1) return "%.1f GB".format(gb)
    val mb = bytes / (1024.0 * 1024.0)
    return "%.0f MB".format(mb)
}

/**
 * e.g. 4_400_000_000L -> "4.1 GB". Always GB, never MB: this is the unit the RAM sentence and the
 * storage banner compare two figures in, and a "needs ~4.1 GB, your phone has 812 MB" that switches
 * units halfway is harder to read than a "0.8 GB" that doesn't.
 *
 * [Locale.US] on purpose, matching what `CompatibilityChecker` did before this moved out of it: a
 * fixed "4.1", not "4,1". The surrounding sentence *is* translated (it comes from `strings.xml`);
 * the decimal separator is left alone because these numbers sit next to mono-spaced GGUF metadata
 * that is not localised either.
 */
internal fun formatGigabytes(bytes: Long): String =
    String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))

/** Short verdict label — the words that used to live in `CompatibilityChecker` (TEN-39). */
@Composable
internal fun compatLabel(verdict: Verdict): String = stringResource(
    when (verdict) {
        Verdict.RunsWell -> R.string.model_compat_runs_well
        Verdict.TightFit -> R.string.model_compat_tight_fit
        Verdict.WontFit -> R.string.model_compat_wont_fit
        Verdict.Unsupported -> R.string.model_compat_unsupported
        Verdict.DraftModel -> R.string.model_compat_draft
    },
)

/**
 * The numbers-first justification behind a verdict: "needs ~4.1 GB, your phone has 8.0 GB".
 *
 * The **only** place those two figures are turned into text, and they are read straight off the
 * [Compatibility] that produced the verdict — never recomputed. That is what stops the sentence
 * from drifting away from the traffic light beside it, and it is why `CompatibilityChecker` no
 * longer formats anything: one estimate, one KV-cache formula behind it
 * ([io.github.tensaiapp.data.models.KvCacheSize]), one sentence.
 */
@Composable
internal fun compatExplanation(compatibility: Compatibility): String = stringResource(
    R.string.model_compat_explanation,
    formatGigabytes(compatibility.estimatedBytes),
    formatGigabytes(compatibility.deviceRamBytes),
)

/**
 * Why a [Verdict.isBlocked] model has no Load action: the one sentence cards, the picker and the
 * detail screen share (TEN-167). Takes the [Compatibility] like [compatExplanation], so the
 * architecture named is the one the verdict was decided from.
 */
@Composable
internal fun blockedExplanation(compatibility: Compatibility): String = when (compatibility.verdict) {
    Verdict.DraftModel -> stringResource(R.string.model_draft_explanation)
    else -> stringResource(R.string.model_unsupported_explanation, compatibility.architecture)
}
