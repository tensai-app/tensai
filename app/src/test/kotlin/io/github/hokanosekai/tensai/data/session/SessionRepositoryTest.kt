package io.github.hokanosekai.tensai.data.session

import io.github.hokanosekai.tensai.engine.KvCacheType
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Fast, JVM-only unit tests for [SessionRepository] — a [FakeSessionDao] stands in for Room (no
 * device needed, same idea as `InferenceQueueTest`'s fake engine), and file I/O runs against a real
 * temp directory under the JVM's own temp dir rather than any Android `filesDir`/`cacheDir` — see
 * `io.github.hokanosekai.tensai.data.models.ModelRepository`'s `storageRoot` KDoc for why a real
 * app dir must never be touched from a test.
 */
class SessionRepositoryTest {

    private lateinit var storageRoot: File
    private lateinit var dao: FakeSessionDao
    private lateinit var repository: SessionRepository

    private val key = SessionModelKey(modelId = "model-1", nCtx = 4096, kvCacheType = KvCacheType.F16, backend = "CPU")

    @Before
    fun setUp() {
        storageRoot = Files.createTempDirectory("tensai-session-test").toFile()
        dao = FakeSessionDao()
        repository = SessionRepository(dao = dao, storageRoot = storageRoot, restoreOnEngine = { true })
    }

    @After
    fun tearDown() {
        storageRoot.deleteRecursively()
    }

    // ---- save ----

    @Test
    fun saveWritesTheFileAtItsFinalPathAndLeavesNoTempFileBehind() = runBlocking {
        val result = repository.save("c1", key) { path -> File(path).writeText("kv-state"); 128 }

        val saved = result as SaveResult.Saved
        assertEquals(128, saved.tokenCount)

        val entity = dao.getByConversation("c1")!!
        val finalFile = File(entity.filePath)
        assertTrue("expected the final file to exist", finalFile.exists())
        assertEquals("kv-state", finalFile.readText())
        assertFalse("expected no leftover .tmp file", File(finalFile.path + ".tmp").exists())
        assertEquals("model-1", entity.modelId)
        assertEquals(4096, entity.nCtx)
        assertEquals("F16", entity.kvCacheType)
        assertEquals(saved.sizeBytes, entity.sizeBytes)
    }

    @Test
    fun secondSaveForTheSameConversationOverwritesAndPreservesCreatedAt() = runBlocking {
        repository.save("c1", key) { path -> File(path).writeText("first"); 10 }
        val firstCreatedAt = dao.getByConversation("c1")!!.createdAt

        Thread.sleep(5)
        repository.save("c1", key) { path -> File(path).writeText("second"); 20 }

        val entity = dao.getByConversation("c1")!!
        assertEquals("second", File(entity.filePath).readText())
        assertEquals(20, entity.tokenCount)
        assertEquals(firstCreatedAt, entity.createdAt)
        assertEquals(1, dao.rows.size) // still just one row for this conversation, not two.
    }

    @Test
    fun saveFailureLeavesNoRowAndNoTempFile() = runBlocking {
        val result = repository.save("c1", key) { path ->
            File(path).writeText("partial")
            throw RuntimeException("native save blew up")
        }

        assertTrue(result is SaveResult.Failed)
        assertNull(dao.getByConversation("c1"))
        assertTrue("expected the sessions dir to have no leftover files", File(storageRoot, "sessions").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun saveFailureDoesNotDisturbAPreviousValidSession() = runBlocking {
        repository.save("c1", key) { path -> File(path).writeText("good"); 10 }

        repository.save("c1", key) { path ->
            File(path).writeText("partial")
            throw RuntimeException("native save blew up")
        }

        val entity = dao.getByConversation("c1")!!
        assertEquals("good", File(entity.filePath).readText())
        assertEquals(10, entity.tokenCount)
    }

    @Test
    fun saveTreatsANegativeTokenCountAsFailureRatherThanPersistingIt() = runBlocking {
        val result = repository.save("c1", key) { path -> File(path).writeText("junk"); -1 }

        assertTrue(result is SaveResult.Failed)
        assertNull(dao.getByConversation("c1"))
    }

    // ---- restore ----

    @Test
    fun restoreReturnsNotFoundWhenNoSessionExists() = runBlocking {
        assertEquals(RestoreResult.NotFound, repository.restore("missing", key))
    }

    @Test
    fun restoreReturnsMismatchAndNeverCallsTheEngineWhenTheModelDiffers() = runBlocking {
        repository.save("c1", key) { path -> File(path).writeText("state"); 10 }
        var engineCalled = false
        val repo = SessionRepository(dao, storageRoot, restoreOnEngine = { engineCalled = true; true })

        val result = repo.restore("c1", key.copy(modelId = "different-model"))

        assertTrue(result is RestoreResult.Mismatch)
        assertFalse("loadSession must never be called on a mismatched key", engineCalled)
        assertTrue("a mismatch must not delete the row", dao.getByConversation("c1") != null)
    }

    @Test
    fun restoreReturnsMismatchWhenNCtxDiffers() = runBlocking {
        repository.save("c1", key) { path -> File(path).writeText("state"); 10 }

        val result = repository.restore("c1", key.copy(nCtx = 2048))

        assertTrue(result is RestoreResult.Mismatch)
    }

    @Test
    fun restoreReturnsMismatchWhenKvCacheTypeDiffers() = runBlocking {
        repository.save("c1", key) { path -> File(path).writeText("state"); 10 }

        val result = repository.restore("c1", key.copy(kvCacheType = KvCacheType.Q8_0))

        assertTrue(result is RestoreResult.Mismatch)
    }

    @Test
    fun restoreReturnsCorruptedAndDeletesTheRowWhenTheFileIsMissing() = runBlocking {
        repository.save("c1", key) { path -> File(path).writeText("state"); 10 }
        File(dao.getByConversation("c1")!!.filePath).delete()

        val result = repository.restore("c1", key)

        assertEquals(RestoreResult.Corrupted, result)
        assertNull(dao.getByConversation("c1"))
    }

    @Test
    fun restoreReturnsFailedAndSelfHealsWhenTheEngineReportsFalse() = runBlocking {
        repository.save("c1", key) { path -> File(path).writeText("state"); 10 }
        val entityPath = dao.getByConversation("c1")!!.filePath
        val repo = SessionRepository(dao, storageRoot, restoreOnEngine = { false })

        val result = repo.restore("c1", key)

        assertEquals(RestoreResult.Failed, result)
        assertNull("a definitively unusable session must be dropped", dao.getByConversation("c1"))
        assertFalse("the unusable file must be cleaned up too", File(entityPath).exists())
    }

    @Test
    fun restoreReturnsFailedRatherThanThrowingWhenTheEngineThrows() = runBlocking {
        repository.save("c1", key) { path -> File(path).writeText("state"); 10 }
        val repo = SessionRepository(dao, storageRoot, restoreOnEngine = { throw RuntimeException("boom") })

        val result = repo.restore("c1", key)

        assertEquals(RestoreResult.Failed, result)
    }

    @Test
    fun restoreSucceedsAndBumpsLastAccessedAt() = runBlocking {
        repository.save("c1", key) { path -> File(path).writeText("state"); 10 }
        val before = dao.getByConversation("c1")!!.lastAccessedAt
        Thread.sleep(5)

        val result = repository.restore("c1", key)

        assertEquals(RestoreResult.Restored(10), result)
        assertTrue(dao.getByConversation("c1")!!.lastAccessedAt > before)
    }

    // ---- storage policy integration (TEN-62: LRU-2) ----

    @Test
    fun savingASecondConversationKeepsBothSessions() = runBlocking {
        repository.save("c1", key) { path -> File(path).writeText("a"); 1 }
        Thread.sleep(5)
        repository.save("c2", key) { path -> File(path).writeText("b"); 1 }

        assertTrue("two sessions total must both survive under LRU-2", dao.getByConversation("c1") != null)
        assertTrue(dao.getByConversation("c2") != null)
        assertEquals(2, File(storageRoot, "sessions").listFiles()?.size)
    }

    @Test
    fun savingAThirdConversationEvictsOnlyTheLeastRecentlyAccessedOne() = runBlocking {
        repository.save("c1", key) { path -> File(path).writeText("a"); 1 }
        Thread.sleep(5)
        repository.save("c2", key) { path -> File(path).writeText("b"); 1 }
        Thread.sleep(5)
        repository.save("c3", key) { path -> File(path).writeText("c"); 1 }

        assertNull("c1 is neither just-written nor the most recently accessed other row", dao.getByConversation("c1"))
        assertTrue("c2 is the most recently accessed of the two non-just-written rows", dao.getByConversation("c2") != null)
        assertTrue(dao.getByConversation("c3") != null)
        assertEquals(2, File(storageRoot, "sessions").listFiles()?.size)
    }

    @Test
    fun evictedSessionsHaveTheirFileDeletedFromDisk() = runBlocking {
        repository.save("c1", key) { path -> File(path).writeText("a"); 1 }
        val evictedPath = dao.getByConversation("c1")!!.filePath
        Thread.sleep(5)
        repository.save("c2", key) { path -> File(path).writeText("b"); 1 }
        Thread.sleep(5)
        repository.save("c3", key) { path -> File(path).writeText("c"); 1 }

        assertFalse(File(evictedPath).exists())
    }

    @Test
    fun aSaveThenRestoreRoundTripSurvivesTheNewEvictionEvenWithAThirdSessionInPlay() = runBlocking {
        // TEN-62's whole point: saving conversation A must not evict conversation B before B gets a
        // chance to restore. Reproduces the switch sequence ChatViewModel drives (save outgoing,
        // then restore incoming) against a repository that already has a third, older session.
        repository.save("stale", key) { path -> File(path).writeText("old"); 1 }
        Thread.sleep(5)
        repository.save("b", key) { path -> File(path).writeText("b-state"); 5 }
        Thread.sleep(5)

        // "a" is saved (as would happen for the outgoing conversation on a switch)...
        repository.save("a", key) { path -> File(path).writeText("a-state"); 3 }
        // ...and "b" must still be restorable afterwards, i.e. its file must not have been evicted.
        val result = repository.restore("b", key)

        assertEquals(RestoreResult.Restored(5), result)
        assertNull("the third, stale session must be the one evicted, not b", dao.getByConversation("stale"))
    }

    // ---- nothing-to-save (TEN-63: empty KV cache) ----

    @Test
    fun aZeroTokenSaveWritesNoFileAndNoRow() = runBlocking {
        val result = repository.save("c1", key) { path -> File(path).writeText("empty"); 0 }

        assertEquals(SaveResult.NothingToSave, result)
        assertNull(dao.getByConversation("c1"))
        assertTrue("expected no leftover file for a 0-token save", File(storageRoot, "sessions").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aZeroTokenSaveDoesNotDisturbAPreviousValidSession() = runBlocking {
        repository.save("c1", key) { path -> File(path).writeText("good"); 10 }

        val result = repository.save("c1", key) { path -> File(path).writeText("empty"); 0 }

        assertEquals(SaveResult.NothingToSave, result)
        val entity = dao.getByConversation("c1")!!
        assertEquals("good", File(entity.filePath).readText())
        assertEquals(10, entity.tokenCount)
    }

    // ---- unchanged (TEN-63: skip re-saving identical state) ----

    @Test
    fun saveIsSkippedWhenTheLiveTokenCountMatchesWhatIsAlreadyPersisted() = runBlocking {
        var engineCalls = 0
        val repo = SessionRepository(dao, storageRoot, restoreOnEngine = { true }, currentTokenCount = { 10 })
        repo.save("c1", key) { path -> engineCalls++; File(path).writeText("state"); 10 }

        val result = repo.save("c1", key) { path -> engineCalls++; File(path).writeText("state-again"); 10 }

        assertEquals(SaveResult.Unchanged(10, 5L), result)
        assertEquals("saveOnEngine must not be called a second time", 1, engineCalls)
        assertEquals("state", File(dao.getByConversation("c1")!!.filePath).readText())
    }

    @Test
    fun saveProceedsWhenTheLiveTokenCountDiffersFromWhatIsPersisted() = runBlocking {
        var liveTokenCount = 10
        val repo = SessionRepository(dao, storageRoot, restoreOnEngine = { true }, currentTokenCount = { liveTokenCount })
        repo.save("c1", key) { path -> File(path).writeText("first"); 10 }

        liveTokenCount = 13
        val result = repo.save("c1", key) { path -> File(path).writeText("second"); 13 }

        assertTrue(result is SaveResult.Saved)
        assertEquals("second", File(dao.getByConversation("c1")!!.filePath).readText())
    }

    @Test
    fun saveProceedsWhenCurrentTokenCountIsUnavailable() = runBlocking {
        val repo = SessionRepository(dao, storageRoot, restoreOnEngine = { true }, currentTokenCount = { null })
        repo.save("c1", key) { path -> File(path).writeText("first"); 10 }

        val result = repo.save("c1", key) { path -> File(path).writeText("second"); 10 }

        assertTrue("no live signal available must never be treated as 'unchanged'", result is SaveResult.Saved)
        assertEquals("second", File(dao.getByConversation("c1")!!.filePath).readText())
    }

    @Test
    fun saveProceedsWhenTheKeyDiffersEvenIfTheLiveTokenCountMatches() = runBlocking {
        val repo = SessionRepository(dao, storageRoot, restoreOnEngine = { true }, currentTokenCount = { 10 })
        repo.save("c1", key) { path -> File(path).writeText("first"); 10 }

        val result = repo.save("c1", key.copy(nCtx = 2048)) { path -> File(path).writeText("second"); 10 }

        assertTrue("a different key means a different save even at the same token count", result is SaveResult.Saved)
    }

    // ---- prune to storage policy (TEN-62: LRU-2) ----

    @Test
    fun pruneToStoragePolicyIsANoOpWithZeroOneOrTwoSessions() = runBlocking {
        repository.pruneToStoragePolicy() // must not throw with nothing persisted

        repository.save("c1", key) { path -> File(path).writeText("a"); 1 }
        repository.pruneToStoragePolicy()
        assertTrue(dao.getByConversation("c1") != null)

        repository.save("c2", key) { path -> File(path).writeText("b"); 1 }
        repository.pruneToStoragePolicy()

        assertTrue(dao.getByConversation("c1") != null)
        assertTrue(dao.getByConversation("c2") != null)
    }

    @Test
    fun pruneToStoragePolicyKeepsTheTwoMostRecentlyAccessedRowsAndDeletesTheRest() = runBlocking {
        dao.upsert(sessionRow("oldest", lastAccessedAt = 1))
        dao.upsert(sessionRow("newer", lastAccessedAt = 2))
        dao.upsert(sessionRow("newest", lastAccessedAt = 3))
        val newestPath = dao.getByConversation("newest")!!.filePath
        File(newestPath).parentFile?.mkdirs()
        File(newestPath).writeText("kept-newest")
        val newerPath = dao.getByConversation("newer")!!.filePath
        File(newerPath).writeText("kept-newer")
        val oldestPath = dao.getByConversation("oldest")!!.filePath
        File(oldestPath).writeText("dropped")

        repository.pruneToStoragePolicy()

        assertNull(dao.getByConversation("oldest"))
        assertFalse(File(oldestPath).exists())
        assertTrue(dao.getByConversation("newer") != null)
        assertTrue(File(newerPath).exists())
        assertTrue(dao.getByConversation("newest") != null)
        assertTrue(File(newestPath).exists())
    }

    private fun sessionRow(conversationId: String, lastAccessedAt: Long) = SessionEntity(
        conversationId = conversationId,
        modelId = key.modelId,
        filePath = File(storageRoot, "sessions/$conversationId.llamastate").absolutePath,
        nCtx = key.nCtx,
        kvCacheType = key.kvCacheType.name,
        backend = key.backend,
        tokenCount = 1,
        sizeBytes = 1L,
        createdAt = lastAccessedAt,
        lastAccessedAt = lastAccessedAt,
    )

    // ---- delete / purgeAll ----

    @Test
    fun deleteRemovesTheFileAndRow() = runBlocking {
        repository.save("c1", key) { path -> File(path).writeText("a"); 1 }
        val path = dao.getByConversation("c1")!!.filePath

        repository.delete("c1")

        assertNull(dao.getByConversation("c1"))
        assertFalse(File(path).exists())
    }

    @Test
    fun deleteIsANoOpForAnUnknownConversation() = runBlocking {
        repository.delete("does-not-exist") // must not throw
    }

    @Test
    fun purgeAllRemovesEveryFileAndRow() = runBlocking {
        // Bypasses save()'s LRU-2 eviction (not what this test is about) — writes two rows
        // directly via the fake dao, same trick as pruneToStoragePolicy's tests above.
        dao.upsert(sessionRow("c1", lastAccessedAt = 1))
        dao.upsert(sessionRow("c2", lastAccessedAt = 2))
        val paths = dao.rows.values.map { it.filePath }
        paths.forEach { File(it).parentFile?.mkdirs(); File(it).writeText("state") }

        repository.purgeAll()

        assertTrue(dao.rows.isEmpty())
        paths.forEach { assertFalse(File(it).exists()) }
    }

    /** In-memory [SessionDao] fake — no Room/Android needed, same convention as `InferenceQueueTest`'s fakes. */
    private class FakeSessionDao : SessionDao {
        val rows = mutableMapOf<String, SessionEntity>()
        private val flow = MutableStateFlow<List<SessionEntity>>(emptyList())

        override suspend fun getByConversation(conversationId: String): SessionEntity? = rows[conversationId]

        override fun observeAll(): Flow<List<SessionEntity>> = flow

        override suspend fun allSessions(): List<SessionEntity> = rows.values.toList()

        override suspend fun upsert(session: SessionEntity) {
            rows[session.conversationId] = session
            flow.value = rows.values.toList()
        }

        override suspend fun delete(conversationId: String) {
            rows.remove(conversationId)
            flow.value = rows.values.toList()
        }

        override suspend fun deleteAll() {
            rows.clear()
            flow.value = emptyList()
        }
    }
}
