package io.github.tensaiapp.ui.screens.chats

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import io.github.tensaiapp.R
import io.github.tensaiapp.data.chat.ChatRepository
import io.github.tensaiapp.data.chat.ConversationEntity
import io.github.tensaiapp.data.models.ModelRepository
import io.github.tensaiapp.data.session.SessionRepository
import io.github.tensaiapp.runtime.ActiveSessionTracker
import io.github.tensaiapp.runtime.ChatTurns
import io.github.tensaiapp.runtime.InferenceQueue
import io.github.tensaiapp.runtime.RuntimeGraph
import io.github.tensaiapp.ui.components.SwipeToDelete
import io.github.tensaiapp.ui.components.TensaiButton
import io.github.tensaiapp.ui.components.TensaiButtonVariant
import io.github.tensaiapp.ui.components.TensaiDialog
import io.github.tensaiapp.ui.components.TensaiDialogAction
import io.github.tensaiapp.ui.navigation.conversationLabel
import io.github.tensaiapp.ui.theme.TensaiMono
import io.github.tensaiapp.ui.theme.TensaiShapes
import io.github.tensaiapp.ui.theme.tensaiTints
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

private const val TAG = "tensai.chats"

/**
 * The Chats page (TEN-165): every conversation, grouped by date, searchable, with a multi-select
 * mode to delete several at once and a swipe to delete one, with undo (TEN-193). Reached from the
 * drawer's "Chats" and "All chats" rows.
 *
 * Draws its own top bar: the normal one (back, title, "New chat") turns into the selection one in
 * place, which the shell's static detail chrome cannot do. [TensaiShell] gives this route no chrome.
 *
 * @param onOpenConversation pushes the conversation on top of this page, so back returns here.
 * @param onNewChat opens a new draft chat, same as the drawer's "New chat".
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatsScreen(
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onNewChat: () -> Unit,
    modifier: Modifier = Modifier,
    chatRepository: ChatRepository = RuntimeGraph.chatRepository,
    modelRepository: ModelRepository = RuntimeGraph.modelRepository,
    sessionRepository: SessionRepository = RuntimeGraph.sessionRepository,
) {
    val conversations by chatRepository.observeConversations().collectAsState(initial = null)
    val models by modelRepository.models.collectAsState(initial = emptyList())
    val modelNames = remember(models) { models.associate { it.id to it.shownName } }

    var query by rememberSaveable { mutableStateOf("") }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirmingDelete by remember { mutableStateOf(false) }

    val scope = RuntimeGraph.applicationScope
    val haptics = LocalHapticFeedback.current

    // Swipe delete (TEN-193): the row goes at once, the delete itself waits for the undo bar's
    // window. Application scope for the delete, as for the selection one.
    var snackbarHeight by remember { mutableIntStateOf(0) }
    val pageScope = rememberCoroutineScope()
    val deletes = remember {
        PendingChatDeletes(pageScope) { id ->
            Log.i(TAG, "swipe delete: commit")
            scope.launch { deleteChats(listOf(id), chatRepository, sessionRepository) }
        }
    }
    // Leaving the app does not wait for the timeout.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) deletes.flush() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val deletedMessage = stringResource(R.string.chats_deleted)
    val undoLabel = stringResource(R.string.chats_undo)
    // About 5 s, longer when the accessibility settings ask for it (what SnackbarHost does for its
    // own durations).
    val undoTimeout = LocalAccessibilityManager.current
        ?.calculateRecommendedTimeoutMillis(5_000L, containsIcons = false, containsText = true, containsControls = true)
        ?: 5_000L

    val untitledLabel = stringResource(R.string.drawer_new_chat)
    val hidden = deletes.hidden
    val all = remember(conversations, hidden) { conversations?.filterNot { it.id in hidden } }
    val visible = remember(all, query, untitledLabel) { all?.let { filterChats(it, query, untitledLabel) }.orEmpty() }
    val visibleIds = remember(visible) { visible.map { it.id } }

    // Only what is on screen counts: a chat hidden by the search (or deleted) is not selected as far
    // as the count, select all/none and delete are concerned.
    val liveSelected = remember(selected, visibleIds) {
        val ids = visibleIds.toHashSet()
        selected.filterTo(HashSet()) { it in ids }
    }

    fun exitSelection() {
        selecting = false
        selected = emptySet()
    }

    BackHandler(enabled = selecting) { exitSelection() }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = {
                    Text(
                        text = if (selecting) {
                            pluralStringResource(R.plurals.chats_selected_count, liveSelected.size, liveSelected.size)
                        } else {
                            stringResource(R.string.nav_chats)
                        },
                        style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    if (selecting) {
                        IconButton(onClick = ::exitSelection) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.chats_exit_selection))
                        }
                    } else {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    }
                },
                actions = {
                    if (selecting) {
                        val everySelected = allSelected(liveSelected, visibleIds)
                        TextButton(onClick = { selected = toggleSelectAll(liveSelected, visibleIds) }) {
                            Text(
                                text = stringResource(if (everySelected) R.string.chats_select_none else R.string.chats_select_all),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        IconButton(onClick = { if (liveSelected.isNotEmpty()) confirmingDelete = true }) {
                            Icon(
                                Icons.Rounded.Delete,
                                contentDescription = stringResource(R.string.chats_delete),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    } else {
                        IconButton(onClick = onNewChat) {
                            Icon(painterResource(R.drawable.ic_edit_square), contentDescription = stringResource(R.string.drawer_new_chat))
                        }
                    }
                },
                // The shell's Scaffold already pads for the status bar.
                windowInsets = WindowInsets(0),
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )

            when {
                all == null -> Unit // initial Room query hasn't emitted yet.
                all.isEmpty() -> EmptyChats(onNewChat = onNewChat)
                else -> {
                    SearchField(query = query, onQueryChange = { query = it })
                    if (visible.isEmpty()) {
                        Text(
                            text = stringResource(R.string.chats_no_match, query.trim()),
                            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, top = 64.dp, end = 16.dp),
                        )
                    } else {
                        val now = remember(visible) { Instant.now() }
                        val zone = remember { ZoneId.systemDefault() }
                        ChatList(
                            sections = remember(visible, now) { groupChats(visible, now, zone) },
                            now = now,
                            zone = zone,
                            modelNames = modelNames,
                            selecting = selecting,
                            selected = liveSelected,
                            untitledLabel = untitledLabel,
                            onClick = { id ->
                                if (selecting) selected = toggleSelected(liveSelected, id) else onOpenConversation(id)
                            },
                            onLongClick = { id ->
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                selecting = true
                                selected = liveSelected + id
                            },
                            onDelete = { id ->
                                Log.i(TAG, "swipe delete: undo window open, $undoTimeout ms")
                                deletes.delete(id, undoTimeout)
                            },
                            bottomPadding = if (deletes.undoable != null) with(LocalDensity.current) { snackbarHeight.toDp() } else 0.dp,
                        )
                    }
                }
            }
        }

        // Drawn straight from the pending state rather than through a SnackbarHost: what is shown and
        // what Undo acts on can then never disagree.
        if (deletes.undoable != null) {
            Snackbar(
                action = {
                    TextButton(
                        onClick = {
                            Log.i(TAG, "swipe delete: undo")
                            deletes.undo()
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = SnackbarDefaults.actionColor),
                    ) { Text(undoLabel) }
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
                    .onSizeChanged { snackbarHeight = it.height }
                    .semantics { liveRegion = LiveRegionMode.Polite },
            ) { Text(deletedMessage) }
        }
    }

    if (confirmingDelete) {
        val ids = liveSelected.toList()
        TensaiDialog(
            onDismissRequest = { confirmingDelete = false },
            icon = Icons.Rounded.Delete,
            iconTint = MaterialTheme.colorScheme.error,
            title = pluralStringResource(R.plurals.chats_delete_title, ids.size, ids.size),
            body = pluralStringResource(R.plurals.chats_delete_body, ids.size),
            actions = listOf(
                TensaiDialogAction(stringResource(R.string.action_cancel), onClick = { confirmingDelete = false }),
                TensaiDialogAction(
                    text = stringResource(R.string.chats_delete),
                    destructive = true,
                    onClick = {
                        confirmingDelete = false
                        exitSelection()
                        // Application scope: leaving the page mid-delete must not strand half of it.
                        scope.launch { deleteChats(ids, chatRepository, sessionRepository) }
                    },
                ),
            ),
        )
    }
}

/**
 * Deletes [ids] with their messages and saved sessions. A generation running for one of them is
 * cancelled first and the active-session pointer dropped, so a later save (backgrounding, model
 * swap) cannot write a session file for a conversation that no longer exists. Its turn is stopped
 * too, and waited for: a cut turn writes its last rows on the way out, and they must not land after
 * the delete.
 */
internal suspend fun deleteChats(
    ids: List<String>,
    chatRepository: ChatRepository,
    sessionRepository: SessionRepository,
    inferenceQueue: InferenceQueue = RuntimeGraph.inferenceQueue,
    chatTurns: ChatTurns = RuntimeGraph.chatTurns,
) {
    ids.forEach { id ->
        if (ActiveSessionTracker.current.value?.conversationId == id) {
            inferenceQueue.cancelActive()
            ActiveSessionTracker.clear()
        }
        chatTurns.stop(id)?.join()
        sessionRepository.delete(id)
        chatRepository.deleteConversation(id)
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 2.dp)
            .height(48.dp)
            .background(scheme.surfaceVariant, CircleShape)
            .padding(start = 16.dp, end = 8.dp),
    ) {
        Icon(Icons.Rounded.Search, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        Box(modifier = Modifier.weight(1f)) {
            if (query.isEmpty()) {
                Text(
                    text = stringResource(R.string.chats_search_placeholder),
                    fontSize = 15.sp,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(fontSize = 15.sp, color = scheme.onSurface),
                cursorBrush = SolidColor(scheme.primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQueryChange("") }) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.chats_search_clear), modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun EmptyChats(onNewChat: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = stringResource(R.string.drawer_recents_empty),
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
        )
        TensaiButton(
            text = stringResource(R.string.drawer_new_chat),
            onClick = onNewChat,
            variant = TensaiButtonVariant.Tonal,
            icon = ImageVector.vectorResource(R.drawable.ic_edit_square),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatList(
    sections: List<ChatSection>,
    now: Instant,
    zone: ZoneId,
    modelNames: Map<String, String>,
    selecting: Boolean,
    selected: Set<String>,
    untitledLabel: String,
    onClick: (String) -> Unit,
    onLongClick: (String) -> Unit,
    onDelete: (String) -> Unit,
    bottomPadding: Dp,
) {
    val tints = MaterialTheme.tensaiTints
    val deleteLabel = stringResource(R.string.chats_delete)
    val locale = LocalConfiguration.current.locales[0]
    val context = LocalContext.current
    val formats = remember(locale, context) { WhenFormats(context, locale) }

    // Headers and rows are separate keyed items (a section can be long). A row draws its own slice
    // of the card: the first and last round their outer corners, and each row after the first sits
    // 1dp up so its top border covers the one above, which is what makes the hairline between rows.
    // [bottomPadding] keeps the last rows clear of the undo snackbar while it shows.
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding)) {
        sections.forEach { section ->
            item(key = "group:${section.group}") {
                Text(
                    text = groupLabel(section.group, now, zone, locale).uppercase(locale),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.06.em,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 20.dp, top = 18.dp, end = 16.dp, bottom = 8.dp),
                )
            }
            itemsIndexed(section.chats, key = { _, chat -> "chat:${chat.id}" }) { index, chat ->
                val radius = 16.dp
                val shape = RoundedCornerShape(
                    topStart = if (index == 0) radius else 0.dp,
                    topEnd = if (index == 0) radius else 0.dp,
                    bottomStart = if (index == section.chats.lastIndex) radius else 0.dp,
                    bottomEnd = if (index == section.chats.lastIndex) radius else 0.dp,
                )
                val whenText = remember(chat.updatedAt, now, formats) { formats.format(chat.updatedAt, now, zone) }
                // The card's slice (clip, border) stays put; only the row slides, over the underlay.
                SwipeToDelete(
                    label = deleteLabel,
                    onDelete = { onDelete(chat.id) },
                    enabled = !selecting,
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .offset(y = if (index == 0) 0.dp else (-1).dp)
                        .clip(shape)
                        .border(1.dp, tints.outline, shape),
                ) {
                    ChatRow(
                        chat = chat,
                        whenText = whenText,
                        model = modelLabel(chat.modelId, modelNames),
                        selecting = selecting,
                        selected = chat.id in selected,
                        untitledLabel = untitledLabel,
                        deleteLabel = deleteLabel,
                        onClick = { onClick(chat.id) },
                        onLongClick = { onLongClick(chat.id) },
                        onDelete = { onDelete(chat.id) },
                        // Opaque, so the underlay only shows while swiping.
                        modifier = Modifier.background(MaterialTheme.colorScheme.surface),
                    )
                }
            }
        }
        item(key = "end") { Box(modifier = Modifier.height(24.dp)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatRow(
    chat: ConversationEntity,
    whenText: String,
    model: String?,
    selecting: Boolean,
    selected: Boolean,
    untitledLabel: String,
    deleteLabel: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val stateText = stringResource(if (selected) R.string.chats_state_selected else R.string.chats_state_not_selected)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .background(if (selected) scheme.primary.copy(alpha = 0.08f) else Color.Transparent)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = stringResource(R.string.chats_long_click_label),
                role = if (selecting) Role.Checkbox else null,
            )
            .semantics(mergeDescendants = true) {
                if (selecting) {
                    this.selected = selected
                    stateDescription = stateText
                } else {
                    // The swipe's equivalent, through the same undo window.
                    customActions = listOf(CustomAccessibilityAction(deleteLabel) { onDelete(); true })
                }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        if (selecting) {
            if (selected) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(22.dp))
            } else {
                Icon(Icons.Rounded.RadioButtonUnchecked, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = conversationLabel(chat.title, untitledLabel),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(text = whenText, fontSize = 12.5.sp, color = scheme.onSurfaceVariant, maxLines = 1)
            }
            if (model != null) {
                Text(
                    text = model,
                    fontFamily = TensaiMono,
                    fontSize = 12.sp,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** The three "when" formats of a row, built once per locale. The time follows the system 24h/12h setting. */
private class WhenFormats(context: android.content.Context, private val locale: Locale) {
    private val time = android.text.format.DateFormat.getTimeFormat(context)
    private val weekday = SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEE"), locale)
    private val dayMonth = SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, "d MMM"), locale)

    fun format(updatedAt: Long, now: Instant, zone: ZoneId): String {
        val date = Date(updatedAt)
        return when (whenStyle(updatedAt, now, zone)) {
            WhenStyle.Time -> time.format(date)
            WhenStyle.Weekday -> weekday.format(date)
            WhenStyle.DayMonth -> dayMonth.format(date)
        }
    }
}

@Composable
private fun groupLabel(group: ChatGroup, now: Instant, zone: ZoneId, locale: Locale): String = when (group) {
    ChatGroup.Today -> stringResource(R.string.chats_group_today)
    ChatGroup.Yesterday -> stringResource(R.string.chats_group_yesterday)
    ChatGroup.Previous7Days -> stringResource(R.string.chats_group_previous_7_days)
    ChatGroup.ThisMonth -> stringResource(R.string.chats_group_this_month)
    is ChatGroup.Month -> remember(group, locale) {
        // The year only when it is not the current one.
        val skeleton = if (group.month.year == now.atZone(zone).year) "LLLL" else "LLLL yyyy"
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
        val millis = group.month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        SimpleDateFormat(pattern, locale).format(Date(millis))
    }
}
