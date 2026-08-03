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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.hokanosekai.tensai.ui.components.KanjiGlyph
import io.github.hokanosekai.tensai.ui.theme.TensaiMono
import io.github.hokanosekai.tensai.ui.theme.tensaiTints
import kotlin.math.roundToInt

/**
 * STUB conversation preview for the drawer's "Recents" section.
 * TODO(TEN-31): replace with the real conversation repository.
 */
private data class RecentConversationStub(val id: String, val title: String)

private val recentConversationsStub = listOf(
    RecentConversationStub("stub-1", "Refactor the parser"),
    RecentConversationStub("stub-2", "Explain this stack trace"),
    RecentConversationStub("stub-3", "Draft a README"),
)

/** Drawer content: brand header, new chat, recents (stub), destinations, device footer. */
@Composable
fun TensaiDrawerContent(
    onNewChat: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
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
                onClick = onNewChat,
                modifier = Modifier.padding(horizontal = 12.dp),
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Recents",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )

            recentConversationsStub.forEach { conversation ->
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

            Spacer(modifier = Modifier.weight(1f))

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 24.dp),
                color = MaterialTheme.tensaiTints.outline,
            )

            DeviceFooter(modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
        }
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
