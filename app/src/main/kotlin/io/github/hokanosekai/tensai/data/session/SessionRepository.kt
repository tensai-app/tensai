package io.github.hokanosekai.tensai.data.session

import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.Flow

/** Result of [SessionRepository.save]. */
sealed class SaveResult {
    /** The session was persisted; [tokenCount]/[sizeBytes] mirror what was written to [SessionEntity]. */
    data class Saved(val tokenCount: Int, val sizeBytes: Long) : SaveResult()

    /**
     * Nothing was written because [SessionRepository.save]'s `currentTokenCount` reported the same
     * token count already on record for this conversation — see that parameter's KDoc for why that
     * check is enough to know the save would write identical bytes.
     */
    data class Unchanged(val tokenCount: Int, val sizeBytes: Long) : SaveResult()

    /**
     * Nothing was persisted because `saveOnEngine` reported an empty KV cache (0 tokens) — there is
     * nothing to resume from anyway, so this conversation's *previous* session (if any) is left
     * untouched rather than replaced with an empty one.
     */
    data object NothingToSave : SaveResult()

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
 * - Either `inferenceQueue::saveSession` (a caller outside the queue — the app backgrounding, or
 *   `ChatViewModel` saving the outgoing conversation before a same-model conversation switch,
 *   TEN-62) or the raw `LlamaKtEngine::saveSession` (a caller *inside* `onBeforeModelSwap`, which
 *   already runs under the queue's own mutex and would deadlock calling back into it — see that
 *   hook's KDoc) for [save]'s `saveOnEngine` parameter, depending on which situation triggered the
 *   save.
 *
 * @param storageRoot root directory state files live under (`storageRoot/sessions/`) — the app
 *   passes `context.filesDir`; tests MUST pass a temp directory (see
 *   `io.github.hokanosekai.tensai.data.models.ModelRepository`'s `storageRoot` for the same
 *   convention and why it matters).
 * @param currentTokenCount cheap, non-suspending read of how many tokens are *currently* resident
 *   in whatever the engine has loaded right now (the app wires
 *   `io.github.hokanosekai.tensai.runtime.InferenceQueue.currentKvTokenCount`), or `null` if that
 *   can't be answered right now. Lets [save] skip the expensive `saveOnEngine` call entirely (see
 *   its KDoc) rather than merely skip the DB write after already paying for it.
 */
class SessionRepository(
    private val dao: SessionDao,
    storageRoot: File,
    private val restoreOnEngine: suspend (path: String) -> Boolean,
    private val currentTokenCount: () -> Int? = { null },
) {

    private val sessionsDir: File = File(storageRoot, "sessions")

    /** Every persisted session, most recently accessed first — for a future Settings > Storage list. */
    fun observeSessions(): Flow<List<SessionEntity>> = dao.observeAll()

    /**
     * Saves the current engine state (via [saveOnEngine]) as [conversationId]'s session, tagged
     * with [key]. Overwrites whatever was previously saved for this conversation, and evicts every
     * session this app's [SessionStoragePolicy] doesn't keep — TEN-63 kept exactly one, TEN-62
     * widened that to two (LRU-2) so a conversation switch can save the outgoing session without
     * evicting the incoming one before it can be restored (see [SessionStoragePolicy]'s KDoc).
     *
     * Two ways this can end up writing nothing, both TEN-63 (see the QA numbers cited in
     * [SessionStoragePolicy]'s KDoc — `MainActivity.onStop` fires on every backgrounding, and
     * without either of these it rewrote the full multi-hundred-MB state each time):
     * - **Unchanged**: if a session already exists for [conversationId] under the same [key] and
     *   [currentTokenCount] reports the same token count already on record for it, [saveOnEngine]
     *   is never even called — same [key] means [saveOnEngine] would deterministically re-serialize
     *   the same KV cache into the same bytes, so the token count alone (an atomic read, no I/O) is
     *   enough to know the write would be a no-op. Checked *before* [saveOnEngine] specifically so
     *   the expensive part (the actual state dump) is the part that gets skipped, not just the
     *   rename/DB-write after paying for it.
     * - **NothingToSave**: if [saveOnEngine] itself reports `0` tokens (an empty KV cache — e.g.
     *   nothing has been prefilled/generated against this conversation yet), there is nothing worth
     *   resuming from; any previous session for this conversation is left as-is rather than
     *   replaced with a useless empty one.
     *
     * Write-then-rename otherwise: [saveOnEngine] is asked to write to a temp path first, and the DB
     * row is only written (and the temp file only renamed into place) once that call has actually
     * succeeded — a failure at any point (the engine call itself, or the rename) leaves any
     * previous session for this conversation exactly as it was, never a half-written file mistaken
     * for a valid one.
     */
    suspend fun save(
        conversationId: String,
        key: SessionModelKey,
        saveOnEngine: suspend (path: String) -> Int,
    ): SaveResult {
        val existingBeforeSave = dao.getByConversation(conversationId)
        if (existingBeforeSave != null &&
            existingBeforeSave.modelId == key.modelId &&
            existingBeforeSave.nCtx == key.nCtx &&
            existingBeforeSave.kvCacheType == key.kvCacheType.name
        ) {
            val liveTokenCount = currentTokenCount()
            if (liveTokenCount != null && liveTokenCount == existingBeforeSave.tokenCount) {
                return SaveResult.Unchanged(existingBeforeSave.tokenCount, existingBeforeSave.sizeBytes)
            }
        }

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
        if (tokenCount == 0) {
            tempFile.delete()
            return SaveResult.NothingToSave
        }
        if (!tempFile.exists()) {
            return SaveResult.Failed(IllegalStateException("Session save reported success but wrote no file"))
        }

        if (!moveIntoPlace(tempFile, finalFile)) {
            tempFile.delete()
            return SaveResult.Failed(IOException("Could not move saved session into place at $finalFile"))
        }

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
                createdAt = existingBeforeSave?.createdAt ?: now,
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

    /**
     * One-time cleanup for whatever this table held under an older, looser storage policy (TEN-34's
     * original multi-session caps, or TEN-63's single-session rule on a device that already
     * upgraded once): applies [SessionStoragePolicy]'s current rule (LRU-2 as of TEN-62) to rows
     * already on disk at app start, since there's no "just written" session yet to hand
     * [SessionStoragePolicy.evictionPlan] — the most-recently-accessed row stands in for it instead
     * (the next best signal of "a conversation actually in use"), so the same call keeps that row
     * plus whichever *other* row is next most recent, exactly like a normal [save] would. A no-op
     * once at most [SessionStoragePolicy]'s cap remains — safe to call unconditionally on every app
     * start.
     */
    suspend fun pruneToStoragePolicy() {
        val all = dao.allSessions()
        val mostRecent = all.maxByOrNull { it.lastAccessedAt } ?: return
        evict(SessionStoragePolicy.evictionPlan(all, justWritten = mostRecent.conversationId))
    }

    private suspend fun enforceStoragePolicy(justWritten: String) {
        evict(SessionStoragePolicy.evictionPlan(dao.allSessions(), justWritten))
    }

    private suspend fun evict(conversationIds: List<String>) {
        for (conversationId in conversationIds) {
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
