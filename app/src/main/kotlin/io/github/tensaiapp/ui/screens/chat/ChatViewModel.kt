package io.github.tensaiapp.ui.screens.chat

import io.github.tensaiapp.agent.AgentEvent
import io.github.tensaiapp.agent.AgentRuntime
import io.github.tensaiapp.agent.ReplyGenerator
import io.github.tensaiapp.agent.ToolCallOutcome
import io.github.tensaiapp.agent.AskUserGate
import io.github.tensaiapp.agent.PendingQuestion
import io.github.tensaiapp.agent.UiConfirmationGate
import io.github.tensaiapp.data.chat.ChatRepository
import io.github.tensaiapp.data.chat.ConversationEntity
import io.github.tensaiapp.data.chat.MessageEntity
import io.github.tensaiapp.data.chat.ReplyStreamSplitter
import io.github.tensaiapp.data.chat.SamplingJson
import io.github.tensaiapp.data.chat.ToolCallsJson
import io.github.tensaiapp.data.chat.ThinkingStreamParser
import io.github.tensaiapp.data.memory.MemoryPrompt
import io.github.tensaiapp.data.models.KvCacheSize
import io.github.tensaiapp.data.models.ModelRepository
import io.github.tensaiapp.data.session.SaveResult
import io.github.tensaiapp.data.session.SessionModelKey
import io.github.tensaiapp.data.session.SessionRepository
import io.github.tensaiapp.engine.ContextBudget
import io.github.tensaiapp.engine.DecodeEvent
import io.github.tensaiapp.engine.DecodeRequest
import io.github.tensaiapp.engine.DecodeStats
import io.github.tensaiapp.engine.LoadedModelInfo
import io.github.tensaiapp.engine.ReasoningBudget
import io.github.tensaiapp.engine.SamplingConfig
import io.github.tensaiapp.engine.StopReason
import io.github.tensaiapp.engine.ToolCall
import io.github.tensaiapp.runtime.ActiveSessionTracker
import io.github.tensaiapp.runtime.AppForegroundTracker
import io.github.tensaiapp.runtime.GenerationStopSignal
import io.github.tensaiapp.runtime.InferenceQueue
import io.github.tensaiapp.runtime.PartTokenCache
import io.github.tensaiapp.runtime.QueueState
import io.github.tensaiapp.runtime.RuntimeGraph
import io.github.tensaiapp.runtime.residentModel
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Fewest tokens a reply must have generated for [ChatViewModel] to warm the cache up behind it
 * (TEN-88). What a warm-up saves is the length of the reply (the next turn re-decodes it, at 6-7
 * tok/s prefill on the reference device), so for a short one the saving is a few seconds, not
 * worth the CPU busy in the background for nothing.
 */
internal const val WarmUpMinTokens = 300

/**
 * Whether a generation that ended with [stats] is worth a warm-up: it ended on its own or at its
 * token cap, and was long enough to matter. A reply the user stopped or the engine broke has no
 * stats at all, so it never gets here; every other [StopReason] is either a reply with nothing to
 * cache or one cut by a budget this ticket did not look at.
 */
internal fun worthWarmingUp(stats: DecodeStats): Boolean =
    stats.tokensGenerated >= WarmUpMinTokens &&
        (stats.stopReason == StopReason.Complete || stats.stopReason == StopReason.TokenBudget)

/** Minimum time between DB writes of a streaming assistant message's growing content — see [ChatViewModel.streamInto]. */
private const val StreamPersistIntervalMs = 100L

/**
 * The conversations with an agent turn running in this process, so a chat screen opened (or rebuilt) while the turn
 * goes on still shows its calls as running rather than stopped. Process-wide for the reason [ActiveSessionTracker] is.
 */
private val runningAgentTurns = MutableStateFlow<Set<String>>(emptySet())

/**
 * How an assistant reply's generation ended, as far as the *reader* is concerned — see
 * [ChatMessageUi.ending].
 *
 * One field with three values, not two booleans, and that is the point: a reply cannot both have
 * broken and have been stopped, so the type says so and no chip row has to be trusted to enforce
 * it. TEN-52 introduced the failure case as a `failed: Boolean`; TEN-39 adds the third and folds
 * both into this.
 */
enum class ReplyEnding {
    /**
     * Nothing to report. The model ended its own turn, or the reply is still streaming — both look
     * identical here, and both are correctly silent: a partial reply on screen right now needs no
     * label, the cursor is the label.
     */
    Normal,

    /** [StopReason.Failed] — generation ended on a [DecodeEvent.Error] (TEN-52). */
    Failed,

    /** [StopReason.Cancelled] — the user tapped stop (TEN-39). See [ChatViewModel.stop]. */
    Stopped,
}

/**
 * UI-facing view of a persisted [MessageEntity] — `null` for system/tool messages, which have
 * nothing to render in V1 (see [toUi]).
 *
 * @param ending how generation ended, when that is worth saying — see [ReplyEnding]. Orthogonal to
 *   [stats], not part of it: neither a failed nor a stopped decode reports stats at all, so this is
 *   the only trace either one leaves.
 */
data class ChatMessageUi(
    val id: String,
    val isUser: Boolean,
    val content: String,
    val thinking: String?,
    val stats: MessageStatsUi?,
    val ending: ReplyEnding,
)

/**
 * How (if at all) an assistant reply's generation was cut short — see [MessageStatsUi.truncation].
 */
enum class TruncationReason {
    /** Not truncated — the model ended its turn on its own. */
    None,

    /**
     * [StopReason.ReasoningBudgetExhausted]: the whole budget went on reasoning that never closed,
     * so there is no answer under it. Since TEN-85 this is the rare case it describes and not the
     * routine one — reaching the reasoning half of the budget no longer ends a reply.
     */
    Reasoning,

    /**
     * [StopReason.ResponseBudgetExhausted], or [StopReason.TokenBudget] for a reply generated
     * with no reasoning/response split (thinking off, or from before TEN-61): the answer itself
     * is cut off mid-thought.
     */
    Response,

    /**
     * [StopReason.ContextBudgetClipped]: the reply used up what the context window had left, not
     * the max-tokens the user set (TEN-79). Kept apart from [Response] because the two point at
     * different actions — "your reply hit its limit, raise it if you want more" against "this
     * conversation has no room left, and raising anything will not give it any". The banner says
     * the second one in full; this is the mark on the row that outlives it.
     */
    Context,
}

/**
 * Formatted decode stats for a finished assistant reply — `null` while streaming, or if generation
 * was stopped early.
 *
 * @param truncation `None` for replies stored before v5 of the schema too — the reason wasn't
 *   recorded there and guessing would be worse than staying quiet (TEN-60).
 */
data class MessageStatsUi(
    val tokPerSec: Float,
    val tokensGenerated: Int,
    val ttftMs: Long,
    val truncation: TruncationReason,
)

internal fun MessageEntity.toUi(): ChatMessageUi? {
    val isUser = when (role) {
        "user" -> true
        "assistant" -> false
        else -> return null // system/tool: tool messages are drawn as tool lines, see [buildChatItems].
    }
    // A thinking-off reply (Qwen3 `/no_think`) still carries an empty `<think>` pair: whitespace is no reasoning.
    val shownThinking = thinking?.takeIf { it.isNotBlank() }
    // The assistant message of a tool call with nothing said before it is a trace, not a reply: it shows only its
    // thinking (as a reasoning block, no bubble) when it has some, and its calls are drawn by [buildChatItems].
    if (role == "assistant" && content.isEmpty() && !toolCallsJson.isNullOrBlank() && shownThinking == null) return null
    // No chip on a reply that made calls (nor on rows stored before that): it belongs to the answer.
    val stats = if (tokensGenerated != null && tokPerSec != null && ttftMs != null && toolCallsJson.isNullOrBlank()) {
        val truncation = when (stopReason) {
            StopReason.ReasoningBudgetExhausted.name -> TruncationReason.Reasoning
            StopReason.ResponseBudgetExhausted.name, StopReason.TokenBudget.name -> TruncationReason.Response
            StopReason.ContextBudgetClipped.name -> TruncationReason.Context
            else -> TruncationReason.None
        }
        MessageStatsUi(
            tokPerSec = tokPerSec,
            tokensGenerated = tokensGenerated,
            ttftMs = ttftMs,
            truncation = truncation,
        )
    } else {
        null
    }
    return ChatMessageUi(
        id = id,
        isUser = isUser,
        content = content,
        thinking = shownThinking,
        stats = stats,
        // Read off the recorded reason, never inferred from what is missing. A row still streaming
        // has no stats and no reason either, so "absence means the user stopped it" would label
        // every reply in flight as stopped — which is exactly why TEN-39 records Cancelled instead
        // of continuing to treat stopping as the absence of the other two outcomes. Rows written
        // before that (a stopped reply from an older build) stay Normal: the reason was not
        // recorded, and guessing is worse than staying quiet, same call TEN-60 made for truncation.
        ending = when (stopReason) {
            StopReason.Failed.name -> ReplyEnding.Failed
            StopReason.Cancelled.name -> ReplyEnding.Stopped
            else -> ReplyEnding.Normal
        },
    )
}

/** Surfaced by [ChatViewModel.send]/[ChatViewModel.regenerate] when they can't proceed. */
sealed class ChatError {
    /** No model is loaded — the UI's CTA routes to Models. */
    data object NoModelLoaded : ChatError()

    /**
     * The conversation has outgrown the model's context window, on either of the two ways that
     * shows up:
     *
     * - the prompt alone already filled it, so the attempt produced zero tokens and no assistant
     *   message was kept for it (TEN-65, [StopReason.ContextBudgetExhausted]) — this banner is the
     *   only trace of it the user gets;
     * - the prompt left a little room and the reply spent all of it (TEN-79,
     *   [StopReason.ContextBudgetClipped]) — there *is* a bubble, cut off, and the banner is what
     *   says the cut came from the window rather than from the max-tokens setting.
     *
     * One error for both because the user's move is the same either way, and it is not in the
     * settings sheet: start a new conversation, or reload the model with a larger context.
     */
    data object ContextWindowFull : ChatError()

    /**
     * A generation broke before producing a single token, so there is no partial reply to hang
     * [StopReason.Failed] on and nothing was kept (TEN-64). Structurally the same situation as
     * [ContextWindowFull] — an attempt that left no turn behind — and reported the same way, as a
     * dismissible banner with a retry, for the same reason: silence would leave the user staring at
     * a chat that simply did not answer.
     *
     * This replaces TEN-52's other answer to the same case, which was to persist
     * `"Error: " + the exception's message` as the reply's body (TEN-39). That put a JNI failure
     * string — `llama_decode failed`, and worse — in a message bubble, permanently, in the middle
     * of a conversation, and then replayed it into every later prompt as if the assistant had said
     * it. The failure is worth reporting; the engine's wording of it is not, and the history is not
     * the place to keep it.
     */
    data object GenerationFailed : ChatError()
}

/**
 * Whether a decode's resolved `content`/`thinking` are worth persisting at all (TEN-64) — `false`
 * when both came out empty, whether because generation was cancelled before a first token, or
 * because it never started at all (TEN-65's [io.github.tensaiapp.engine.StopReason.ContextBudgetExhausted]).
 * An empty-everything assistant turn is not a reply: keeping it would show a blank bubble with no
 * explanation. The one exception is a stop the user asked for ([keepsEmptyTurn], TEN-110): that
 * turn is kept empty and recorded as Cancelled, so it carries the "stopped" chip and Regenerate,
 * and [ChatRepository.toEngineMessages] keeps it out of every later prompt.
 *
 * Does not special-case a failure, and no longer needs to. TEN-52 used to hand an empty failed turn
 * the exception's own message so it would pass this check and survive; TEN-39 lets it be deleted
 * like any other empty turn and reports the failure as a [ChatError.GenerationFailed] banner
 * instead. The user still hears about it — they just don't get a JNI error string permanently
 * lodged in their conversation, and replayed into every prompt after it.
 */
internal fun hasPersistableContent(content: String, thinking: String?): Boolean =
    content.isNotEmpty() || !thinking.isNullOrEmpty()

/**
 * Whether an assistant turn with nothing in it is still kept, for the reason it ended (TEN-110):
 * only a stop the user asked for. Tapping stop during prefill, before any token, used to delete the
 * turn like any other empty one ([hasPersistableContent]), leaving the user's message alone on
 * screen with no bubble, no "stopped" chip and no Regenerate, while the same tap a token later left
 * all three. The row is kept empty and recorded as [StopReason.Cancelled] so both look the same.
 *
 * The other empty endings stay deleted: a failure reports through [ChatError.GenerationFailed], and
 * an exhausted context through [ChatError.ContextWindowFull], neither of which has a stop to show.
 * The empty row never reaches a prompt, see [ChatRepository.toEngineMessages].
 */
internal fun keepsEmptyTurn(stopReason: StopReason?): Boolean = stopReason == StopReason.Cancelled

/** Whether [text] holds the opening of a tool call in any of the formats llama.cpp parses (a guess on the marker, not a parse). */
internal fun hasToolCallMarker(text: String): Boolean =
    ToolCallMarkers.any { text.contains(it, ignoreCase = true) }

private val ToolCallMarkers = listOf("tool_call", "python_tag", "<function", "<invoke")

/**
 * What an agent turn ends on when the model, after its tool calls, wrote no answer at all (TEN-172).
 * Stored text, so it follows the app language ([language] is a two-letter code) rather than a resource.
 */
internal fun toolFallbackMessage(language: String): String =
    if (language == "fr") {
        "Je n'ai pas pu terminer avec les outils disponibles."
    } else {
        "I couldn't finish with the tools available."
    }

/**
 * The per-conversation settings the chat screen edits, in the shape [ConversationEntity] stores
 * them. Read off the row once there is one, held in memory for a draft (TEN-106) and handed to
 * [ChatRepository.createConversation] on the draft's first send. The defaults are a fresh row's.
 */
private data class ConversationSettings(
    val samplingJson: String? = null,
    val systemPrompt: String? = null,
    val enableThinking: Boolean = true,
    val toolsEnabled: Boolean = true,
)

private fun ConversationEntity?.settings(): ConversationSettings =
    if (this == null) ConversationSettings() else ConversationSettings(samplingJson, systemPrompt, enableThinking, toolsEnabled)

/**
 * Drives a single conversation's chat screen (TEN-32): streams [ChatRepository]'s persisted
 * messages, and turns [InferenceQueue.generate] events into DB writes as a reply streams in.
 *
 * `conversationId` names an existing conversation, or is `null` for a new chat (TEN-106). A new
 * chat is a *draft*: no row exists until its first [send], which creates it (see
 * [ensureConversation]) and from then on this instance is bound to that id, exposed as
 * [currentConversationId]. Until then the screen reads an empty history and the settings held in
 * [draft], so opening and leaving a new chat writes nothing. The route keeps saying "new" after
 * the row exists: swapping it for the real id would rebuild this instance in the middle of the
 * first reply, dropping what only it holds ([error], [canStop]'s own half). `ChatScreen` saves
 * [currentConversationId] instead, so a screen recreated after process death reopens the
 * conversation rather than a fresh draft.
 *
 * Like [io.github.tensaiapp.ui.screens.loading.LoadModelViewModel], every generation runs
 * on [scope] (defaulting to [RuntimeGraph.applicationScope]), **not** a Compose-scoped coroutine:
 * leaving the chat screen (back to the root chat, switching conversations) must not cut off a reply that's
 * mid-stream, the same way a model load survives leaving the loading screen. This class is
 * deliberately not an `androidx.lifecycle.ViewModel` for the same reason `LoadModelViewModel`
 * isn't — the project has no such dependency yet (see `RuntimeGraph`'s "framework-free
 * composition root" KDoc). `ChatScreen` `remember`s one instance per `conversationId`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    conversationId: String?,
    private val chatRepository: ChatRepository = RuntimeGraph.chatRepository,
    private val inferenceQueue: InferenceQueue = RuntimeGraph.inferenceQueue,
    private val modelRepository: ModelRepository = RuntimeGraph.modelRepository,
    private val sessionRepository: SessionRepository = RuntimeGraph.sessionRepository,
    private val scope: CoroutineScope = RuntimeGraph.applicationScope,
    private val agentRuntime: AgentRuntime = RuntimeGraph.agentRuntime,
    private val confirmationGate: UiConfirmationGate = RuntimeGraph.confirmationGate,
    private val askUserGate: AskUserGate = RuntimeGraph.askUserGate,
    private val memoryPrompt: MemoryPrompt = RuntimeGraph.memoryPrompt,
    private val partTokens: PartTokenCache = RuntimeGraph.partTokenCache,
) {
    private val _conversationId = MutableStateFlow(conversationId)

    /** The conversation this instance is bound to, `null` while it is still a draft (TEN-106). Set once, by the draft's first [send]. */
    val currentConversationId: StateFlow<String?> = _conversationId.asStateFlow()

    /** A draft's settings, until its first [send] turns them into a row. Unused once [currentConversationId] is set. */
    private val draft = MutableStateFlow(ConversationSettings())

    /**
     * Serializes "is there a row yet" between [ensureConversation] and the settings setters, so a
     * setting changed while the first send is creating the row lands either in the draft it copies
     * or in the row it created, never in a draft nobody reads any more.
     */
    private val draftMutex = Mutex()

    /** This conversation's settings: the row's once it exists, the [draft]'s before. */
    private val settings: Flow<ConversationSettings> = _conversationId.flatMapLatest { id ->
        if (id == null) draft else chatRepository.observeConversation(id).map { it.settings() }
    }

    /** Tool calls of an assistant message, parsed once per version of its `toolCallsJson`: a rebuild of [items] runs per streamed token. */
    private val parsedCalls = java.util.concurrent.ConcurrentHashMap<String, Pair<String, List<ToolCall>>>()

    private fun callsOf(message: MessageEntity): List<ToolCall> {
        val json = message.toolCallsJson.orEmpty()
        parsedCalls[message.id]?.takeIf { it.first == json }?.let { return it.second }
        return ToolCallsJson.decode(json).also { parsedCalls[message.id] = json to it }
    }

    /**
     * What the list shows (TEN-173, TEN-174): the messages with the tool lines and confirmation cards of the
     * agent's calls between them, see [buildChatItems]. Rebuilt from the stored rows as a turn writes them, so
     * a chat looks the same running or reopened; only the live parts (a call still running, the card waiting
     * for its answer) come from this process. Built off the main thread. Empty for a draft.
     */
    val items: Flow<List<ChatItem>> = _conversationId.flatMapLatest { id ->
        if (id == null) {
            flowOf(emptyList())
        } else {
            combine(
                chatRepository.observeMessages(id),
                chatRepository.observeToolCalls(id),
                runningAgentTurns,
                confirmationGate.pending,
                confirmationGate.allowed,
            ) { stored, calls, running, pending, allowed ->
                buildChatItems(stored, calls, id in running, pending.keys, allowed, agentRuntime::requiresConfirmation, ::callsOf)
            }.flowOn(Dispatchers.Default)
        }
    }

    /** This conversation's messages, oldest first, mapped for display (see [toUi]): the message rows of [items], one subscription to the store. */
    val messages: Flow<List<ChatMessageUi>> = items.map { list -> list.filterIsInstance<MessageItem>().map { it.message } }

    /** The user's answer to the confirmation card of [callId]. The turn goes on with it. */
    fun answerConfirmation(callId: String, allow: Boolean) = confirmationGate.answer(callId, allow)

    /**
     * The question of this chat's running turn the model is waiting on (TEN-187), `null` when there is none: the screen
     * swaps the input bar for its card. At most one, a turn runs its calls one after the other.
     */
    val pendingQuestion: Flow<PendingQuestion?> = askUserGate.pending.map { pending -> pending.values.firstOrNull { it.callId in turnCallIds } }
        .distinctUntilChanged()

    /** The user's answer to [callId]'s question: the chosen options, then the typed text if any. The turn goes on with it. */
    fun answerQuestion(callId: String, answers: List<String>) = askUserGate.answer(callId, answers)

    /**
     * The user closed the question of [callId] (the X): the model is told so, the turn goes on.
     * While a question is open the card replaces the input pill, so Stop is out of reach: the X is the way out, the model
     * goes on without an answer and the pill (with its Stop) is back as soon as the question closes.
     */
    fun dismissQuestion(callId: String) = askUserGate.dismiss(callId)

    /** Mirrors [InferenceQueue.state] — [ChatScreen] derives `isGenerating`/`modelName`/`nCtx` from it directly. */
    val queueState: StateFlow<QueueState> = inferenceQueue.state

    /** `true` for as long as a reply *this* instance started is still streaming — see [canStop]. */
    private val _generatingHere = MutableStateFlow(false)

    /**
     * `true` for as long as an agent turn *this* instance started is running, tool execution included:
     * the queue is idle then, so [canStop] cannot read it off [queueState]. See [stop].
     */
    private val _agentRunning = MutableStateFlow(false)

    /** The running agent turn's stop signal, marked by [stop] so the reply it cuts is recorded as stopped. */
    @Volatile
    private var agentStopSignal: GenerationStopSignal? = null

    /** The ids of the calls the running agent turn made, so [leave] denies the cards of this turn and no other. */
    @Volatile
    private var turnCallIds: Set<String> = emptySet()

    /**
     * Whether the stop button on *this* conversation's screen would stop a reply belonging to it
     * (TEN-79).
     *
     * [queueState] alone cannot answer that. It is app-wide, so with a reply streaming in
     * conversation A every conversation's screen reads `Generating` and every one of them offers a
     * stop — which, pressed on B, cancels A. The user is shown a control over a generation they
     * are not looking at, and A's reply is cut off with nothing on screen to explain why.
     *
     * Ownership is read from two sources because neither covers the whole of it on its own.
     * [_generatingHere] is exact but instance-scoped: navigating away and back rebuilds the
     * `ChatViewModel` (`ChatScreen` `remember`s one per conversation id) while the generation, which
     * runs on [scope], keeps going — the new instance never started it and would offer no way to
     * stop a reply that is visibly still streaming. [ActiveSessionTracker] survives that, being
     * keyed on the conversation rather than on an instance, but is best-effort by design (see its
     * KDoc) and reports nothing for a loaded model it cannot match back to a persisted row. Either
     * one saying "mine" is enough; requiring both would inherit both weaknesses.
     */
    val canStop: Flow<Boolean> =
        combine(inferenceQueue.state, ActiveSessionTracker.current, _generatingHere, _conversationId, _agentRunning) { state, active, here, id, agent ->
            // An agent turn is stoppable between its generations too: a tool runs while the queue is idle.
            agent || (state is QueueState.Generating && (here || (id != null && active?.first == id)))
        }

    /**
     * Whether the currently loaded model's chat template supports thinking — gates whether
     * [ChatScreen] shows the enable/disable toggle at all. [QueueState.ModelReady.info] carries no
     * `supportsThinking` flag of its own — that's a GGUF-header concept
     * ([io.github.tensaiapp.engine.GgufInfo.supportsThinking]), not something the engine's
     * load-time [io.github.tensaiapp.engine.LoadedModelInfo] repeats — so this matches the
     * loaded model back to its persisted [io.github.tensaiapp.data.models.ModelEntity], by
     * [io.github.tensaiapp.engine.LoadedModelInfo.modelId] since TEN-80. The display-name
     * match it replaces could land on a different row that happened to share a GGUF name and read
     * that row's `supportsThinking` instead — the toggle then showed or hid according to a file
     * that was not loaded.
     *
     * Read through [residentModel] rather than `ModelReady` alone, or the toggle disappears from
     * the chrome for the whole duration of a reply — the model is still resident (TEN-76).
     */
    val currentModelSupportsThinking: Flow<Boolean> =
        combine(inferenceQueue.state, modelRepository.models) { state, models ->
            val loadedId = state.residentModel?.modelId ?: return@combine false
            models.any { it.id == loadedId && it.thinkingAvailable }
        }

    /**
     * Whether the currently loaded model's chat template takes tools, the Tools badge's own rule
     * ([io.github.tensaiapp.data.models.ModelEntity.toolsAvailable]). Gates the agent path of [generateReply].
     */
    val currentModelSupportsTools: Flow<Boolean> =
        combine(inferenceQueue.state, modelRepository.models) { state, models ->
            val loadedId = state.residentModel?.modelId ?: return@combine false
            models.any { it.id == loadedId && it.toolsAvailable }
        }

    /** This conversation's persisted thinking toggle — see [ConversationEntity.enableThinking]. Defaults to `true` (matching [SamplingConfig.enableThinking]'s default) until the conversation row is read. */
    val enableThinking: Flow<Boolean> = settings.map { it.enableThinking }

    /** This conversation's persisted Tools switch, see [ConversationEntity.toolsEnabled]. On until the conversation row is read. */
    val toolsEnabled: Flow<Boolean> = settings.map { it.toolsEnabled }

    /** Flips this conversation's [ConversationEntity.toolsEnabled]: applies to the next [send]/[regenerate], like [setEnableThinking]. */
    fun setToolsEnabled(enabled: Boolean) {
        updateSettings({ it.copy(toolsEnabled = enabled) }) { id -> chatRepository.setToolsEnabled(id, enabled) }
    }

    /** Flips this conversation's [ConversationEntity.enableThinking] — takes effect on the next [send]/[regenerate], not the one already streaming. */
    fun setEnableThinking(enabled: Boolean) {
        updateSettings({ it.copy(enableThinking = enabled) }) { id -> chatRepository.setEnableThinking(id, enabled) }
    }

    /**
     * This conversation's sampling override, decoded from [ConversationEntity.samplingJson]
     * (TEN-35) — [SamplingConfig]'s defaults until the row is read, and for a conversation that has
     * never been customized. `enableThinking`/`reasoningBudget` are *not* meaningful here: both are
     * resolved at generation time, see [generateReply] and [SamplingJson]'s KDoc.
     */
    val sampling: Flow<SamplingConfig> = settings.map { SamplingJson.decode(it.samplingJson) }

    /** This conversation's [ConversationEntity.systemPrompt], empty when unset — see [ChatRepository.setSystemPrompt]. */
    val systemPrompt: Flow<String> = settings.map { it.systemPrompt.orEmpty() }

    /** Persists [config] for this conversation. Applies to the next [send]/[regenerate] — see [ChatRepository.setSampling]. */
    fun setSampling(config: SamplingConfig) {
        updateSettings({ it.copy(samplingJson = SamplingJson.encode(config)) }) { id -> chatRepository.setSampling(id, config) }
    }

    /**
     * Drops the sampling override and, when [resetThinking], turns thinking back on, in one write.
     * The system prompt is left alone, see [ChatRepository.resetSampling].
     */
    fun resetTuning(resetThinking: Boolean) {
        updateSettings({
            it.copy(samplingJson = null, enableThinking = if (resetThinking) true else it.enableThinking)
        }) { id -> chatRepository.resetTuning(id, resetThinking) }
    }

    /** Persists [prompt] as this conversation's system prompt. Applies to the next [send]/[regenerate]. */
    fun setSystemPrompt(prompt: String) {
        updateSettings({ it.copy(systemPrompt = prompt.ifBlank { null }) }) { id -> chatRepository.setSystemPrompt(id, prompt) }
    }

    /**
     * Applies a settings change to the draft while there is no row ([onDraft]), to the row once
     * there is one ([onRow]). Same rules for both: the change applies to the next generation.
     */
    private fun updateSettings(
        onDraft: (ConversationSettings) -> ConversationSettings,
        onRow: suspend (conversationId: String) -> Unit,
    ) {
        scope.launch {
            val id = draftMutex.withLock {
                _conversationId.value ?: run {
                    draft.update(onDraft)
                    null
                }
            }
            if (id != null) onRow(id)
        }
    }

    /**
     * This instance's conversation id, creating the row first if this is still a draft (TEN-106).
     * The row is born with the draft's settings and stamped with the model resident right now, the
     * one about to answer. Idempotent: under [draftMutex], so a second caller gets the first one's
     * id rather than a second row.
     */
    private suspend fun ensureConversation(): String = draftMutex.withLock {
        _conversationId.value ?: run {
            val settings = draft.value
            val id = chatRepository.createConversation(
                modelId = inferenceQueue.state.value.residentModel?.modelId,
                samplingJson = settings.samplingJson,
                systemPrompt = settings.systemPrompt,
                enableThinking = settings.enableThinking,
                toolsEnabled = settings.toolsEnabled,
            )
            _conversationId.value = id
            id
        }
    }

    /**
     * Latest [send]/[regenerate] failure, consumed by [ChatScreen] via [clearError].
     *
     * **Scoped to one generation, and cleared by the next one starting** (TEN-84). Every value
     * this holds describes a single attempt — "that one broke before its first token", "that one
     * ran out of window" — so a new attempt makes it stale by definition, and nothing else was
     * clearing it: only [clearError], i.e. only the user dismissing the banner by hand.
     *
     * Left to survive, it does not merely linger. The banner is rendered above the message list
     * regardless of which turn it came from, so a failure the user ignored sits over a *later*
     * reply that succeeded, and its Retry button — which reads as "retry the thing that failed" —
     * calls [regenerate], whose first act is to delete the conversation's last assistant message.
     * That message is the successful reply. The stale banner ends up destroying the very turn that
     * proved it obsolete. [ChatError.ContextWindowFull] had the same shape with a slower fuse:
     * it outlived a reload of the model at a larger context, still telling the user to do the
     * thing they had just done.
     */
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
     * The cache warm-up this instance started after its last long reply, if it is still running
     * (TEN-88). Its own job rather than part of [generationJob]: that one must be over for
     * [send] to accept the next message, and a warm-up is precisely what runs *after* the reply is
     * done, while the user reads. [send], [regenerate] and [leave] cancel it; so does the queue,
     * for everything else that wants the engine (see [InferenceQueue.warmUp]).
     */
    private var warmUpJob: Job? = null

    /**
     * Keeps this instance honest about its row: run from the screen's composition (cancelled with it).
     * When the row it is bound to is gone (deleted from the Chats page while this screen sat under it
     * on the back stack, or already gone when the screen came back), it stops what it owns and falls
     * back to a fresh draft, so nothing is written for a deleted id and a later send starts a new
     * conversation. A draft has no row to watch and emits nothing here.
     */
    suspend fun watchConversation() {
        _conversationId
            .flatMapLatest { id -> if (id == null) emptyFlow() else chatRepository.observeConversation(id).map { id to it } }
            .collect { (id, row) -> if (row == null) dropDeletedConversation(id) }
    }

    private suspend fun dropDeletedConversation(id: String) = draftMutex.withLock {
        if (_conversationId.value != id) return@withLock
        // Before the reset, and the pointer cleared with it: a reply of ours still streaming would
        // otherwise leave the deleted id in ActiveSessionTracker for the next save to pick up.
        if (ActiveSessionTracker.current.value?.first == id) {
            inferenceQueue.cancelActive()
            ActiveSessionTracker.clear()
        }
        generationJob?.cancel()
        warmUpJob?.cancel()
        _error.value = null
        draft.value = ConversationSettings()
        _conversationId.value = null
    }

    /**
     * To be called when the screen showing this conversation goes away (a different conversation,
     * a new chat, Models): the user is no longer about to type here, so the warm-up that was
     * preparing for it stops and gives the CPU back. What it had already decoded stays in the
     * cache. Idempotent, and a no-op when nothing is warming up. Nothing else is torn down: a reply
     * still streaming must survive leaving the screen, see this class's KDoc.
     *
     * One thing is answered for the user: a confirmation card still waiting (TEN-174) is denied, nobody is left
     * to tap it and the turn would hang on it. The model is told the action was refused.
     * Same for a question (TEN-187): it is dismissed, the model is told the user gave no answer.
     */
    fun leave() {
        warmUpJob?.cancel()
        if (_agentRunning.value) {
            confirmationGate.deny(turnCallIds)
            askUserGate.dismiss(turnCallIds)
        }
    }

    /**
     * Appends [text] as a user message and streams a reply. A no-op (besides surfacing
     * [ChatError.NoModelLoaded]) if no model is loaded; also a no-op if [text] is blank or a
     * generation is already running — `ChatScreen` disables send in both cases, this is just belt
     * and suspenders against a stray call.
     *
     * Clears [error] on the way in — see its KDoc. Here rather than deeper in (in [streamInto],
     * say) so the banner dies with the tap that supersedes it, not several suspension points
     * later: [generateReply] restores a persisted session before it decodes anything, which is
     * file I/O, and a generation that breaks before ever reaching the engine must not leave the
     * previous one's banner up either.
     */
    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || generationJob?.isActive == true) return
        if (inferenceQueue.state.value !is QueueState.ModelReady) {
            _error.value = ChatError.NoModelLoaded
            return
        }
        _error.value = null
        // Synchronously, before the launch: the warm-up is read-only on the conversation, but one
        // that starts after this message is stored would read it as the end of the history.
        warmUpJob?.cancel()
        generationJob = scope.launch {
            val conversationId = ensureConversation()
            chatRepository.appendUserMessage(conversationId, trimmed)
            generateReply(conversationId)
        }
    }

    /**
     * Stops the active generation, if any. The partial reply streamed so far is left exactly as
     * is — see [streamInto]'s `finally`, which persists whatever was accumulated regardless of how
     * generation ended, now with [StopReason.Cancelled] recorded against it.
     *
     * **This reverses TEN-52's deliberate silence here** ("the user knows they tapped stop, so
     * there is nothing to tell them"), and the reasoning it reverses is right about the moment and
     * wrong about the row. In the second the button is pressed there is indeed nothing to say. But
     * the row outlives that second: reopened a week later, or scrolled past twenty turns up, a
     * stopped reply was byte-for-byte a complete short answer — no stats, no reason, no mark — and
     * the reader had no way to tell "the model said this" from "the model was saying this when I
     * cut it off". That is the same class of confusion TEN-52 itself fixed for failures, with the
     * user rather than the engine as the cause. It also matters beyond the reading: a truncated
     * reply is replayed into every later prompt by [ChatRepository.toEngineMessages] as if it were
     * a finished one, and the chip is what tells the user why the model keeps building on half a
     * sentence.
     *
     * The chip cannot collide with TEN-52's "generation failed" one: both come from the single
     * [ChatMessageUi.ending] field, so the row renders exactly one of them, and `failed` still wins
     * when a decode manages to both error and be stopped (see the `finally` below).
     *
     * Nothing is recorded *here* (TEN-79). This method used to set a flag on this instance, which
     * is the wrong object to hold it: the queue is app-wide and [InferenceQueue.cancelActive] stops
     * whichever generation is running, which is not necessarily one this conversation started. The
     * fact now travels with the generation — see [GenerationStopSignal] — so the reply that
     * actually gets cut is the reply that gets the chip, whichever screen the button was on and
     * whether it was a button at all (the notification's stop action lands on the same path).
     */
    fun stop() {
        inferenceQueue.cancelActive()
        // The notification's Stop only calls [InferenceQueue.cancelActive], so it does nothing during a tool: only
        // this in-app Stop ends the turn there (see [AgentRuntime]).
        // A tool running between two generations has no queue job to cancel: end the whole turn, its
        // `finally` records what was cut. Marked first, for the reason [InferenceQueue.cancelActive] does.
        if (_agentRunning.value) {
            agentStopSignal?.markRequested()
            generationJob?.cancel()
        }
    }

    /**
     * Drops the last assistant reply (if the conversation's last message is one — read fresh from
     * [ChatRepository] rather than remembered in this instance, so it's correct even if this
     * `ChatViewModel` was just recreated, e.g. after navigating away and back) and generates a new
     * one for the same history. A no-op if a generation is already running or no model is loaded
     * (same [ChatError.NoModelLoaded] surfacing as [send]).
     *
     * Clears [error] on the way in for the same reason [send] does, and this is the entry point
     * the failure banner's own Retry button goes through: it is what makes a retry that works
     * take the banner down with it.
     */
    fun regenerate() {
        if (generationJob?.isActive == true) return
        // A draft has no reply to redo. Not reachable from the UI (no bubble, and a failure banner
        // only follows a send, which created the row), checked so it stays a no-op regardless.
        val conversationId = _conversationId.value ?: return
        if (inferenceQueue.state.value !is QueueState.ModelReady) {
            _error.value = ChatError.NoModelLoaded
            return
        }
        _error.value = null
        warmUpJob?.cancel()
        generationJob = scope.launch {
            // The whole last turn: with tools it is replies and tool results, not one assistant message.
            chatRepository.observeMessages(conversationId).first()
                .takeLastWhile { it.role == "assistant" || it.role == "tool" }
                .forEach { chatRepository.deleteMessage(it.id) }
            generateReply(conversationId)
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

    private val _contextUsage = MutableStateFlow<ContextUsageState>(ContextUsageState.NoModel)

    /**
     * The context ring's figure (TEN-160): the resident model's KV-cache fill over its window. Not
     * observed, refreshed by [refreshContextUsage]: here after a restore, a generation (ended,
     * stopped or failed) and a warm-up, and by [ChatScreen] whenever [queueState] moves (model load,
     * swap, unload) or this instance's conversation gets its id.
     */
    val contextUsage: StateFlow<ContextUsageState> = _contextUsage.asStateFlow()

    /** Model loading or a reply generating: the ring spins. App-wide like [queueState], the cache is one. */
    val busy: Flow<Boolean> = inferenceQueue.state.map { it is QueueState.Loading || it is QueueState.Generating }

    private val _contextSheetOpen = MutableStateFlow(false)

    /** Set by a tap on the ring, cleared by [closeContextSheet]. The sheet renders once [contextSheet] is built. */
    val contextSheetOpen: StateFlow<Boolean> = _contextSheetOpen.asStateFlow()

    private val _contextSheet = MutableStateFlow<ContextSheetState?>(null)

    /** What the context sheet shows (TEN-161), built by [openContextSheet]; `null` while closed or still counting. */
    val contextSheet: StateFlow<ContextSheetState?> = _contextSheet.asStateFlow()

    // What an estimate falls back on once nothing is loaded to measure: the last window and template
    // overhead seen with a model resident. In memory only, per instance. The counts of the system prompt,
    // tool definitions and memory block are in [partTokens], shared by every instance.
    private var lastWindow = 0
    private var lastOverhead: Int? = null

    /** Tokens of an assistant message's serialized tool calls, by message id: not stored, counted once per instance. */
    private val toolCallTokens = HashMap<String, Int>()

    fun openContextSheet() {
        _contextSheetOpen.value = true
        scope.launch {
            val state = buildContextSheet()
            if (_contextSheetOpen.value) _contextSheet.value = state
        }
    }

    fun closeContextSheet() {
        _contextSheetOpen.value = false
        _contextSheet.value = null
    }

    /**
     * Builds the sheet's figures. With a model resident, the messages without a token count, or
     * counted under another model ([MessageEntity.tokenCountModelId]), are counted now and persisted;
     * without one the stored counts are all there is and the breakdown is an estimate (see
     * [contextBreakdown]). Reasoning is counted for the current turn only (the rows after the last user
     * message, see [ChatRepository.toEngineMessages]): that is all of it a prompt holds.
     */
    private suspend fun buildContextSheet(): ContextSheetState {
        val info = inferenceQueue.state.value.residentModel
        val id = _conversationId.value
        val conversation = id?.let { chatRepository.getConversation(it) }
        var history = id?.let { chatRepository.observeMessages(it).first() }.orEmpty()
        val systemPrompt = conversation?.systemPrompt?.takeIf { it.isNotBlank() }

        val definitionsText = definitionsTextFor(conversation, info)
        // The facts ride in the first user message, so they weigh on the prompt from the first send on.
        val memoryBlock = if (history.any { it.role == "user" }) memoryPrompt.blockFor(id) else null
        val partKey = info?.let { it.modelId ?: it.name } ?: conversation?.modelId
        val thinkingTexts = history.drop(history.indexOfLast { it.role == "user" } + 1)
            .filter { it.role == "assistant" && !it.thinking.isNullOrBlank() }.map { it.thinking!! }

        if (info != null) {
            val countedWith = info.modelId ?: info.name
            val stale = history.filter {
                (it.role == "user" || it.role == "assistant" || it.role == "tool") && it.content.isNotEmpty() &&
                    (it.tokenCount == null || it.tokenCountModelId != countedWith)
            }
            val uncountedCalls = history.filter {
                it.role == "assistant" && !it.toolCallsJson.isNullOrBlank() && it.id !in toolCallTokens
            }
            val texts = stale.map { it.content } + uncountedCalls.map { it.toolCallsJson!! } +
                listOfNotNull(systemPrompt, definitionsText, memoryBlock) + thinkingTexts
            val counted = if (texts.isEmpty()) emptyList() else inferenceQueue.countTokens(texts)
            if (counted != null) {
                stale.forEachIndexed { i, message -> chatRepository.setTokenCount(message.id, counted[i], countedWith) }
                uncountedCalls.forEachIndexed { i, message -> toolCallTokens[message.id] = counted[stale.size + i] }
                var next = stale.size + uncountedCalls.size
                if (systemPrompt != null) partTokens.put(partKey, systemPrompt, counted[next++])
                if (definitionsText != null) partTokens.put(partKey, definitionsText, counted[next++])
                if (memoryBlock != null) partTokens.put(partKey, memoryBlock, counted[next++])
                thinkingTexts.forEach { partTokens.put(partKey, it, counted[next++]) }
                if (stale.isNotEmpty() && id != null) history = chatRepository.observeMessages(id).first()
            }
        }

        val inPrompt = history.filter { it.content.isNotEmpty() }
        val counts = ContextCounts(
            system = systemPrompt?.let { partTokens.get(partKey, it) } ?: 0,
            user = inPrompt.filter { it.role == "user" }.sumOf { it.tokenCount ?: 0 },
            assistant = inPrompt.filter { it.role == "assistant" }.sumOf { it.tokenCount ?: 0 },
            toolDefinitions = definitionsText?.let { partTokens.get(partKey, it) } ?: 0,
            memory = memoryBlock?.let { partTokens.get(partKey, it) } ?: 0,
            thinking = thinkingTexts.sumOf { partTokens.get(partKey, it) ?: 0 },
            // An assistant message of calls only has no content, so it is counted here and not above.
            toolResults = inPrompt.filter { it.role == "tool" }.sumOf { it.tokenCount ?: 0 } +
                history.filter { it.role == "assistant" }.sumOf { toolCallTokens[it.id] ?: 0 },
        )
        if (info != null) lastWindow = info.nCtxEffective
        val owner = ActiveSessionTracker.current.value?.first
        val cacheUsed = if (info != null && (owner == null || owner == id)) inferenceQueue.currentKvTokenCount() ?: 0 else null
        val breakdown = contextBreakdown(
            length = info?.nCtxEffective ?: lastWindow,
            cacheUsed = cacheUsed,
            counts = counts,
            reserve = ContextBudget.ReservedTokens,
            lastOverhead = lastOverhead,
        )
        // Not measured while tools are in the count: their template wrapping lands in the overhead too, and
        // would stick to the estimate of a conversation that has none.
        if (!breakdown.estimate && counts.toolDefinitions + counts.toolResults == 0) {
            lastOverhead = breakdown.rows.firstOrNull { it.part == ContextPart.Overhead }?.tokens ?: 0
        }

        val runtime = if (info == null) {
            ContextRuntime.NoModel(conversation?.modelId)
        } else {
            val model = info.modelId?.let { modelId -> modelRepository.models.first().firstOrNull { it.id == modelId } }
            ContextRuntime.Loaded(
                model = listOfNotNull(model?.shownName ?: info.name, model?.quantLabel?.takeIf { it.isNotBlank() })
                    .joinToString(" · "),
                backend = info.backend,
                nCtx = info.nCtxEffective,
                contextSetting = model?.contextSettingOverride,
                kvCacheType = info.kvCacheType,
                kvCacheBytes = model?.let { KvCacheSize.bytesAt(it, info.nCtxEffective.toLong(), info.kvCacheType) },
                lastReply = history.lastOrNull { it.role == "assistant" && it.tokPerSec != null }?.toUi()?.stats,
            )
        }
        val activity = id?.let { chatRepository.toolActivity(it) }.orEmpty()
        return ContextSheetState(breakdown, runtime, activity)
    }

    /**
     * The tool definitions as the sheet counts them, `null` when tools do not weigh on the prompt: they
     * only do when the agent path would run, their definitions are rendered into it, and the calls and
     * results of earlier turns are replayed (TEN-172).
     */
    private suspend fun definitionsTextFor(conversation: ConversationEntity?, info: LoadedModelInfo?): String? {
        val toolsInPlay = conversation?.toolsEnabled != false && agentRuntime.hasTools() &&
            (info?.modelId?.let { modelId -> modelRepository.models.first().any { it.id == modelId && it.toolsAvailable } } ?: false)
        if (!toolsInPlay) return null
        return agentRuntime.definitions().joinToString("\n") { "${it.name}: ${it.description} ${it.parametersJson}" }
    }

    /**
     * Counts the fixed parts of the prompt about to be sent, before the engine is busy with it: the
     * sheet cannot count while a reply is generating, and without these the cache the reply fills (the
     * system prompt and tools of the shared prefix, TEN-182) would read as template overhead (TEN-190).
     */
    private suspend fun countFixedParts(conversation: ConversationEntity?, info: LoadedModelInfo) {
        val systemPrompt = conversation?.systemPrompt?.takeIf { it.isNotBlank() }
        val key = info.modelId ?: info.name
        val texts = listOfNotNull(
            systemPrompt,
            definitionsTextFor(conversation, info),
            conversation?.let { memoryPrompt.blockFor(it.id) },
        ).filter { partTokens.get(key, it) == null }
        if (texts.isEmpty()) return
        val counted = inferenceQueue.countTokens(texts) ?: return
        texts.forEachIndexed { i, text -> partTokens.put(key, text, counted[i]) }
    }

    init {
        refreshContextUsage()
    }

    /**
     * Re-reads [contextUsage] off the queue. The cache is the engine's one, not this conversation's:
     * when [ActiveSessionTracker] says another conversation owns it, this one has nothing in it yet
     * (a restore happens on its first send) and reads as empty rather than showing someone else's fill.
     * With no owner reported the cache is taken as this one's.
     */
    fun refreshContextUsage() {
        val info = inferenceQueue.state.value.residentModel
        val length = info?.nCtxEffective ?: 0
        _contextUsage.value = if (info == null || length <= 0) {
            ContextUsageState.NoModel
        } else {
            val owner = ActiveSessionTracker.current.value?.first
            val used = if (owner == null || owner == _conversationId.value) {
                inferenceQueue.currentKvTokenCount() ?: 0
            } else {
                0
            }
            ContextUsageState.Loaded(used, length)
        }
    }

    /**
     * Starts a fresh assistant message and streams into it — the shared tail of [send] and
     * [regenerate].
     *
     * The reasoning/response split only applies when thinking is actually going to happen:
     * [ConversationEntity.enableThinking] defaults to `true` even for a conversation started
     * against a model with no thinking template at all (see its KDoc), so gating on that flag
     * alone would split — and needlessly shrink — the budget for a plain model that will never
     * emit [io.github.tensaiapp.data.chat.ThinkingStreamParser.CloseTag] in the first
     * place. [currentModelSupportsThinking] is what actually knows that.
     *
     * This is also where TEN-35's per-conversation sampling is read, and it is deliberately read
     * *here* rather than held in a field: "applies to the next decode, without reloading the model"
     * is exactly what building the [DecodeRequest] from a fresh row gives, for free. A change saved
     * while a reply is streaming lands on the turn after it, not on the one in flight.
     *
     * The three settings come from three places and are recombined here, each from whichever holds
     * it: the sampling knobs from [ConversationEntity.samplingJson], `enableThinking` from its own
     * column, and the reasoning split derived from the knobs' own `maxTokens` via
     * [ReasoningBudget.splitting] — never stored, so it cannot go stale against the max-tokens the
     * user actually set.
     */
    private suspend fun generateReply(conversationId: String) {
        // Before the sync below, not left to [InferenceQueue.generate]: a switch saves the outgoing
        // conversation first, and the save would then be the one cancelling the outgoing
        // conversation's warm-up, after this conversation's user has already been kept waiting.
        inferenceQueue.cancelWarmUp()
        syncSessionForConversationSwitch(conversationId)
        reportActiveSession(conversationId)
        refreshContextUsage()
        // The model this reply is generated on, which is what a warm-up behind it must find loaded.
        val model = inferenceQueue.state.value.residentModel

        val conversation = chatRepository.getConversation(conversationId)
        val history = chatRepository.observeMessages(conversationId).first()
        if (model != null) countFixedParts(conversation, model)
        // Tools need a template that takes them, the conversation's switch on, and a tool the user granted.
        // Anything else is the plain path below, untouched by tool calling (TEN-172).
        if (conversation?.toolsEnabled != false && currentModelSupportsTools.first() && agentRuntime.hasTools()) {
            streamAgentTurn(conversationId, decodeRequestFor(conversation, history))
            return
        }
        val assistantId = chatRepository.startAssistantMessage(conversationId)
        val request = decodeRequestFor(conversation, history)
        val finished = streamInto(assistantId, request)
        if (model != null && finished != null && worthWarmingUp(finished)) {
            warmUpAfterReply(conversationId, model)
        }
    }

    /** The plain [DecodeRequest] of [conversation]'s next reply, see [generateReply] for where each setting comes from. */
    private suspend fun decodeRequestFor(conversation: ConversationEntity?, history: List<MessageEntity>): DecodeRequest {
        val enableThinking = conversation?.enableThinking ?: true
        val thinkingActive = enableThinking && currentModelSupportsThinking.first()
        val sampling = SamplingJson.decode(conversation?.samplingJson)
        val reasoningBudget = if (thinkingActive) {
            ReasoningBudget.splitting(sampling.maxTokens, ThinkingStreamParser.CloseTag)
        } else {
            null
        }
        return DecodeRequest(
            messages = chatRepository.toEngineMessages(history, conversation?.systemPrompt, conversation?.let { memoryPrompt.blockFor(it.id) }),
            sampling = sampling.copy(enableThinking = enableThinking, reasoningBudget = reasoningBudget),
        )
    }

    /**
     * Starts the warm-up behind a long reply that just ended normally (TEN-88): rewrites the KV
     * cache into the form the next turn's prompt will take, while the user reads and types, see
     * [InferenceQueue.warmUp] and [io.github.tensaiapp.engine.WarmUpPrompt]. Launched on
     * its own job so [generateReply] (and with it [generationJob]) ends, and [send] is accepted,
     * the moment the reply is on screen.
     *
     * Two checks before any work, both against a window the reply left open. The cache must still
     * be this conversation's: [ActiveSessionTracker] is what [reportActiveSession] filled in when
     * this turn started, so anything else there means another conversation has taken the engine
     * since, and prefilling this one's history would only evict what that one is building (and, with
     * no id reported at all, there is nothing to say whose cache it is). And the history must still
     * end on the assistant's reply: a [send] that stored its message in the meantime has moved the
     * end of the conversation, and a prompt built on it would be the wrong prefix. [send] cancels
     * this job first, so that second check is a backstop for a different instance's send.
     *
     * The messages go through [ChatRepository.toEngineMessages] and the thinking flag is read off
     * the row, exactly as [generateReply] builds a real turn, so the prompt this prefills is
     * the one the next turn will render, to the byte; the engine then cuts it where the user's
     * words would start.
     */
    private fun warmUpAfterReply(conversationId: String, model: LoadedModelInfo) {
        // Only while the user is here. A reply that lands in the background has already had the cold
        // cache saved ([io.github.tensaiapp.runtime.saveSessionAfterBackgroundedGeneration]),
        // and a warm-up would burn minutes of CPU unseen, its result never written down: any save
        // ([InferenceQueue.saveSession]) cancels it.
        if (!AppForegroundTracker.isForeground.value) return
        warmUpJob?.cancel()
        warmUpJob = scope.launch {
            if (ActiveSessionTracker.current.value?.first != conversationId) return@launch
            val conversation = chatRepository.getConversation(conversationId) ?: return@launch
            val history = chatRepository.observeMessages(conversationId).first()
            if (history.lastOrNull()?.role != "assistant") return@launch
            inferenceQueue.warmUp(
                messages = chatRepository.toEngineMessages(history, conversation.systemPrompt, memoryPrompt.blockFor(conversationId)),
                enableThinking = conversation.enableThinking,
                expectedModel = model,
            )
            refreshContextUsage()
        }
    }

    /**
     * Resolves the [SessionModelKey] for whatever model is currently loaded, **together with the
     * [LoadedModelInfo] it was derived from**, or `null` if none is loaded or what is loaded
     * carries no [LoadedModelInfo.modelId] (a bare-path load, which no app path produces — see
     * `LoadModelViewModel`).
     *
     * The id is read straight off the load rather than looked up. Until TEN-80 this matched the
     * loaded model back to a persisted [io.github.tensaiapp.data.models.ModelEntity] by
     * display name, against a list ordered `importedAt DESC` — so with two quantisations of one
     * model imported (they share a GGUF name), loading the older one produced the *newer* one's
     * id. Every key built here then named the wrong file:
     * [io.github.tensaiapp.data.session.SessionEntity]'s whole promise is that
     * a session saved against one model is never handed to another's `loadSession`, and
     * [SessionRepository.restore]'s mismatch check was left comparing only `nCtx` and
     * `kvCacheType` — which two quantisations of one model, planned by the same deterministic
     * [io.github.tensaiapp.runtime.LoadPolicy] against the same device, will readily
     * agree on. The same wrong id also reached [ActiveSessionTracker.report], where
     * [syncSessionForConversationSwitch]'s `outgoingKey != incomingKey` could then read a real
     * model swap as "same model".
     *
     * The info is carried out rather than dropped because every restore has to hand it to
     * [InferenceQueue.restoreSession] as its `expectedModel` (TEN-81): this whole resolution runs
     * outside the queue's mutex, so the model it describes may be gone by the time the restore
     * takes the lock, and the key alone — which names a model by *database id* — is not something
     * the queue can compare against what it has loaded.
     */
    private fun resolveSessionKey(): Pair<LoadedModelInfo, SessionModelKey>? {
        val info = inferenceQueue.state.value.residentModel ?: return null
        val modelId = info.modelId ?: return null
        return info to SessionModelKey(
            modelId = modelId,
            nCtx = info.nCtxEffective,
            kvCacheType = info.kvCacheType,
            backend = info.backend,
            chatTemplate = info.chatTemplate.orEmpty(),
        )
    }

    /**
     * Keeps this conversation's session in sync with whatever the engine's KV cache currently
     * holds, before the upcoming generation — TEN-34's cold-start restore, plus TEN-62's third save
     * trigger for switching conversations without a model swap. Three cases:
     *
     * - **Empty cache** (a cold start, or right after
     *   [io.github.tensaiapp.runtime.InferenceQueue.onBeforeModelSwap] swapped in a new
     *   model and froze the previous one's session to disk): nothing live to lose, so this
     *   conversation's persisted session is restored if one exists (TEN-34, unchanged).
     * - **Non-empty cache already bound to this conversation** ([ActiveSessionTracker.current]
     *   points at [conversationId]): the normal case of a conversation's second-and-later turn —
     *   the cache is this conversation's own live state, so nothing to do.
     * - **Non-empty cache bound to a *different* conversation** (TEN-62): the user switched
     *   conversations without the model changing. The outgoing conversation's live state is saved
     *   first via [SessionRepository.save] (`inferenceQueue::saveSession` as `saveOnEngine` — this
     *   runs outside [io.github.tensaiapp.runtime.InferenceQueue]'s mutex, unlike
     *   `onBeforeModelSwap`, see [SessionRepository]'s KDoc), and only restored into if that save
     *   didn't just lose data: [io.github.tensaiapp.data.session.SaveResult.Saved],
     *   [io.github.tensaiapp.data.session.SaveResult.Unchanged] (the fast A→B→A path with
     *   nothing generated in between — same token count already on disk, so the outgoing state
     *   *is* already saved) and
     *   [io.github.tensaiapp.data.session.SaveResult.NothingToSave] (an empty cache
     *   reported at save time — nothing to lose) all permit the restore.
     *   [io.github.tensaiapp.data.session.SaveResult.Failed] does not: TEN-34's guarantee
     *   is that a session is never restored over live state that wasn't first written to disk, so a
     *   failed save aborts the switch and this conversation falls back to a normal re-prefill
     *   instead, keeping the outgoing conversation's in-memory state exactly as it was (never
     *   restoring over it, never silently discarding anything the user generated).
     *   [io.github.tensaiapp.data.session.SaveResult.Discarded] (TEN-38: the user cleared
     *   saved sessions from Settings while this save was running) does not permit it either, and
     *   for the same reason — nothing was written, so nothing may be restored over. The switch
     *   falling back to a re-prefill is the correct outcome there too: after a purge there is no
     *   session left to restore into this conversation anyway. Nor does
     *   [io.github.tensaiapp.data.session.SaveResult.NotAttempted] (TEN-78: the queue was
     *   busy and the engine was never asked) — again nothing on disk, again no restore, but this
     *   one is the transient case and the switch works on the next attempt.
     *
     * Guarded to only run this last case when [ActiveSessionTracker.current]'s recorded
     * [SessionModelKey] matches this conversation's own (via [resolveSessionKey]): a different key
     * means a model swap already happened and `onBeforeModelSwap` already saved the outgoing
     * session (TEN-34) — this must not save it a second time. [ActiveSessionTracker] is best-effort
     * (see its KDoc): if it's `null` (nobody reported since the tracker was last cleared), there is
     * no known outgoing conversation to save, so the switch case is skipped entirely — the same
     * "leave the live cache alone" behavior this method always had for a non-empty cache before
     * TEN-62.
     */
    private suspend fun syncSessionForConversationSwitch(conversationId: String) {
        if ((inferenceQueue.kvUsedPercent() ?: 0f) == 0f) {
            val (info, key) = resolveSessionKey() ?: return
            restoreOnto(conversationId, key, info)
            return
        }

        val (outgoingConversationId, outgoingKey) = ActiveSessionTracker.current.value ?: return
        if (outgoingConversationId == conversationId) return

        val (incomingInfo, incomingKey) = resolveSessionKey() ?: return
        if (outgoingKey != incomingKey) return

        val saveResult = sessionRepository.save(outgoingConversationId, outgoingKey, saveOnEngine = inferenceQueue::saveSession)
        val outgoingStateIsSafe = when (saveResult) {
            is SaveResult.Saved, is SaveResult.Unchanged, SaveResult.NothingToSave -> true
            // TEN-78 splits what used to be one branch. Both still block the restore, for the one
            // reason that matters here — nothing was written, so there is nothing to restore over —
            // but they are not the same event and nothing downstream may conflate them again:
            // `Failed` is the engine breaking, `NotAttempted` is a queue that was busy and a switch
            // that simply has to wait for the next turn.
            is SaveResult.Failed, SaveResult.Discarded, SaveResult.NotAttempted -> false
        }
        if (!outgoingStateIsSafe) return

        restoreOnto(conversationId, incomingKey, incomingInfo)
    }

    /**
     * [SessionRepository.restore] against [key], asserting under the queue's own mutex that
     * [expectedModel] is still what's loaded when the engine is finally handed the file (TEN-81).
     *
     * The assertion is the whole reason the engine call is passed in per restore rather than wired
     * once in `RuntimeGraph`: [key] was validated against [expectedModel] here, outside any lock,
     * and a `loadModel` finishing in between leaves the queue back on [QueueState.ModelReady] with a
     * *different* model — which the restore would otherwise have fed one model's KV state to.
     */
    private suspend fun restoreOnto(
        conversationId: String,
        key: SessionModelKey,
        expectedModel: LoadedModelInfo,
    ) {
        sessionRepository.restore(conversationId, key) { path ->
            inferenceQueue.restoreSession(path, expectedModel = expectedModel)
        }
    }

    /** Reports this conversation as the one currently bound to the loaded model — see [ActiveSessionTracker]. */
    private suspend fun reportActiveSession(conversationId: String) {
        val (_, key) = resolveSessionKey() ?: return
        ActiveSessionTracker.report(conversationId, key)
    }

    /**
     * Collects [InferenceQueue.generate]'s events into [assistantId]'s content/thinking, splitting
     * the two apart with a [ReplyStreamSplitter] — which prefers the engine's own split
     * ([DecodeEvent.Parsed], TEN-47, correct for whatever markers the model's chat template uses)
     * and falls back to [ThinkingStreamParser]'s `<think>` handling when the engine offers none.
     *
     * Tokens accumulate in memory and are persisted to Room at most every
     * [StreamPersistIntervalMs] — hammering Room on every token (often several per second) would
     * be pure write amplification for a value nothing reads until the next recomposition tick
     * anyway. The `finally` block does exactly one more write, with whatever ended up in the
     * buffer: this is what makes the persisted content always exactly match what was generated
     * regardless of whether the last throttle tick happened to land on the true last token — and
     * it's what runs on [DecodeEvent.Done], on [DecodeEvent.Error], and on cancellation ([stop])
     * alike, so "keep the partial reply" needs no separate code path. It's wrapped in
     * [NonCancellable] so a [stop] mid-flush can't also race that final write. That write is an
     * update only when there is [hasPersistableContent] to keep (TEN-64) — an assistant turn that
     * ends with nothing at all, reasoning included, is deleted instead of persisted empty.
     *
     * A generation has exactly three ways to end, and the persisted row has to tell them apart
     * (TEN-52): it finished (the engine's own [StopReason], carried by [DecodeEvent.Done]'s stats),
     * the user stopped it ([StopReason.Cancelled], TEN-39), or it failed ([StopReason.Failed], and
     * no stats, since [DecodeEvent.Error] carries none). A [DecodeEvent.Error] arriving after the
     * first token used to leave the row indistinguishable from a stopped one, so a real engine
     * failure was persisted as if the model had simply been interrupted on purpose; and a stopped
     * one used to be recorded as nothing at all, which a still-streaming row also looks like.
     * All three are now written down.
     *
     * @return the generation's [DecodeStats] if it ended on its own, `null` if it was stopped or
     *   failed (or this coroutine was cancelled, in which case nothing is returned at all), what
     *   [generateReply] decides on a warm-up from (TEN-88).
     */
    private suspend fun streamInto(assistantId: String, request: DecodeRequest): DecodeStats? {
        val parser = ReplyStreamSplitter()
        var stats: DecodeStats? = null
        var failed = false
        // What the caller gets back: the stats of a generation that ended on its own, and nothing
        // for one that was stopped or failed. Set in the `finally` once the row is written, so
        // whatever reads the history next sees the finished reply.
        var finished: DecodeStats? = null
        var lastPersistAt = 0L
        // Fresh per turn, and handed to the queue so it is *this* generation that gets marked when
        // the user stops one — see [GenerationStopSignal] for why a flag on this object could not
        // be trusted to mean that.
        val stopSignal = GenerationStopSignal()
        _generatingHere.value = true
        try {
            inferenceQueue.generate(request, stopSignal).collect { event ->
                when (event) {
                    is DecodeEvent.Token -> {
                        parser.accept(event.text)
                        val now = System.currentTimeMillis()
                        if (now - lastPersistAt >= StreamPersistIntervalMs) {
                            chatRepository.updateAssistantMessage(assistantId, parser.content, parser.thinking)
                            lastPersistAt = now
                        }
                    }
                    // Shares the token path's throttle rather than adding one: the engine emits at
                    // most one of these per sampled token, so the two together still can't outpace
                    // [StreamPersistIntervalMs]. The `finally` below is what guarantees the last
                    // split lands in Room regardless of where the throttle window happened to fall.
                    is DecodeEvent.Parsed -> {
                        parser.acceptParsed(event.content, event.reasoning)
                        val now = System.currentTimeMillis()
                        if (now - lastPersistAt >= StreamPersistIntervalMs) {
                            chatRepository.updateAssistantMessage(assistantId, parser.content, parser.thinking)
                            lastPersistAt = now
                        }
                    }
                    is DecodeEvent.Done -> stats = event.stats
                    // Only a request with tools yields one, and this path never has any.
                    is DecodeEvent.ToolTurn -> Unit
                    // The cause is deliberately not kept. Nothing downstream is allowed to show it
                    // (TEN-39), so holding on to it would only be an invitation to.
                    is DecodeEvent.Error -> failed = true
                }
            }
        } finally {
            // Dropped before the row is written, not after: this only ever gates the stop button
            // (see [canStop]), and the generation it could act on is over the moment collection is.
            _generatingHere.value = false
            refreshContextUsage()
            parser.finish()
            val content = parser.content
            val kept = hasPersistableContent(content, parser.thinking)

            // See the KDoc's three outcomes, in priority order.
            //
            // `failed` wins over everything, for the case a misbehaving engine emits both terminal
            // events, or the user happens to tap stop on a decode that was already breaking: "it
            // broke" is the more actionable of the two, and the more surprising.
            //
            // A stop only counts while there is no `stats`: tapping stop a heartbeat after
            // DecodeEvent.Done has landed cancels a generation that already finished, and that
            // reply is complete — labelling it "stopped" would be a lie the user could catch by
            // reading it.
            val stopReason = when {
                failed -> StopReason.Failed
                stopSignal.wasRequested && stats == null -> StopReason.Cancelled
                else -> stats?.stopReason
            }
            withContext(NonCancellable) {
                // TEN-110: a stop is the one empty ending that keeps its row (see
                // [keepsEmptyTurn]); every other empty ending is still deleted.
                if (kept || keepsEmptyTurn(stopReason)) {
                    chatRepository.updateAssistantMessage(
                        assistantId,
                        content = content,
                        thinking = parser.thinking,
                        stats = stats,
                        stopReason = stopReason,
                    )
                } else {
                    // TEN-64: nothing worth keeping — see hasPersistableContent's KDoc. Including a
                    // failure that produced nothing: TEN-52 used to keep those by writing the
                    // exception's message into the body, which is what the banner below replaces.
                    chatRepository.deleteMessage(assistantId)
                }
            }
            // `failed` wins over `stats` here as it does for the recorded reason above.
            finished = stats.takeUnless { failed }

            // Two ways to end a turn with nothing to show for it, both of which the user has to
            // hear about because no bubble was left behind to say it for them:
            //
            // - TEN-65: llama.cpp produced literally nothing because the prompt alone left no room
            //   to generate into (see ChatError.ContextWindowFull).
            // - TEN-39: the decode broke before its first token (see ChatError.GenerationFailed).
            //
            // A failure that *did* produce text needs no banner — the reply is on screen carrying
            // its own "generation failed" chip, and a banner on top would say the same thing twice.
            //
            // TEN-79 adds the one exception to that rule: a reply cut off by the context window
            // (StopReason.ContextBudgetClipped) *did* leave a bubble behind, and still gets the
            // banner. Its chip can only say the reply was cut off, and the user's reading of "cut
            // off" is "raise max-tokens" — a setting that, in this one case, is not what stopped it
            // and cannot lengthen it. Only the banner names the conversation itself as the problem
            // and points at the way out.
            if (stats?.stopReason == StopReason.ContextBudgetExhausted ||
                stats?.stopReason == StopReason.ContextBudgetClipped
            ) {
                _error.value = ChatError.ContextWindowFull
            } else if (failed && !kept) {
                _error.value = ChatError.GenerationFailed
            }
        }
        return finished
    }

    /**
     * The agent path of [generateReply] (TEN-172): runs [request] through [agentRuntime] and records
     * each step as it comes. A turn is several assistant messages: every [AgentEvent.ReplyStarted]
     * opens one (streamed and throttled as in [streamInto]), a reply that made calls keeps them in
     * its `toolCallsJson`, and each [AgentEvent.ToolCallFinished] adds the `tool` message and the audit
     * row. The list draws them as tool lines and confirmation cards, see [items].
     *
     * Stop ends the turn by cancellation, wherever it is, tool execution included (see [stop]). The
     * `finally` then closes what was cut: calls of the last reply that never finished get a `stopped`
     * result (a flow cannot emit once cancelled, so the runtime leaves that to us) and a reply cut
     * mid-stream ends as [streamInto]'s would, kept when it has content or was stopped.
     */
    private suspend fun streamAgentTurn(conversationId: String, request: DecodeRequest) {
        val stopSignal = GenerationStopSignal()
        agentStopSignal = stopSignal
        var parser = ReplyStreamSplitter(toolsActive = true)
        // The reply being streamed, and the last one that made calls: the audit rows point at it.
        var openReplyId: String? = null
        var callingReplyId: String? = null
        // Calls of [callingReplyId] with no result recorded yet.
        var unanswered = emptyList<ToolCall>()
        var lastStats: DecodeStats? = null
        var failed = false
        var lastPersistAt = 0L
        // What the open reply has stored as its calls while they are still being written (TEN-185).
        var writing = emptyList<String>()
        // A placeholder was ever stored on the open reply, whatever it holds now: the `finally` clears on this.
        var wrotePlaceholders = false
        // The engine reported a call (of any name) for the open reply, see the parse-failure rescue below.
        var sawCallName = false

        suspend fun persistThrottled() {
            val now = System.currentTimeMillis()
            val id = openReplyId ?: return
            if (now - lastPersistAt >= StreamPersistIntervalMs) {
                chatRepository.updateAssistantMessage(id, parser.content, parser.thinking)
                lastPersistAt = now
            }
        }

        /**
         * Shows the calls the engine sees being written as running ones, so the tool line is there before the
         * reply ends. Stored as the reply's calls under placeholder ids: [AgentEvent.ReplyFinished] replaces
         * them with the real ones (or clears them), and the `finally` below clears them on a cut reply.
         * A name that is not a registered tool (still being written, or invented) shows as a generic one.
         * Only written when the names change, not per token.
         */
        suspend fun showWritingCalls(names: List<String>) {
            val id = openReplyId ?: return
            val shown = names.map { if (agentRuntime.tierOf(it) != null) it else "" }
            if (shown == writing) return
            writing = shown
            wrotePlaceholders = true
            chatRepository.setToolCalls(id, shown.mapIndexed { i, name -> ToolCall("$WritingCallIdPrefix$i", name, "{}") })
        }

        _generatingHere.value = true
        _agentRunning.value = true
        runningAgentTurns.update { it + conversationId }
        turnCallIds = emptySet()
        try {
            agentRuntime.run(request, ReplyGenerator { inferenceQueue.generate(it, stopSignal) }).collect { event ->
                when (event) {
                    AgentEvent.TurnStarted -> Unit
                    is AgentEvent.ReplyStarted -> {
                        parser = ReplyStreamSplitter(toolsActive = true)
                        lastPersistAt = 0L
                        writing = emptyList()
                        wrotePlaceholders = false
                        sawCallName = false
                        // Not cancellable: an id that never reaches [openReplyId] is a row nobody closes.
                        openReplyId = withContext(NonCancellable) { chatRepository.startAssistantMessage(conversationId) }
                    }
                    is AgentEvent.Token -> {
                        parser.accept(event.text)
                        persistThrottled()
                    }
                    is AgentEvent.Parsed -> {
                        val wasEngine = parser.usingEngineSplit
                        parser.acceptParsed(event.content, event.reasoning)
                        if (event.toolCalls.isNotEmpty()) sawCallName = true
                        // The first split replaces what the fallback showed: no waiting for the throttle.
                        if (!wasEngine && parser.usingEngineSplit) lastPersistAt = 0L
                        showWritingCalls(event.toolCalls)
                        persistThrottled()
                    }
                    is AgentEvent.ReplyFinished -> {
                        val id = openReplyId ?: return@collect
                        openReplyId = null
                        parser.finish()
                        // Before any write: a stop landing in the middle of them must still find these calls to close.
                        if (event.calls.isNotEmpty()) {
                            callingReplyId = id
                            unanswered = event.calls
                            turnCallIds = turnCallIds + event.calls.map { it.id }
                        }
                        // With calls (or calls dropped after the limit), the engine's own content is the one
                        // without the call markup.
                        var content = if ((event.calls.isNotEmpty() || event.droppedCalls) && event.content != null) event.content else parser.content
                        // A parse the engine rejected with nothing resolved: the model's own prose is kept when the engine
                        // never saw a call in this reply and the text holds no call marker (the parse failed on something
                        // else), otherwise [toolFallbackMessage] below. Never the raw call.
                        if (event.toolParseFailed && event.calls.isEmpty() && !hasPersistableContent(content, parser.thinking) &&
                            !sawCallName && parser.fallbackContent.isNotBlank() && !hasToolCallMarker(parser.fallbackContent)
                        ) {
                            content = parser.fallbackContent
                        }
                        // The answer after tool use came out empty: say so rather than leave bare calls on screen.
                        val noAnswer = (event.index > 0 || event.toolParseFailed) && event.calls.isEmpty() &&
                            !hasPersistableContent(content, parser.thinking) &&
                            event.stats?.stopReason != StopReason.ContextBudgetExhausted &&
                            event.stats?.stopReason != StopReason.ContextBudgetClipped
                        if (noAnswer) content = toolFallbackMessage(Locale.getDefault().language)
                        // Not cancellable: the reply and its calls land together, or the `finally` below has
                        // nothing coherent to close.
                        withContext(NonCancellable) {
                            if (event.calls.isNotEmpty() || hasPersistableContent(content, parser.thinking)) {
                                // No stats on a reply that made calls: the chip belongs to the answer.
                                chatRepository.updateAssistantMessage(id, content, parser.thinking, event.stats.takeIf { event.calls.isEmpty() })
                                chatRepository.setToolCalls(id, event.calls)
                            } else {
                                chatRepository.deleteMessage(id)
                            }
                        }
                        lastStats = event.stats
                    }
                    is AgentEvent.ToolCallStarted -> Unit
                    is AgentEvent.ToolCallFinished -> {
                        val replyId = callingReplyId ?: return@collect
                        withContext(NonCancellable) { chatRepository.recordToolCall(conversationId, replyId, event.outcome) }
                        unanswered = unanswered.filterNot { it.id == event.outcome.call.id }
                    }
                    AgentEvent.Done -> Unit
                    is AgentEvent.Error -> failed = true
                }
            }
        } finally {
            _agentRunning.value = false
            runningAgentTurns.update { it - conversationId }
            _generatingHere.value = false
            agentStopSignal = null
            refreshContextUsage()
            var keptCutReply = false
            withContext(NonCancellable) {
                val replyId = callingReplyId
                if (replyId != null) {
                    for (call in unanswered) {
                        val outcome = ToolCallOutcome.stopped(call, agentRuntime.tierOf(call.name), System.currentTimeMillis())
                        chatRepository.recordToolCall(conversationId, replyId, outcome)
                    }
                }
                openReplyId?.let { id ->
                    parser.finish()
                    if (wrotePlaceholders) chatRepository.setToolCalls(id, emptyList())
                    val stopReason = when {
                        failed -> StopReason.Failed
                        stopSignal.wasRequested -> StopReason.Cancelled
                        else -> null
                    }
                    keptCutReply = hasPersistableContent(parser.content, parser.thinking)
                    if (keptCutReply || keepsEmptyTurn(stopReason)) {
                        chatRepository.updateAssistantMessage(id, parser.content, parser.thinking, stopReason = stopReason)
                    } else {
                        chatRepository.deleteMessage(id)
                    }
                }
            }
            // The same two banners as [streamInto], read off the last reply that finished.
            if (lastStats?.stopReason == StopReason.ContextBudgetExhausted ||
                lastStats?.stopReason == StopReason.ContextBudgetClipped
            ) {
                _error.value = ChatError.ContextWindowFull
            } else if (failed && !keptCutReply) {
                _error.value = ChatError.GenerationFailed
            }
        }
    }
}
