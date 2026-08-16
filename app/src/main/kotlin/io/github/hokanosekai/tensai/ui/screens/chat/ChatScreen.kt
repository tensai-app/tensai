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
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import io.github.hokanosekai.tensai.runtime.QueueState
import io.github.hokanosekai.tensai.runtime.residentModel
import io.github.hokanosekai.tensai.ui.components.InputPill
import io.github.hokanosekai.tensai.ui.components.KanjiGlyph
import io.github.hokanosekai.tensai.ui.components.MessageBubble
import io.github.hokanosekai.tensai.ui.theme.tensaiTints

/** Max length of the short label shown in [InputPill]'s model chip — the full name lives on [io.github.hokanosekai.tensai.ui.navigation.TensaiTopBar]'s pill. */
private const val ModelChipLabelMaxLength = 18

/**
 * The chat screen for `chat/{conversationId}` (TEN-32) — replaces `ChatScreenPlaceholder`. Chrome
 * (drawer hamburger, model pill in the top bar) is owned by `TensaiShell`; this composable is body
 * content only.
 *
 * @param conversationId always names an existing conversation — see [ChatViewModel]'s KDoc for why
 *   the "new chat" sentinel is resolved before this composable is ever reached.
 * @param onNavigateToModels used both by [ChatError.NoModelLoaded]'s banner and by the input
 *   pill's model chip.
 */
@Composable
fun ChatScreen(
    conversationId: String,
    onNavigateToModels: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ChatViewModel = remember(conversationId) { ChatViewModel(conversationId) },
) {
    val messages by viewModel.messages.collectAsState(initial = null)
    val error by viewModel.error.collectAsState()
    val queueState by viewModel.queueState.collectAsState()
    val isGenerating = queueState is QueueState.Generating
    // Generating still has a model resident, so the pill and the "no model" banner must not fall
    // back to the empty state mid-reply — see residentModel's KDoc (TEN-46, TEN-58).
    val modelInfo = queueState.residentModel
    val supportsThinking by viewModel.currentModelSupportsThinking.collectAsState(initial = false)
    val enableThinking by viewModel.enableThinking.collectAsState(initial = true)

    var input by rememberSaveable { mutableStateOf("") }
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

    LaunchedEffect(messages, followStream) {
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
        if (supportsThinking) {
            ThinkingToggleRow(
                enabled = enableThinking,
                onToggle = viewModel::setEnableThinking,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        val currentMessages = messages
        Box(modifier = Modifier.weight(1f)) {
            when {
                currentMessages == null -> Unit // initial Room query hasn't emitted yet.
                currentMessages.isEmpty() -> EmptyConversationState()
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
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
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            )
        }

        InputPill(
            value = input,
            onValueChange = { input = it },
            onSend = {
                if (input.isNotBlank()) {
                    viewModel.send(input)
                    input = ""
                    // The reply starts streaming right away — leaving the keyboard up would cover
                    // most of it (TEN-56).
                    keyboardController?.hide()
                }
            },
            onStop = viewModel::stop,
            isGenerating = isGenerating,
            modelLabel = modelInfo?.name?.let(::shortModelLabel) ?: "no model",
            onModelClick = onNavigateToModels,
            modifier = Modifier.padding(16.dp),
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
                contentDescription = "Jump to the latest message",
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
                text = "No model loaded",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onNavigateToModels) { Text("Load a model") }
        }
    }
}

/**
 * Shown when [ChatViewModel.error] holds [ChatError.ContextWindowFull] (TEN-65) — the prompt alone
 * already filled the model's context window, so the last "reply" wasn't a reply at all: nothing was
 * generated, and no empty bubble was kept for it (see [ChatViewModel.streamInto]). Same sobre
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
                text = "This conversation is full — start a new chat to keep going",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

/**
 * Enable/disable [io.github.hokanosekai.tensai.engine.SamplingConfig.enableThinking] for this
 * conversation (TEN-33) — only shown when [ChatViewModel.currentModelSupportsThinking] is `true`.
 * Kept as a plain inline row rather than a top-bar action: `TensaiTopBar` is shared chrome for both
 * [io.github.hokanosekai.tensai.ui.navigation.TensaiDestination.Home] and
 * [io.github.hokanosekai.tensai.ui.navigation.TensaiDestination.Chat], and Home has no conversation
 * (nor a loaded-model-supports-thinking concept) to hang a toggle off — wiring one in there would
 * mean threading `ChatViewModel` state up through `TensaiShell` for every route, not just this one.
 */
@Composable
private fun ThinkingToggleRow(enabled: Boolean, onToggle: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Psychology,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 6.dp),
        )
        Text(
            text = "Thinking",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

/** e.g. "Qwen3-4B-Instruct-Q4_K_M" -> "Qwen3-4B-Instruct…" — keeps [InputPill]'s chip compact. */
private fun shortModelLabel(name: String): String =
    if (name.length <= ModelChipLabelMaxLength) name else name.take(ModelChipLabelMaxLength).trimEnd() + "…"
