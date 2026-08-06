package io.github.hokanosekai.tensai.data.chat

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single message in a [ConversationEntity]'s history.
 *
 * The schema is tool-ready without a migration: [toolCallsJson] is reserved for a future
 * assistant tool-call message and [role] already accepts `"tool"` (mirroring
 * [io.github.hokanosekai.tensai.engine.MessageRole]) — both are unused (always `null`/absent) until
 * tool-calling ships, so filling them in later is just writing to an existing nullable column, not
 * a schema change.
 *
 * @param id stable identifier — the anchor [ChatDao.updateMessage] targets while an assistant
 *   reply streams in (see [ChatRepository.startAssistantMessage]).
 * @param conversationId owning [ConversationEntity.id], indexed for [ChatDao.observeMessages].
 * @param role one of `system`/`user`/`assistant`/`tool` — see
 *   [io.github.hokanosekai.tensai.engine.MessageRole].
 * @param content the message text. For a streaming assistant message this starts empty and grows
 *   in place via [ChatDao.updateMessage].
 * @param thinking the model's separated `<think>` content, or `null` — reserved for TEN-33, unset
 *   in V1.
 * @param toolCallsJson reserved placeholder for a future tool-call message — see class KDoc.
 *   Always `null` in V1.
 * @param tokensGenerated see [io.github.hokanosekai.tensai.engine.DecodeStats.tokensGenerated] —
 *   `null` until the assistant message finishes generating (or for non-assistant messages).
 * @param tokPerSec see [io.github.hokanosekai.tensai.engine.DecodeStats.tokPerSec].
 * @param ttftMs see [io.github.hokanosekai.tensai.engine.DecodeStats.ttftMs].
 * @param createdAt epoch millis when the message row was created — drives
 *   [ChatDao.observeMessages]'s sort.
 */
@Entity(
    tableName = "messages",
    indices = [Index(value = ["conversationId"])],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val thinking: String?,
    val toolCallsJson: String?,
    val tokensGenerated: Int?,
    val tokPerSec: Float?,
    val ttftMs: Long?,
    val createdAt: Long,
)
