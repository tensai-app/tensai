package io.github.hokanosekai.tensai.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.hokanosekai.tensai.R
import io.github.hokanosekai.tensai.data.chat.ChatRepository
import io.github.hokanosekai.tensai.runtime.RuntimeGraph
import io.github.hokanosekai.tensai.ui.components.KanjiGlyph
import io.github.hokanosekai.tensai.ui.theme.tensaiTints

/**
 * Drawer content: brand header, destinations, recents (order per the design's DrawerOverlay,
 * TEN-116, TEN-164).
 *
 * @param currentRoute / currentConversationId / createdConversationId the active destination, which decides the selected
 *   row, see [drawerSelectedKey].
 * @param onOpenConversation navigates to `chat/{id}`: an existing conversation tapped from
 *   "Recents", or the new-chat sentinel for "New chat", which opens a draft and creates no row
 *   until its first message (TEN-106).
 */
@Composable
fun TensaiDrawerContent(
    onOpenConversation: (String) -> Unit,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    currentRoute: String? = null,
    currentConversationId: String? = null,
    createdConversationId: String? = null,
    chatRepository: ChatRepository = RuntimeGraph.chatRepository,
) {
    val conversations by chatRepository.observeConversations().collectAsState(initial = null)

    val selectedKey = drawerSelectedKey(currentRoute, currentConversationId, createdConversationId)
    val itemPadding = Modifier.padding(horizontal = 12.dp)

    ModalDrawerSheet(modifier = modifier) {
        // One scroll for everything (TEN-164): with only the recents scrolling, a short screen or a
        // large font left them a sliver under a header and a destination block that never moved.
        // The recents are capped (see [drawerRecents]), so the list stays short either way.
        val currentConversations = conversations
        val untitledLabel = stringResource(R.string.drawer_new_chat)
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 12.dp),
                ) {
                    KanjiGlyph(size = 30.dp)
                    Text(
                        text = "tensai",
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                    )
                }
            }

            item {
                Column(modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)) {
                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.drawer_new_chat), color = MaterialTheme.colorScheme.primary) },
                        icon = {
                            Icon(
                                painterResource(R.drawable.ic_edit_square),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        selected = false,
                        shape = CircleShape,
                        colors = drawerItemColors(),
                        onClick = { onOpenConversation(TensaiDestination.Chat.NEW_CONVERSATION_ID) },
                        modifier = itemPadding,
                    )

                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.nav_chats)) },
                        icon = { Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null) },
                        selected = selectedKey == "chats",
                        shape = CircleShape,
                        colors = drawerItemColors(),
                        onClick = { onNavigate(TensaiDestination.Chats.route) },
                        modifier = itemPadding,
                    )

                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.nav_models)) },
                        icon = { Icon(painterResource(R.drawable.ic_deployed_code), contentDescription = null) },
                        selected = selectedKey == "models",
                        shape = CircleShape,
                        colors = drawerItemColors(),
                        onClick = { onNavigate(TensaiDestination.Models.route) },
                        modifier = itemPadding,
                    )

                    NavigationDrawerItem(
                        label = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(stringResource(R.string.nav_server))
                                Text(
                                    text = stringResource(R.string.nav_server_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        icon = { Icon(Icons.Rounded.Lan, contentDescription = null) },
                        selected = selectedKey == "server",
                        shape = CircleShape,
                        colors = drawerItemColors(),
                        onClick = { onNavigate(TensaiDestination.Server.route) },
                        modifier = itemPadding,
                    )

                    NavigationDrawerItem(
                        label = { Text(stringResource(R.string.nav_settings)) },
                        icon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                        selected = selectedKey == "settings",
                        shape = CircleShape,
                        colors = drawerItemColors(),
                        onClick = { onNavigate(TensaiDestination.Settings.route) },
                        modifier = itemPadding,
                    )
                }
            }

            item {
                Text(
                    text = stringResource(R.string.drawer_recents).uppercase(Locale.current.platformLocale),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.04.em,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // The bottom gap keeps the label off the first row's pill when that row is the
                    // active one (TEN-130).
                    modifier = Modifier.padding(start = 24.dp, top = 8.dp, end = 24.dp, bottom = 12.dp),
                )
            }

            when {
                currentConversations == null -> Unit // initial Room query hasn't emitted yet.
                currentConversations.isEmpty() -> item { EmptyRecentsState() }
                else -> {
                    val recents = drawerRecents(currentConversations)
                    items(recents.shown, key = { it.id }) { conversation ->
                        NavigationDrawerItem(
                            label = {
                                Text(
                                    text = conversationLabel(conversation.title, untitledLabel),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            icon = { Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null) },
                            selected = selectedKey == recentKey(conversation.id),
                            shape = CircleShape,
                            colors = drawerItemColors(),
                            onClick = { onOpenConversation(conversation.id) },
                            modifier = itemPadding,
                        )
                    }
                    if (recents.showAllChats) {
                        item {
                            NavigationDrawerItem(
                                label = {
                                    Text(
                                        text = stringResource(R.string.drawer_all_chats),
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Medium,
                                    )
                                },
                                badge = {
                                    Icon(
                                        Icons.Rounded.ChevronRight,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                selected = false,
                                shape = CircleShape,
                                colors = drawerItemColors(),
                                onClick = { onNavigate(TensaiDestination.Chats.route) },
                                modifier = itemPadding,
                            )
                        }
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

internal fun recentKey(conversationId: String): String = "recent:$conversationId"

/**
 * Which drawer row is active (TEN-116): "chats", "models", "server", "settings", [recentKey] for a saved
 * conversation open on `chat/{id}`, or null (a new-chat draft, anything else). [route] is the
 * nav graph's route pattern, [conversationId] the chat argument, [createdConversationId] the real id
 * of a draft whose route still says "new" after its first send.
 */
internal fun drawerSelectedKey(
    route: String?,
    conversationId: String?,
    createdConversationId: String? = null,
): String? = when {
    route == TensaiDestination.Chats.route -> "chats"
    route == TensaiDestination.Models.route -> "models"
    route == TensaiDestination.Server.route -> "server"
    route == TensaiDestination.Settings.route -> "settings"
    route?.startsWith("chat/") == true ->
        (conversationId?.takeUnless { it == TensaiDestination.Chat.NEW_CONVERSATION_ID } ?: createdConversationId)
            ?.let(::recentKey)
    else -> null
}

/**
 * Fill for every drawer row: [NavigationDrawerItemDefaults.colors] defaults to a secondary
 * container for the selected state, the design's `ListItem` uses `primary-16` (see TEN-116).
 */
@Composable
private fun drawerItemColors() = NavigationDrawerItemDefaults.colors(
    selectedContainerColor = MaterialTheme.tensaiTints.primary16,
    selectedTextColor = MaterialTheme.colorScheme.primary,
    selectedIconColor = MaterialTheme.colorScheme.primary,
)

/**
 * What a "Recents" row shows for a conversation [title] (TEN-105): the title itself, or
 * [untitledLabel] (the localized "New chat") while it is still blank, i.e. before the first message
 * names it. The row stores no display text for that state, so the label follows the app language.
 */
internal fun conversationLabel(title: String, untitledLabel: String): String = title.ifBlank { untitledLabel }

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
