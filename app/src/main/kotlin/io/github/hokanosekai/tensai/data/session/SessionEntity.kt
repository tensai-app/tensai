package io.github.hokanosekai.tensai.data.session

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A persisted llama.cpp KV-cache/prompt state (TEN-34), letting a conversation resume without a
 * full re-prefill — see [SessionRepository].
 *
 * There is at most one row per [conversationId] ([conversationId] is the primary key, not a
 * separate UUID): a conversation only ever has one "current" session on disk, and
 * [SessionRepository.save] overwrites it wholesale rather than accumulating history. Since TEN-63,
 * that ceiling is also a table-wide one: [SessionStoragePolicy] keeps at most one row in the whole
 * `sessions` table, period — the app persists a session for whichever single conversation was most
 * recently saved, not one per conversation. Not a hard foreign key to
 * [io.github.hokanosekai.tensai.data.chat.ConversationEntity] or
 * [io.github.hokanosekai.tensai.data.models.ModelEntity] — same rationale as
 * [io.github.hokanosekai.tensai.data.chat.ConversationEntity.modelId]: either could be deleted
 * while this row remains, and that's fine, it just becomes unrestorable and eventually gets
 * evicted (see [SessionStoragePolicy]).
 *
 * @param conversationId the [io.github.hokanosekai.tensai.data.chat.ConversationEntity.id] this
 *   state belongs to.
 * @param modelId the [io.github.hokanosekai.tensai.data.models.ModelEntity.id] that produced this
 *   state — part of [SessionRepository.restore]'s mismatch check: a session saved against one
 *   model must never be handed to a different model's [io.github.hokanosekai.tensai.engine.LlamaKtEngine.loadSession].
 * @param filePath absolute path to the raw state file on disk (see [SessionRepository]'s storage
 *   layout).
 * @param nCtx the context window the state was saved under — part of the mismatch check, same
 *   reasoning as [modelId].
 * @param kvCacheType name of the [io.github.hokanosekai.tensai.engine.KvCacheType] the state was
 *   saved under (stored as the enum's [Enum.name], same convention as
 *   [io.github.hokanosekai.tensai.data.chat.MessageEntity.stopReason]) — part of the mismatch check.
 * @param backend the compute backend that produced this state
 *   ([io.github.hokanosekai.tensai.engine.LoadedModelInfo.backend]) — informational only, *not*
 *   checked by [SessionRepository.restore]: the on-disk KV-cache format doesn't depend on which
 *   backend ran it, only on [modelId]/[nCtx]/[kvCacheType].
 * @param tokenCount number of tokens included in the saved state, as reported by
 *   [io.github.hokanosekai.tensai.engine.LlamaKtEngine.saveSession].
 * @param sizeBytes size of the file at [filePath] on disk, in bytes — feeds
 *   [SessionStoragePolicy]'s total-size cap.
 * @param createdAt epoch millis when this conversation's session was first saved (preserved across
 *   later overwrites of the same row).
 * @param lastAccessedAt epoch millis of the most recent save or successful restore — drives
 *   [SessionStoragePolicy]'s LRU eviction.
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val conversationId: String,
    val modelId: String,
    val filePath: String,
    val nCtx: Int,
    val kvCacheType: String,
    val backend: String,
    val tokenCount: Int,
    val sizeBytes: Long,
    val createdAt: Long,
    val lastAccessedAt: Long,
)
