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

    // ---- storage policy integration ----

    @Test
    fun savingBeyondTheSessionCountCapEvictsTheOldest() = runBlocking {
        val repo = SessionRepository(dao, storageRoot, restoreOnEngine = { true }, maxSessionCount = 2, maxTotalBytes = Long.MAX_VALUE)

        repo.save("c1", key) { path -> File(path).writeText("a"); 1 }
        Thread.sleep(5)
        repo.save("c2", key) { path -> File(path).writeText("b"); 1 }
        Thread.sleep(5)
        repo.save("c3", key) { path -> File(path).writeText("c"); 1 }

        assertNull("oldest conversation should have been evicted", dao.getByConversation("c1"))
        assertTrue(dao.getByConversation("c2") != null)
        assertTrue(dao.getByConversation("c3") != null)
        assertEquals(2, File(storageRoot, "sessions").listFiles()?.size)
    }

    @Test
    fun evictedSessionsHaveTheirFileDeletedFromDisk() = runBlocking {
        val repo = SessionRepository(dao, storageRoot, restoreOnEngine = { true }, maxSessionCount = 1, maxTotalBytes = Long.MAX_VALUE)

        repo.save("c1", key) { path -> File(path).writeText("a"); 1 }
        val evictedPath = dao.getByConversation("c1")!!.filePath
        Thread.sleep(5)
        repo.save("c2", key) { path -> File(path).writeText("b"); 1 }

        assertFalse(File(evictedPath).exists())
    }

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
        repository.save("c1", key) { path -> File(path).writeText("a"); 1 }
        repository.save("c2", key) { path -> File(path).writeText("b"); 1 }
        val paths = dao.rows.values.map { it.filePath }

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
