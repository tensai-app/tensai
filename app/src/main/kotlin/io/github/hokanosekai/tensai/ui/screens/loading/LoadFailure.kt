package io.github.hokanosekai.tensai.ui.screens.loading

import io.github.hokanosekai.tensai.engine.ModelLoadException
import io.github.hokanosekai.tensai.runtime.QueueBusyException

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
        /** Classifies a load failure. Total: every throwable maps to something. */
        fun of(throwable: Throwable): LoadFailure = when (throwable) {
            is ModelLoadException -> UnreadableModel
            is QueueBusyException -> EngineBusy
            else -> Unknown
        }
    }
}
