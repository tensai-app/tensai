package io.github.hokanosekai.tensai.ui.screens.chat

import io.github.hokanosekai.tensai.data.chat.ChatRepository
import io.github.hokanosekai.tensai.data.chat.ConversationEntity
import io.github.hokanosekai.tensai.data.chat.MessageEntity
import io.github.hokanosekai.tensai.data.chat.ReplyStreamSplitter
import io.github.hokanosekai.tensai.data.chat.SamplingJson
import io.github.hokanosekai.tensai.data.chat.ThinkingStreamParser
import io.github.hokanosekai.tensai.data.models.ModelRepository
import io.github.hokanosekai.tensai.data.session.SaveResult
import io.github.hokanosekai.tensai.data.session.SessionModelKey
import io.github.hokanosekai.tensai.data.session.SessionRepository
import io.github.hokanosekai.tensai.engine.DecodeEvent
import io.github.hokanosekai.tensai.engine.DecodeRequest
import io.github.hokanosekai.tensai.engine.DecodeStats
import io.github.hokanosekai.tensai.engine.ReasoningBudget
import io.github.hokanosekai.tensai.engine.SamplingConfig
import io.github.hokanosekai.tensai.engine.StopReason
import io.github.hokanosekai.tensai.runtime.ActiveSessionTracker
import io.github.hokanosekai.tensai.runtime.InferenceQueue
import io.github.hokanosekai.tensai.runtime.QueueState
import io.github.hokanosekai.tensai.runtime.RuntimeGraph
import io.github.hokanosekai.tensai.runtime.residentModel
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
     * [StopReason.ReasoningBudgetExhausted]: the reasoning budget ran out before the model's
     * `<think>` block ever closed — there may be no answer at all yet.
     */
    Reasoning,

    /**
     * [StopReason.ResponseBudgetExhausted], or [StopReason.TokenBudget] for a reply generated
     * with no reasoning/response split (thinking off, or from before TEN-61): the answer itself
     * is cut off mid-thought.
     */
    Response,
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

private fun MessageEntity.toUi(): ChatMessageUi? {
    val isUser = when (role) {
        "user" -> true
        "assistant" -> false
        else -> return null // system/tool: nothing to render in V1.
    }
    val stats = if (tokensGenerated != null && tokPerSec != null && ttftMs != null) {
        val truncation = when (stopReason) {
            StopReason.ReasoningBudgetExhausted.name -> TruncationReason.Reasoning
            StopReason.ResponseBudgetExhausted.name, StopReason.TokenBudget.name -> TruncationReason.Response
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
        thinking = thinking,
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
     * The last generation attempted produced zero tokens because the conversation's prompt alone
     * already filled the model's context window (TEN-65, see
     * [io.github.hokanosekai.tensai.engine.StopReason.ContextBudgetExhausted]). No assistant message
     * was kept for that attempt (see [ChatViewModel.streamInto]'s [hasPersistableContent] check), so
     * this is the only trace of it the user sees.
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
 * because it never started at all (TEN-65's [io.github.hokanosekai.tensai.engine.StopReason.ContextBudgetExhausted]).
 * An empty-everything assistant turn is not a reply: keeping it would only get replayed into every
 * later prompt via [ChatRepository.toEngineMessages] for nothing, and would show as a blank bubble
 * with no explanation.
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
    private val sessionRepository: SessionRepository = RuntimeGraph.sessionRepository,
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

    /**
     * This conversation's sampling override, decoded from [ConversationEntity.samplingJson]
     * (TEN-35) — [SamplingConfig]'s defaults until the row is read, and for a conversation that has
     * never been customized. `enableThinking`/`reasoningBudget` are *not* meaningful here: both are
     * resolved at generation time, see [generateReply] and [SamplingJson]'s KDoc.
     */
    val sampling: Flow<SamplingConfig> =
        chatRepository.observeConversation(conversationId).map { SamplingJson.decode(it?.samplingJson) }

    /** This conversation's [ConversationEntity.systemPrompt], empty when unset — see [ChatRepository.setSystemPrompt]. */
    val systemPrompt: Flow<String> =
        chatRepository.observeConversation(conversationId).map { it?.systemPrompt.orEmpty() }

    /** Persists [config] for this conversation. Applies to the next [send]/[regenerate] — see [ChatRepository.setSampling]. */
    fun setSampling(config: SamplingConfig) {
        scope.launch { chatRepository.setSampling(conversationId, config) }
    }

    /** Drops the sampling override, back to defaults — see [ChatRepository.resetSampling] for why the system prompt is left alone. */
    fun resetSampling() {
        scope.launch { chatRepository.resetSampling(conversationId) }
    }

    /** Persists [prompt] as this conversation's system prompt. Applies to the next [send]/[regenerate]. */
    fun setSystemPrompt(prompt: String) {
        scope.launch { chatRepository.setSystemPrompt(conversationId, prompt) }
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
     * Set by [stop] and read by [streamInto]'s `finally` to record [StopReason.Cancelled].
     *
     * `@Volatile` because the two run on different threads: `stop()` is called from the UI thread,
     * the `finally` from [scope] ([kotlinx.coroutines.Dispatchers.Default]). Cleared at the top of
     * every [streamInto], so a flag left over from a previous turn cannot mislabel the next one.
     *
     * This is what makes "the user stopped it" a *fact* rather than an inference. It cannot be
     * confused with any other cancellation because nothing else sets it: leaving the chat screen
     * doesn't cancel a generation at all (it runs on [scope], see this class's KDoc), and the
     * notification's stop action goes through
     * [io.github.hokanosekai.tensai.runtime.InferenceService], whose cancel is not routed here — a
     * generation stopped from the notification therefore stays unlabelled rather than being
     * labelled wrongly, which is the safe direction to be incomplete in.
     */
    @Volatile
    private var stopRequested = false

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
     */
    fun stop() {
        stopRequested = true
        inferenceQueue.cancelActive()
    }

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

    /**
     * Starts a fresh assistant message and streams into it — the shared tail of [send] and
     * [regenerate].
     *
     * The reasoning/response split only applies when thinking is actually going to happen:
     * [ConversationEntity.enableThinking] defaults to `true` even for a conversation started
     * against a model with no thinking template at all (see its KDoc), so gating on that flag
     * alone would split — and needlessly shrink — the budget for a plain model that will never
     * emit [io.github.hokanosekai.tensai.data.chat.ThinkingStreamParser.CloseTag] in the first
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
    private suspend fun generateReply() {
        syncSessionForConversationSwitch()
        reportActiveSession()

        val conversation = chatRepository.getConversation(conversationId)
        val history = chatRepository.observeMessages(conversationId).first()
        val assistantId = chatRepository.startAssistantMessage(conversationId)
        val enableThinking = conversation?.enableThinking ?: true
        val thinkingActive = enableThinking && currentModelSupportsThinking.first()
        val sampling = SamplingJson.decode(conversation?.samplingJson)
        val reasoningBudget = if (thinkingActive) {
            ReasoningBudget.splitting(sampling.maxTokens, ThinkingStreamParser.CloseTag)
        } else {
            null
        }
        val request = DecodeRequest(
            messages = chatRepository.toEngineMessages(history, conversation?.systemPrompt),
            sampling = sampling.copy(enableThinking = enableThinking, reasoningBudget = reasoningBudget),
        )
        streamInto(assistantId, request)
    }

    /**
     * Resolves the [SessionModelKey] for whatever model is currently loaded, or `null` if none is
     * loaded or it can't be matched back to a persisted [io.github.hokanosekai.tensai.data.models.ModelEntity]
     * (by display name — see [currentModelSupportsThinking]'s KDoc for the same by-name-match
     * caveat this shares).
     */
    private suspend fun resolveSessionKey(): SessionModelKey? {
        val info = inferenceQueue.state.value.residentModel ?: return null
        val model = modelRepository.models.first().find { it.displayName == info.name } ?: return null
        return SessionModelKey(modelId = model.id, nCtx = info.nCtxEffective, kvCacheType = info.kvCacheType, backend = info.backend)
    }

    /**
     * Keeps this conversation's session in sync with whatever the engine's KV cache currently
     * holds, before the upcoming generation — TEN-34's cold-start restore, plus TEN-62's third save
     * trigger for switching conversations without a model swap. Three cases:
     *
     * - **Empty cache** (a cold start, or right after
     *   [io.github.hokanosekai.tensai.runtime.InferenceQueue.onBeforeModelSwap] swapped in a new
     *   model and froze the previous one's session to disk): nothing live to lose, so this
     *   conversation's persisted session is restored if one exists (TEN-34, unchanged).
     * - **Non-empty cache already bound to this conversation** ([ActiveSessionTracker.current]
     *   points at [conversationId]): the normal case of a conversation's second-and-later turn —
     *   the cache is this conversation's own live state, so nothing to do.
     * - **Non-empty cache bound to a *different* conversation** (TEN-62): the user switched
     *   conversations without the model changing. The outgoing conversation's live state is saved
     *   first via [SessionRepository.save] (`inferenceQueue::saveSession` as `saveOnEngine` — this
     *   runs outside [io.github.hokanosekai.tensai.runtime.InferenceQueue]'s mutex, unlike
     *   `onBeforeModelSwap`, see [SessionRepository]'s KDoc), and only restored into if that save
     *   didn't just lose data: [io.github.hokanosekai.tensai.data.session.SaveResult.Saved],
     *   [io.github.hokanosekai.tensai.data.session.SaveResult.Unchanged] (the fast A→B→A path with
     *   nothing generated in between — same token count already on disk, so the outgoing state
     *   *is* already saved) and
     *   [io.github.hokanosekai.tensai.data.session.SaveResult.NothingToSave] (an empty cache
     *   reported at save time — nothing to lose) all permit the restore.
     *   [io.github.hokanosekai.tensai.data.session.SaveResult.Failed] does not: TEN-34's guarantee
     *   is that a session is never restored over live state that wasn't first written to disk, so a
     *   failed save aborts the switch and this conversation falls back to a normal re-prefill
     *   instead, keeping the outgoing conversation's in-memory state exactly as it was (never
     *   restoring over it, never silently discarding anything the user generated).
     *   [io.github.hokanosekai.tensai.data.session.SaveResult.Discarded] (TEN-38: the user cleared
     *   saved sessions from Settings while this save was running) does not permit it either, and
     *   for the same reason — nothing was written, so nothing may be restored over. The switch
     *   falling back to a re-prefill is the correct outcome there too: after a purge there is no
     *   session left to restore into this conversation anyway.
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
    private suspend fun syncSessionForConversationSwitch() {
        if ((inferenceQueue.kvUsedPercent() ?: 0f) == 0f) {
            val key = resolveSessionKey() ?: return
            sessionRepository.restore(conversationId, key)
            return
        }

        val (outgoingConversationId, outgoingKey) = ActiveSessionTracker.current.value ?: return
        if (outgoingConversationId == conversationId) return

        val incomingKey = resolveSessionKey() ?: return
        if (outgoingKey != incomingKey) return

        val saveResult = sessionRepository.save(outgoingConversationId, outgoingKey, saveOnEngine = inferenceQueue::saveSession)
        val outgoingStateIsSafe = when (saveResult) {
            is SaveResult.Saved, is SaveResult.Unchanged, SaveResult.NothingToSave -> true
            is SaveResult.Failed, SaveResult.Discarded -> false
        }
        if (!outgoingStateIsSafe) return

        sessionRepository.restore(conversationId, incomingKey)
    }

    /** Reports this conversation as the one currently bound to the loaded model — see [ActiveSessionTracker]. */
    private suspend fun reportActiveSession() {
        val key = resolveSessionKey() ?: return
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
     */
    private suspend fun streamInto(assistantId: String, request: DecodeRequest) {
        val parser = ReplyStreamSplitter()
        var stats: DecodeStats? = null
        var failed = false
        var lastPersistAt = 0L
        stopRequested = false
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
                    // The cause is deliberately not kept. Nothing downstream is allowed to show it
                    // (TEN-39), so holding on to it would only be an invitation to.
                    is DecodeEvent.Error -> failed = true
                }
            }
        } finally {
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
                stopRequested && stats == null -> StopReason.Cancelled
                else -> stats?.stopReason
            }
            withContext(NonCancellable) {
                if (kept) {
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

            // Two ways to end a turn with nothing to show for it, both of which the user has to
            // hear about because no bubble was left behind to say it for them:
            //
            // - TEN-65: llama.cpp produced literally nothing because the prompt alone left no room
            //   to generate into (see ChatError.ContextWindowFull).
            // - TEN-39: the decode broke before its first token (see ChatError.GenerationFailed).
            //
            // A failure that *did* produce text needs no banner — the reply is on screen carrying
            // its own "generation failed" chip, and a banner on top would say the same thing twice.
            if (stats?.stopReason == StopReason.ContextBudgetExhausted) {
                _error.value = ChatError.ContextWindowFull
            } else if (failed && !kept) {
                _error.value = ChatError.GenerationFailed
            }
        }
    }
}
