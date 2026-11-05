package io.github.tensaiapp.data.session

import io.github.tensaiapp.engine.KvCacheType
import io.github.tensaiapp.runtime.ActiveSessionTracker
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Fast, JVM-only unit tests for [SessionRepository] — a [FakeSessionDao] stands in for Room (no
 * device needed, same idea as `InferenceQueueTest`'s fake engine), and file I/O runs against a real
 * temp directory under the JVM's own temp dir rather than any Android `filesDir`/`cacheDir` — see
 * `io.github.tensaiapp.data.models.ModelRepository`'s `storageRoot` KDoc for why a real
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
        repository = SessionRepository(dao = dao, storageRoot = storageRoot)
    }

    /**
     * The `restoreOnEngine` for the many tests that are not about what the engine answers — since
     * TEN-81 it is a per-call parameter (see [SessionRepository.restore]'s KDoc for why the caller
     * has to own it), so the tests that only need "it worked" say so once, here.
     */
    private val restored: suspend (String, Boolean) -> SessionRestoreOutcome = { _, _ -> SessionRestoreOutcome.Restored }

    @After
    fun tearDown() {
        storageRoot.deleteRecursively()
    }

    // ---- save ----

    @Test
    fun saveWritesTheFileAtItsFinalPathAndLeavesNoTempFileBehind() = runBlocking {
        val result = repository.save("c1", key) { path -> File(path).writeText("kv-state"); SessionSaveOutcome.Wrote(128) }

        val saved = result as SaveResult.Saved
        assertEquals(128, saved.tokenCount)

        val entity = dao.getByConversation("c1")!!
        val finalFile = File(entity.filePath)
        assertTrue("expected the final file to exist", finalFile.exists())
        assertEquals("kv-state", finalFile.readText())
        assertEquals("expected no leftover .tmp file", listOf(finalFile.name), finalFile.parentFile!!.list()!!.toList())
        assertEquals("model-1", entity.modelId)
        assertEquals(4096, entity.nCtx)
        assertEquals("F16", entity.kvCacheType)
        assertEquals(saved.sizeBytes, entity.sizeBytes)
    }

    @Test
    fun secondSaveForTheSameConversationOverwritesAndPreservesCreatedAt() = runBlocking {
        repository.save("c1", key) { path -> File(path).writeText("first"); SessionSaveOutcome.Wrote(10) }
        val firstCreatedAt = dao.getByConversation("c1")!!.createdAt

        Thread.sleep(5)
        repository.save("c1", key) { path -> File(path).writeText("second"); SessionSaveOutcome.Wrote(20) }

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
        repository.save("c1", key) { path -> File(path).writeText("good"); SessionSaveOutcome.Wrote(10) }

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
        val result = repository.save("c1", key) { path -> File(path).writeText("junk"); SessionSaveOutcome.Wrote(-1) }

        assertTrue(result is SaveResult.Failed)
        assertNull(dao.getByConversation("c1"))
    }

    /**
     * TEN-78. A queue too busy to dump is not a broken engine, and the difference is not cosmetic:
     * `ChatViewModel` reads [SaveResult.Failed] as "the outgoing conversation's live state is not
     * safe" and cancels the incoming conversation's restore over it. Since the busy case is
     * *a reply streaming in* — the app's most ordinary state — that verdict was being handed down
     * constantly, about an engine that had not been consulted.
     */
    @Test
    fun saveReportsNotAttemptedRatherThanFailedWhenTheEngineWasNeverAsked() = runBlocking {
        val result = repository.save("c1", key) { SessionSaveOutcome.NotAttempted }

        assertEquals(SaveResult.NotAttempted, result)
        assertNull(dao.getByConversation("c1"))
        assertTrue(
            "nothing was written, so nothing may be left behind either",
            File(storageRoot, "sessions").listFiles().orEmpty().isEmpty(),
        )
    }

    @Test
    fun aNotAttemptedSaveDoesNotDisturbAPreviousValidSession() = runBlocking {
        repository.save("c1", key) { path -> File(path).writeText("good"); SessionSaveOutcome.Wrote(10) }

        val result = repository.save("c1", key) { SessionSaveOutcome.NotAttempted }

        assertEquals(SaveResult.NotAttempted, result)
        val entity = dao.getByConversation("c1")!!
        assertEquals("good", File(entity.filePath).readText())
        assertEquals(10, entity.tokenCount)
    }

    @Test
    fun saveStillReportsFailedWhenTheEngineWasAskedAndCouldNot() = runBlocking {
        val result = repository.save("c1", key) { path ->
            File(path).writeText("half a dump")
            SessionSaveOutcome.EngineFailed
        }

        assertTrue("an engine that tried and failed is still a failure", result is SaveResult.Failed)
        assertNull(dao.getByConversation("c1"))
        assertTrue(
            "and its partial dump still goes",
            File(storageRoot, "sessions").listFiles().orEmpty().isEmpty(),
        )
    }

    // ---- restore ----

    @Test
    fun restoreReturnsNotFoundWhenNoSessionExists() = runBlocking {
        assertEquals(RestoreResult.NotFound, repository.restore("missing", key, restored))
    }

    @Test
    fun restoreReturnsMismatchAndNeverCallsTheEngineWhenTheModelDiffers() = runBlocking {
        repository.save("c1", key) { path -> File(path).writeText("state"); SessionSaveOutcome.Wrote(10) }
        var engineCalled = false

        val result = repository.restore("c1", key.copy(modelId = "different-model")) { _, _ ->
            engineCalled = true
            SessionRestoreOutcome.Restored
        }

        assertTrue(result is RestoreResult.Mismatch)
        assertFalse("loadSession must never be called on a mismatched key", engineCalled)
        assertTrue("a mismatch must not delete the row", dao.getByConversation("c1") != null)
    }

    @Test
    fun restoreReturnsMismatchWhenNCtxDiffers() = runBlocking {
        repository.save("c1", key) { path -> File(path).writeText("state"); SessionSaveOutcome.Wrote(10) }

        val result = repository.restore("c1", key.copy(nCtx = 2048), restored)

        assertTrue(result is RestoreResult.Mismatch)
    }

    @Test
    fun restoreReturnsMismatchWhenKvCacheTypeDiffers() = runBlocking {
        repository.save("c1", key) { path -> File(path).writeText("state"); SessionSaveOutcome.Wrote(10) }

        val result = repository.restore("c1", key.copy(kvCacheType = KvCacheType.Q8_0), restored)

        assertTrue(result is RestoreResult.Mismatch)
    }

    @Test
    fun restoreReturnsMismatchWhenTheChatTemplateDiffers() = runBlocking {
        repository.save("c1", key) { path -> File(path).writeText("state"); SessionSaveOutcome.Wrote(10) }

        assertTrue(repository.restore("c1", key.copy(chatTemplate = "mistral"), restored) is RestoreResult.Mismatch)
        assertTrue("a mismatch must not delete the row", dao.getByConversation("c1") != null)
    }

    /** TEN-196: a cache saved without the tool prefix says so on restore, and a later save with it says otherwise. */
    @Test
    fun restoreHandsTheEngineWhetherTheSessionWasSavedWithoutTools() = runBlocking {
        val handed = mutableListOf<Boolean>()
        repository.save("c1", key) { path -> File(path).writeText("creative"); SessionSaveOutcome.Wrote(10, withoutTools = true) }
        repository.restore("c1", key) { _, withoutTools -> handed += withoutTools; SessionRestoreOutcome.Restored }
        repository.save("c1", key) { path -> File(path).writeText("tools"); SessionSaveOutcome.Wrote(20) }
        repository.restore("c1", key) { _, withoutTools -> handed += withoutTools; SessionRestoreOutcome.Restored }

        assertEquals(listOf(true, false), handed)
    }

    @Test
    fun restoreReturnsCorruptedAndDeletesTheRowWhenTheFileIsMissing() = runBlocking {
        repository.save("c1", key) { path -> File(path).writeText("state"); SessionSaveOutcome.Wrote(10) }
        File(dao.getByConversation("c1")!!.filePath).delete()

        val result = repository.restore("c1", key, restored)

        assertEquals(RestoreResult.Corrupted, result)
        assertNull(dao.getByConversation("c1"))
    }

    /**
     * The self-heal, still doing its job. TEN-81 narrows *when* it fires, and this is the case it
     * must keep firing on: the engine was asked, looked at the file, and refused. Nothing about
     * that session will improve by keeping it, and keeping it costs one of the two slots
     * [SessionStoragePolicy] allows.
     */
    @Test
    fun restoreReturnsFailedAndSelfHealsWhenTheEngineRefuses() = runBlocking {
        repository.save("c1", key) { path -> File(path).writeText("state"); SessionSaveOutcome.Wrote(10) }
        val entityPath = dao.getByConversation("c1")!!.filePath

        val result = repository.restore("c1", key) { _, _ -> SessionRestoreOutcome.EngineRefused }

        assertEquals(RestoreResult.Failed, result)
        assertNull("a definitively unusable session must be dropped", dao.getByConversation("c1"))
        assertFalse("the unusable file must be cleaned up too", File(entityPath).exists())
    }

    @Test
    fun restoreReturnsFailedRatherThanThrowingWhenTheEngineThrows() = runBlocking {
        repository.save("c1", key) { path -> File(path).writeText("state"); SessionSaveOutcome.Wrote(10) }

        val result = repository.restore("c1", key) { _, _ -> throw RuntimeException("boom") }

        assertEquals(RestoreResult.Failed, result)
    }

    /**
     * TEN-81. The engine was never asked — the queue was mid-load, mid-generation, or holding a
     * different model than the one this session's key was checked against (see
     * [io.github.tensaiapp.runtime.InferenceQueue.restoreSession]). That is a statement
     * about *timing*, and the self-heal above answers statements about the *file*.
     *
     * What used to happen instead: the queue reported the same `false` for both, this fell into
     * the self-heal, and a model load overlapping a restore deleted several hundred MB of valid
     * state worth 107 s of prefill on the measured device. Permanently — the conversation had no
     * session left to restore ever again, and re-prefilled cold every single time.
     */
    @Test
    fun restoreLeavesTheSessionEntirelyAloneWhenTheEngineWasNeverAsked() = runBlocking {
        repository.save("c1", key) { path -> File(path).writeText("state"); SessionSaveOutcome.Wrote(10) }
        val entityPath = dao.getByConversation("c1")!!.filePath

        val result = repository.restore("c1", key) { _, _ -> SessionRestoreOutcome.NotAttempted }

        assertEquals(RestoreResult.NotAttempted, result)
        assertNotNull(
            "a busy queue is no verdict on the session — the row must survive it",
            dao.getByConversation("c1"),
        )
        assertTrue("and so must the file it points at", File(entityPath).exists())
    }

    /**
     * The other half of the same guarantee: an untouched session is still *restorable*. Deleting
     * the row is what made TEN-81 permanent rather than a lost turn, so the test that matters is
     * that the very next attempt works.
     */
    @Test
    fun aSessionLeftAloneByABusyQueueIsStillRestorableOnTheNextAttempt() = runBlocking {
        repository.save("c1", key) { path -> File(path).writeText("state"); SessionSaveOutcome.Wrote(10) }
        repository.restore("c1", key) { _, _ -> SessionRestoreOutcome.NotAttempted }

        val result = repository.restore("c1", key, restored)

        assertEquals(RestoreResult.Restored(10), result)
    }

    @Test
    fun restoreSucceedsAndBumpsLastAccessedAt() = runBlocking {
        repository.save("c1", key) { path -> File(path).writeText("state"); SessionSaveOutcome.Wrote(10) }
        val before = dao.getByConversation("c1")!!.lastAccessedAt
        Thread.sleep(5)

        val result = repository.restore("c1", key, restored)

        assertEquals(RestoreResult.Restored(10), result)
        assertTrue(dao.getByConversation("c1")!!.lastAccessedAt > before)
    }

    // ---- storage policy integration (TEN-62: LRU-2) ----

    @Test
    fun savingASecondConversationKeepsBothSessions() = runBlocking {
        repository.save("c1", key) { path -> File(path).writeText("a"); SessionSaveOutcome.Wrote(1) }
        Thread.sleep(5)
        repository.save("c2", key) { path -> File(path).writeText("b"); SessionSaveOutcome.Wrote(1) }

        assertTrue("two sessions total must both survive under LRU-2", dao.getByConversation("c1") != null)
        assertTrue(dao.getByConversation("c2") != null)
        assertEquals(2, File(storageRoot, "sessions").listFiles()?.size)
    }

    @Test
    fun savingAThirdConversationEvictsOnlyTheLeastRecentlyAccessedOne() = runBlocking {
        repository.save("c1", key) { path -> File(path).writeText("a"); SessionSaveOutcome.Wrote(1) }
        Thread.sleep(5)
        repository.save("c2", key) { path -> File(path).writeText("b"); SessionSaveOutcome.Wrote(1) }
        Thread.sleep(5)
        repository.save("c3", key) { path -> File(path).writeText("c"); SessionSaveOutcome.Wrote(1) }

        assertNull("c1 is neither just-written nor the most recently accessed other row", dao.getByConversation("c1"))
        assertTrue("c2 is the most recently accessed of the two non-just-written rows", dao.getByConversation("c2") != null)
        assertTrue(dao.getByConversation("c3") != null)
        assertEquals(2, File(storageRoot, "sessions").listFiles()?.size)
    }

    @Test
    fun evictedSessionsHaveTheirFileDeletedFromDisk() = runBlocking {
        repository.save("c1", key) { path -> File(path).writeText("a"); SessionSaveOutcome.Wrote(1) }
        val evictedPath = dao.getByConversation("c1")!!.filePath
        Thread.sleep(5)
        repository.save("c2", key) { path -> File(path).writeText("b"); SessionSaveOutcome.Wrote(1) }
        Thread.sleep(5)
        repository.save("c3", key) { path -> File(path).writeText("c"); SessionSaveOutcome.Wrote(1) }

        assertFalse(File(evictedPath).exists())
    }

    @Test
    fun aSaveThenRestoreRoundTripSurvivesTheNewEvictionEvenWithAThirdSessionInPlay() = runBlocking {
        // TEN-62's whole point: saving conversation A must not evict conversation B before B gets a
        // chance to restore. Reproduces the switch sequence ChatViewModel drives (save outgoing,
        // then restore incoming) against a repository that already has a third, older session.
        repository.save("stale", key) { path -> File(path).writeText("old"); SessionSaveOutcome.Wrote(1) }
        Thread.sleep(5)
        repository.save("b", key) { path -> File(path).writeText("b-state"); SessionSaveOutcome.Wrote(5) }
        Thread.sleep(5)

        // "a" is saved (as would happen for the outgoing conversation on a switch)...
        repository.save("a", key) { path -> File(path).writeText("a-state"); SessionSaveOutcome.Wrote(3) }
        // ...and "b" must still be restorable afterwards, i.e. its file must not have been evicted.
        val result = repository.restore("b", key, restored)

        assertEquals(RestoreResult.Restored(5), result)
        assertNull("the third, stale session must be the one evicted, not b", dao.getByConversation("stale"))
    }

    // ---- nothing-to-save (TEN-63: empty KV cache) ----

    @Test
    fun aZeroTokenSaveWritesNoFileAndNoRow() = runBlocking {
        val result = repository.save("c1", key) { path -> File(path).writeText("empty"); SessionSaveOutcome.Wrote(0) }

        assertEquals(SaveResult.NothingToSave, result)
        assertNull(dao.getByConversation("c1"))
        assertTrue("expected no leftover file for a 0-token save", File(storageRoot, "sessions").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aZeroTokenSaveDoesNotDisturbAPreviousValidSession() = runBlocking {
        repository.save("c1", key) { path -> File(path).writeText("good"); SessionSaveOutcome.Wrote(10) }

        val result = repository.save("c1", key) { path -> File(path).writeText("empty"); SessionSaveOutcome.Wrote(0) }

        assertEquals(SaveResult.NothingToSave, result)
        val entity = dao.getByConversation("c1")!!
        assertEquals("good", File(entity.filePath).readText())
        assertEquals(10, entity.tokenCount)
    }

    // ---- unchanged (TEN-63: skip re-saving identical state) ----

    @Test
    fun saveIsSkippedWhenTheLiveTokenCountMatchesWhatIsAlreadyPersisted() = runBlocking {
        var engineCalls = 0
        val repo = SessionRepository(dao, storageRoot, currentTokenCount ={ 10 })
        repo.save("c1", key) { path -> engineCalls++; File(path).writeText("state"); SessionSaveOutcome.Wrote(10) }

        val result = repo.save("c1", key) { path -> engineCalls++; File(path).writeText("state-again"); SessionSaveOutcome.Wrote(10) }

        assertEquals(SaveResult.Unchanged(10, 5L), result)
        assertEquals("saveOnEngine must not be called a second time", 1, engineCalls)
        assertEquals("state", File(dao.getByConversation("c1")!!.filePath).readText())
    }

    @Test
    fun saveProceedsWhenTheLiveTokenCountDiffersFromWhatIsPersisted() = runBlocking {
        var liveTokenCount = 10
        val repo = SessionRepository(dao, storageRoot, currentTokenCount ={ liveTokenCount })
        repo.save("c1", key) { path -> File(path).writeText("first"); SessionSaveOutcome.Wrote(10) }

        liveTokenCount = 13
        val result = repo.save("c1", key) { path -> File(path).writeText("second"); SessionSaveOutcome.Wrote(13) }

        assertTrue(result is SaveResult.Saved)
        assertEquals("second", File(dao.getByConversation("c1")!!.filePath).readText())
    }

    @Test
    fun saveProceedsWhenCurrentTokenCountIsUnavailable() = runBlocking {
        val repo = SessionRepository(dao, storageRoot, currentTokenCount ={ null })
        repo.save("c1", key) { path -> File(path).writeText("first"); SessionSaveOutcome.Wrote(10) }

        val result = repo.save("c1", key) { path -> File(path).writeText("second"); SessionSaveOutcome.Wrote(10) }

        assertTrue("no live signal available must never be treated as 'unchanged'", result is SaveResult.Saved)
        assertEquals("second", File(dao.getByConversation("c1")!!.filePath).readText())
    }

    @Test
    fun saveProceedsWhenTheKeyDiffersEvenIfTheLiveTokenCountMatches() = runBlocking {
        val repo = SessionRepository(dao, storageRoot, currentTokenCount ={ 10 })
        repo.save("c1", key) { path -> File(path).writeText("first"); SessionSaveOutcome.Wrote(10) }

        val result = repo.save("c1", key.copy(nCtx = 2048)) { path -> File(path).writeText("second"); SessionSaveOutcome.Wrote(10) }

        assertTrue("a different key means a different save even at the same token count", result is SaveResult.Saved)
    }

    // ---- prune to storage policy (TEN-62: LRU-2) ----

    @Test
    fun pruneToStoragePolicyIsANoOpWithZeroOneOrTwoSessions() = runBlocking {
        repository.pruneToStoragePolicy() // must not throw with nothing persisted

        repository.save("c1", key) { path -> File(path).writeText("a"); SessionSaveOutcome.Wrote(1) }
        repository.pruneToStoragePolicy()
        assertTrue(dao.getByConversation("c1") != null)

        repository.save("c2", key) { path -> File(path).writeText("b"); SessionSaveOutcome.Wrote(1) }
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

    @Test
    fun pruneToStoragePolicyDeletesTempDumpsLeftByADeadProcess() = runBlocking {
        // Each save names its own temp file (TEN-180), so a dump the process died in is never
        // overwritten by the next one: app start is what takes it.
        repository.save("c1", key) { path -> File(path).writeText("kept"); SessionSaveOutcome.Wrote(1) }
        val sessionsDir = File(storageRoot, "sessions")
        val leftover = File(sessionsDir, "c1.7.llamastate.tmp").apply { writeText("half a dump") }

        repository.pruneToStoragePolicy()

        assertFalse(leftover.exists())
        assertEquals("kept", File(dao.getByConversation("c1")!!.filePath).readText())
    }

    @Test
    fun pruneToStoragePolicyDeletesTempDumpsEvenWithNoRows() = runBlocking {
        val leftover = File(storageRoot, "sessions/c1.1.llamastate.tmp").apply { parentFile!!.mkdirs(); writeText("x") }

        repository.pruneToStoragePolicy()

        assertFalse(leftover.exists())
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
        repository.save("c1", key) { path -> File(path).writeText("a"); SessionSaveOutcome.Wrote(1) }
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

        val report = repository.purgeAll()

        assertTrue(dao.rows.isEmpty())
        paths.forEach { assertFalse(File(it).exists()) }
        assertEquals(2, report.sessionCount)
        assertEquals("state".length.toLong() * 2, report.freedBytes)
    }

    @Test
    fun purgeAllOnAnEmptyTableIsAHarmlessNoOp() = runBlocking {
        val report = repository.purgeAll()

        assertEquals(PurgeReport(sessionCount = 0, orphanFileCount = 0, freedBytes = 0), report)
    }

    @Test
    fun purgeAllBillsARowWhoseFileIsAlreadyGoneAtNothing() = runBlocking {
        dao.upsert(sessionRow("c1", lastAccessedAt = 1)) // row only, no file on disk

        val report = repository.purgeAll()

        assertTrue(dao.rows.isEmpty())
        assertEquals(1, report.sessionCount)
        assertEquals("freedBytes is what came off the disk, not what the table claimed", 0L, report.freedBytes)
    }

    @Test
    fun purgeAllSweepsStateFilesNoRowPointsAt() = runBlocking {
        // What a save killed between its rename and its DB write leaves behind (process death, a
        // kill during onStop's best-effort save). Nothing else in the app cleans these up: every
        // other eviction path walks rows, and there is no row.
        val sessionsDir = File(storageRoot, "sessions").apply { mkdirs() }
        val orphan = File(sessionsDir, "ghost.llamastate").apply { writeText("1234") }
        val orphanTemp = File(sessionsDir, "ghost2.llamastate.tmp").apply { writeText("12") }

        val report = repository.purgeAll()

        assertFalse(orphan.exists())
        assertFalse(orphanTemp.exists())
        assertEquals(0, report.sessionCount)
        assertEquals(2, report.orphanFileCount)
        assertEquals(6L, report.freedBytes)
    }

    @Test
    fun purgeAllLeavesTheDirectoryAndAnythingItDoesNotOwnAlone() = runBlocking {
        val sessionsDir = File(storageRoot, "sessions").apply { mkdirs() }
        val notOurs = File(sessionsDir, "README.txt").apply { writeText("hands off") }

        repository.purgeAll()

        assertTrue("the sessions directory itself must survive", sessionsDir.isDirectory)
        assertTrue("purge matches this class's own file names, it is not a deleteRecursively", notOurs.exists())
    }

    // ---- overlapping saves of one conversation (TEN-180) ----

    @Test
    fun aSaveThatCommitsWhileAnotherIsStillDumpingLeavesTheLaterCommitWholeOnDisk() = runBlocking {
        // `onStop` twice in a row during a long dump: the second save starts and commits while the
        // first is still writing. A shared temp path had the first one's tail land in a fresh file
        // and commit that, a truncated session in place of a good one.
        val result = repository.save("c1", key) { path ->
            File(path).writeText("first-")
            val inner = repository.save("c1", key) { innerPath -> File(innerPath).writeText("second"); SessionSaveOutcome.Wrote(20) }
            assertTrue("precondition: the overlapping save committed", inner is SaveResult.Saved)
            File(path).appendText("whole")
            SessionSaveOutcome.Wrote(10)
        }

        assertTrue(result is SaveResult.Saved)
        val entity = dao.getByConversation("c1")!!
        assertEquals("first-whole", File(entity.filePath).readText())
        assertEquals(10, entity.tokenCount)
        assertEquals(File(entity.filePath).length(), entity.sizeBytes)
        assertEquals("no temp file left behind", listOf("c1.llamastate"), File(storageRoot, "sessions").list()!!.toList())
    }

    @Test
    fun aSaveThatFailsWhileAnotherIsDumpingDoesNotDeleteTheOthersTempFile() = runBlocking {
        // A failed save cleans up its own temp file, and only its own: with one shared path it took
        // the other save's finished dump with it, and that one reported "wrote no file", which a
        // conversation switch reads as a reason to cancel its restore.
        val result = repository.save("c1", key) { path ->
            File(path).writeText("good")
            val inner = repository.save("c1", key) { innerPath -> File(innerPath).writeText("half"); SessionSaveOutcome.EngineFailed }
            assertTrue("precondition: the overlapping save failed", inner is SaveResult.Failed)
            SessionSaveOutcome.Wrote(10)
        }

        assertTrue("got $result", result is SaveResult.Saved)
        assertEquals("good", File(dao.getByConversation("c1")!!.filePath).readText())
        assertEquals(listOf("c1.llamastate"), File(storageRoot, "sessions").list()!!.toList())
    }

    @Test
    fun aSaveWhoseConversationNoLongerOwnsTheCacheIsRefusedAndKeepsThePreviousSession() = runBlocking {
        repository.save("c1", key) { path -> File(path).writeText("c1's own"); SessionSaveOutcome.Wrote(10) }

        // A switch restored another conversation's session between the caller reading the owner and
        // the dump: what was written is that conversation's state, under this one's name.
        var owner = "c1"
        val result = repository.save("c1", key, ownsCache = { owner == "c1" }) { path ->
            File(path).writeText("c2's restored state")
            owner = "c2"
            SessionSaveOutcome.Wrote(30)
        }

        assertEquals(SaveResult.OwnerChanged, result)
        val entity = dao.getByConversation("c1")!!
        assertEquals("c1's own", File(entity.filePath).readText())
        assertEquals(10, entity.tokenCount)
        assertEquals(listOf("c1.llamastate"), File(storageRoot, "sessions").list()!!.toList())
    }

    @Test
    fun aSaveWhoseOwnerLeftAndCameBackDuringTheDumpIsRefused() = runBlocking {
        repository.save("c1", key) { path -> File(path).writeText("c1's own"); SessionSaveOutcome.Wrote(10) }
        ActiveSessionTracker.clear()
        ActiveSessionTracker.report("c1", key)
        val active = ActiveSessionTracker.current.value

        // `onStop` read c1. Its dump runs after a switch restored c2's session, then the user goes back to c1,
        // whose restore finds nothing to load and leaves c2's state in the cache: c1 is reported again.
        val result = try {
            repository.save("c1", key, ownsCache = { ActiveSessionTracker.current.value == active }) { path ->
                ActiveSessionTracker.clearIf("c1")
                File(path).writeText("c2's restored state")
                ActiveSessionTracker.report("c2", key)
                ActiveSessionTracker.clearIf("c2")
                ActiveSessionTracker.report("c1", key)
                SessionSaveOutcome.Wrote(30)
            }
        } finally {
            ActiveSessionTracker.clear()
        }

        assertEquals(SaveResult.OwnerChanged, result)
        assertEquals("c1's own", File(dao.getByConversation("c1")!!.filePath).readText())
    }

    @Test
    fun aSaveCancelledWhileWaitingToCommitTakesItsTempFileWithIt() = runBlocking {
        // Another mutation holds the commit lock; the save has dumped and waits for it, then is
        // cancelled. Its temp name is its own, so nothing else would ever remove that file.
        val dumping = CompletableDeferred<Unit>()
        val dumpDone = CompletableDeferred<Unit>()
        val save = launch {
            repository.save("c1", key) { path ->
                File(path).writeText("kv-state")
                dumping.complete(Unit)
                dumpDone.await()
                SessionSaveOutcome.Wrote(10)
            }
        }
        dumping.await()
        val holding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        dao.beforeAllSessions = { holding.complete(Unit); release.await() }
        val holder = launch { repository.deleteForModel("another-model") }
        holding.await()
        dumpDone.complete(Unit)
        yield()

        save.cancelAndJoin()
        dao.beforeAllSessions = null
        release.complete(Unit)
        holder.join()

        assertNull(dao.getByConversation("c1"))
        assertEquals(emptyList<String>(), File(storageRoot, "sessions").list()!!.toList())
    }

    // ---- purgeAll racing a save (TEN-38) ----

    @Test
    fun aSaveThatWasAlreadyRunningWhenTheUserClearedIsDiscardedRatherThanCommitted() = runBlocking {
        // The exact interleaving Settings > Clear can hit: the app is backgrounding (or swapping
        // models), a multi-hundred-MB state dump is in flight, and the user taps Clear. The dump
        // is allowed to finish — it is not cancellable — but its row and file must not appear
        // afterwards, or the storage the user just reclaimed silently comes back.
        val result = repository.save("c1", key) { path ->
            File(path).writeText("kv-state")
            repository.purgeAll()
            SessionSaveOutcome.Wrote(128)
        }

        assertEquals(SaveResult.Discarded, result)
        assertNull("no row may be written after the purge", dao.getByConversation("c1"))
        assertEquals(
            "and no file either — an orphan here is a few hundred MB no eviction path would " +
                "ever find again",
            emptyList<String>(),
            File(storageRoot, "sessions").listFiles().orEmpty().map { it.name },
        )
    }

    @Test
    fun aSaveThatFinishesBeforeTheClearIsPurgedNormally() = runBlocking {
        // The other order, for contrast: nothing exotic, the save commits and the purge takes it.
        val saved = repository.save("c1", key) { path -> File(path).writeText("kv-state"); SessionSaveOutcome.Wrote(128) }
        assertTrue(saved is SaveResult.Saved)

        val report = repository.purgeAll()

        assertEquals(1, report.sessionCount)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun aClearDuringAnUnchangedSaveStillLeavesNothingBehind() = runBlocking {
        // The fast path that returns without ever calling the engine: it reads the existing row
        // under the same lock the purge commits in, so it can't report "already saved" about a row
        // that is being deleted.
        val repo = SessionRepository(
            dao = dao,
            storageRoot = storageRoot,
            currentTokenCount = { 128 },
        )
        repo.save("c1", key) { path -> File(path).writeText("kv-state"); SessionSaveOutcome.Wrote(128) }
        repo.purgeAll()

        val result = repo.save("c1", key) { path -> File(path).writeText("kv-state"); SessionSaveOutcome.Wrote(128) }

        assertTrue(
            "with the row gone, the same call has to write a real session rather than claim one " +
                "is already there",
            result is SaveResult.Saved,
        )
    }

    @Test
    fun aRestoreInFlightWhenTheUserClearsDoesNotPutItsRowBack() = runBlocking {
        repository.save("c1", key) { path -> File(path).writeText("kv-state"); SessionSaveOutcome.Wrote(128) }

        // The engine is mid-read when the purge lands. The restore itself succeeds — POSIX keeps
        // the open file alive — and the conversation resumes, which is correct: Clear deletes
        // files, it does not reach into RAM.
        val result = repository.restore("c1", key) { _, _ ->
            repository.purgeAll()
            SessionRestoreOutcome.Restored
        }

        assertEquals(RestoreResult.Restored(128), result)
        assertTrue(
            "but its bookkeeping write must not resurrect a row pointing at a file the user just " +
                "deleted",
            dao.rows.isEmpty(),
        )
    }

    // ---- cancellation (TEN-73) ----

    /**
     * A cancelled state dump is not a failed save. [SaveResult.Failed] used to carry the
     * [CancellationException] itself, which is two wrongs at once: a panic reported as a verdict on
     * the engine, and — because the cancellation became a return value — a coroutine that had been
     * cancelled left running as though it had not.
     *
     * The outcome is captured inside the coroutine and asserted *after* [cancelAndJoin]: asserting
     * in there would only ever observe the exception `cancel()` itself throws, never what [save]
     * chose to hand back. Same shape as `ModelImporterTest`'s cancellation tests, for the same
     * reason.
     */
    @Test
    fun cancellingDuringTheStateDumpPropagatesTheCancellationRatherThanReportingAFailedSave() = runBlocking {
        val inDump = CompletableDeferred<Unit>()
        var outcome: Any? = null

        val job = launch(Dispatchers.IO) {
            try {
                outcome = repository.save("c1", key) { path ->
                    // Half a dump on disk before the cancellation lands, so the cleanup below has
                    // something to prove it removed.
                    File(path).writeText("half a state dump")
                    inDump.complete(Unit)
                    awaitCancellation()
                }
            } catch (e: Throwable) {
                outcome = e
                throw e
            }
        }
        inDump.await()
        job.cancelAndJoin()

        assertTrue(
            "a cancelled dump must surface as a cancellation, not as $outcome",
            outcome is CancellationException,
        )
        assertNull("nothing may be committed for a save that never finished", dao.getByConversation("c1"))
        assertEquals(
            "the partial dump goes with the cancellation",
            emptyList<String>(),
            File(storageRoot, "sessions").listFiles().orEmpty().map { it.name },
        )
    }

    /**
     * The same swallow on the restore side, where it also destroyed data: a `false` from
     * `restoreOnEngine` means "this session is unusable", and [SessionRepository.restore] answers
     * it by deleting the state file and its row so a broken session can't occupy the slot forever.
     * Turning a cancellation into that `false` pointed the self-heal at a session that was never
     * shown to be broken.
     */
    @Test
    fun cancellingDuringTheEngineRestoreKeepsTheSessionRatherThanSelfHealingItAway() = runBlocking {
        repository.save("c1", key) { path -> File(path).writeText("kv-state"); SessionSaveOutcome.Wrote(128) }
        val statePath = dao.getByConversation("c1")!!.filePath

        val inRestore = CompletableDeferred<Unit>()
        var outcome: Any? = null

        val job = launch(Dispatchers.IO) {
            try {
                outcome = repository.restore("c1", key) { _, _ ->
                    inRestore.complete(Unit)
                    awaitCancellation()
                }
            } catch (e: Throwable) {
                outcome = e
                throw e
            }
        }
        inRestore.await()
        job.cancelAndJoin()

        assertTrue(
            "a cancelled restore must surface as a cancellation, not as $outcome",
            outcome is CancellationException,
        )
        assertNotNull(
            "a cancellation says nothing about the session, so the row must survive it",
            dao.getByConversation("c1"),
        )
        assertTrue("and so must the file it points at", File(statePath).exists())
    }

    /** In-memory [SessionDao] fake — no Room/Android needed, same convention as `InferenceQueueTest`'s fakes. */
    private class FakeSessionDao : SessionDao {
        val rows = mutableMapOf<String, SessionEntity>()
        private val flow = MutableStateFlow<List<SessionEntity>>(emptyList())

        override suspend fun getByConversation(conversationId: String): SessionEntity? = rows[conversationId]

        override fun observeAll(): Flow<List<SessionEntity>> = flow

        /** Suspends [allSessions], which the repository only calls under its commit lock. */
        var beforeAllSessions: (suspend () -> Unit)? = null

        override suspend fun allSessions(): List<SessionEntity> {
            beforeAllSessions?.invoke()
            return rows.values.toList()
        }

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
