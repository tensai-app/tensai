package io.github.hokanosekai.tensai.ui.screens.chat

import io.github.hokanosekai.tensai.data.chat.ChatRepository
import io.github.hokanosekai.tensai.data.chat.ConversationEntity
import io.github.hokanosekai.tensai.data.chat.MessageEntity
import io.github.hokanosekai.tensai.data.chat.ThinkingStreamParser
import io.github.hokanosekai.tensai.data.models.ModelRepository
import io.github.hokanosekai.tensai.engine.DecodeEvent
import io.github.hokanosekai.tensai.engine.DecodeRequest
import io.github.hokanosekai.tensai.engine.DecodeStats
import io.github.hokanosekai.tensai.engine.SamplingConfig
import io.github.hokanosekai.tensai.engine.StopReason
import io.github.hokanosekai.tensai.runtime.InferenceQueue
import io.github.hokanosekai.tensai.runtime.QueueState
import io.github.hokanosekai.tensai.runtime.RuntimeGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Minimum time between DB writes of a streaming assistant message's growing content — see [ChatViewModel.streamInto]. */
private const val StreamPersistIntervalMs = 100L

/**
 * UI-facing view of a persisted [MessageEntity] — `null` for system/tool messages, which have
 * nothing to render in V1 (see [toUi]).
 */
data class ChatMessageUi(
    val id: String,
    val isUser: Boolean,
    val content: String,
    val thinking: String?,
    val stats: MessageStatsUi?,
)

/**
 * Formatted decode stats for a finished assistant reply — `null` while streaming, or if generation
 * was stopped early.
 *
 * @param truncated the reply ran into [io.github.hokanosekai.tensai.engine.SamplingConfig.maxTokens]
 *   rather than ending on its own. `false` for replies stored before v5 of the schema, where the
 *   reason wasn't recorded and guessing would be worse than staying quiet (TEN-60).
 */
data class MessageStatsUi(
    val tokPerSec: Float,
    val tokensGenerated: Int,
    val ttftMs: Long,
    val truncated: Boolean,
)

private fun MessageEntity.toUi(): ChatMessageUi? {
    val isUser = when (role) {
        "user" -> true
        "assistant" -> false
        else -> return null // system/tool: nothing to render in V1.
    }
    val stats = if (tokensGenerated != null && tokPerSec != null && ttftMs != null) {
        MessageStatsUi(
            tokPerSec = tokPerSec,
            tokensGenerated = tokensGenerated,
            ttftMs = ttftMs,
            truncated = stopReason == StopReason.TokenBudget.name,
        )
    } else {
        null
    }
    return ChatMessageUi(id = id, isUser = isUser, content = content, thinking = thinking, stats = stats)
}

/** Surfaced by [ChatViewModel.send]/[ChatViewModel.regenerate] when they can't proceed. */
sealed class ChatError {
    /** No model is loaded — the UI's CTA routes to Models. */
    data object NoModelLoaded : ChatError()
}

/**
 * Drives a single conversation's chat screen (TEN-32): streams [ChatRepository]'s persisted
 * messages, and turns [InferenceQueue.generate] events into DB writes as a reply streams in.
 *
 * `conversationId` is expected to always name an existing conversation — resolving the "new chat"
 * sentinel into a real, persisted id happens one layer up, in
 * [io.github.hokanosekai.tensai.ui.navigation.TensaiNavGraph], before this class is ever
 * constructed.
 *
 * Like [io.github.hokanosekai.tensai.ui.screens.loading.LoadModelViewModel], every generation runs
 * on [scope] (defaulting to [RuntimeGraph.applicationScope]), **not** a Compose-scoped coroutine:
 * leaving the chat screen (back to Home, switching conversations) must not cut off a reply that's
 * mid-stream, the same way a model load survives leaving the loading screen. This class is
 * deliberately not an `androidx.lifecycle.ViewModel` for the same reason `LoadModelViewModel`
 * isn't — the project has no such dependency yet (see `RuntimeGraph`'s "framework-free
 * composition root" KDoc). `ChatScreen` `remember`s one instance per `conversationId`.
 */
class ChatViewModel(
    private val conversationId: String,
    private val chatRepository: ChatRepository = RuntimeGraph.chatRepository,
    private val inferenceQueue: InferenceQueue = RuntimeGraph.inferenceQueue,
    private val modelRepository: ModelRepository = RuntimeGraph.modelRepository,
    private val scope: CoroutineScope = RuntimeGraph.applicationScope,
) {
    /** This conversation's messages, oldest first, mapped for display — see [toUi]. */
    val messages: Flow<List<ChatMessageUi>> =
        chatRepository.observeMessages(conversationId).map { list -> list.mapNotNull { it.toUi() } }

    /** Mirrors [InferenceQueue.state] — [ChatScreen] derives `isGenerating`/`modelName`/`nCtx` from it directly. */
    val queueState: StateFlow<QueueState> = inferenceQueue.state

    /**
     * Whether the currently loaded model's chat template supports thinking — gates whether
     * [ChatScreen] shows the enable/disable toggle at all. [QueueState.ModelReady.info] carries no
     * `supportsThinking` flag of its own — that's a GGUF-header concept
     * ([io.github.hokanosekai.tensai.engine.GgufInfo.supportsThinking]), not something the engine's
     * load-time [io.github.hokanosekai.tensai.engine.LoadedModelInfo] repeats — so this matches the
     * loaded model back to its persisted [io.github.hokanosekai.tensai.data.models.ModelEntity] by
     * display name, the same by-name match
     * [io.github.hokanosekai.tensai.data.models.ModelRepository.deleteModel]'s in-use guard already
     * relies on, with the same caveat noted there (a false negative if two imported files happen to
     * share a display name).
     */
    val currentModelSupportsThinking: Flow<Boolean> =
        combine(inferenceQueue.state, modelRepository.models) { state, models ->
            val loadedName = (state as? QueueState.ModelReady)?.info?.name ?: return@combine false
            models.any { it.displayName == loadedName && it.supportsThinking }
        }

    /** This conversation's persisted thinking toggle — see [ConversationEntity.enableThinking]. Defaults to `true` (matching [SamplingConfig.enableThinking]'s default) until the conversation row is read. */
    val enableThinking: Flow<Boolean> =
        chatRepository.observeConversation(conversationId).map { it?.enableThinking ?: true }

    /** Flips this conversation's [ConversationEntity.enableThinking] — takes effect on the next [send]/[regenerate], not the one already streaming. */
    fun setEnableThinking(enabled: Boolean) {
        scope.launch { chatRepository.setEnableThinking(conversationId, enabled) }
    }

    /** Latest [send]/[regenerate] failure, consumed by [ChatScreen] via [clearError]. */
    private val _error = MutableStateFlow<ChatError?>(null)
    val error: StateFlow<ChatError?> = _error.asStateFlow()

    /**
     * The in-flight append-and-generate (or regenerate-and-generate) coroutine, if any. Not used
     * by [stop] (that goes straight through [InferenceQueue.cancelActive] — this job outlives the
     * cancelled generation while it flushes the partial reply, see [streamInto]'s `finally`);
     * kept so a second [send]/[regenerate] tap while one is already running is a safe no-op.
     */
    private var generationJob: Job? = null

    /**
     * Appends [text] as a user message and streams a reply. A no-op (besides surfacing
     * [ChatError.NoModelLoaded]) if no model is loaded; also a no-op if [text] is blank or a
     * generation is already running — `ChatScreen` disables send in both cases, this is just belt
     * and suspenders against a stray call.
     */
    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || generationJob?.isActive == true) return
        if (inferenceQueue.state.value !is QueueState.ModelReady) {
            _error.value = ChatError.NoModelLoaded
            return
        }
        generationJob = scope.launch {
            chatRepository.appendUserMessage(conversationId, trimmed)
            generateReply()
        }
    }

    /**
     * Stops the active generation, if any. The partial reply streamed so far is left exactly as
     * is — see [streamInto]'s `finally`, which persists whatever was accumulated regardless of how
     * generation ended. No explicit "stopped" flag: a finished reply is distinguished from a
     * stopped one purely by whether stats ended up non-null (only [DecodeEvent.Done] sets them).
     */
    fun stop() = inferenceQueue.cancelActive()

    /**
     * Drops the last assistant reply (if the conversation's last message is one — read fresh from
     * [ChatRepository] rather than remembered in this instance, so it's correct even if this
     * `ChatViewModel` was just recreated, e.g. after navigating away and back) and generates a new
     * one for the same history. A no-op if a generation is already running or no model is loaded
     * (same [ChatError.NoModelLoaded] surfacing as [send]).
     */
    fun regenerate() {
        if (generationJob?.isActive == true) return
        if (inferenceQueue.state.value !is QueueState.ModelReady) {
            _error.value = ChatError.NoModelLoaded
            return
        }
        generationJob = scope.launch {
            val last = chatRepository.observeMessages(conversationId).first().lastOrNull()
            if (last != null && last.role == "assistant") {
                chatRepository.deleteMessage(last.id)
            }
            generateReply()
        }
    }

    fun clearError() {
        _error.value = null
    }

    /**
     * Best-effort KV-cache fill ratio for the loaded model — see [InferenceQueue.kvUsedPercent].
     * Not surfaced anywhere in the V1 layout (no design slot for it yet); exposed here for a
     * future context-usage indicator, per the ticket's "légitime à ajouter" note.
     */
    fun kvUsedPercent(): Float? = inferenceQueue.kvUsedPercent()

    /** Starts a fresh assistant message and streams into it — the shared tail of [send] and [regenerate]. */
    private suspend fun generateReply() {
        val conversation = chatRepository.getConversation(conversationId)
        val history = chatRepository.observeMessages(conversationId).first()
        val assistantId = chatRepository.startAssistantMessage(conversationId)
        val request = DecodeRequest(
            messages = chatRepository.toEngineMessages(history, conversation?.systemPrompt),
            sampling = SamplingConfig(enableThinking = conversation?.enableThinking ?: true),
        )
        streamInto(assistantId, request)
    }

    /**
     * Collects [InferenceQueue.generate]'s events into [assistantId]'s content/thinking, splitting
     * the two apart with a [ThinkingStreamParser] — see its KDoc for how it copes with `<think>`
     * tags arriving fragmented across tokens.
     *
     * Tokens accumulate in memory and are persisted to Room at most every
     * [StreamPersistIntervalMs] — hammering Room on every token (often several per second) would
     * be pure write amplification for a value nothing reads until the next recomposition tick
     * anyway. The `finally` block does exactly one more write, unconditionally, with whatever
     * ended up in the buffer: this is what makes the persisted content always exactly match what
     * was generated regardless of whether the last throttle tick happened to land on the true last
     * token — and it's what runs on [DecodeEvent.Done], on [DecodeEvent.Error], and on cancellation
     * ([stop]) alike, so "keep the partial reply" needs no separate code path. It's wrapped in
     * [NonCancellable] so a [stop] mid-flush can't also race that final write.
     */
    private suspend fun streamInto(assistantId: String, request: DecodeRequest) {
        val parser = ThinkingStreamParser()
        var stats: DecodeStats? = null
        var errorFallback: String? = null
        var lastPersistAt = 0L
        try {
            inferenceQueue.generate(request).collect { event ->
                when (event) {
                    is DecodeEvent.Token -> {
                        parser.accept(event.text)
                        val now = System.currentTimeMillis()
                        if (now - lastPersistAt >= StreamPersistIntervalMs) {
                            chatRepository.updateAssistantMessage(assistantId, parser.content, parser.thinking)
                            lastPersistAt = now
                        }
                    }
                    is DecodeEvent.Done -> stats = event.stats
                    is DecodeEvent.Error -> {
                        if (parser.content.isEmpty() && parser.thinking == null) {
                            errorFallback = "Error: ${event.cause.message ?: "generation failed"}"
                        }
                    }
                }
            }
        } finally {
            parser.finish()
            withContext(NonCancellable) {
                chatRepository.updateAssistantMessage(
                    assistantId,
                    content = errorFallback ?: parser.content,
                    thinking = parser.thinking,
                    stats = stats,
                )
            }
        }
    }
}
