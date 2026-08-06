package io.github.hokanosekai.tensai.ui.screens.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.unit.dp
import io.github.hokanosekai.tensai.runtime.QueueState
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
    val queueState by viewModel.queueState.collectAsState()
    val isGenerating = queueState is QueueState.Generating
    val modelInfo = (queueState as? QueueState.ModelReady)?.info
    val supportsThinking by viewModel.currentModelSupportsThinking.collectAsState(initial = false)
    val enableThinking by viewModel.enableThinking.collectAsState(initial = true)

    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Auto-scroll to the newest message as it streams in, unless the user has scrolled up to
    // read earlier history — re-checked on every message list update, not just once.
    val isNearBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisible >= layoutInfo.totalItemsCount - 2
        }
    }
    LaunchedEffect(messages) {
        val count = messages?.size ?: return@LaunchedEffect
        if (count > 0 && isNearBottom) listState.animateScrollToItem(count - 1)
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (modelInfo == null) {
            NoModelBanner(onNavigateToModels = onNavigateToModels, modifier = Modifier.fillMaxWidth())
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
        }

        InputPill(
            value = input,
            onValueChange = { input = it },
            onSend = {
                if (input.isNotBlank()) {
                    viewModel.send(input)
                    input = ""
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
