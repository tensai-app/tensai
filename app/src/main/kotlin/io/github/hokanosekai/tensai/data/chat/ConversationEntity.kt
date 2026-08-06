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
 *   [ChatRepository.appendUserMessage]) — "New chat" until then.
 * @param modelId the [io.github.hokanosekai.tensai.data.models.ModelEntity] this conversation was
 *   started against, or `null` if no model was loaded at creation time. Not a hard foreign key —
 *   the model can be deleted later while the conversation (and its history) remains.
 * @param samplingJson serialized [io.github.hokanosekai.tensai.engine.SamplingConfig] override for
 *   this conversation, or `null` to use the app default — reserved for TEN-35, unset in V1.
 * @param systemPrompt system prompt prepended to every generation for this conversation (see
 *   [ChatRepository.toEngineMessages]), or `null` for none.
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
    val createdAt: Long,
    val updatedAt: Long,
)
