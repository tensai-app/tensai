package io.github.tensaiapp.ui.screens.models

import io.github.tensaiapp.engine.InferenceEngine
import io.github.tensaiapp.engine.LoadedModel
import io.github.tensaiapp.engine.LoadedModelInfo
import io.github.tensaiapp.engine.ModelLoadConfig
import io.github.tensaiapp.runtime.InferenceQueue
import io.github.tensaiapp.runtime.QueueState
import io.github.tensaiapp.runtime.residentModel
import io.github.tensaiapp.runtime.residentModelId
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** TEN-107: a loaded card whose file is gone offers Unload (Remove is a dead end), and it unloads. */
class ModelCardActionTest {

    @Test
    fun loadedAndMissingOffersUnloadNotRemove() {
        assertEquals(MissingFileAction.Unload, missingFileAction(isLoaded = true))
    }

    @Test
    fun notLoadedAndMissingStillOffersRemove() {
        assertEquals(MissingFileAction.Remove, missingFileAction(isLoaded = false))
    }

    @Test
    fun unloadActionReleasesTheResidentModelThroughTheQueue() = runBlocking {
        val engine = FakeEngine()
        val queue = InferenceQueue(engine)
        withTimeout(5_000) { queue.loadModel(ModelLoadConfig(path = "/gone.gguf", modelId = "m1")) }
        assertEquals("m1", queue.residentModelId)

        withTimeout(5_000) { unloadResidentModel(queue) }

        assertEquals(1, engine.frees)
        assertEquals(true, queue.state.value is QueueState.Idle)
        // The card now reads not-loaded + missing, so it falls back to Remove.
        assertNull(queue.state.value.residentModel)
        assertEquals(MissingFileAction.Remove, missingFileAction(isLoaded = queue.state.value.residentModel != null))
    }

    private class FakeEngine : InferenceEngine {
        var frees = 0

        override suspend fun loadModel(config: ModelLoadConfig): LoadedModel = object : LoadedModel {
            override val metadata = LoadedModelInfo(
                name = "n",
                nCtxEffective = config.nCtx,
                backend = "CPU",
                kvCacheType = config.kvCacheType,
                modelId = config.modelId,
            )

            override suspend fun decode(request: io.github.tensaiapp.engine.DecodeRequest) =
                kotlinx.coroutines.flow.emptyFlow<io.github.tensaiapp.engine.DecodeEvent>()

            override suspend fun tokenize(text: String): Int = 0

            override suspend fun free() {
                frees++
            }
        }
    }
}
