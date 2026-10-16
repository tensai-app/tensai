package io.github.hokanosekai.tensai.ui.screens.models

import io.github.hokanosekai.tensai.data.session.SessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** TEN-121/122: which action a card offers per state, and which saved sessions belong to a model. */
class ModelActionTest {

    @Test
    fun normalModelOffersLoad() {
        assertEquals(ModelAction.Load, modelAction(isLoaded = false, fileExists = true, wontFit = false))
    }

    @Test
    fun wontFitOffersLoadAnywayUntilLoaded() {
        assertEquals(ModelAction.LoadAnyway, modelAction(isLoaded = false, fileExists = true, wontFit = true))
        assertEquals(ModelAction.Unload, modelAction(isLoaded = true, fileExists = true, wontFit = true))
    }

    @Test
    fun loadedWithFileOffersUnload() {
        assertEquals(ModelAction.Unload, modelAction(isLoaded = true, fileExists = true, wontFit = false))
    }

    @Test
    fun missingFileOverridesTheVerdict() {
        assertEquals(ModelAction.Remove, modelAction(isLoaded = false, fileExists = false, wontFit = true))
        assertEquals(ModelAction.UnloadMissing, modelAction(isLoaded = true, fileExists = false, wontFit = false))
    }

    private fun session(conversation: String, model: String, path: String) = SessionEntity(
        conversationId = conversation, modelId = model, filePath = path, nCtx = 4096, kvCacheType = "F16",
        backend = "Cpu", tokenCount = 1, sizeBytes = 999L, createdAt = 0L, lastAccessedAt = 0L,
    )

    @Test
    fun sessionsAreAttributedByModelIdAndMeasuredOnDisk() {
        val all = listOf(session("c1", "a", "/s/1"), session("c2", "a", "/s/2"), session("c3", "b", "/s/3"))
        val sizes = mapOf("/s/1" to 100L, "/s/2" to 50L, "/s/3" to 7L)
        val result = sessionsOfModel("a", all) { sizes[it] }
        assertEquals(2, result?.count)
        assertEquals(150L, result?.bytes)
    }

    @Test
    fun sessionWhoseFileIsGoneDoesNotCountAndNoneMeansNull() {
        val all = listOf(session("c1", "a", "/s/1"))
        assertNull(sessionsOfModel("a", all) { null })
        assertNull(sessionsOfModel("zzz", all) { 10L })
    }

    @Test
    fun blockedModelOffersADisabledLoadUntilLoadedOrMissing() {
        assertEquals(ModelAction.LoadBlocked, modelAction(isLoaded = false, fileExists = true, wontFit = false, blocked = true))
        assertEquals(ModelAction.Unload, modelAction(isLoaded = true, fileExists = true, wontFit = false, blocked = true))
        assertEquals(ModelAction.Remove, modelAction(isLoaded = false, fileExists = false, wontFit = false, blocked = true))
    }
}
