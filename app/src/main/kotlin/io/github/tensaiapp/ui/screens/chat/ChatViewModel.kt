package io.github.tensaiapp.ui.screens.chat

import android.util.Log
import io.github.tensaiapp.BuildConfig
import io.github.tensaiapp.agent.AgentEvent
import io.github.tensaiapp.agent.AgentRuntime
import io.github.tensaiapp.agent.ReplyGenerator
import io.github.tensaiapp.agent.SystemPrompt
import io.github.tensaiapp.agent.ToolCallOutcome
import io.github.tensaiapp.agent.ToolCallStatus
import io.github.tensaiapp.agent.ToolTier
import io.github.tensaiapp.agent.AskUserGate
import io.github.tensaiapp.agent.PendingQuestion
import io.github.tensaiapp.agent.UiConfirmationGate
import io.github.tensaiapp.data.chat.ChatRepository
import io.github.tensaiapp.data.chat.ConversationEntity
import io.github.tensaiapp.data.chat.MessageEntity
import io.github.tensaiapp.data.chat.ReplyStreamSplitter
import io.github.tensaiapp.data.chat.SamplingJson
import io.github.tensaiapp.data.chat.SendState
import io.github.tensaiapp.data.chat.ToolCallsJson
import io.github.tensaiapp.data.chat.ThinkingStreamParser
import io.github.tensaiapp.data.memory.MemoryPrompt
import io.github.tensaiapp.data.models.KvCacheSize
import io.github.tensaiapp.data.models.ModelRepository
import io.github.tensaiapp.data.session.SaveResult
import io.github.tensaiapp.data.session.SessionModelKey
import io.github.tensaiapp.data.session.SessionRepository
import io.github.tensaiapp.data.session.SessionRestoreOutcome
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
import io.github.tensaiapp.runtime.ChatTurns
import io.github.tensaiapp.runtime.AppForegroundTracker
import io.github.tensaiapp.runtime.GenerationStopSignal
import io.github.tensaiapp.runtime.InferenceQueue
import io.github.tensaiapp.runtime.PartTokenCache
import io.github.tensaiapp.runtime.QueueState
import io.github.tensaiapp.runtime.RuntimeGraph
import io.github.tensaiapp.runtime.residentModel
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
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

/**
 * The sampling of a reply, see [ChatViewModel.generateReply] for where each part comes from: the knobs of
 * [samplingJson], the [enableThinking] flag and, only when the loaded model can think ([thinkingSupported]),
 * the reasoning budget of their `maxTokens`: [ReasoningBudget.forMessage] of [lastUserMessage] (TEN-166), the
 * whole split ([ReasoningBudget.splitting]) when there is none to read. Shared with the agent bench, so it measures
 * the requests a send builds: its Adaptive budget passes each prompt as the message, the chat's rule; its Default
 * passes none and keeps the split, as runs before TEN-166's rule did.
 */
internal fun replySampling(
    samplingJson: String?,
    enableThinking: Boolean,
    thinkingSupported: Boolean,
    lastUserMessage: String? = null,
): SamplingConfig {
    val sampling = SamplingJson.decode(samplingJson)
    val reasoningBudget = when {
        !enableThinking || !thinkingSupported -> null
        lastUserMessage == null -> ReasoningBudget.splitting(sampling.maxTokens, ThinkingStreamParser.CloseTag)
        else -> ReasoningBudget.forMessage(lastUserMessage, sampling.maxTokens, ThinkingStreamParser.CloseTag)
    }
    return sampling.copy(enableThinking = enableThinking, reasoningBudget = reasoningBudget)
}

/** The log tag of the chat's replies. */
internal const val ChatLogTag = "tensai.chat"

/** Minimum time between DB writes of a streaming assistant message's growing content — see [ChatViewModel.streamInto]. */
private const val StreamPersistIntervalMs = 100L

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
 * @param sendState a user message that has not gone out yet (TEN-224), `null` once it has.
 */
data class ChatMessageUi(
    val id: String,
    val isUser: Boolean,
    val content: String,
    val thinking: String?,
    val stats: MessageStatsUi?,
    val ending: ReplyEnding,
    val sendState: SendState? = null,
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
        sendState = SendState.entries.firstOrNull { it.name == sendState },
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
 * [text] without the tool calls the model wrote in it as plain text (TEN-221), the prose around them kept. A final
 * answer makes no call (the forced one past the guards, or one the engine's parser did not read as a call): a model
 * that still writes one, in its own template's shape or another's (LFM3 writing Qwen3-Coder's `<tool_call>
 * <function=...>`), must not have the markup shown or replayed. Each call runs to its closing tag or, unclosed, to
 * the end of its stretch of prose; a closing or opening tag left alone is dropped too. The word "function" in prose
 * is not a tag.
 *
 * Code is left as written: a ``` block or a `span` showing a call is an answer about the format, not a call. A tag
 * outside backticks is taken for a call, a real mention of one included: the two cannot be told apart.
 */
internal fun withoutToolCallMarkup(text: String): String {
    if (!hasToolCallMarker(text)) return text
    val out = StringBuilder()
    var at = 0
    for (code in CodeSpans.findAll(text)) {
        out.append(withoutCalls(text.substring(at, code.range.first))).append(code.value)
        at = code.range.last + 1
    }
    out.append(withoutCalls(text.substring(at)))
    val stripped = out.toString()
    return if (stripped == text) text else stripped.trim()
}

/** [prose] (no code in it) without its calls, the blank lines and the gap a call leaves mid-line closed up. */
private fun withoutCalls(prose: String): String {
    val stripped = WrittenToolCalls.fold(prose) { acc, call -> acc.replace(call, "") }
    return if (stripped == prose) prose else stripped.replace(BlankLines, "\n\n").replace(InnerSpaces, " ")
}

/** A ``` block (unclosed: to the end) or an inline `span`. */
private val CodeSpans = Regex("""```.*?(?:```|\z)|`[^`\n]*`""", RegexOption.DOT_MATCHES_ALL)

private val WrittenToolCalls = listOf(
    // Hermes, Qwen (JSON inside) and Qwen3-Coder (XML-ish inside).
    Regex("""<tool_call>.*?(?:</tool_call>|\z)""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
    // LFM's own.
    Regex("""<\|tool_call_start\|>.*?(?:<\|tool_call_end\|>|\z)""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
    // Llama 3.x and Qwen3-Coder without the outer tag.
    Regex("""<function(?:=[^>]*)?>.*?(?:</function>|\z)""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
    Regex("""</?(?:tool_call|function|parameter)(?:=[^>]*)?>|<\|tool_call_(?:start|end)\|>""", RegexOption.IGNORE_CASE),
)

private val BlankLines = Regex("""\n\s*\n(?:\s*\n)+""")

private val InnerSpaces = Regex("""(?<=\S)[ \t]{2,}(?=\S)""")

/**
 * What an agent turn ends on when the model, after its tool calls, wrote no answer at all (TEN-172).
 * Stored text, so it follows the app language ([language] is a two-letter code) rather than a resource.
 * [refused] when the turn's last call was refused (TEN-221): the user is told so, not that the tools fell short.
 * Only that call: an earlier one may have done something, so the line does not say nothing was.
 */
internal fun toolFallbackMessage(language: String, refused: Boolean = false): String =
    when {
        refused && language == "fr" -> "L'action a été refusée."
        refused -> "The action was refused."
        language == "fr" -> "Je n'ai pas pu terminer avec les outils disponibles."
        else -> "I couldn't finish with the tools available."
    }

/** Thinking of a new chat, until its toggle is flipped. Also the agent bench's default. */
internal const val DefaultEnableThinking = true

/**
 * The per-conversation settings the chat screen edits, in the shape [ConversationEntity] stores
 * them. Read off the row once there is one, held in memory for a draft (TEN-106) and handed to
 * [ChatRepository.createConversation] on the draft's first send. The defaults are a fresh row's.
 * [createdAt] is read, never edited: the base prompt's date (see [SystemPrompt]), `null` for a draft.
 */
private data class ConversationSettings(
    val samplingJson: String? = null,
    val systemPrompt: String? = null,
    val enableThinking: Boolean = DefaultEnableThinking,
    val toolsEnabled: Boolean = true,
    val createdAt: Long? = null,
)

private fun ConversationEntity?.settings(): ConversationSettings =
    if (this == null) ConversationSettings() else ConversationSettings(samplingJson, systemPrompt, enableThinking, toolsEnabled, createdAt)

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
    private val toolGrants: Flow<Set<ToolTier>> = RuntimeGraph.toolSettings.grants,
    private val chatTurns: ChatTurns = RuntimeGraph.chatTurns,
    /** The user's instructions for every chat ([io.github.tensaiapp.data.InstructionsSettings]): a layer of each request, previewed by the settings sheet. */
    val globalInstructions: Flow<String> = RuntimeGraph.instructionsSettings.global,
    private val systemPrompts: SystemPrompt = SystemPrompt(),
    /** A line about a reply for logcat, under [ChatLogTag]: replaced by the JVM tests, which have no logcat. */
    private val log: (String) -> Unit = { Log.i(ChatLogTag, it) },
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
                chatTurns.running,
                combine(confirmationGate.pending, confirmationGate.targets, ::Pair),
                confirmationGate.allowed,
            ) { stored, calls, running, (pending, targets), allowed ->
                buildChatItems(stored, calls, id in running, pending.keys, allowed, targets, agentRuntime::requiresConfirmation, agentRuntime::trustWith, ::callsOf)
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
    val pendingQuestion: Flow<PendingQuestion?> = askUserGate.pending.map { pending ->
        val callIds = runningTurn()?.callIds.orEmpty()
        pending.values.firstOrNull { it.callId in callIds }
    }
        .distinctUntilChanged()

    /** The user's answer to [callId]'s question: the chosen options, then the typed text if any. The turn goes on with it. */
    fun answerQuestion(callId: String, answers: List<String>) = askUserGate.answer(callId, answers)

    /**
     * The user closed the question of [callId] (the X): the model is told so, the turn goes on.
     * While a question is open the card replaces the input pill, so Stop is out of reach: the X is the way out, the model
     * goes on without an answer and the pill (with its Stop) is back as soon as the question closes.
     */
    fun dismissQuestion(callId: String) {
        askUserGate.dismiss(callId)
    }

    /** Mirrors [InferenceQueue.state] — [ChatScreen] derives `isGenerating`/`modelName`/`nCtx` from it directly. */
    val queueState: StateFlow<QueueState> = inferenceQueue.state

    /** `true` for as long as a reply *this* instance started is still streaming — see [canStop]. */
    private val _generatingHere = MutableStateFlow(false)

    /**
     * This conversation's running turn, whichever instance started it: a screen reopened mid-turn is a new
     * instance, and the turn's job, stop signal and calls are what it needs to stop it, answer its cards and
     * refuse a second send. See [ChatTurns].
     */
    private fun runningTurn(): ChatTurns.Turn? = _conversationId.value?.let { chatTurns.running.value[it] }

    /**
     * A turn runs in any conversation, see [ChatTurns]. The queue reads idle for parts of it (a restore, a
     * tool between two generations), so [ChatScreen] adds this to [queueState] to know the engine is spoken
     * for, and [send] queues.
     */
    val turnRunning: Flow<Boolean> = chatTurns.running.map { it.isNotEmpty() }

    /**
     * The conversation whose turn holds the engine when it is not this one, `null` otherwise (TEN-224): a send here
     * then waits for it, and the line under the composer opens it.
     */
    val answeringElsewhere: Flow<String?> = combine(_conversationId, chatTurns.running) { id, turns ->
        turns.keys.firstOrNull { it != id }
    }.distinctUntilChanged()

    /** This conversation has a message waiting for the engine (TEN-224): Send becomes Cancel send. */
    val sendPending: Flow<Boolean> = combine(_conversationId, chatTurns.pending) { id, pending -> id != null && id in pending }
        .distinctUntilChanged()

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
        combine(inferenceQueue.state, ActiveSessionTracker.current, _generatingHere, _conversationId, chatTurns.running) { state, active, here, id, turns ->
            // An agent turn is stoppable between its generations too: a tool runs while the queue is idle.
            (id != null && id in turns) || (state is QueueState.Generating && (here || (id != null && active?.conversationId == id)))
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
            chatTurns.bind(this, id)
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
        if (ActiveSessionTracker.current.value?.conversationId == id) {
            inferenceQueue.cancelActive()
            ActiveSessionTracker.clear()
        }
        generationJob?.cancel()
        chatTurns.stop(id)
        chatTurns.unqueue(id)
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
     *
     * And so are the ones the turn raises later, until a screen shows the conversation again: the screen stops
     * showing it in [ChatTurns], whose [ChatTurns.answerUnattended] answers them.
     */
    fun leave() {
        warmUpJob?.cancel()
        chatTurns.hide(this)
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
     *
     * Also a no-op while this conversation's own turn runs (the screen offers Stop instead) or while it
     * already has a message waiting. While another conversation's turn runs, see [ChatTurns], the message
     * waits instead (TEN-224): the queue reads [QueueState.ModelReady] while an agent turn runs a tool, and
     * a reply started then would take the engine from under it (its next generation is refused as busy,
     * and here the history has calls with no result yet). It is stored at once as
     * [SendState.Pending] and [ChatTurns.dispatchPending] starts it once the engine is free, oldest first,
     * whether this screen is still open or not. Sends already waiting put this one behind them too.
     */
    fun send(text: String) = submit(text, replacing = null)

    /**
     * Sends a [SendState.NotSent] [message] again, the tap on its row (TEN-224): as a new send, so it lands last
     * in the conversation and waits like one, and its old row goes once the new one is stored.
     */
    fun resend(message: ChatMessageUi) {
        if (message.sendState == SendState.NotSent) submit(message.content, replacing = message.id)
    }

    private fun submit(text: String, replacing: String?) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || generationJob?.isActive == true) return
        val current = _conversationId.value
        if (current != null && (current in chatTurns.running.value || current in chatTurns.pending.value)) return
        val waits = chatTurns.running.value.isNotEmpty() || chatTurns.pending.value.isNotEmpty()
        // A message that waits only needs a model resident: the one generating now, which it is written for.
        val state = inferenceQueue.state.value
        val waitsFor = if (waits) state.residentModel else null
        if (if (waits) waitsFor == null else state !is QueueState.ModelReady) {
            _error.value = ChatError.NoModelLoaded
            return
        }
        _error.value = null
        // Synchronously, before the launch: the warm-up is read-only on the conversation, but one
        // that starts after this message is stored would read it as the end of the history.
        warmUpJob?.cancel()
        generationJob = scope.launch {
            val conversationId = ensureConversation()
            if (waitsFor != null) {
                queue(conversationId, trimmed, replacing, waitsFor)
                return@launch
            }
            asTurn(conversationId) { turn ->
                chatRepository.appendUserMessage(conversationId, trimmed)
                replacing?.let { chatRepository.deleteMessage(it) }
                generateReply(conversationId, turn)
            }
        }
    }

    /** Stores [text] as [conversationId]'s waiting message and queues it in [ChatTurns] for [model], see [send]. */
    private suspend fun queue(conversationId: String, text: String, replacing: String?, model: LoadedModelInfo) {
        val messageId = chatRepository.appendUserMessage(conversationId, text, SendState.Pending)
        val send = ChatTurns.PendingSend(messageId, text, model) { startQueued(conversationId, messageId) }
        // Another screen on this conversation queued one first: one message waits per conversation.
        if (!chatTurns.queue(conversationId, send)) {
            chatRepository.deleteMessage(messageId)
            return
        }
        replacing?.let { chatRepository.deleteMessage(it) }
        log("send queued behind another chat")
    }

    /**
     * Starts the waiting message [messageId] as [conversationId]'s turn: [ChatTurns.dispatchPending] runs it once
     * the engine is free, on this instance whether its screen is still open or not, as a turn outlives its screen.
     * The turn is registered before this returns, so the dispatcher sees it running before it looks again. The
     * message goes out (its [SendState] cleared) only if it is still there: its conversation may be gone.
     * Not [generationJob]: this runs on the dispatcher's thread, and [ChatTurns.running] already refuses a second send.
     */
    private fun startQueued(conversationId: String, messageId: String) {
        _error.value = null
        warmUpJob?.cancel()
        launchTurn(conversationId) { turn ->
            if (chatRepository.observeMessages(conversationId).first().none { it.id == messageId }) return@launchTurn
            chatRepository.setSendState(messageId, null)
            generateReply(conversationId, turn)
        }
    }

    /**
     * Takes back this conversation's waiting message (TEN-224): Cancel send, the cross on its row or a long
     * press on it. Its row goes, and the conversation too when that was its only message (a draft's first
     * send), which turns this screen back into a draft. Answers the text for the composer, `null` when
     * nothing waits any more (it has just started).
     */
    fun cancelSend(): String? {
        val id = _conversationId.value ?: return null
        val send = chatTurns.unqueue(id) ?: return null
        scope.launch {
            chatRepository.deleteMessage(send.messageId)
            chatRepository.deleteConversationIfEmpty(id)
        }
        return send.text
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
        // A tool running between two generations has no queue job to cancel: end the whole turn, its
        // `finally` records what was cut. Through [ChatTurns], so a screen reopened mid-turn stops it too.
        _conversationId.value?.let(chatTurns::stop)
    }

    /**
     * Runs [block] as [conversationId]'s turn in [ChatTurns], its job the calling coroutine's. The entry is
     * dropped once [block] is over, its own `finally` included: a turn that was cut still has rows to
     * write, and a send let through before them would land in the middle.
     */
    private suspend fun asTurn(conversationId: String, block: suspend (ChatTurns.Turn) -> Unit) {
        val turn = ChatTurns.Turn(currentCoroutineContext().job)
        chatTurns.start(conversationId, turn)
        try {
            block(turn)
        } finally {
            chatTurns.finish(conversationId, turn)
        }
    }

    /**
     * [asTurn] for a turn started from outside a coroutine ([startQueued]): the turn is in [ChatTurns] when this
     * returns, not once the coroutine first runs. Dropped when the job completes, which a job cancelled before it
     * ran does too.
     */
    private fun launchTurn(conversationId: String, block: suspend (ChatTurns.Turn) -> Unit) {
        lateinit var turn: ChatTurns.Turn
        val job = scope.launch(start = CoroutineStart.LAZY) { block(turn) }
        turn = ChatTurns.Turn(job)
        chatTurns.start(conversationId, turn)
        job.invokeOnCompletion { chatTurns.finish(conversationId, turn) }
        job.start()
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
     *
     * Refused while a turn runs anywhere: a reply redone has no message to wait with.
     */
    fun regenerate() {
        if (generationJob?.isActive == true || chatTurns.running.value.isNotEmpty()) return
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
            asTurn(conversationId) { turn ->
                // The whole last turn: with tools it is replies and tool results, not one assistant message.
                chatRepository.observeMessages(conversationId).first()
                    .takeLastWhile { it.role == "assistant" || it.role == "tool" }
                    .forEach { chatRepository.deleteMessage(it.id) }
                generateReply(conversationId, turn)
            }
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

    private val _warmingPrefix = MutableStateFlow(false)

    /**
     * Model loading, a reply generating or this chat's tool prefix warming up (TEN-205): the ring spins.
     * App-wide like [queueState] for the first two, the cache is one.
     */
    val busy: Flow<Boolean> = combine(inferenceQueue.state, _warmingPrefix) { state, warming ->
        state is QueueState.Loading || state is QueueState.Generating || warming
    }

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

        val definitionsText = definitionsTextFor(conversation, info)
        // The system message of the path a send would take: the agent's names the tools in play.
        val systemPrompt = systemPromptFor(conversation.settings(), agent = definitionsText != null, globalInstructions.first())
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
        val owner = ActiveSessionTracker.current.value?.conversationId
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
        val definitionsText = definitionsTextFor(conversation, info)
        val systemPrompt = systemPromptFor(conversation.settings(), agent = definitionsText != null, globalInstructions.first())
        val key = info.modelId ?: info.name
        val texts = listOfNotNull(
            systemPrompt,
            definitionsText,
            conversation?.let { memoryPrompt.blockFor(it.id) },
        ).filter { partTokens.get(key, it) == null }
        if (texts.isEmpty()) return
        val counted = inferenceQueue.countTokens(texts) ?: return
        texts.forEachIndexed { i, text -> partTokens.put(key, text, counted[i]) }
    }

    init {
        refreshContextUsage()
        // Built by the screen that shows it, see [leave].
        chatTurns.show(this, _conversationId.value)
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
            val owner = ActiveSessionTracker.current.value?.conversationId
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
     * user actually set. Since TEN-166 the reasoning share of that split depends on the message
     * replied to, see [ReasoningBudget.forMessage].
     */
    private suspend fun generateReply(conversationId: String, turn: ChatTurns.Turn) {
        // Before the sync below, not left to [InferenceQueue.generate]: a switch saves the outgoing
        // conversation first, and the save would then be the one cancelling the outgoing
        // conversation's warm-up, after this conversation's user has already been kept waiting.
        inferenceQueue.cancelWarmUp()
        syncSessionForConversationSwitch(conversationId)
        // Not reported here: the sync may have left the cache holding another conversation's state
        // (a blocked switch, a restore that did not run), and a save picked up before this turn's
        // prefill would write it under this conversation's name. The generation reports this
        // conversation as it takes the queue, see [reportActiveSession].
        refreshContextUsage()
        // The model this reply is generated on, which is what a warm-up behind it must find loaded.
        val model = inferenceQueue.state.value.residentModel

        val conversation = chatRepository.getConversation(conversationId)
        val history = chatRepository.observeMessages(conversationId).first()
        if (model != null) countFixedParts(conversation, model)
        // Tools need a template that takes them, the conversation's switch on, and a tool the user granted.
        // Anything else is the plain path below, untouched by tool calling (TEN-172).
        if (agentPathOn(conversation?.toolsEnabled != false)) {
            val settings = conversation.settings()
            val global = globalInstructions.first()
            // The base prompt names the tools the turn offers, read by the runtime when it starts: see [AgentRuntime.run].
            streamAgentTurn(conversationId, decodeRequestFor(conversation, history, agent = true), turn) { tools ->
                systemPromptWith(settings, tools, global)
            }
            return
        }
        val assistantId = chatRepository.startAssistantMessage(conversationId)
        val request = decodeRequestFor(conversation, history, agent = false)
        val finished = streamInto(assistantId, request, turn.stopSignal, ownedGenerator(conversationId, turn.stopSignal))
        if (model != null && finished != null && worthWarmingUp(finished)) {
            warmUpAfterReply(conversationId, model)
        }
    }

    /**
     * Whether a reply runs the agent path rather than a plain generation: [toolsEnabled] on the conversation,
     * a template that takes tools and a tool the user granted. [generateReply]'s rule, and the warm-up's
     * ([keepPrefixWarm]): a prefix is only worth building for a request that will carry tools.
     */
    private suspend fun agentPathOn(toolsEnabled: Boolean): Boolean =
        toolsEnabled && currentModelSupportsTools.first() && agentRuntime.hasTools()

    /**
     * The [DecodeRequest] of [conversation]'s next reply, without tools (the agent loop adds them), see
     * [generateReply] for where each setting comes from. [agent]: the reply runs the agent path, whose
     * system message names the tools a turn offers.
     */
    private suspend fun decodeRequestFor(conversation: ConversationEntity?, history: List<MessageEntity>, agent: Boolean): DecodeRequest {
        val settings = conversation.settings()
        val system = systemPromptFor(settings, agent, globalInstructions.first())
        return requestFor(settings, history, conversation?.let { memoryPrompt.blockFor(it.id) }, system)
    }

    /**
     * The request of a reply to [history], its reasoning budget read off the last user message (TEN-166) and logged
     * once: [prefixRequest]'s empty history has none, so it keeps the split and logs nothing.
     */
    private suspend fun requestFor(settings: ConversationSettings, history: List<MessageEntity>, memory: String?, system: String?): DecodeRequest {
        val lastUserMessage = history.lastOrNull { it.role == "user" }?.content
        val sampling = replySampling(settings.samplingJson, settings.enableThinking, currentModelSupportsThinking.first(), lastUserMessage)
        if (lastUserMessage != null) {
            sampling.reasoningBudget?.let { budget ->
                log("reasoning budget: ${budget.reasoningMaxTokens} (${if (ReasoningBudget.looksLikeProblem(lastUserMessage)) "problem" else "simple"})")
            }
        }
        return DecodeRequest(messages = chatRepository.toEngineMessages(history, system, memory), sampling = sampling)
    }

    /**
     * The system message of a request on [settings], see [SystemPrompt] for its layers: the base prompt,
     * then [global] and the conversation's own instructions. [agent]: for the agent path, the base prompt
     * then names the tools [AgentRuntime.definitions] offers right now; the plain path offers none.
     */
    private fun systemPromptFor(settings: ConversationSettings, agent: Boolean, global: String): String? =
        systemPromptWith(settings, if (agent) agentRuntime.definitions().mapTo(HashSet()) { it.name } else null, global)

    /** [systemPromptFor] with the offered [tools] named, `null` for the plain path. */
    private fun systemPromptWith(settings: ConversationSettings, tools: Set<String>?, global: String): String? =
        systemPrompts.build(tools = tools, startedAt = settings.createdAt, global = global, chat = settings.systemPrompt)

    /**
     * The request whose prefix the first message of a conversation with [settings] will have (TEN-205):
     * built by [requestFor] on an empty history, so the system message and the thinking flag come
     * from the code a real send uses, and given the tools [AgentRuntime.run] gives every request of a
     * turn. Memory is left out on purpose, it sits in the first user message, after the cut (see
     * [MemoryContext]). The engine keys its cache on the text of the prefix it renders from this
     * (`restoreOrBuildToolPrefix`), so equal inputs here are the same file there, and a change of
     * any layer of the system message ([global] included) is another file.
     */
    private suspend fun prefixRequest(settings: ConversationSettings, global: String): DecodeRequest =
        requestFor(settings, emptyList(), memory = null, systemPromptFor(settings, agent = true, global)).copy(tools = agentRuntime.definitions())

    /**
     * What decides [keepPrefixWarm]'s prefix besides the model: the settings that reach the prompt (the user's
     * [global] instructions and the conversation's start, for the base prompt's date, included), the granted
     * tiers (they decide the tool block, [grants] only triggers, the registry is read when the request is built)
     * and whether the conversation is still empty.
     */
    private data class PrefixTarget(
        val model: LoadedModelInfo,
        val systemPrompt: String?,
        val global: String,
        val createdAt: Long?,
        val enableThinking: Boolean,
        val toolsEnabled: Boolean,
        val grants: Set<ToolTier>,
        val empty: Boolean,
        val foreground: Boolean,
        val idle: Boolean,
    )

    /**
     * Keeps the KV cache holding the tool prefix of this conversation's first message (TEN-205): builds or
     * restores it (TEN-182) as soon as a model is loaded and idle, while the user is still reading or typing,
     * instead of on their first message. Run from the screen's composition, so leaving it cancels the warm-up
     * (what was decoded stays, as a valid prefix the next request reads as resident).
     *
     * Runs again when something that reaches the prefix changes: the model, this conversation's system
     * prompt, the user's instructions for every chat, thinking or tools switch, the granted tiers, or the app coming back to the foreground
     * (leaving it cancels the warm-up). A change cancels the warm-up in flight and
     * the next one replaces the prefix in the cache, which [InferenceQueue.warmToolPrefix] allows because
     * nothing else is in it.
     *
     * Only for a conversation with no message yet (a draft included). One with a history has a saved
     * session to restore on its first send (TEN-34), and a prefix in the cache would be one more thing
     * that restore has to know about; its prefix is a file read away once the session is restored anyway.
     * [InferenceQueue.warmToolPrefix] is skipped on its own for a cache that holds a conversation, unless
     * [saveConversationInCache] has saved it first and its prefix is not this one.
     *
     * A [send] while this runs cancels it through the queue (like every other user of the engine) and its
     * generation prefills only what the warm-up had not reached. The warm-up that was cut writes no file.
     */
    suspend fun keepPrefixWarm() {
        val empty = _conversationId.flatMapLatest { id ->
            if (id == null) flowOf(true) else chatRepository.observeMessages(id).map { it.isEmpty() }
        }
        combine(
            inferenceQueue.state.map { it.residentModel },
            combine(settings, globalInstructions, ::Pair),
            toolGrants,
            empty,
            combine(AppForegroundTracker.isForeground, chatTurns.running) { foreground, turns -> foreground to turns.isEmpty() },
        ) { model, (settings, global), grants, empty, (foreground, idle) ->
            model?.let {
                PrefixTarget(
                    it, settings.systemPrompt, global, settings.createdAt, settings.enableThinking, settings.toolsEnabled,
                    grants, empty, foreground, idle,
                )
            }
        }
            .distinctUntilChanged()
            .collectLatest { target -> if (target != null) warmPrefix(target) }
    }

    private suspend fun warmPrefix(target: PrefixTarget) {
        // Not while a turn runs, in any conversation: one holds the cache between its generations, and the
        // save below would swap it out. Part of the target, so the turn ending starts the warm-up.
        if (!target.empty || !target.idle || generationJob?.isActive == true) return
        // As the after-reply warm-up: only with the user here, minutes of CPU unseen are not worth it. Part of
        // the target, so leaving the app cancels a warm-up in flight and coming back starts the next one.
        if (!target.foreground) return
        if (!agentPathOn(target.toolsEnabled)) return
        val request = prefixRequest(
            ConversationSettings(
                systemPrompt = target.systemPrompt,
                enableThinking = target.enableThinking,
                toolsEnabled = target.toolsEnabled,
                createdAt = target.createdAt,
            ),
            target.global,
        )
        _warmingPrefix.value = true
        try {
            val saved = saveConversationInCache(request)
            // The cache is no longer that conversation's: a save under its id would put the prefix in place of its
            // session. Only its id is cleared: a send since has reported the new owner.
            inferenceQueue.warmToolPrefix(
                request,
                expectedModel = target.model,
                replaceSaved = saved?.second,
                onReplace = { saved?.let { ActiveSessionTracker.clearIf(it.first) } },
            )
        } finally {
            _warmingPrefix.value = false
            refreshContextUsage()
        }
    }

    /**
     * Saves the conversation the cache holds so [warmPrefix] may put [request]'s prefix in its place, and
     * answers its id with the token count the cache held before the save ([InferenceQueue.warmToolPrefix]'s
     * `replaceSaved`), or `null` to leave the cache alone. The outgoing half of [syncSessionForConversationSwitch], with its
     * rules: only for the conversation [ActiveSessionTracker] names, on the model it names, and only once the
     * save has its state on disk. Without a reason to replace there is no save either: a cache that is empty
     * or holds only a prefix already gives way, and one built with this same prefix is reused as it is by the
     * first message.
     */
    private suspend fun saveConversationInCache(request: DecodeRequest): Pair<String, Int>? {
        val resident = inferenceQueue.currentKvTokenCount() ?: return null
        if (resident == 0 || inferenceQueue.holdsOnlyWarmedPrefix() || inferenceQueue.holdsPrefixOf(request)) return null
        val outgoing = ActiveSessionTracker.current.value ?: return null
        val (outgoingConversationId, outgoingKey) = outgoing
        if (outgoingConversationId == _conversationId.value) return null
        val (_, key) = resolveSessionKey() ?: return null
        if (outgoingKey != key) return null
        val result = sessionRepository.save(
            outgoingConversationId,
            outgoingKey,
            ownsCache = { ActiveSessionTracker.current.value == outgoing },
            saveOnEngine = inferenceQueue::saveSession,
        )
        return when (result) {
            is SaveResult.Saved, is SaveResult.Unchanged -> outgoingConversationId to resident
            else -> null
        }
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
     * this turn's generation took the queue, so anything else there means another conversation has
     * taken the engine since, and prefilling this one's history would only evict what that one is building (and, with
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
            if (ActiveSessionTracker.current.value?.conversationId != conversationId) return@launch
            val conversation = chatRepository.getConversation(conversationId) ?: return@launch
            val history = chatRepository.observeMessages(conversationId).first()
            if (history.lastOrNull()?.role != "assistant") return@launch
            inferenceQueue.warmUp(
                messages = chatRepository.toEngineMessages(
                    history,
                    systemPromptFor(conversation.settings(), agent = false, globalInstructions.first()),
                    memoryPrompt.blockFor(conversationId),
                ),
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
        // A cache holding only the tool prefix (TEN-205) is as good as empty: what it holds is a file away.
        if ((inferenceQueue.kvUsedPercent() ?: 0f) == 0f || inferenceQueue.holdsOnlyWarmedPrefix()) {
            val (info, key) = resolveSessionKey() ?: return
            restoreOnto(conversationId, key, info)
            return
        }

        val outgoing = ActiveSessionTracker.current.value ?: return
        val (outgoingConversationId, outgoingKey) = outgoing
        if (outgoingConversationId == conversationId) return

        val (incomingInfo, incomingKey) = resolveSessionKey() ?: return
        if (outgoingKey != incomingKey) return

        val saveResult = sessionRepository.save(
            outgoingConversationId,
            outgoingKey,
            ownsCache = { ActiveSessionTracker.current.value == outgoing },
            saveOnEngine = inferenceQueue::saveSession,
        )
        val outgoingStateIsSafe = when (saveResult) {
            is SaveResult.Saved, is SaveResult.Unchanged, SaveResult.NothingToSave -> true
            // TEN-78 splits what used to be one branch. Both still block the restore, for the one
            // reason that matters here — nothing was written, so there is nothing to restore over —
            // but they are not the same event and nothing downstream may conflate them again:
            // `Failed` is the engine breaking, `NotAttempted` is a queue that was busy and a switch
            // that simply has to wait for the next turn.
            is SaveResult.Failed, SaveResult.Discarded, SaveResult.NotAttempted, SaveResult.OwnerChanged -> false
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
        // The cache stops being the outgoing conversation's before the restore can replace it, not
        // once this one reports: a save picked up off the tracker meanwhile (`onStop`) would put this
        // conversation's state in the other's file. Its `ownsCache` sees the owner gone (TEN-180).
        ActiveSessionTracker.current.value?.conversationId?.takeIf { it != conversationId }?.let(ActiveSessionTracker::clearIf)
        sessionRepository.restore(conversationId, key) { path, withoutTools ->
            inferenceQueue.restoreSession(path, expectedModel = expectedModel, withoutTools = withoutTools).also {
                if (it == SessionRestoreOutcome.Restored) log("session restored, saved without tools: $withoutTools")
            }
        }
    }

    /**
     * Reports this conversation as the one currently bound to the loaded model, see [ActiveSessionTracker]. Run by
     * each generation of a turn as it takes the queue ([InferenceQueue.generate]'s `onStart`): only then is the cache
     * this conversation's whatever the switch before it did, and no save can come between the report and the prefill.
     * A generation the queue refuses reports nothing, and the tracker stays on the conversation whose state the cache
     * still holds. See [ownedGenerator] for a generation that takes the queue and decodes nothing.
     */
    private fun reportActiveSession(conversationId: String) {
        val (_, key) = resolveSessionKey() ?: return
        ActiveSessionTracker.report(conversationId, key)
    }

    /**
     * The generator of one turn of [conversationId]: [InferenceQueue.generate] reporting it the cache's owner as each
     * generation takes the queue ([reportActiveSession]). A turn whose generations took the queue and produced
     * nothing (an exhausted context budget, a blank prompt, a failure or a stop before the first token) may have left
     * the previous owner's state in the cache, so the report is taken back: a save lost is the cost, a session
     * overwritten with another conversation's state is what it avoids.
     */
    private fun ownedGenerator(conversationId: String, stopSignal: GenerationStopSignal): ReplyGenerator {
        var started = false
        var decoded = false
        return ReplyGenerator { request ->
            inferenceQueue.generate(request, stopSignal) {
                started = true
                reportActiveSession(conversationId)
            }
                .onEach { if (it !is DecodeEvent.Done && it !is DecodeEvent.Error) decoded = true }
                .onCompletion { if (started && !decoded) ActiveSessionTracker.clearIf(conversationId) }
        }
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
     * @param generator the turn's [ownedGenerator], which reports who owns the cache.
     */
    private suspend fun streamInto(
        assistantId: String,
        request: DecodeRequest,
        stopSignal: GenerationStopSignal,
        generator: ReplyGenerator,
    ): DecodeStats? {
        val parser = ReplyStreamSplitter()
        var stats: DecodeStats? = null
        var failed = false
        // What the caller gets back: the stats of a generation that ended on its own, and nothing
        // for one that was stopped or failed. Set in the `finally` once the row is written, so
        // whatever reads the history next sees the finished reply.
        var finished: DecodeStats? = null
        var lastPersistAt = 0L
        // [stopSignal] is the turn's, fresh per turn, and handed to the queue so it is *this* generation
        // that gets marked when the user stops one: see [GenerationStopSignal] for why a flag on this
        // object could not be trusted to mean that.
        _generatingHere.value = true
        try {
            generator.generate(request).collect { event ->
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
     *
     * [system] builds the system message from the tools the turn offers, see [AgentRuntime.run].
     */
    private suspend fun streamAgentTurn(
        conversationId: String,
        request: DecodeRequest,
        turn: ChatTurns.Turn,
        system: (Set<String>) -> String?,
    ) {
        val stopSignal = turn.stopSignal
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
        // The turn's last call, when it ended ok: what an answer that never came falls back on (TEN-204).
        var lastOk: ToolCallOutcome? = null
        // The turn's last call was refused: that fallback says so (TEN-221).
        var lastRefused = false

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
        try {
            agentRuntime.run(request, ownedGenerator(conversationId, stopSignal), system).collect { event ->
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
                        // An empty answer the runtime asks for again (TEN-204): the next reply replaces this one.
                        if (event.retrying) {
                            if (BuildConfig.DEBUG) log("empty answer after a tool, asking again; raw: ${event.raw}")
                            if (wrotePlaceholders) withContext(NonCancellable) { chatRepository.setToolCalls(id, emptyList()) }
                            withContext(NonCancellable) { chatRepository.deleteMessage(id) }
                            return@collect
                        }
                        // Before any write: a stop landing in the middle of them must still find these calls to close.
                        if (event.calls.isNotEmpty()) {
                            callingReplyId = id
                            unanswered = event.calls
                            turn.callIds = turn.callIds + event.calls.map { it.id }
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
                        // A final answer holding a call written as text (TEN-221): the forced one cannot drop what the
                        // engine's parser did not read as a call. Only the prose stays.
                        val shown = if (event.calls.isEmpty()) withoutToolCallMarkup(content) else content
                        val wroteCall = shown != content
                        content = shown
                        // The answer after tool use came out with no text, reasoning or not (TEN-204): say what the tool
                        // found when it can, or that it could not finish, rather than leave a bubble with nothing in it.
                        val noAnswer = (event.index > 0 || event.toolParseFailed || wroteCall) && event.calls.isEmpty() &&
                            content.isBlank() &&
                            event.stats?.stopReason != StopReason.ContextBudgetExhausted &&
                            event.stats?.stopReason != StopReason.ContextBudgetClipped &&
                            event.stats?.stopReason != StopReason.Cancelled && !stopSignal.wasRequested
                        if (noAnswer) content = lastOk?.let(agentRuntime::fallbackAnswer) ?: toolFallbackMessage(Locale.getDefault().language, lastRefused)
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
                        lastOk = event.outcome.takeIf { it.status == ToolCallStatus.Ok }
                        lastRefused = event.outcome.status == ToolCallStatus.Refused
                    }
                    AgentEvent.Done -> Unit
                    is AgentEvent.Error -> failed = true
                }
            }
        } finally {
            _generatingHere.value = false
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
                    // A call the cut reply was writing as text is not kept either (TEN-221).
                    val cutContent = withoutToolCallMarkup(parser.content)
                    keptCutReply = hasPersistableContent(cutContent, parser.thinking)
                    if (keptCutReply || keepsEmptyTurn(stopReason)) {
                        chatRepository.updateAssistantMessage(id, cutContent, parser.thinking, stopReason = stopReason)
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
