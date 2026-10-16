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

    /**
     * llama.cpp does not know the file's architecture (TEN-167). Deterministic: retrying, on CPU or
     * not, fails the same way, so the dialog offers no retry.
     */
    UnsupportedArchitecture,

    /**
     * The file has fewer tensors than its architecture needs: truncated, or a layout this app cannot
     * read (a draft head for speculative decoding is one, but the message does not say so) (TEN-167). Deterministic, like [UnsupportedArchitecture].
     */
    IncompleteModel,

    /** Something else was already using the queue. Retrying in a moment is a real fix for this one. */
    EngineBusy,

    /** Anything unclassified — carries nothing, on purpose. */
    Unknown,

    ;

    /** A retry (same backend or CPU) cannot change the outcome: the file or this build is the problem. */
    val isDeterministic: Boolean get() = this == UnsupportedArchitecture || this == IncompleteModel

    companion object {
        private val UNKNOWN_ARCH = Regex("(?:unknown|unsupported) model architecture: '([^']*)'")

        /** The architecture llama.cpp reported as unknown for [throwable], null for any other failure. */
        fun unknownArchitectureOf(throwable: Throwable): String? =
            (throwable as? ModelLoadException)?.nativeReason?.let { UNKNOWN_ARCH.find(it)?.groupValues?.get(1) }

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
            is ModelLoadException -> classifyNativeReason(throwable.nativeReason)
            is QueueBusyException -> EngineBusy
            else -> Unknown
        }
    }
}

/**
 * The two llama.cpp messages that mean something specific (TEN-167), seen on device: an
 * `unknown model architecture: 'spark2_5'`, and `done_getting_tensors: wrong number of tensors;
 * expected 81, got 58` (a DFlash head). Anything else, or no reason at all, is a plain unreadable file.
 */
internal fun classifyNativeReason(reason: String?): LoadFailure = when {
    reason == null -> LoadFailure.UnreadableModel
    "unknown model architecture" in reason || "unsupported model architecture" in reason -> LoadFailure.UnsupportedArchitecture
    "wrong number of tensors" in reason -> LoadFailure.IncompleteModel
    else -> LoadFailure.UnreadableModel
}
