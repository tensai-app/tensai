package io.github.tensaiapp.ui.screens.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.tensaiapp.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import io.github.tensaiapp.runtime.QueueState
import io.github.tensaiapp.runtime.residentModel
import io.github.tensaiapp.ui.components.AskAnswerBubble
import io.github.tensaiapp.ui.components.InputPill
import io.github.tensaiapp.ui.components.SuggestionChips
import io.github.tensaiapp.ui.components.MessageBubble
import io.github.tensaiapp.ui.components.ToolLine
import io.github.tensaiapp.ui.components.rememberReducedMotion
import io.github.tensaiapp.ui.theme.TensaiMotion
import io.github.tensaiapp.ui.theme.tensaiTints
import io.github.tensaiapp.ui.tools.toolLineSummary

/**
 * The chat screen for `chat/{conversationId}` (TEN-32) — replaces `ChatScreenPlaceholder`. Chrome
 * (drawer hamburger, conversation title in the top bar) is owned by `TensaiShell`; this composable is body
 * content only.
 *
 * @param conversationId an existing conversation, or `null` for a new chat: a draft that gets its
 *   row on the first send (TEN-106), see [ChatViewModel]'s KDoc.
 * @param onNavigateToModels the context sheet's "Load" when it has no model to name, and the model picker's "Manage models".
 * @param onNavigateToSettings the context sheet's way to the context length setting (TEN-161).
 * @param onNavigateToLoad the context sheet's "Load" (the model the conversation was started on) and the
 *   model picker's pick. The caller makes the load come back to this conversation.
 * @param onNavigateToDiscover the model picker's way to Discover.
 * @param onNavigateToImport the model picker's empty state, to the Models screen's file picker.
 * @param onConversationCreated the draft got its real id (first send), reported for the drawer.
 * @param onEmptyChanged whether the conversation has no message, for the shell's top bar (the brand
 *   and no context ring while empty).
 * @param contextRequests taps on the top bar's context ring (owned by `TensaiShell`).
 * @param onContextRingState the ring's figure and busy flag, for the shell's top bar to draw (TEN-160).
 * @param settingsRequests taps on the top bar's params button (owned by `TensaiShell`), which open
 *   [ChatSettingsSheet].
 */
@Composable
fun ChatScreen(
    conversationId: String?,
    onNavigateToModels: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    onNavigateToLoad: (modelId: String) -> Unit = {},
    onNavigateToDiscover: () -> Unit = {},
    onNavigateToImport: () -> Unit = {},
    modifier: Modifier = Modifier,
    settingsRequests: Flow<Unit> = emptyFlow(),
    onConversationCreated: (String) -> Unit = {},
    onEmptyChanged: (Boolean) -> Unit = {},
    contextRequests: Flow<Unit> = emptyFlow(),
    onContextRingState: (ContextRingState) -> Unit = {},
) {
    // A draft's row, once its first send created it (TEN-106). Saved so a screen rebuilt after
    // process death reopens that conversation instead of an empty draft, the route itself still
    // saying "new". Read only when the view model is built: it is that instance's own id coming
    // back, never a reason to build another one.
    var createdConversationId by rememberSaveable { mutableStateOf<String?>(null) }
    val viewModel = remember(conversationId) { ChatViewModel(conversationId ?: createdConversationId) }
    // Leaving this conversation (another one, a new chat, Models) ends the cache warm-up
    // that was preparing for the user's next message here. Keyed on the instance, so switching
    // conversations disposes the outgoing one's. The reply itself is not touched, see ChatViewModel.leave.
    DisposableEffect(viewModel) { onDispose { viewModel.leave() } }
    // Its row deleted from the Chats page while this screen was beneath it: back to a fresh draft.
    LaunchedEffect(viewModel) { viewModel.watchConversation() }
    // The tool prefix of its first message, built while the user reads or types (TEN-205).
    LaunchedEffect(viewModel) { viewModel.keepPrefixWarm() }
    val currentConversationId by viewModel.currentConversationId.collectAsState()
    LaunchedEffect(currentConversationId) {
        if (conversationId == null) createdConversationId = currentConversationId
        // The route keeps saying "new" after the first send; the shell's drawer needs the real id
        // to highlight this chat's row (TEN-116). Replacing the route instead would rebuild this
        // screen's view model, see the remember(conversationId) above.
        if (conversationId == null) currentConversationId?.let(onConversationCreated)
    }

    val messages by viewModel.messages.collectAsState(initial = null)
    // What the list draws: the messages with the tool lines and confirmation cards between them (TEN-173).
    val items by viewModel.items.collectAsState(initial = null)
    // The question the model waits on (TEN-187): its card takes the input pill's place while it is open.
    val question by viewModel.pendingQuestion.collectAsState(initial = null)
    // The tool line whose calls the sheet shows, by key: the sheet reads its calls from the live items.
    var toolSheetKey by rememberSaveable { mutableStateOf<String?>(null) }
    val isEmpty = showsEmptyState(messages?.size, isDraft = conversationId == null && createdConversationId == null)
    LaunchedEffect(messages) { messages?.let { onEmptyChanged(it.isEmpty()) } }
    val error by viewModel.error.collectAsState()
    val queueState by viewModel.queueState.collectAsState()
    val isGenerating = queueState is QueueState.Generating
    // Narrower than isGenerating, and only the stop button uses it — see ChatViewModel.canStop.
    val canStop by viewModel.canStop.collectAsState(initial = false)
    // Generating still has a model resident, so the pill's chip must not fall
    // back to "Load a model" mid-reply, see residentModel's KDoc (TEN-46, TEN-58).
    val modelInfo = queueState.residentModel
    // Null until first emission, like sampling below: the sheet opens only once all of them are known,
    // so the thinking row never flashes disabled or off before the real values arrive.
    val supportsThinking by viewModel.currentModelSupportsThinking.collectAsState(initial = null)
    val enableThinking by viewModel.enableThinking.collectAsState(initial = null)
    // Null until Room's first emission — the settings sheet is seeded from these once and must not
    // open on placeholder values it would then commit back, see ChatSettingsSheet's KDoc.
    val sampling by viewModel.sampling.collectAsState(initial = null)
    val systemPrompt by viewModel.systemPrompt.collectAsState(initial = null)
    val supportsTools by viewModel.currentModelSupportsTools.collectAsState(initial = null)
    val toolsEnabled by viewModel.toolsEnabled.collectAsState(initial = null)

    var input by rememberSaveable { mutableStateOf("") }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    // The params button lives in the shell's top bar (TEN-115), so it only signals; this screen owns
    // the sheet and its state. Same pattern as the Models "+" import request.
    // Two guards, both about a tap that must not be queued: only the screen on top reacts (during a
    // transition two chats are composed, and one tap would open both sheets), and nothing opens
    // before Room has emitted the values the sheet seeds from (the tap would look dead, then the
    // sheet would pop up late).
    val settingsLoaded by rememberUpdatedState(
        sampling != null && systemPrompt != null && enableThinking != null && supportsThinking != null &&
            supportsTools != null && toolsEnabled != null,
    )
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(settingsRequests, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            settingsRequests.collect { if (settingsLoaded) settingsOpen = true }
        }
    }

    // The ring lives in the shell's top bar like the params button, so this screen feeds it: the
    // view model re-reads the cache whenever the queue moves (load, swap, unload, generation) or
    // the draft gets its id, and the figure goes up with the busy flag. A tap goes the other way.
    val contextUsage by viewModel.contextUsage.collectAsState()
    val busy by viewModel.busy.collectAsState(initial = false)
    LaunchedEffect(viewModel, queueState, currentConversationId) { viewModel.refreshContextUsage() }
    LaunchedEffect(contextUsage, busy) { onContextRingState(ContextRingState(contextUsage, busy)) }
    LaunchedEffect(contextRequests, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            contextRequests.collect { viewModel.openContextSheet() }
        }
    }

    val listState = rememberLazyListState()
    val keyboardController = LocalSoftwareKeyboardController.current

    // "At the bottom" is a position, not an index: a streaming bubble taller than the viewport
    // keeps the last item visible long after its tail has scrolled out of sight, so an index-based
    // test reads as "at the bottom" while the tokens land off-screen (TEN-57).
    val isAtBottom by remember { derivedStateOf { !listState.canScrollForward } }

    // Following the stream is armed while the reader stays at the bottom, and only a drag can
    // disarm it. Disarming on position alone would let the growing bubble push the viewport away
    // from the bottom and switch itself off.
    var followStream by remember { mutableStateOf(true) }
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(isDragged, isAtBottom) {
        if (isDragged || isAtBottom) followStream = isAtBottom
    }

    // Height of the overlay (starters + pill) floating over the list, measured: the list pads its
    // bottom by it so the last message rests above the pill while scrolling under it (TEN-133).
    var overlayHeightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current

    // Keyed on the overlay height too: a growing input (several lines) grows the bottom padding, and
    // a reader following the stream must stay glued to the end rather than be left above it.
    LaunchedEffect(items, followStream, overlayHeightPx) {
        if (!followStream) return@LaunchedEffect
        val count = items?.size ?: return@LaunchedEffect
        if (count == 0) return@LaunchedEffect
        // Snap rather than animate: at streaming speed the next token arrives long before an
        // animation settles, and a perpetually restarting animation is what used to fight the
        // reader's own drag. scrollToItem alone aligns the item's *top* with the viewport, so
        // scrollBy pushes through the rest of a tall bubble — it clamps at the end of the list.
        listState.scrollToItem(count - 1)
        listState.scrollBy(Float.MAX_VALUE)
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (error is ChatError.ContextWindowFull) {
            ContextWindowFullBanner(onDismiss = viewModel::clearError, modifier = Modifier.fillMaxWidth())
        }
        if (error is ChatError.GenerationFailed) {
            GenerationFailedBanner(
                onRetry = {
                    viewModel.clearError()
                    viewModel.regenerate()
                },
                onDismiss = viewModel::clearError,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val currentItems = items
        Box(modifier = Modifier.weight(1f)) {
            val overlayHeight = with(density) { overlayHeightPx.toDp() }
            // The greeting, subtitle and watermark fade out at the first message (TEN-169), and the list
            // fades in over them, but only then: opening a saved conversation shows its messages at once.
            val reducedMotion = rememberReducedMotion()
            val fade = if (reducedMotion) snap<Float>() else tween<Float>(TensaiMotion.DurBase)
            var sawEmpty by remember { mutableStateOf(false) }
            LaunchedEffect(isEmpty) { if (isEmpty) sawEmpty = true }
            // Alpha rather than AnimatedVisibility: inside this Box the one in scope is the enclosing Column's.
            val emptyAlpha by animateFloatAsState(if (isEmpty) 1f else 0f, fade, label = "emptyState")
            if (emptyAlpha > 0f) {
                ChatEmptyState(modifier = Modifier.alpha(emptyAlpha).padding(bottom = overlayHeight))
            }
            val listAlpha by animateFloatAsState(
                if (currentItems.isNullOrEmpty()) 0f else 1f,
                if (sawEmpty) fade else snap(),
                label = "messages",
            )
            if (currentItems != null && currentItems.isNotEmpty()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().alpha(listAlpha),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 16.dp,
                        bottom = overlayHeight + 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    itemsIndexed(currentItems, key = { _, item -> item.key }) { index, item ->
                        val endsTheTurn = index == currentItems.lastIndex && !isGenerating && !canStop
                        when (item) {
                            is MessageItem -> {
                                val message = item.message
                                val isLastAssistantReply = index == currentItems.lastIndex && !message.isUser
                                val isThinkingActive = isLastAssistantReply && isGenerating &&
                                    message.content.isEmpty() && !message.thinking.isNullOrEmpty()
                                MessageBubble(
                                    message = message,
                                    isThinkingActive = isThinkingActive,
                                    onRegenerate = viewModel::regenerate.takeIf { isLastAssistantReply && !isGenerating },
                                    isGenerating = isLastAssistantReply && isGenerating,
                                )
                            }
                            is ToolLineItem -> Column {
                                ToolLine(
                                    summary = toolLineSummary(item.calls),
                                    onClick = { toolSheetKey = item.key },
                                )
                                // A turn that ends on a call (stopped during a tool) has no answer to hang Regenerate on.
                                if (endsTheTurn) RegenerateButton(viewModel::regenerate)
                            }
                            is ConfirmationItem -> Column {
                                ToolConfirmCard(
                                    item = item,
                                    onAnswer = { allow -> viewModel.answerConfirmation(item.call.id, allow) },
                                )
                                if (endsTheTurn) RegenerateButton(viewModel::regenerate)
                            }
                            is AskAnswerItem -> AskAnswerBubble(item.question, item.answers)
                        }
                    }
                }
            }

            JumpToBottomButton(
                visible = !isAtBottom && !currentItems.isNullOrEmpty(),
                // Re-arming the follow is the whole action: the effect above owns the scrolling,
                // so the button never races it with a second one of its own.
                onClick = { followStream = true },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = overlayHeight + 12.dp),
            )

            // Floats over the list (TEN-133) instead of stacking under it, so the reply scrolls
            // beneath the translucent pill.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onSizeChanged { overlayHeightPx = it.height },
            ) {
                // Starters for a conversation with no message yet: one line that scrolls, just above the
                // pill. A tap fills the pill, the user edits before sending. They fade out with the rest of
                // the empty state at the first message.
                AnimatedVisibility(visible = isEmpty, enter = EnterTransition.None, exit = fadeOut(fade)) {
                    SuggestionChips(
                        onPick = { input = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }

                val background = MaterialTheme.colorScheme.background
                val pillMargin = 16.dp
                // Scrim: transparent at the pill's vertical center, fully background by its bottom
                // edge and solid below, so text fades out under the pill's lower half and never
                // slides crisply through the margin beneath it. Drawn behind the pill (and its ring).
                val pillModifier = Modifier
                    .drawBehind {
                        val margin = pillMargin.toPx()
                        val start = margin + (size.height - 2 * margin) / 2f
                        val end = size.height - margin
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(Color.Transparent, background),
                                startY = start,
                                endY = end,
                            ),
                            topLeft = Offset(0f, start),
                            size = Size(size.width, size.height - start),
                        )
                    }
                    .padding(pillMargin)
                val asked = question
                if (asked != null) {
                    AskCard(
                        question = asked,
                        onAnswer = { answers -> viewModel.answerQuestion(asked.callId, answers) },
                        onDismiss = { viewModel.dismissQuestion(asked.callId) },
                        modifier = pillModifier,
                    )
                } else {
                    InputPill(
                        value = input,
                        onValueChange = { input = it },
                        onSend = {
                            if (input.isNotBlank()) {
                                viewModel.send(input)
                                input = ""
                                // The reply starts streaming right away, leaving the keyboard up would cover
                                // most of it (TEN-56).
                                keyboardController?.hide()
                            }
                        },
                        onStop = viewModel::stop,
                        isGenerating = isGenerating,
                        canStop = canStop,
                        modelLabel = modelChipLabel(modelInfo?.name, stringResource(R.string.chat_load_a_model)),
                        onModelClick = { pickerOpen = true },
                        // No model, nothing to send to: the chip is the way forward, it opens the picker.
                        canSend = modelInfo != null,
                        translucent = true,
                        modifier = pillModifier,
                    )
                }
            }
        }
    }

    val contextSheetOpen by viewModel.contextSheetOpen.collectAsState()
    val contextSheet by viewModel.contextSheet.collectAsState()
    val currentContextSheet = contextSheet
    if (contextSheetOpen && currentContextSheet != null) {
        ContextSheet(
            state = currentContextSheet,
            onLoad = { modelId ->
                viewModel.closeContextSheet()
                if (modelId != null) onNavigateToLoad(modelId) else onNavigateToModels()
            },
            onOpenSettings = {
                viewModel.closeContextSheet()
                onNavigateToSettings()
            },
            onDismiss = viewModel::closeContextSheet,
        )
    }

    val toolSheetLine = items?.firstOrNull { it is ToolLineItem && it.key == toolSheetKey } as? ToolLineItem
    // The line is gone (the reply was regenerated or the chat deleted): nothing to show, and no stale key to come back to.
    LaunchedEffect(items, toolSheetKey) {
        if (items != null && toolSheetKey != null && toolSheetLine == null) toolSheetKey = null
    }
    if (toolSheetLine != null) {
        ToolCallsSheet(
            title = toolLineSummary(toolSheetLine.calls).label,
            calls = toolSheetLine.calls,
            onDismiss = { toolSheetKey = null },
        )
    }

    if (pickerOpen) {
        ModelPickerSheet(
            loadedModelId = modelInfo?.modelId,
            busy = pickerBusy(queueState),
            onPick = { modelId ->
                pickerOpen = false
                onNavigateToLoad(modelId)
            },
            onManage = {
                pickerOpen = false
                onNavigateToModels()
            },
            onDiscover = {
                pickerOpen = false
                onNavigateToDiscover()
            },
            onImport = {
                pickerOpen = false
                onNavigateToImport()
            },
            onDismiss = { pickerOpen = false },
        )
    }

    val currentSampling = sampling
    val currentSystemPrompt = systemPrompt
    val currentEnableThinking = enableThinking
    val currentSupportsThinking = supportsThinking
    val currentSupportsTools = supportsTools
    val currentToolsEnabled = toolsEnabled
    if (settingsOpen && currentSampling != null && currentSystemPrompt != null &&
        currentEnableThinking != null && currentSupportsThinking != null &&
        currentSupportsTools != null && currentToolsEnabled != null
    ) {
        ChatSettingsSheet(
            config = currentSampling,
            systemPrompt = currentSystemPrompt,
            enableThinking = currentEnableThinking,
            supportsThinking = currentSupportsThinking,
            supportsTools = currentSupportsTools,
            toolsEnabled = currentToolsEnabled,
            onToolsEnabledChange = viewModel::setToolsEnabled,
            contextSize = modelInfo?.nCtxEffective,
            onConfigChange = viewModel::setSampling,
            onSystemPromptChange = viewModel::setSystemPrompt,
            onEnableThinkingChange = viewModel::setEnableThinking,
            onReset = { viewModel.resetTuning(resetThinking = currentSupportsThinking) },
            onDismiss = { settingsOpen = false },
        )
    }
}

/**
 * Floating "back to the newest message" affordance, shown only while the conversation is scrolled
 * away from its bottom (TEN-57). Deliberately just an arrow: it sits over the conversation, so
 * anything wordier would compete with the text it covers.
 */
@Composable
private fun JumpToBottomButton(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(initialScale = 0.85f),
        exit = fadeOut() + scaleOut(targetScale = 0.85f),
        modifier = modifier,
    ) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = 8.dp,
        ) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(R.string.chat_jump_to_latest),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(8.dp).size(24.dp),
            )
        }
    }
}

/**
 * Shown when [ChatViewModel.error] holds [ChatError.ContextWindowFull] (TEN-65) — the prompt alone
 * already filled the model's context window, so the last "reply" wasn't a reply at all: nothing was
 * generated, and no empty bubble was kept for it (see [ChatViewModel.streamInto]; a user stop is the one
 * empty ending that does keep its row, TEN-110). Same sobre
 * treatment as [GenerationFailedBanner]. Dismissing just clears the flag, the conversation itself is still
 * full, no context trimming happens here (TEN-65 deliberately leaves that out of scope), so starting
 * a new chat is the actual way forward.
 */
@Composable
private fun ContextWindowFullBanner(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.tensaiTints.warning12, modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_context_full),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_dismiss)) }
        }
    }
}

/**
 * Shown when [ChatViewModel.error] holds [ChatError.GenerationFailed] (TEN-39) — a decode that
 * broke before its first token, so no turn was kept for it (see [ChatViewModel.streamInto]; unlike
 * a user stop, which keeps an empty "stopped" turn, TEN-110).
 *
 * Same treatment as [ContextWindowFullBanner], because it is the same situation: an attempt that
 * left nothing behind to explain itself. What it deliberately does *not* do is say why, in the
 * engine's words — see [ChatError.GenerationFailed] for what that used to look like in a message
 * bubble. "Try again" is the honest action: the failures that reach here (a decode aborted by
 * memory pressure, a backend hiccup) are usually not repeatable, and regenerating from the same
 * history is one tap.
 */
@Composable
private fun GenerationFailedBanner(
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.tensaiTints.error12, modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_generation_failed),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.chat_generation_failed_dismiss)) }
            TextButton(onClick = onRetry) { Text(stringResource(R.string.chat_generation_failed_retry)) }
        }
    }
}

/** The regenerate action alone, under a tool line or card that ends the conversation. Same look as the one in a reply's row. */
@Composable
private fun RegenerateButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(28.dp)) {
        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = stringResource(R.string.chat_regenerate),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}
