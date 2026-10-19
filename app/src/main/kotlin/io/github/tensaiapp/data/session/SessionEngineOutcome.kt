package io.github.tensaiapp.data.session

/**
 * What became of a request to dump the engine's KV-cache state to a file — the return type of
 * [SessionRepository.save]'s `saveOnEngine`.
 *
 * It exists to say **which** of two unrelated things happened, because
 * [io.github.tensaiapp.runtime.InferenceQueue.saveSession] used to answer both with `-1`
 * (TEN-78): the engine tried and failed, or nobody ever asked it because the queue was busy. The
 * first is a verdict, the second is a coin toss about timing, and [SessionRepository.save] turned
 * both into [SaveResult.Failed] — which `ChatViewModel` reads as "the outgoing state is not safe"
 * and answers by cancelling the incoming restore. A generation in flight was enough to trigger it,
 * which is to say: the commonest state the app is in.
 *
 * Same defect TEN-73 fixed for cancellation, and the same shape of fix: one channel, one message.
 * Lives here rather than in `runtime` so [SessionRepository] stays free of any import from the
 * queue it is deliberately decoupled from (see that class's KDoc) — the dependency points
 * `runtime` → `data.session`, the direction it already points for [SessionModelKey].
 */
sealed class SessionSaveOutcome {
    /**
     * The engine dumped its state. [tokenCount] is what it reported it wrote, `0` for an empty KV
     * cache — see [SaveResult.NothingToSave] for what the repository does with that.
     */
    data class Wrote(val tokenCount: Int) : SessionSaveOutcome()

    /** The engine was asked and could not do it. A verdict: something is wrong with the dump. */
    data object EngineFailed : SessionSaveOutcome()

    /**
     * The engine was never asked — the queue was loading, generating, or holding no model at all.
     * Says nothing about anything: no state was written, and no state was shown to be unwritable.
     */
    data object NotAttempted : SessionSaveOutcome()
}

/**
 * What became of a request to load a session file back onto the engine — the return type of
 * [SessionRepository.restore]'s `restoreOnEngine`.
 *
 * The save side's twin, and the more dangerous of the two (TEN-81): [SessionRepository.restore]
 * answers a refusal by deleting the state file *and* its row, so that a permanently-unusable
 * session cannot occupy this conversation's one slot forever. That self-heal is right for
 * [EngineRefused] and catastrophic for [NotAttempted] — a concurrent `loadModel` was enough to
 * destroy a valid multi-hundred-MB session worth, per TEN-63's measurements, 107 s of prefill.
 */
sealed class SessionRestoreOutcome {
    /** The engine loaded the state. The KV cache now holds what the file held. */
    data object Restored : SessionRestoreOutcome()

    /**
     * The engine was asked and refused (or threw). A verdict on the file: it is not usable against
     * what is currently loaded, and retrying it will not change that. This is the only outcome
     * [SessionRepository.restore] is allowed to self-heal on.
     */
    data object EngineRefused : SessionRestoreOutcome()

    /**
     * The engine was never asked. Either the queue was not in a position to
     * ([io.github.tensaiapp.runtime.QueueState.Loading],
     * [io.github.tensaiapp.runtime.QueueState.Generating],
     * [io.github.tensaiapp.runtime.QueueState.Idle]), or — the variant that made TEN-81
     * worse than data loss — the model resident by the time the lock was taken is no longer the one
     * the caller validated this session against, so handing the file over would have fed one
     * model's KV state to another.
     */
    data object NotAttempted : SessionRestoreOutcome()
}
