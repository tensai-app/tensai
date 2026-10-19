package io.github.tensaiapp.ui.screens.chat

import io.github.tensaiapp.compat.Verdict
import io.github.tensaiapp.runtime.QueueState

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
    /** The architecture [verdict] was decided from, named by the explanation of a [blocked] row. */
    val architecture: String = "",
) {
    /** A loaded model never reads as "won't fit": it is already running. */
    val wontFit: Boolean get() = verdict == Verdict.WontFit && !loaded

    /** Unsupported architecture or draft head: it has no "Load anyway", a tap only explains why (TEN-167). */
    val blocked: Boolean get() = verdict?.isBlocked == true && !loaded
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

    /** A model that won't fit, or that is blocked: open or fold its explanation row, nothing is loaded yet. */
    data object ToggleConfirm : PickerTap

    /** Close the sheet and open the load screen for the row. */
    data object Pick : PickerTap
}

fun pickerTap(row: PickerRow, busy: PickerBusy?): PickerTap = when {
    busy != null -> PickerTap.Ignore
    row.loaded -> PickerTap.Close
    row.wontFit || row.blocked -> PickerTap.ToggleConfirm
    else -> PickerTap.Pick
}

/** "Load anyway" on the confirm row: the same pick, unless the picker got busy in the meantime. */
fun pickerLoadAnyway(row: PickerRow, busy: PickerBusy?): PickerTap =
    if (busy != null || !row.wontFit) PickerTap.Ignore else PickerTap.Pick
