package io.github.hokanosekai.tensai.ui.screens

import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.runtime.QueueState
import io.github.hokanosekai.tensai.runtime.RuntimeGraph
import io.github.hokanosekai.tensai.runtime.residentModel
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * Drives Home (TEN-37): the input pill's state, and the one action the screen owns, turning typed
 * text into a new chat to navigate into.
 *
 * Writes nothing (TEN-106). Home used to create the conversation row before navigating; the chat
 * it opens is now a draft whose first send creates the row, and that first send is this very
 * prompt, sent by `ChatScreen` (see `TensaiDestination.Chat`'s `prompt` argument). So a send that
 * never reaches a model (none loaded by the time the chat opens) leaves no empty conversation
 * behind.
 *
 * Takes flows rather than the repositories behind them so a test can hand it a fixed queue state
 * and model list without standing up an engine — see `HomeViewModelTest`.
 */
class HomeViewModel(
    queueState: Flow<QueueState> = RuntimeGraph.inferenceQueue.state,
    models: Flow<List<ModelEntity>> = RuntimeGraph.modelRepository.models,
    fileExists: (String) -> Boolean = { File(it).exists() },
) {

    /** What the pill should render — see [HomePill.state] for the rule. */
    val pillState: Flow<HomePillState> = combine(queueState, models) { state, list ->
        HomePill.state(loadedModelName = state.residentModel?.name, models = list, fileExists = fileExists)
    }

    /**
     * Hands the trimmed [text] to [onStarted], which is where `HomeScreen` clears its input and
     * navigates to a new chat carrying it. Synchronous: a second tap reads the input that first
     * call already cleared, and stops at the blank check. A no-op on blank [text].
     */
    fun start(text: String, onStarted: (prompt: String) -> Unit) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        onStarted(trimmed)
    }
}
