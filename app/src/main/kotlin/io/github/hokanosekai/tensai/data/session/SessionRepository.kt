package io.github.hokanosekai.tensai.data.session

import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.Flow

/** Result of [SessionRepository.save]. */
sealed class SaveResult {
    /** The session was persisted; [tokenCount]/[sizeBytes] mirror what was written to [SessionEntity]. */
    data class Saved(val tokenCount: Int, val sizeBytes: Long) : SaveResult()

    /** Nothing was persisted — any previous session for this conversation is untouched. */
    data class Failed(val cause: Throwable) : SaveResult()
}

/** Result of [SessionRepository.restore]. */
sealed class RestoreResult {
    /** No session exists for this conversation — a normal prefill is the only option. */
    data object NotFound : RestoreResult()

    /**
     * A session exists but was saved against a different model/context/KV-cache-type. Left
     * untouched (not deleted): it may become restorable again later (e.g. the user switches back
     * to the original model). [io.github.hokanosekai.tensai.engine.LlamaKtEngine.loadSession] is
     * never called in this case — the whole point of the check.
     */
    data class Mismatch(val expected: SessionModelKey, val actual: SessionEntity) : RestoreResult()

    /** The DB row pointed at a file that no longer exists on disk. The stale row was deleted. */
    data object Corrupted : RestoreResult()

    /**
     * The underlying engine restore itself failed or threw. Treated as self-healing: the file and
     * row are deleted so a permanently-unusable session doesn't keep occupying this conversation's
     * one slot (see [SessionEntity]'s KDoc) or getting retried forever.
     */
    data object Failed : RestoreResult()

    /** Restored successfully; [tokenCount] mirrors [SessionEntity.tokenCount]. */
    data class Restored(val tokenCount: Int) : RestoreResult()
}

/**
 * Owns persisted [SessionEntity] rows and the raw state files backing them (TEN-34) — lets a
 * conversation resume against the engine's KV cache instead of a full re-prefill.
 *
 * Deliberately decoupled from both [io.github.hokanosekai.tensai.engine.LlamaKtEngine] and
 * [io.github.hokanosekai.tensai.runtime.InferenceQueue]: [restoreOnEngine] and [save]'s
 * `saveOnEngine` parameter are plain suspend functions instead, which is what makes this class
 * fully unit-testable with fakes and no Room/Android/native dependency. Callers pass:
 * - `inferenceQueue::restoreSession` for [restoreOnEngine] — restore always happens from outside
 *   any [io.github.hokanosekai.tensai.runtime.InferenceQueue]-held lock, so it must go through that
 *   mutex-protected entry point.
 * - Either `inferenceQueue::saveSession` (a caller outside the queue, e.g. the app backgrounding)
 *   or the raw `LlamaKtEngine::saveSession` (a caller *inside* `onBeforeModelSwap`, which already
 *   runs under the queue's own mutex and would deadlock calling back into it — see that hook's
 *   KDoc) for [save]'s `saveOnEngine` parameter, depending on which situation triggered the save.
 *
 * @param storageRoot root directory state files live under (`storageRoot/sessions/`) — the app
 *   passes `context.filesDir`; tests MUST pass a temp directory (see
 *   `io.github.hokanosekai.tensai.data.models.ModelRepository`'s `storageRoot` for the same
 *   convention and why it matters).
 */
class SessionRepository(
    private val dao: SessionDao,
    storageRoot: File,
    private val restoreOnEngine: suspend (path: String) -> Boolean,
    private val maxTotalBytes: Long = SessionStoragePolicy.DEFAULT_MAX_TOTAL_BYTES,
    private val maxSessionCount: Int = SessionStoragePolicy.DEFAULT_MAX_SESSION_COUNT,
) {

    private val sessionsDir: File = File(storageRoot, "sessions")

    /** Every persisted session, most recently accessed first — for a future Settings > Storage list. */
    fun observeSessions(): Flow<List<SessionEntity>> = dao.observeAll()

    /**
     * Saves the current engine state (via [saveOnEngine]) as [conversationId]'s session, tagged
     * with [key]. Overwrites whatever was previously saved for this conversation.
     *
     * Write-then-rename: [saveOnEngine] is asked to write to a temp path first, and the DB row is
     * only written (and the temp file only renamed into place) once that call has actually
     * succeeded — a failure at any point (the engine call itself, or the rename) leaves any
     * previous session for this conversation exactly as it was, never a half-written file
     * mistaken for a valid one. On success, also runs [SessionStoragePolicy] to evict older
     * sessions if the caps are now exceeded.
     */
    suspend fun save(
        conversationId: String,
        key: SessionModelKey,
        saveOnEngine: suspend (path: String) -> Int,
    ): SaveResult {
        sessionsDir.mkdirs()
        val finalFile = finalPathFor(conversationId)
        val tempFile = tempPathFor(conversationId)

        val tokenCount = try {
            saveOnEngine(tempFile.absolutePath)
        } catch (e: Exception) {
            tempFile.delete()
            return SaveResult.Failed(e)
        }
        if (tokenCount < 0) {
            tempFile.delete()
            return SaveResult.Failed(IllegalStateException("Session save reported failure (returned $tokenCount)"))
        }
        if (!tempFile.exists()) {
            return SaveResult.Failed(IllegalStateException("Session save reported success but wrote no file"))
        }

        if (!moveIntoPlace(tempFile, finalFile)) {
            tempFile.delete()
            return SaveResult.Failed(IOException("Could not move saved session into place at $finalFile"))
        }

        val existing = dao.getByConversation(conversationId)
        val now = System.currentTimeMillis()
        val sizeBytes = finalFile.length()
        dao.upsert(
            SessionEntity(
                conversationId = conversationId,
                modelId = key.modelId,
                filePath = finalFile.absolutePath,
                nCtx = key.nCtx,
                kvCacheType = key.kvCacheType.name,
                backend = key.backend,
                tokenCount = tokenCount,
                sizeBytes = sizeBytes,
                createdAt = existing?.createdAt ?: now,
                lastAccessedAt = now,
            ),
        )
        enforceStoragePolicy(justWritten = conversationId)
        return SaveResult.Saved(tokenCount, sizeBytes)
    }

    /**
     * Restores [conversationId]'s session onto the currently loaded model via [restoreOnEngine],
     * but only if it was saved against a model/context/KV-cache-type matching [key] — see
     * [RestoreResult.Mismatch]'s KDoc for why that check runs *before* ever calling
     * [restoreOnEngine], not after.
     */
    suspend fun restore(conversationId: String, key: SessionModelKey): RestoreResult {
        val entity = dao.getByConversation(conversationId) ?: return RestoreResult.NotFound

        if (entity.modelId != key.modelId || entity.nCtx != key.nCtx || entity.kvCacheType != key.kvCacheType.name) {
            return RestoreResult.Mismatch(key, entity)
        }

        val file = File(entity.filePath)
        if (!file.exists()) {
            dao.delete(conversationId)
            return RestoreResult.Corrupted
        }

        val restored = try {
            restoreOnEngine(entity.filePath)
        } catch (e: Exception) {
            false
        }

        if (!restored) {
            file.delete()
            dao.delete(conversationId)
            return RestoreResult.Failed
        }

        dao.upsert(entity.copy(lastAccessedAt = System.currentTimeMillis()))
        return RestoreResult.Restored(entity.tokenCount)
    }

    /** Deletes [conversationId]'s session (file + row), if any. No-op if it doesn't exist. */
    suspend fun delete(conversationId: String) {
        val entity = dao.getByConversation(conversationId) ?: return
        File(entity.filePath).delete()
        dao.delete(conversationId)
    }

    /** Deletes every persisted session (file + row). */
    suspend fun purgeAll() {
        dao.allSessions().forEach { File(it.filePath).delete() }
        dao.deleteAll()
    }

    private suspend fun enforceStoragePolicy(justWritten: String) {
        val all = dao.allSessions()
        val toEvict = SessionStoragePolicy.evictionPlan(all, justWritten, maxTotalBytes, maxSessionCount)
        for (conversationId in toEvict) {
            dao.getByConversation(conversationId)?.let { File(it.filePath).delete() }
            dao.delete(conversationId)
        }
    }

    private fun finalPathFor(conversationId: String) = File(sessionsDir, "$conversationId.llamastate")

    private fun tempPathFor(conversationId: String) = File(sessionsDir, "$conversationId.llamastate.tmp")

    private fun moveIntoPlace(temp: File, final: File): Boolean {
        if (temp.renameTo(final)) return true
        // renameTo can fail across filesystems (or on some FUSE-backed storage) even when both
        // paths are otherwise valid — fall back to a real copy rather than surfacing a save
        // failure for what the caller has no reason to think would ever cross a filesystem
        // boundary.
        return runCatching { temp.copyTo(final, overwrite = true) }
            .onSuccess { temp.delete() }
            .isSuccess
    }
}
