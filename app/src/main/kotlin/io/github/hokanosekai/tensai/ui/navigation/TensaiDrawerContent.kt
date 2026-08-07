package io.github.hokanosekai.tensai.ui.navigation

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.hokanosekai.tensai.data.chat.ChatRepository
import io.github.hokanosekai.tensai.data.models.ModelRepository
import io.github.hokanosekai.tensai.runtime.QueueState
import io.github.hokanosekai.tensai.runtime.RuntimeGraph
import io.github.hokanosekai.tensai.ui.components.KanjiGlyph
import io.github.hokanosekai.tensai.ui.theme.TensaiMono
import io.github.hokanosekai.tensai.ui.theme.tensaiTints
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Drawer content: brand header, new chat, recents, destinations, device footer.
 *
 * @param onOpenConversation navigates to `chat/{id}` — called both for an existing conversation
 *   tapped from "Recents" and for the id created by "New chat".
 */
@Composable
fun TensaiDrawerContent(
    onOpenConversation: (String) -> Unit,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    chatRepository: ChatRepository = RuntimeGraph.chatRepository,
    modelRepository: ModelRepository = RuntimeGraph.modelRepository,
) {
    val conversations by chatRepository.observeConversations().collectAsState(initial = null)
    val queueState by RuntimeGraph.inferenceQueue.state.collectAsState()
    val models by modelRepository.models.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    // Same by-displayName caveat as ModelsScreen's `loadedModelName` — see ModelRepository's KDoc.
    val loadedModelId = (queueState as? QueueState.ModelReady)?.info?.name
        ?.let { name -> models.find { it.displayName == name }?.id }

    fun startNewChat() {
        scope.launch {
            val id = chatRepository.createConversation(loadedModelId)
            onOpenConversation(id)
        }
    }

    ModalDrawerSheet(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(horizontal = 24.dp),
            ) {
                KanjiGlyph(size = 22.dp)
                Text(text = "tensai", style = MaterialTheme.typography.titleMedium)
            }

            Spacer(modifier = Modifier.height(16.dp))

            NavigationDrawerItem(
                label = { Text("New chat", color = MaterialTheme.colorScheme.primary) },
                icon = { Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                selected = false,
                shape = RectangleShape,
                colors = NavigationDrawerItemDefaults.colors(
                    unselectedContainerColor = MaterialTheme.tensaiTints.primary12,
                ),
                onClick = ::startNewChat,
                modifier = Modifier.padding(horizontal = 12.dp),
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Recents",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )

            // Recents is the only part that grows without bound, so it is the only part that
            // scrolls: the destinations below stay pinned and reachable however many
            // conversations pile up (TEN-59). weight(1f) also keeps that bottom block against the
            // bottom edge when there are only a handful, which is what the old trailing Spacer did.
            val currentConversations = conversations
            LazyColumn(modifier = Modifier.weight(1f)) {
                when {
                    currentConversations == null -> Unit // initial Room query hasn't emitted yet.
                    currentConversations.isEmpty() -> item { EmptyRecentsState() }
                    else -> items(currentConversations, key = { it.id }) { conversation ->
                        NavigationDrawerItem(
                            label = {
                                Text(
                                    text = conversation.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            selected = false,
                            shape = RectangleShape,
                            onClick = { onOpenConversation(conversation.id) },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                color = MaterialTheme.tensaiTints.outline,
            )

            NavigationDrawerItem(
                label = { Text("Models") },
                icon = { Icon(Icons.Default.Storage, contentDescription = null) },
                selected = false,
                shape = RectangleShape,
                onClick = { onNavigate(TensaiDestination.Models.route) },
                modifier = Modifier.padding(horizontal = 12.dp),
            )

            NavigationDrawerItem(
                label = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Server")
                        Text(
                            text = "V2",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                icon = { Icon(Icons.Default.Dns, contentDescription = null) },
                selected = false,
                shape = RectangleShape,
                onClick = { onNavigate(TensaiDestination.Server.route) },
                modifier = Modifier.padding(horizontal = 12.dp),
            )

            NavigationDrawerItem(
                label = { Text("Settings") },
                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                selected = false,
                shape = RectangleShape,
                onClick = { onNavigate(TensaiDestination.Settings.route) },
                modifier = Modifier.padding(horizontal = 12.dp),
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 24.dp),
                color = MaterialTheme.tensaiTints.outline,
            )

            DeviceFooter(modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
        }
    }
}

/** "Recents" empty state — no conversation created yet. */
@Composable
private fun EmptyRecentsState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        KanjiGlyph(size = 40.dp, modifier = Modifier.alpha(0.3f))
        Text(
            text = "No chats yet",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** Real device chip: SoC (API 31+) or hardware string, plus total RAM rounded to the nearest GB. */
@Composable
private fun DeviceFooter(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val label = remember(context) { deviceChipLabel(context) }
    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = TensaiMono),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

private fun deviceChipLabel(context: Context): String {
    val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN } ?: Build.HARDWARE
    } else {
        Build.HARDWARE
    }
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    val memoryInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
    val totalGb = (memoryInfo.totalMem / (1024.0 * 1024.0 * 1024.0)).roundToInt()
    return "$soc · $totalGb GB"
}
