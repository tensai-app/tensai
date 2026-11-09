package io.github.tensaiapp.data.chat

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A single message in a [ConversationEntity]'s history.
 *
 * Tool calling (TEN-172): an assistant message that made calls carries them in [toolCallsJson], and
 * each result is its own `tool` message (see [toolCallId], [toolName]).
 *
 * @param id stable identifier — the anchor [ChatDao.updateMessage] targets while an assistant
 *   reply streams in (see [ChatRepository.startAssistantMessage]).
 * @param conversationId owning [ConversationEntity.id], indexed for [ChatDao.observeMessages].
 * @param role one of `system`/`user`/`assistant`/`tool` — see
 *   [io.github.tensaiapp.engine.MessageRole].
 * @param content the message text. For a streaming assistant message this starts empty and grows
 *   in place via [ChatDao.updateMessage].
 * @param thinking the model's separated `<think>` content, or `null` — reserved for TEN-33, unset
 *   in V1.
 * @param toolCallsJson the calls an assistant message made, as [ToolCallsJson] encodes them, or `null`
 *   for none. An assistant message with calls may have empty [content]: it is not a stop before the answer.
 * @param toolCallId for a `tool` message, the id of the call it answers, `null` otherwise (schema v17).
 * @param toolName for a `tool` message, the tool that ran, `null` otherwise (schema v17).
 * @param tokensGenerated see [io.github.tensaiapp.engine.DecodeStats.tokensGenerated] —
 *   `null` until the assistant message finishes generating (or for non-assistant messages).
 * @param tokPerSec see [io.github.tensaiapp.engine.DecodeStats.tokPerSec]. Definition
 *   changed in TEN-109: new rows are generation speed with prefill excluded, rows written before
 *   hold tokens / total time with prefill included (no migration). `0` means "not measurable".
 * @param ttftMs see [io.github.tensaiapp.engine.DecodeStats.ttftMs].
 * @param stopReason name of the [io.github.tensaiapp.engine.StopReason] the decode ended
 *   on, or `null` for messages generated before this was recorded (schema v4 and earlier), for
 *   non-assistant messages, and for a reply the user stopped (a cancelled generation has no stop
 *   reason of its own, TEN-52). Stored as the enum name rather than its ordinal so that reordering
 *   the enum can't silently rewrite history, which is also why
 *   [io.github.tensaiapp.engine.StopReason.Failed] needed no migration to start being
 *   written here: it is one more string in a column that already held strings.
 * @param createdAt epoch millis when the message row was created — drives
 *   [ChatDao.observeMessages]'s sort.
 * @param tokenCount tokens of [content] under the tokenizer of the model [tokenCountModelId] names, or
 *   `null` until the context sheet (TEN-161) counted it. Never the thinking: only the current turn's
 *   reasoning, between a call and its result, is replayed, see [ChatRepository.toEngineMessages].
 * @param tokenCountModelId the loaded model's id (or name, for a load with no row) [tokenCount] was
 *   counted with. A count under another model is stale: a different tokenizer, a different figure.
 * @param sendState name of the [SendState] of a user message that has not gone out yet (schema v20), `null` for
 *   every message that has, and for every other role.
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
    val stopReason: String?,
    val createdAt: Long,
    val tokenCount: Int? = null,
    val tokenCountModelId: String? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val sendState: String? = null,
)

/**
 * Where a user message stands while it has not gone out (TEN-224), stored by name in [MessageEntity.sendState] like
 * [MessageEntity.stopReason]. Neither state is in a prompt, see [ChatRepository.toEngineMessages].
 */
enum class SendState {
    /** Sent while another chat was answering: it goes out by itself once the engine is free. */
    Pending,

    /** Its wait was cut (a model switch or unload, the app killed): it goes out on a tap, never by itself. */
    NotSent,
}
