package io.github.hokanosekai.tensai.ui.screens.chat

import io.github.hokanosekai.tensai.data.ContextSetting
import io.github.hokanosekai.tensai.engine.KvCacheType

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

/** Everything the context sheet shows, built once when it opens, see [ChatViewModel.openContextSheet]. */
data class ContextSheetState(val breakdown: ContextBreakdown, val runtime: ContextRuntime)
