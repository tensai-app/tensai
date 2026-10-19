package io.github.tensaiapp.runtime

import io.github.tensaiapp.data.session.SessionModelKey
import kotlinx.coroutines.flow.Flow

/**
 * TEN-78's third save trigger: **a generation that finishes while the app is in the background.**
 *
 * The two triggers TEN-34 shipped both fire at a moment when the queue is, by construction, not
 * able to save. `MainActivity.onStop` fires the instant the user leaves — which for the single most
 * ordinary thing anyone does with this app (ask for a long answer, put the phone down) is the
 * middle of a decode, and [InferenceQueue.saveSession] declines mid-generation on purpose (see its
 * KDoc: one engine thread, so a dump either queues behind the whole reply while holding the queue's
 * mutex, or interleaves between tokens and freezes half an answer). `onBeforeModelSwap` fires only
 * if a model is swapped or unloaded, which backgrounding does not do. So the reply lands, the KV
 * cache holds the whole conversation, nothing writes it down, and the next cold open re-prefills
 * for 107 s what could have been restored in 4.6 (TEN-63's measurement, cited in
 * `SessionStoragePolicy`'s KDoc).
 *
 * This closes it from the other end: watch [queueState], and when a generation ends back on
 * [QueueState.ModelReady] with nobody looking, save whatever [activeSession] currently points at.
 * Collects forever — the app runs it on `RuntimeGraph.applicationScope`, which nothing cancels.
 *
 * Three deliberate narrownesses:
 * - **Only when backgrounded.** A session file is hundreds of MB (~115 KiB/token for Qwen3-1.7B);
 *   writing one after every reply the user sits and watches would be the dominant cost of using the
 *   app, for a state the existing triggers already save at the moment it matters.
 * - **Only [QueueState.Generating] → [QueueState.ModelReady].** Ending on [QueueState.Idle] means
 *   the model went away, and [InferenceQueue.onBeforeModelSwap] has already saved on the way past —
 *   saving again would only race it.
 * - **Best-effort, like [ActiveSessionTracker] itself.** [queueState] is conflated, so a
 *   generation short enough to begin and end between two emissions is not seen at all. That is the
 *   case where there is least to save and the collector is least likely to be behind; the trade is
 *   the same one the tracker makes, and for the same reason.
 *
 * @param queueState [InferenceQueue.state], as a flow.
 * @param isForeground reads [AppForegroundTracker.isForeground] at the moment the generation ends —
 *   deliberately sampled then rather than collected, because what matters is where the user is when
 *   the reply lands, not where they were when it started.
 * @param activeSession reads [ActiveSessionTracker.current] — `null` when nothing has reported
 *   itself, in which case there is no conversation to attribute the KV cache to and nothing is
 *   saved.
 * @param save does the actual persisting (the app wires `SessionRepository.save` with
 *   `InferenceQueue::saveSession`). Must not throw: this collector has no supervisor of its own and
 *   would stop watching for good.
 */
suspend fun saveSessionAfterBackgroundedGeneration(
    queueState: Flow<QueueState>,
    isForeground: () -> Boolean,
    activeSession: () -> Pair<String, SessionModelKey>?,
    save: suspend (conversationId: String, key: SessionModelKey) -> Unit,
) {
    var wasGenerating = false
    queueState.collect { state ->
        val hadBeenGenerating = wasGenerating
        wasGenerating = state is QueueState.Generating
        if (!hadBeenGenerating || state !is QueueState.ModelReady || isForeground()) return@collect
        val (conversationId, key) = activeSession() ?: return@collect
        save(conversationId, key)
    }
}
