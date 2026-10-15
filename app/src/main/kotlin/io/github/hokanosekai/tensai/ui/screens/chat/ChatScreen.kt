package io.github.hokanosekai.tensai.ui.screens.chat

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.Icon
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
import io.github.hokanosekai.tensai.R
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import io.github.hokanosekai.tensai.runtime.QueueState
import io.github.hokanosekai.tensai.runtime.residentModel
import io.github.hokanosekai.tensai.ui.components.InputPill
import io.github.hokanosekai.tensai.ui.components.SuggestionChips
import io.github.hokanosekai.tensai.ui.components.KanjiGlyph
import io.github.hokanosekai.tensai.ui.components.MessageBubble
import io.github.hokanosekai.tensai.ui.theme.tensaiTints

/**
 * The chat screen for `chat/{conversationId}` (TEN-32) — replaces `ChatScreenPlaceholder`. Chrome
 * (drawer hamburger, conversation title in the top bar) is owned by `TensaiShell`; this composable is body
 * content only.
 *
 * @param conversationId an existing conversation, or `null` for a new chat: a draft that gets its
 *   row on the first send (TEN-106), see [ChatViewModel]'s KDoc.
 * @param onNavigateToModels used by [ChatError.NoModelLoaded]'s banner.
 * @param onConversationCreated the draft got its real id (first send), reported for the drawer.
 * @param contextRequests taps on the top bar's context ring (owned by `TensaiShell`).
 * @param onContextRingState the ring's figure and busy flag, for the shell's top bar to draw (TEN-160).
 * @param settingsRequests taps on the top bar's params button (owned by `TensaiShell`), which open
 *   [ChatSettingsSheet].
 * @param initialPrompt a first turn handed over by Home (TEN-37), sent once on arrival — see
 *   [io.github.hokanosekai.tensai.ui.navigation.TensaiDestination.Chat.ARG_PROMPT]. `null` for
 *   every other way into a conversation.
 */
@Composable
fun ChatScreen(
    conversationId: String?,
    onNavigateToModels: () -> Unit,
    modifier: Modifier = Modifier,
    initialPrompt: String? = null,
    settingsRequests: Flow<Unit> = emptyFlow(),
    onConversationCreated: (String) -> Unit = {},
    contextRequests: Flow<Unit> = emptyFlow(),
    onContextRingState: (ContextRingState) -> Unit = {},
) {
    // A draft's row, once its first send created it (TEN-106). Saved so a screen rebuilt after
    // process death reopens that conversation instead of an empty draft, the route itself still
    // saying "new". Read only when the view model is built: it is that instance's own id coming
    // back, never a reason to build another one.
    var createdConversationId by rememberSaveable { mutableStateOf<String?>(null) }
    val viewModel = remember(conversationId) { ChatViewModel(conversationId ?: createdConversationId) }
    // Leaving this conversation (another one, a new chat, Home, Models) ends the cache warm-up
    // that was preparing for the user's next message here. Keyed on the instance, so switching
    // conversations disposes the outgoing one's. The reply itself is not touched, see ChatViewModel.leave.
    DisposableEffect(viewModel) { onDispose { viewModel.leave() } }
    val currentConversationId by viewModel.currentConversationId.collectAsState()
    LaunchedEffect(currentConversationId) {
        if (conversationId == null && currentConversationId != null) createdConversationId = currentConversationId
        // The route keeps saying "new" after the first send; the shell's drawer needs the real id
        // to highlight this chat's row (TEN-116). Replacing the route instead would rebuild this
        // screen's view model, see the remember(conversationId) above.
        if (conversationId == null) currentConversationId?.let(onConversationCreated)
    }

    val messages by viewModel.messages.collectAsState(initial = null)
    val error by viewModel.error.collectAsState()
    val queueState by viewModel.queueState.collectAsState()
    val isGenerating = queueState is QueueState.Generating
    // Narrower than isGenerating, and only the stop button uses it — see ChatViewModel.canStop.
    val canStop by viewModel.canStop.collectAsState(initial = false)
    // Generating still has a model resident, so the pill and the "no model" banner must not fall
    // back to the empty state mid-reply — see residentModel's KDoc (TEN-46, TEN-58).
    val modelInfo = queueState.residentModel
    // Null until first emission, like sampling below: the sheet opens only once all of them are known,
    // so the thinking row never flashes disabled or off before the real values arrive.
    val supportsThinking by viewModel.currentModelSupportsThinking.collectAsState(initial = null)
    val enableThinking by viewModel.enableThinking.collectAsState(initial = null)
    // Null until Room's first emission — the settings sheet is seeded from these once and must not
    // open on placeholder values it would then commit back, see ChatSettingsSheet's KDoc.
    val sampling by viewModel.sampling.collectAsState(initial = null)
    val systemPrompt by viewModel.systemPrompt.collectAsState(initial = null)

    var input by rememberSaveable { mutableStateOf("") }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    // The params button lives in the shell's top bar (TEN-115), so it only signals; this screen owns
    // the sheet and its state. Same pattern as the Models "+" import request.
    // Two guards, both about a tap that must not be queued: only the screen on top reacts (during a
    // transition two chats are composed, and one tap would open both sheets), and nothing opens
    // before Room has emitted the values the sheet seeds from (the tap would look dead, then the
    // sheet would pop up late).
    val settingsLoaded by rememberUpdatedState(
        sampling != null && systemPrompt != null && enableThinking != null && supportsThinking != null,
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

    // Home's send hands its text over on the route rather than writing it itself, so the first
    // reply streams here where it can be watched. rememberSaveable and not a plain flag: the route
    // still carries the prompt after process death, and a restored back stack entry must not
    // re-ask the question it already asked.
    var initialPromptSent by rememberSaveable(conversationId) { mutableStateOf(false) }
    LaunchedEffect(conversationId, initialPrompt) {
        if (initialPrompt.isNullOrBlank() || initialPromptSent) return@LaunchedEffect
        initialPromptSent = true
        viewModel.send(initialPrompt)
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
    LaunchedEffect(messages, followStream, overlayHeightPx) {
        if (!followStream) return@LaunchedEffect
        val count = messages?.size ?: return@LaunchedEffect
        if (count == 0) return@LaunchedEffect
        // Snap rather than animate: at streaming speed the next token arrives long before an
        // animation settles, and a perpetually restarting animation is what used to fight the
        // reader's own drag. scrollToItem alone aligns the item's *top* with the viewport, so
        // scrollBy pushes through the rest of a tall bubble — it clamps at the end of the list.
        listState.scrollToItem(count - 1)
        listState.scrollBy(Float.MAX_VALUE)
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (modelInfo == null) {
            NoModelBanner(onNavigateToModels = onNavigateToModels, modifier = Modifier.fillMaxWidth())
        }
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
        val currentMessages = messages
        Box(modifier = Modifier.weight(1f)) {
            val overlayHeight = with(density) { overlayHeightPx.toDp() }
            when {
                currentMessages == null -> Unit // initial Room query hasn't emitted yet.
                currentMessages.isEmpty() -> EmptyConversationState(modifier = Modifier.padding(bottom = overlayHeight))
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 16.dp,
                            bottom = overlayHeight + 8.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        itemsIndexed(currentMessages, key = { _, message -> message.id }) { index, message ->
                            val isLastAssistantReply = index == currentMessages.lastIndex && !message.isUser
                            val isThinkingActive = isLastAssistantReply && isGenerating &&
                                message.content.isEmpty() && !message.thinking.isNullOrEmpty()
                            MessageBubble(
                                message = message,
                                isThinkingActive = isThinkingActive,
                                onRegenerate = viewModel::regenerate.takeIf { isLastAssistantReply && !isGenerating },
                            )
                        }
                    }
                }
            }

            JumpToBottomButton(
                visible = !isAtBottom && !currentMessages.isNullOrEmpty(),
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
                // Starters for a conversation with no message yet; the same chips as Home, and they fill the
                // pill the same way. Gone as soon as there is a message.
                if (currentMessages != null && currentMessages.isEmpty()) {
                    SuggestionChips(
                        onPick = { input = it },
                        wrap = true,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    )
                }

                val background = MaterialTheme.colorScheme.background
                val pillMargin = 16.dp
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
                    modelLabel = modelInfo?.name ?: stringResource(R.string.top_bar_no_model),
                    onModelClick = onNavigateToModels,
                    translucent = true,
                    // Scrim: transparent at the pill's vertical center, fully background by its bottom
                    // edge and solid below, so text fades out under the pill's lower half and never
                    // slides crisply through the margin beneath it. Drawn behind the pill (and its ring).
                    modifier = Modifier
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
                        .padding(pillMargin),
                )
            }
        }
    }

    val currentSampling = sampling
    val currentSystemPrompt = systemPrompt
    val currentEnableThinking = enableThinking
    val currentSupportsThinking = supportsThinking
    if (settingsOpen && currentSampling != null && currentSystemPrompt != null &&
        currentEnableThinking != null && currentSupportsThinking != null
    ) {
        ChatSettingsSheet(
            config = currentSampling,
            systemPrompt = currentSystemPrompt,
            enableThinking = currentEnableThinking,
            supportsThinking = currentSupportsThinking,
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

@Composable
private fun EmptyConversationState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        KanjiGlyph(size = 96.dp, modifier = Modifier.alpha(0.15f))
    }
}

@Composable
private fun NoModelBanner(onNavigateToModels: () -> Unit, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.tensaiTints.warning12, modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_no_model_loaded),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onNavigateToModels) { Text(stringResource(R.string.chat_load_a_model)) }
        }
    }
}

/**
 * Shown when [ChatViewModel.error] holds [ChatError.ContextWindowFull] (TEN-65) — the prompt alone
 * already filled the model's context window, so the last "reply" wasn't a reply at all: nothing was
 * generated, and no empty bubble was kept for it (see [ChatViewModel.streamInto]; a user stop is the one
 * empty ending that does keep its row, TEN-110). Same sobre
 * treatment as [NoModelBanner]. Dismissing just clears the flag — the conversation itself is still
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
