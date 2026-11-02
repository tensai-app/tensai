package io.github.tensaiapp.data.session

import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    /**
     * Nothing was persisted because `saveOnEngine` was never in a position to try — the queue was
     * loading a model, streaming a reply, or holding none at all (see
     * [SessionSaveOutcome.NotAttempted]).
     *
     * Distinct from [Failed] on purpose, and that distinction is TEN-78: both mean "nothing on
     * disk", but nothing went *wrong* here, and no [Throwable] exists to blame. It used to arrive
     * as `Failed(IllegalStateException("Session save reported failure (returned -1)"))`, which is a
     * sentence about an engine that was never called. Callers deciding whether live state is safe
     * to overwrite must treat it like [Failed] — the state was not written — but nothing may report
     * it to the user as an error, and nothing may conclude anything about the engine from it.
     */
    data object NotAttempted : SaveResult()

    /**
     * Nothing was persisted because [SessionRepository.purgeAll] ran while this save was in
     * flight (TEN-38's Settings > Storage > Clear) — the state dump completed, and was then thrown
     * away rather than committed, because the user asked for an empty sessions directory *after*
     * this save started.
     *
     * Distinct from [Failed] on purpose even though both mean "nothing on disk": nothing went
     * wrong here, and no previous session survives either (the purge took it). Callers deciding
     * whether live state is safe to overwrite must treat it like [Failed] — the state was not
     * written — but callers reporting an error to the user must not.
     */
    data object Discarded : SaveResult()

    /**
     * Nothing was persisted because [SessionRepository.save]'s `ownsCache` said, once the dump was
     * done, that the KV cache was no longer this conversation's (TEN-180): a conversation switch
     * restoring another session can land between a caller reading the owner and the queue taking
     * its dump, and what was dumped may then be the other conversation's state. Its temp file is
     * deleted and the previous session for this conversation is left untouched.
     *
     * Like [Discarded], nothing went wrong and nothing was written: callers deciding whether live
     * state is safe to overwrite must treat it like [Failed], callers reporting errors must not.
     */
    data object OwnerChanged : SaveResult()

    /** Nothing was persisted — any previous session for this conversation is untouched. */
    data class Failed(val cause: Throwable) : SaveResult()
}

/**
 * What a [SessionRepository.purgeAll] removed — Settings > Storage reports it back to the user.
 *
 * @param sessionCount rows deleted from the `sessions` table.
 * @param orphanFileCount state files deleted that no row pointed at — a save interrupted between
 *   its rename and its DB write (process death, a kill during `onStop`'s best-effort save) leaves
 *   exactly this, and nothing else in the app ever cleans it up, since every other eviction path
 *   works from rows.
 * @param freedBytes total bytes of every file actually deleted, rows and orphans together. A row
 *   whose file was already gone contributes 0 — this is what came off the disk, not what the
 *   table claimed.
 */
data class PurgeReport(val sessionCount: Int, val orphanFileCount: Int, val freedBytes: Long)

/** Result of [SessionRepository.restore]. */
sealed class RestoreResult {
    /** No session exists for this conversation — a normal prefill is the only option. */
    data object NotFound : RestoreResult()

    /**
     * A session exists but was saved against a different model/context/KV-cache-type. Left
     * untouched (not deleted): it may become restorable again later (e.g. the user switches back
     * to the original model). [io.github.tensaiapp.engine.LlamaKtEngine.loadSession] is
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

    /**
     * `restoreOnEngine` never asked the engine — see [SessionRestoreOutcome.NotAttempted]. The
     * session is left exactly as it was, file and row both, and the conversation falls back to a
     * normal prefill for this turn only.
     *
     * The whole of TEN-81 is the gap between this and [Failed]. They used to be the same `false`,
     * and [Failed]'s answer to it is to delete the state file: a model load overlapping a restore —
     * the user tapping through to Models mid-save, say — was enough to destroy a valid session
     * worth 107 s of prefill (TEN-63's measurement, cited in [SessionStoragePolicy]'s KDoc), and
     * the conversation restarted cold from then on, permanently. A busy queue says nothing about a
     * session, so nothing is done to it.
     */
    data object NotAttempted : RestoreResult()

    /** Restored successfully; [tokenCount] mirrors [SessionEntity.tokenCount]. */
    data class Restored(val tokenCount: Int) : RestoreResult()
}

/**
 * Owns persisted [SessionEntity] rows and the raw state files backing them (TEN-34) — lets a
 * conversation resume against the engine's KV cache instead of a full re-prefill.
 *
 * Deliberately decoupled from both [io.github.tensaiapp.engine.LlamaKtEngine] and
 * [io.github.tensaiapp.runtime.InferenceQueue]: [save]'s `saveOnEngine` and [restore]'s
 * `restoreOnEngine` are plain suspend functions instead, which is what makes this class fully
 * unit-testable with fakes and no Room/Android/native dependency. Both are per-call parameters
 * rather than constructor wiring, because in both directions *which* engine entry point is correct
 * depends on where the caller is standing:
 * - [save] takes either `inferenceQueue::saveSession` (a caller outside the queue — the app
 *   backgrounding, `ChatViewModel` saving the outgoing conversation before a same-model
 *   conversation switch, TEN-62) or the raw `LlamaKtEngine::saveSession` (a caller *inside*
 *   `onBeforeModelSwap`, which already runs under the queue's own mutex and would deadlock calling
 *   back into it — see that hook's KDoc).
 * - [restore] takes a lambda closing over the [io.github.tensaiapp.engine.LoadedModelInfo]
 *   the caller validated [restore]'s `key` against, so the queue can re-check it under its own lock
 *   (TEN-81 — see [io.github.tensaiapp.runtime.InferenceQueue.restoreSession]'s
 *   `expectedModel`). Only the caller knows which model it looked at; this class holds a
 *   [SessionModelKey], which names a model by database id and cannot be compared to a loaded one.
 *   Restore always happens from outside any queue-held lock, so it goes through that
 *   mutex-protected entry point rather than the raw engine.
 *
 * @param storageRoot root directory state files live under (`storageRoot/sessions/`) — the app
 *   passes `context.filesDir`; tests MUST pass a temp directory (see
 *   `io.github.tensaiapp.data.models.ModelRepository`'s `storageRoot` for the same
 *   convention and why it matters).
 * @param currentTokenCount cheap, non-suspending read of how many tokens are *currently* resident
 *   in whatever the engine has loaded right now (the app wires
 *   `io.github.tensaiapp.runtime.InferenceQueue.currentKvTokenCount`), or `null` if that
 *   can't be answered right now. Lets [save] skip the expensive `saveOnEngine` call entirely (see
 *   its KDoc) rather than merely skip the DB write after already paying for it.
 */
class SessionRepository(
    private val dao: SessionDao,
    storageRoot: File,
    private val currentTokenCount: () -> Int? = { null },
) {

    private val sessionsDir: File = File(storageRoot, "sessions")

    /**
     * Serializes the *commit* half of everything that mutates a row or a file — [save]'s
     * rename-and-upsert, [restore]'s bookkeeping write, [delete], [purgeAll],
     * [pruneToStoragePolicy]. Nothing inside this lock ever calls back into
     * [io.github.tensaiapp.runtime.InferenceQueue], which is what makes it a **leaf**
     * lock and therefore deadlock-free: the two long, engine-touching calls this class makes
     * ([save]'s `saveOnEngine` and [restoreOnEngine]) both run outside it, so a save arriving
     * from `onBeforeModelSwap` (already holding the queue's mutex, then taking this one) can
     * never sit opposite a save arriving from `ChatViewModel` (holding this one, then wanting the
     * queue's).
     *
     * The engine work staying outside is also the point: a purge must not have to wait behind a
     * multi-hundred-MB state dump, and a generation's save must not be blocked by a purge.
     *
     * ### Two saves of the same conversation
     *
     * They can overlap: `onStop` firing twice during a multi-hundred-MB dump, `onStop` beside a
     * conversation switch's save, the swap hook beside a queued save. The queue's own mutex
     * serializes the dumps, but not what [save] does around them, so a shared temp path let one
     * save rename a file the other was still writing, or delete the other's temp before its rename
     * (TEN-180). Each [save] therefore dumps into a temp path of its own ([tempPathFor]); only the
     * rename into the one final path is shared, and that happens under this lock. Whichever commits
     * last wins, with a complete file and a row that matches it.
     *
     * Not a per-conversation lock around the whole save on purpose: it would be held across the
     * dump, and `onBeforeModelSwap` takes it under the queue's mutex while a `ChatViewModel` save
     * would hold it while waiting for the queue's, the deadlock this lock is a leaf to avoid.
     */
    private val commitMutex = Mutex()

    /**
     * Bumped by every [purgeAll]. [save] reads it before it starts and re-reads it under
     * [commitMutex] before committing: a different value means a purge landed in between, and the
     * save resolves to [SaveResult.Discarded] instead of writing a row the user just asked to be
     * rid of. Read outside the lock too, so it is atomic rather than mutex-guarded state.
     */
    private val purgeEpoch = AtomicLong(0)

    /** Numbers each [save]'s temp file, see [commitMutex]'s KDoc. */
    private val tempSequence = AtomicLong(0)

    /** Every persisted session, most recently accessed first — feeds Settings > Storage (TEN-38). */
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
     * A third, unrelated way (TEN-38): **Discarded**, if [purgeAll] runs between this call
     * starting and it committing — see [SaveResult.Discarded] and [commitMutex].
     *
     * A fourth (TEN-78): **NotAttempted**, if [saveOnEngine] answers
     * [SessionSaveOutcome.NotAttempted] because the queue was busy. Not a failure, and specifically
     * not [SaveResult.Failed] — see that variant's KDoc for the difference and what it cost.
     *
     * Cancellation is **not** one of the ways this ends in a [SaveResult]: a cancelled
     * [saveOnEngine] re-throws (TEN-73), taking the temp file with it. It is not a failure to
     * report — the caller asked for this — and answering with one would swallow it, leaving a
     * coroutine that has been cancelled running on as though it had not.
     *
     * No caller can currently reach that: every path in ends on
     * `RuntimeGraph.applicationScope`, which nothing cancels, except the debug agent bench's Stop,
     * which cancels its own job and so makes its saves (the cache handover) `NonCancellable`.
     * TEN-73 fixed the shape rather than a symptom, and the ticket's own premise ("l'app tuée
     * pendant `MainActivity.onStop`") turned out to be wrong for the same reason.
     *
     * Write-then-rename otherwise: [saveOnEngine] is asked to write to a temp path first, and the DB
     * row is only written (and the temp file only renamed into place) once that call has actually
     * succeeded — a failure at any point (the engine call itself, or the rename) leaves any
     * previous session for this conversation exactly as it was, never a half-written file mistaken
     * for a valid one.
     *
     * @param ownsCache asked once the dump is done: does the KV cache still belong to
     *   [conversationId]? `false` resolves to [SaveResult.OwnerChanged]. A caller that picked
     *   [conversationId] off `ActiveSessionTracker` passes a re-read of it: a switch clears the
     *   outgoing owner before it restores another session, so a dump that may have read the restored
     *   state always finishes with the owner already moved. The swap hook keeps the default, it
     *   dumps under the queue's mutex with the owner it cleared itself.
     */
    suspend fun save(
        conversationId: String,
        key: SessionModelKey,
        ownsCache: () -> Boolean = { true },
        saveOnEngine: suspend (path: String) -> SessionSaveOutcome,
    ): SaveResult {
        // Read under the lock, together, so a purge can't slip between them: an
        // `existingBeforeSave` row that a purge is about to delete would otherwise let the
        // Unchanged branch below claim this conversation is already on disk when it is about to
        // stop being.
        val (epochAtStart, existingBeforeSave) = commitMutex.withLock {
            purgeEpoch.get() to dao.getByConversation(conversationId)
        }
        if (existingBeforeSave != null &&
            existingBeforeSave.modelId == key.modelId &&
            existingBeforeSave.nCtx == key.nCtx &&
            existingBeforeSave.kvCacheType == key.kvCacheType.name &&
            existingBeforeSave.chatTemplate == key.chatTemplate
        ) {
            val liveTokenCount = currentTokenCount()
            if (liveTokenCount != null && liveTokenCount == existingBeforeSave.tokenCount) {
                return SaveResult.Unchanged(existingBeforeSave.tokenCount, existingBeforeSave.sizeBytes)
            }
        }

        sessionsDir.mkdirs()
        val finalFile = finalPathFor(conversationId)
        val tempFile = tempPathFor(conversationId)

        val outcome = try {
            saveOnEngine(tempFile.absolutePath)
        } catch (e: CancellationException) {
            // Ahead of the catch below, which would otherwise answer a cancellation with
            // [SaveResult.Failed] — a `CancellationException` in a field callers read as "what the
            // engine said went wrong", and, having turned the cancellation into a return value,
            // the calling coroutine left running as though it had never been cancelled (TEN-73).
            // The temp file still goes with it: the partial dump is worthless either way, and
            // `delete()` doesn't suspend, so it runs even here.
            tempFile.delete()
            throw e
        } catch (e: Exception) {
            // A `saveOnEngine` that throws rather than reporting is the raw-engine wiring
            // (`LlamaKtEngine::saveSession` throws `InferenceException`); it means the engine tried,
            // so it lands on [SaveResult.Failed] like [SessionSaveOutcome.EngineFailed] does.
            tempFile.delete()
            return SaveResult.Failed(e)
        }
        val wrote = when (outcome) {
            // TEN-78: the queue was never in a position to ask the engine. Nothing was written and
            // nothing broke — reporting this as a failure is what cancelled a perfectly good
            // incoming restore every time a reply happened to be streaming.
            SessionSaveOutcome.NotAttempted -> {
                tempFile.delete()
                return SaveResult.NotAttempted
            }
            SessionSaveOutcome.EngineFailed -> {
                tempFile.delete()
                return SaveResult.Failed(IllegalStateException("Session save reported failure"))
            }
            is SessionSaveOutcome.Wrote -> outcome
        }
        val tokenCount = wrote.tokenCount
        if (tokenCount < 0) {
            tempFile.delete()
            return SaveResult.Failed(IllegalStateException("Session save reported failure (returned $tokenCount)"))
        }
        if (tokenCount == 0) {
            tempFile.delete()
            return SaveResult.NothingToSave
        }
        if (!ownsCache()) {
            tempFile.delete()
            return SaveResult.OwnerChanged
        }

        return try {
            commitMutex.withLock {
                // Checked before the file checks below, not after: a purge deletes the temp file out
                // from under an in-flight dump (see [purgeAll]), so "the engine wrote nothing" and
                // "the user cleared storage mid-save" would otherwise be reported as the same
                // failure, and only one of them is one.
                if (purgeEpoch.get() != epochAtStart) {
                    tempFile.delete()
                    return@withLock SaveResult.Discarded
                }
                if (!tempFile.exists()) {
                    return@withLock SaveResult.Failed(
                        IllegalStateException("Session save reported success but wrote no file"),
                    )
                }
                if (!moveIntoPlace(tempFile, finalFile)) {
                    tempFile.delete()
                    return@withLock SaveResult.Failed(
                        IOException("Could not move saved session into place at $finalFile"),
                    )
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
                        chatTemplate = key.chatTemplate,
                        withoutTools = wrote.withoutTools,
                        backend = key.backend,
                        tokenCount = tokenCount,
                        sizeBytes = sizeBytes,
                        createdAt = existingBeforeSave?.createdAt ?: now,
                        lastAccessedAt = now,
                    ),
                )
                evictLocked(SessionStoragePolicy.evictionPlan(dao.allSessions(), justWritten = conversationId))
                SaveResult.Saved(tokenCount, sizeBytes)
            }
        } catch (e: Throwable) {
            // A throw from the DAO, or a cancellation while waiting on [commitMutex]: the temp name
            // is this save's own, so nothing else would ever overwrite or remove it.
            tempFile.delete()
            throw e
        }
    }

    /**
     * Restores [conversationId]'s session onto the currently loaded model via [restoreOnEngine],
     * but only if it was saved against a model/context/KV-cache-type matching [key] — see
     * [RestoreResult.Mismatch]'s KDoc for why that check runs *before* ever calling
     * [restoreOnEngine], not after.
     *
     * That ordering is also why [restoreOnEngine] is a per-call parameter and not constructor
     * wiring: the check above runs against a snapshot, outside any lock the queue holds, and the
     * caller is the only one who knows which loaded model it read. It closes over that model so the
     * queue can re-assert it under its own mutex (TEN-81) — see this class's KDoc.
     *
     * @param restoreOnEngine hands the state file to the engine, with [SessionEntity.withoutTools] (TEN-196). Its
     *   [SessionRestoreOutcome.EngineRefused] is the *only* answer that self-heals the session off
     *   disk; [SessionRestoreOutcome.NotAttempted] leaves it strictly alone. See
     *   [RestoreResult.NotAttempted].
     */
    suspend fun restore(
        conversationId: String,
        key: SessionModelKey,
        restoreOnEngine: suspend (path: String, withoutTools: Boolean) -> SessionRestoreOutcome,
    ): RestoreResult {
        val epochAtStart = purgeEpoch.get()
        val entity = dao.getByConversation(conversationId) ?: return RestoreResult.NotFound

        if (entity.modelId != key.modelId || entity.nCtx != key.nCtx || entity.kvCacheType != key.kvCacheType.name ||
            entity.chatTemplate != key.chatTemplate
        ) {
            return RestoreResult.Mismatch(key, entity)
        }

        val file = File(entity.filePath)
        if (!file.exists()) {
            dao.delete(conversationId)
            return RestoreResult.Corrupted
        }

        val outcome = try {
            restoreOnEngine(entity.filePath, entity.withoutTools)
        } catch (e: CancellationException) {
            // The same swallow as [save]'s, except this one would destroy data on its way past
            // (TEN-73): a refusal falls into the self-healing branch below, which deletes this
            // conversation's state file *and* its row. A cancelled caller is no evidence that the
            // session is unusable — it says nothing about the session at all — so that branch
            // would throw away a good multi-hundred-MB session and re-prefill from scratch forever
            // after.
            //
            // Would, not does: like [LoadFailure.of]'s guard, this is a backstop, not a live bug.
            // Every caller of this method runs on `RuntimeGraph.applicationScope`
            // (`ChatViewModel.scope`), which nothing in the app cancels — backgrounding does not,
            // which is the whole point of that scope. This is here because the consequence of
            // being wrong is silent data loss, and the next caller need only be scoped normally.
            throw e
        } catch (e: Exception) {
            // A `restoreOnEngine` that throws has been asked and could not do it, which is a
            // verdict on the file — same class as [SessionRestoreOutcome.EngineRefused], and
            // self-healed the same way. Only [SessionRestoreOutcome.NotAttempted] means "never
            // asked", and it cannot arrive by exception.
            SessionRestoreOutcome.EngineRefused
        }

        when (outcome) {
            // TEN-81, the whole ticket: a queue that was loading, generating, or resident on a
            // different model than the one [key] was checked against never touched this file. It is
            // no evidence the session is broken, so nothing is deleted and the caller re-prefills
            // for this turn only. The self-heal below is for sessions actually shown to be
            // unusable, and it is not reversible.
            SessionRestoreOutcome.NotAttempted -> return RestoreResult.NotAttempted
            SessionRestoreOutcome.EngineRefused -> {
                commitMutex.withLock {
                    file.delete()
                    dao.delete(conversationId)
                }
                return RestoreResult.Failed
            }
            SessionRestoreOutcome.Restored -> Unit
        }

        commitMutex.withLock {
            // A [purgeAll] that landed while the engine was reading the file wins: the restore
            // itself did happen (the state is in RAM, the conversation resumes), but the user
            // asked for an empty sessions directory, and re-writing this row would put a session
            // back in the table pointing at a file that is no longer there.
            if (purgeEpoch.get() == epochAtStart) {
                dao.upsert(entity.copy(lastAccessedAt = System.currentTimeMillis()))
            }
        }
        return RestoreResult.Restored(entity.tokenCount)
    }

    /** Deletes [conversationId]'s session (file + row), if any. No-op if it doesn't exist. */
    suspend fun delete(conversationId: String) {
        commitMutex.withLock {
            val entity = dao.getByConversation(conversationId) ?: return@withLock
            File(entity.filePath).delete()
            dao.delete(conversationId)
        }
    }

    /**
     * Deletes every session saved against [modelId], rows whose file is already gone included
     * (they are not restorable and the screen that offers this does not count them). Under
     * [commitMutex] like every other mutation, so it cannot race a [save]. Returns how many rows
     * went.
     */
    suspend fun deleteForModel(modelId: String): Int = commitMutex.withLock {
        val rows = dao.allSessions().filter { it.modelId == modelId }
        for (row in rows) {
            File(row.filePath).delete()
            dao.delete(row.conversationId)
        }
        rows.size
    }

    /**
     * Deletes every persisted session — every row, every file each row points at, and every
     * *orphan* state file left in the sessions directory by a save that died between its rename
     * and its DB write. Settings > Storage > Clear (TEN-38) is the only caller.
     *
     * Deliberately **not** a `deleteRecursively` on the directory: it removes exactly the two file
     * shapes this class creates (`<id>.llamastate` and its `<id>.<n>.llamastate.tmp` dumps),
     * leaves the directory itself in place, and never touches anything else that might one day
     * share the path.
     *
     * ### What it does to a generation in flight
     *
     * Nothing, on purpose. A session file is a frozen copy of a KV cache, not the cache itself:
     * the resident model and whatever is decoding against it live in RAM and are untouched, so a
     * reply keeps streaming through a purge and the conversation on screen keeps its context.
     * What the user loses is the ability to *resume* those conversations after a model swap or a
     * restart — which is exactly what they asked to reclaim the disk of.
     *
     * The reverse direction is the one that needs care, and it is handled by [commitMutex] +
     * [purgeEpoch] rather than by making the two operations wait for each other: a [save] already
     * in flight (the app backgrounding, `onBeforeModelSwap`, a conversation switch) completes its
     * state dump, finds the epoch bumped, and resolves to [SaveResult.Discarded] — its temp file
     * deleted, no row written. Without that, a purge would race the tail of a save and leave
     * either a row whose file was just deleted or, worse, a multi-hundred-MB file no row points at
     * and no eviction path will ever find. Session persistence is not disabled by a purge: the
     * *next* save trigger after it writes a session again, and that is intended — Clear frees the
     * disk now, it does not turn the feature off.
     */
    suspend fun purgeAll(): PurgeReport = commitMutex.withLock {
        purgeEpoch.incrementAndGet()

        val rows = dao.allSessions()
        var freedBytes = 0L
        for (row in rows) {
            freedBytes += deleteAndMeasure(File(row.filePath))
        }
        dao.deleteAll()

        // Every row is gone, so anything still matching this class's own naming under
        // `sessions/` is by definition unreferenced.
        var orphanFileCount = 0
        for (file in sessionsDir.listFiles().orEmpty()) {
            if (!file.isFile) continue
            if (!file.name.endsWith(STATE_SUFFIX) && !file.name.endsWith(TEMP_SUFFIX)) continue
            val bytes = deleteAndMeasure(file)
            if (!file.exists()) {
                orphanFileCount++
                freedBytes += bytes
            }
        }

        PurgeReport(sessionCount = rows.size, orphanFileCount = orphanFileCount, freedBytes = freedBytes)
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
     *
     * Also deletes every temp dump left in the sessions directory (TEN-180): each [save] names its
     * own, so a dump cut short by the process dying is never overwritten by a later one, and no
     * row counts it in Storage. Safe here and nowhere else: this runs at app start, before any
     * model is loaded, so no save can be dumping.
     */
    suspend fun pruneToStoragePolicy() {
        commitMutex.withLock {
            sessionsDir.listFiles().orEmpty()
                .filter { it.isFile && it.name.endsWith(TEMP_SUFFIX) }
                .forEach { it.delete() }
            val all = dao.allSessions()
            val mostRecent = all.maxByOrNull { it.lastAccessedAt } ?: return@withLock
            evictLocked(SessionStoragePolicy.evictionPlan(all, justWritten = mostRecent.conversationId))
        }
    }

    /** Caller must hold [commitMutex]. */
    private suspend fun evictLocked(conversationIds: List<String>) {
        for (conversationId in conversationIds) {
            dao.getByConversation(conversationId)?.let { File(it.filePath).delete() }
            dao.delete(conversationId)
        }
    }

    /** Bytes [file] held if the delete removed it, `0` if it was already gone or survived. */
    private fun deleteAndMeasure(file: File): Long {
        if (!file.isFile) return 0
        val length = file.length()
        return if (file.delete()) length else 0
    }

    private fun finalPathFor(conversationId: String) = File(sessionsDir, "$conversationId$STATE_SUFFIX")

    /** A new path on every call, still ending in [TEMP_SUFFIX] so [purgeAll] sweeps a leftover one. */
    private fun tempPathFor(conversationId: String) =
        File(sessionsDir, "$conversationId.${tempSequence.incrementAndGet()}$TEMP_SUFFIX")

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

    private companion object {
        /** Suffix of a committed state file — also what [purgeAll]'s orphan sweep matches on. */
        const val STATE_SUFFIX = ".llamastate"

        /** Suffix of an in-progress state dump, before [moveIntoPlace] renames it. */
        const val TEMP_SUFFIX = ".llamastate.tmp"
    }
}
