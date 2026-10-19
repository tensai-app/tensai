package io.github.tensaiapp.ui.screens.chat

import io.github.tensaiapp.data.ContextSetting
import io.github.tensaiapp.data.chat.ToolActivityRow
import io.github.tensaiapp.engine.KvCacheType

/** The sheet's Runtime card. */
sealed interface ContextRuntime {
    /** No model resident. [modelId] is the one the conversation was started on, what "Load" targets. */
    data class NoModel(val modelId: String?) : ContextRuntime

    /**
     * @param model the model's shown name and quantisation.
     * @param backend the engine's own label, mapped to a hardware line by the screen.
     * @param contextSetting the model's own override, `null` for "follow the global setting": the
     *   screen resolves it against the global one.
     * @param kvCacheBytes expected size of the cache, `null` when the model's row is gone.
     * @param lastReply the last assistant reply's stats, `null` before one was measured.
     */
    data class Loaded(
        val model: String,
        val backend: String,
        val nCtx: Int,
        val contextSetting: ContextSetting?,
        val kvCacheType: KvCacheType,
        val kvCacheBytes: Long?,
        val lastReply: MessageStatsUi?,
    ) : ContextRuntime
}

/** Rows the "Agent activity" table shows before "Show N more". */
internal const val ActivityRowCap = 10

/** The table's rows, the first [ActivityRowCap] unless [showAll]. */
internal fun List<ToolActivityRow>.visibleActivity(showAll: Boolean): List<ToolActivityRow> =
    if (showAll) this else take(ActivityRowCap)

/**
 * Everything the context sheet shows, built once when it opens, see [ChatViewModel.openContextSheet].
 *
 * @param activity per-tool totals of the conversation, most called first, every status counted. Empty
 *   when no tool ran: the "Agent activity" section is then absent (TEN-162).
 */
data class ContextSheetState(
    val breakdown: ContextBreakdown,
    val runtime: ContextRuntime,
    val activity: List<ToolActivityRow> = emptyList(),
)
