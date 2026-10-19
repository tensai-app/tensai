package io.github.tensaiapp.data.chat

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One tool call of an assistant reply, the audit log of the vision (TEN-172). Read by the "Agent
 * activity" section (TEN-162) and the chat's call sheet (TEN-173).
 *
 * @param id row identifier.
 * @param messageId the assistant [MessageEntity.id] that made the call. Not a hard foreign key,
 *   same as the rest of the chat tables: [ChatDao.deleteMessageAndToolCalls] and
 *   [ChatDao.deleteConversation] cascade by hand.
 * @param callId the call's own id, the one its `tool` message answers with ([MessageEntity.toolCallId]).
 * @param tool name of the tool the model asked for, even when it does not exist.
 * @param tier [io.github.tensaiapp.agent.ToolTier] name, `null` for an unknown tool.
 * @param server MCP server the tool comes from, `null` for the built-in ones (MCP is v0.4.0).
 * @param argsJson the arguments as the model wrote them.
 * @param startedAt epoch millis the call began.
 * @param durationMs how long the tool ran, 0 when it never did (refused, invalid, unknown).
 * @param resultTokens tokens of the result the model was shown, after truncation.
 * @param status [io.github.tensaiapp.agent.ToolCallStatus.storageName]: `ok`, `error`,
 *   `refused`, `timeout` or `stopped`.
 * @param destination where a Network tool sent the request, `null` otherwise.
 */
@Entity(
    tableName = "tool_calls",
    indices = [Index(value = ["messageId"])],
)
data class ToolCallEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val callId: String,
    val tool: String,
    val tier: String?,
    val server: String?,
    val argsJson: String,
    val startedAt: Long,
    val durationMs: Long,
    val resultTokens: Int,
    val status: String,
    val destination: String?,
)

/**
 * What [ChatDao.toolActivity] returns: a tool's totals over a conversation, whatever the status of
 * its calls ([ToolCallEntity.status]).
 *
 * @param calls number of calls.
 * @param resultTokens tokens of the results shown to the model, summed.
 * @param durationMs time the tool ran, summed.
 */
data class ToolActivityRow(
    val tool: String,
    val calls: Int,
    val resultTokens: Int,
    val durationMs: Long,
)
