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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.hokanosekai.tensai.R
import io.github.hokanosekai.tensai.data.chat.ChatRepository
import io.github.hokanosekai.tensai.runtime.residentModel
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
) {
    val conversations by chatRepository.observeConversations().collectAsState(initial = null)
    val queueState by RuntimeGraph.inferenceQueue.state.collectAsState()
    val scope = rememberCoroutineScope()

    // Straight off the load since TEN-80. Resolving it through the model list by display name gave
    // the newest row sharing that name, so a new chat opened under the older of two quantisations
    // was stamped with the other one's id — a wrong answer where this one cannot be wrong.
    // `residentModel`, not `ModelReady` alone: a "New chat" started while a reply is streaming in
    // another conversation would otherwise be created with a null modelId (TEN-76).
    val loadedModelId = queueState.residentModel?.modelId

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
                label = { Text(stringResource(R.string.drawer_new_chat), color = MaterialTheme.colorScheme.primary) },
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
                text = stringResource(R.string.drawer_recents),
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
                label = { Text(stringResource(R.string.nav_models)) },
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
                        Text(stringResource(R.string.nav_server))
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
                label = { Text(stringResource(R.string.nav_settings)) },
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

/**
 * "Recents" empty state (TEN-39, state 1) — no conversation created yet.
 *
 * Reached by exactly one real event: a library with no conversation rows in it, which is a blank
 * install and also every install whose last chat was deleted. The glyph is at 0.3 alpha and the
 * line is a `bodyMedium` in `onSurfaceVariant` — deliberately quieter than the "New chat" item
 * above it, which is the thing to do about it.
 */
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
            text = stringResource(R.string.drawer_recents_empty),
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
