package io.github.hokanosekai.tensai.data.chat

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A chat conversation, persisted so it survives process death and backs the drawer's "Recents"
 * list (see [ChatDao.observeConversations]).
 *
 * @param id stable identifier — used as the nav arg for
 *   [io.github.hokanosekai.tensai.ui.navigation.TensaiDestination.Chat].
 * @param title auto-derived from the first user message (truncated, see
 *   [ChatRepository.appendUserMessage]), empty until then (TEN-105). Empty means untitled: the UI
 *   shows the localized "New chat" string for it, nothing display-facing is stored here.
 * @param modelId the [io.github.hokanosekai.tensai.data.models.ModelEntity] this conversation was
 *   started against, or `null` if no model was loaded at creation time. Not a hard foreign key —
 *   the model can be deleted later while the conversation (and its history) remains.
 * @param samplingJson serialized [io.github.hokanosekai.tensai.engine.SamplingConfig] override for
 *   this conversation, or `null` to use the defaults (TEN-35). Read and written only through
 *   [SamplingJson], which owns the format and, crucially, the guarantee that reading it back never
 *   throws — see its KDoc.
 * @param systemPrompt system prompt prepended to every generation for this conversation (see
 *   [ChatRepository.toEngineMessages]), or `null` for none.
 * @param enableThinking whether [io.github.hokanosekai.tensai.engine.SamplingConfig.enableThinking]
 *   is passed as `true` for this conversation's generations (TEN-33) — toggled from `ChatScreen`,
 *   gated on the loaded model's [io.github.hokanosekai.tensai.data.models.ModelEntity.supportsThinking].
 *   Added in schema version 4, see [io.github.hokanosekai.tensai.data.models.TensaiDb]'s KDoc.
 * @param toolsEnabled whether the model may call tools in this conversation (TEN-172). Read by
 *   [io.github.hokanosekai.tensai.ui.screens.chat.ChatViewModel] when the model's template takes tools;
 *   the switch to flip it is TEN-173's. Added in schema version 17, `true` for every existing row.
 * @param createdAt epoch millis when the conversation was created.
 * @param updatedAt epoch millis of the most recent message — drives [ChatDao.observeConversations]'s sort.
 */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val modelId: String?,
    val samplingJson: String?,
    val systemPrompt: String?,
    val enableThinking: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val toolsEnabled: Boolean = true,
)
