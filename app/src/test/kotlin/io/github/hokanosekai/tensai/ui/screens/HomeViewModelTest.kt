package io.github.hokanosekai.tensai.ui.screens

import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.engine.KvCacheType
import io.github.hokanosekai.tensai.engine.LoadedModelInfo
import io.github.hokanosekai.tensai.runtime.QueueState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [HomeViewModel]'s two jobs, without a device: what the pill state resolves to once the queue and
 * the model list are wired to it, and what sending from Home actually does before it navigates.
 *
 * The state matrix itself lives in `HomePillTest` — what is proven here is the wiring around it
 * (that a generation still reads as "a model is loaded") and the send path: the trimmed prompt is
 * handed to navigation, and nothing else happens. Home writes no row since TEN-106; the chat it
 * opens is a draft whose first send creates the conversation, which `ChatViewModelTest` covers.
 */
class HomeViewModelTest {

    private fun newViewModel(
        queueState: Flow<QueueState> = flowOf(QueueState.Idle),
        models: Flow<List<ModelEntity>> = flowOf(emptyList()),
        fileExists: (String) -> Boolean = { true },
    ) = HomeViewModel(
        queueState = queueState,
        models = models,
        fileExists = fileExists,
    )

    @Test
    fun anEmptyLibraryAndAnIdleQueueMakeThePillACallToAction() = runBlocking {
        val state = newViewModel().pillState.first()

        assertEquals(HomePillState.NoModel, state)
    }

    @Test
    fun aModelOnDiskButNothingLoadedOffersTheLoad() = runBlocking {
        val state = newViewModel(models = flowOf(listOf(model("a")))).pillState.first()

        assertEquals(HomePillState.LoadNeeded(modelId = "a", displayName = "a"), state)
    }

    @Test
    fun aGenerationStillCountsAsAModelBeingLoaded() = runBlocking {
        // A model stays resident across a generation (see `residentModel`, TEN-46/TEN-58). Reading
        // only ModelReady here would flip Home back to "load a model" for the whole duration of a
        // reply running in some other conversation.
        val state = newViewModel(queueState = flowOf(QueueState.Generating(loadedInfo()))).pillState.first()

        assertEquals(HomePillState.Ready("Qwen3-4B"), state)
    }

    @Test
    fun aLoadInProgressReadsAsNoModelYet() = runBlocking {
        val state = newViewModel(
            queueState = flowOf(QueueState.Loading(progress = 0.4f)),
            models = flowOf(listOf(model("a"))),
        ).pillState.first()

        assertEquals(
            "Loading carries no resident model, so Home offers the load it is already doing — " +
                "transient, and tapping it just returns to the loading screen",
            HomePillState.LoadNeeded(modelId = "a", displayName = "a"),
            state,
        )
    }

    @Test
    fun thePillFollowsTheQueueAsAModelFinishesLoading() = runBlocking {
        val queueState = MutableStateFlow<QueueState>(QueueState.Idle)
        val viewModel = newViewModel(queueState = queueState, models = flowOf(listOf(model("a"))))

        assertEquals(HomePillState.LoadNeeded(modelId = "a", displayName = "a"), viewModel.pillState.first())

        queueState.value = QueueState.ModelReady(loadedInfo())

        assertEquals(HomePillState.Ready("Qwen3-4B"), viewModel.pillState.first())
    }

    @Test
    fun sendingFromHomeHandsTheTrimmedPromptToNavigation() {
        val carried = mutableListOf<String>()

        newViewModel().start("  Summarize a log  ") { prompt -> carried += prompt }

        assertEquals("exactly one navigation, carrying the prompt trimmed", listOf("Summarize a log"), carried)
    }

    @Test
    fun blankTextSendsNothingAndNavigatesNowhere() {
        var carried: String? = null

        newViewModel().start("   \n  ") { prompt -> carried = prompt }

        assertNull("nowhere to navigate to", carried)
    }

    private fun loadedInfo() = LoadedModelInfo(
        name = "Qwen3-4B",
        nCtxEffective = 4096,
        backend = "cpu",
        kvCacheType = KvCacheType.F16,
    )

    private fun model(name: String) = ModelEntity(
        id = name,
        displayName = name,
        fileName = "$name.gguf",
        filePath = "/models/$name.gguf",
        sizeBytes = 1_000_000,
        architecture = "qwen2",
        quantLabel = "Q4_K_M",
        paramCount = 1_500_000_000,
        contextLength = 4096,
        embeddingLength = 1536,
        blockCount = 28,
        supportsThinking = false,
        importedAt = 0L,
    )
}
