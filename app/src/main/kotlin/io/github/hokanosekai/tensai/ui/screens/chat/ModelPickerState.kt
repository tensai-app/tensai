package io.github.hokanosekai.tensai.ui.screens.chat

import io.github.hokanosekai.tensai.compat.Verdict
import io.github.hokanosekai.tensai.runtime.QueueState

/** Why the picker's rows are locked, which note shows above them. */
enum class PickerBusy { Reply, Loading }

/** The lock for a queue state: a reply generating, or a model loading. `null` when picking is allowed. */
fun pickerBusy(state: QueueState): PickerBusy? = when (state) {
    is QueueState.Generating -> PickerBusy.Reply
    is QueueState.Loading -> PickerBusy.Loading
    QueueState.Idle, is QueueState.ModelReady -> null
}

/**
 * One model on the phone.
 *
 * @param verdict `null` until the context preferences are known: no status is shown from a guess.
 * @param needsBytes what the verdict was measured against, see `Compatibility.estimatedBytes`.
 * @param phoneBytes the phone's total RAM, what [needsBytes] is compared to.
 */
data class PickerRow(
    val id: String,
    val name: String,
    val facts: String?,
    val verdict: Verdict?,
    val loaded: Boolean,
    val needsBytes: Long = 0,
    val phoneBytes: Long = 0,
) {
    /** A loaded model never reads as "won't fit": it is already running. */
    val wontFit: Boolean get() = verdict == Verdict.WontFit && !loaded
}

/** Loaded model first, then runs well, tight fit, won't fit, then by name (stable). */
fun orderPickerRows(rows: List<PickerRow>): List<PickerRow> =
    rows.sortedWith(
        compareByDescending<PickerRow> { it.loaded }
            .thenBy { it.verdict?.ordinal ?: Verdict.entries.size }
            .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
    )

/** What a tap on a row does. */
sealed interface PickerTap {
    /** Busy: the row is inert. */
    data object Ignore : PickerTap

    /** The loaded model: nothing to load, the sheet just closes. */
    data object Close : PickerTap

    /** A model that won't fit: open or fold its "Needs X, phone has Y" row, nothing is loaded yet. */
    data object ToggleConfirm : PickerTap

    /** Close the sheet and open the load screen for the row. */
    data object Pick : PickerTap
}

fun pickerTap(row: PickerRow, busy: PickerBusy?): PickerTap = when {
    busy != null -> PickerTap.Ignore
    row.loaded -> PickerTap.Close
    row.wontFit -> PickerTap.ToggleConfirm
    else -> PickerTap.Pick
}

/** "Load anyway" on the confirm row: the same pick, unless the picker got busy in the meantime. */
fun pickerLoadAnyway(row: PickerRow, busy: PickerBusy?): PickerTap =
    if (busy != null || !row.wontFit) PickerTap.Ignore else PickerTap.Pick
