package io.github.hokanosekai.tensai.ui.screens.loading

import io.github.hokanosekai.tensai.engine.ModelLoadException
import io.github.hokanosekai.tensai.runtime.QueueBusyException
import kotlinx.coroutines.CancellationException

/**
 * Why a load stopped, as much of it as is worth telling the user (TEN-39).
 *
 * The same classify-at-the-boundary rule as
 * [io.github.hokanosekai.tensai.ui.screens.models.ImportFailure], for the same reason: the
 * "Couldn't load this model" dialog used to print `e.message`, and the exceptions reaching it come
 * straight off the JNI boundary — `llama_model_load: error loading model: unable to allocate
 * backend buffer` is a real one. That is a stack-trace sentence, not something a person can act on,
 * and it is what this ticket's acceptance criterion rules out.
 *
 * Out-of-memory is *not* a case here: it is caught separately, before this, and gets its own
 * dialog (TEN-30's [LoadUiState.OomFailed]).
 */
enum class LoadFailure {

    /** The engine refused the file itself — truncated, corrupt, or a GGUF variant it can't read. */
    UnreadableModel,

    /** Something else was already using the queue. Retrying in a moment is a real fix for this one. */
    EngineBusy,

    /** Anything unclassified — carries nothing, on purpose. */
    Unknown,

    ;

    companion object {
        /**
         * Classifies a load failure. Total over every throwable that *is* a failure — with the
         * same one exception [ImportFailure.of][io.github.hokanosekai.tensai.ui.screens.models.ImportFailure.Companion.of]
         * makes, for the same reasons (TEN-73 after TEN-53).
         *
         * A [CancellationException] is re-thrown rather than classified. It would otherwise land
         * on [Unknown] and be swallowed on the way — a cancellation turned into a return value
         * leaves the coroutine carrying it running on as though it had not been cancelled, which
         * is the structured-concurrency contract broken rather than a cosmetic wording problem.
         *
         * Unlike TEN-53's case, this one is not reachable from the app today: every
         * [io.github.hokanosekai.tensai.runtime.InferenceQueue.loadModel] call in the app runs on
         * `RuntimeGraph.applicationScope`, deliberately so (see [LoadModelViewModel]'s KDoc —
         * "Continue in background" is the whole feature), and that scope is never cancelled. This
         * is the backstop for the caller that one day doesn't, since a classifier taking
         * `Throwable` is exactly the shape that invites it.
         */
        fun of(throwable: Throwable): LoadFailure = when (throwable) {
            is CancellationException -> throw throwable
            is ModelLoadException -> UnreadableModel
            is QueueBusyException -> EngineBusy
            else -> Unknown
        }
    }
}
