package io.github.hokanosekai.tensai.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.hokanosekai.tensai.R
import io.github.hokanosekai.tensai.download.LoadRequests
import io.github.hokanosekai.tensai.runtime.RuntimeGraph
import io.github.hokanosekai.tensai.ui.screens.chat.ContextRing
import io.github.hokanosekai.tensai.ui.screens.chat.ContextRingState
import io.github.hokanosekai.tensai.ui.screens.onboarding.OnboardingEntry
import io.github.hokanosekai.tensai.ui.screens.onboarding.OnboardingStep
import io.github.hokanosekai.tensai.ui.theme.tensaiTints
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Which chrome (top bar) a given route gets. */
private enum class TensaiChrome { Main, Detail, None }

private fun chromeForRoute(route: String?): TensaiChrome = when {
    route?.startsWith("chat/") == true -> TensaiChrome.Main
    route == TensaiDestination.Models.route -> TensaiChrome.Detail
    route?.startsWith("model/") == true -> TensaiChrome.Detail
    route?.startsWith(TensaiDestination.Repo.PREFIX) == true -> TensaiChrome.Detail
    route == TensaiDestination.Settings.route -> TensaiChrome.Detail
    route == TensaiDestination.Device.route -> TensaiChrome.Detail
    route == TensaiDestination.Memory.route -> TensaiChrome.Detail
    route == TensaiDestination.Licenses.route -> TensaiChrome.Detail
    route == TensaiDestination.Server.route -> TensaiChrome.Detail
    // Chats draws its own bar, which turns into the selection bar in place.
    else -> TensaiChrome.None // loading, onboarding, chats
}

@StringRes
private fun detailTitleForRoute(route: String?): Int? = when {
    route == TensaiDestination.Models.route -> R.string.nav_models
    route?.startsWith("model/") == true -> R.string.nav_model_details
    route?.startsWith(TensaiDestination.Repo.PREFIX) == true -> R.string.nav_hugging_face
    route == TensaiDestination.Settings.route -> R.string.nav_settings
    route == TensaiDestination.Device.route -> R.string.device_title
    route == TensaiDestination.Memory.route -> R.string.memory_title
    route == TensaiDestination.Licenses.route -> R.string.settings_licenses
    route == TensaiDestination.Server.route -> R.string.nav_server
    else -> null
}

/**
 * Navigation shell: drawer + top bar wrapping [TensaiNavGraph]. Owns the [androidx.navigation.NavHostController]
 * and the drawer's [DrawerState][androidx.compose.material3.DrawerState].
 *
 * Note: Material3's [ModalNavigationDrawer] only exposes [androidx.compose.material3.DrawerDefaults.scrimColor] —
 * there's no public hook to swap its slide animation for [io.github.hokanosekai.tensai.ui.theme.TensaiMotion].
 *
 * @param onboardingEntry whether this launch starts in the first-run flow, and at which step —
 * picks a new draft chat vs [TensaiDestination.Onboarding] as the start destination. See
 * [io.github.hokanosekai.tensai.ui.screens.onboarding.OnboardingGate] for the rule, and
 * `MainActivity` for how it is resolved before first composition.
 */
@Composable
fun TensaiShell(onboardingEntry: OnboardingEntry) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // TEN-111: the IME sits above the drawer. Drop focus and hide it as soon as the drawer starts
    // opening (button or edge swipe: targetValue flips to Open on drag settle intent, not on click).
    // Only Open triggers it, so closing never steals or restores focus.
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(drawerState) {
        snapshotFlow { drawerState.targetValue == DrawerValue.Open }
            .distinctUntilChanged()
            .filter { it }
            .collect {
                focusManager.clearFocus()
                keyboardController?.hide()
            }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val chrome = chromeForRoute(currentRoute)

    // The top bar's title (TEN-129): the open chat's conversation title, read off the same Room flow
    // as the drawer's recents so it follows the first message's auto-title. Null until it emits.
    val conversations by RuntimeGraph.chatRepository.observeConversations().collectAsState(initial = null)

    // The top bar's "+" on Models (TEN-108) and the picker it opens live on opposite sides of the
    // NavHost: the launcher and the import it starts belong to ModelsScreen's scope. The bar only
    // signals; the screen collects and launches. No replay, so a tap never fires twice.
    val modelsImportRequests = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }

    // Same shape for the chat's params button (TEN-115): the button is top-bar chrome, the sheet it
    // opens belongs to ChatScreen's scope.
    val chatSettingsRequests = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
    val chatContextRequests = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
    // What the open chat reports for the ring (TEN-160); the screen on top is the one that writes it.
    var chatContextRing by remember { mutableStateOf(ContextRingState()) }
    val deviceCopyRequests = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }

    // Draft chats that got their row, by back stack entry id: their route still says "new".
    // Not pruned when an entry leaves the back stack: the only way to list live entries is
    // NavController.currentBackStack, which is restricted to androidx.navigation (lint
    // RestrictedApi). A stale entry is two short strings that no drawer row matches.
    val createdChatIds = remember { mutableStateMapOf<String, String>() }

    // Whether a chat has no message yet, by back stack entry id, as its screen reports it: the top bar
    // shows the brand and no ring then. Until it reports, a draft counts as empty and a saved
    // conversation does not, so the bar does not flip as the screen appears. Same pruning as above.
    val emptyChats = remember { mutableStateMapOf<String, Boolean>() }

    val untitledLabel = stringResource(R.string.drawer_new_chat)
    // A draft's route still says "new" once it has its row, so createdChatIds comes first: the same
    // resolution as the drawer's active row. No row yet (or still blank) reads as the untitled label.
    val chatTitle = if (currentRoute?.startsWith("chat/") == true) {
        val argId = backStackEntry?.arguments?.getString(TensaiDestination.Chat.ARG_CONVERSATION_ID)
        val chatId = backStackEntry?.id?.let(createdChatIds::get)
            ?: argId?.takeUnless { it == TensaiDestination.Chat.NEW_CONVERSATION_ID }
        val empty = backStackEntry?.id?.let(emptyChats::get) ?: (chatId == null)
        if (empty) null else conversationLabel(conversations?.firstOrNull { it.id == chatId }?.title.orEmpty(), untitledLabel)
    } else {
        null
    }
    // Empty chat: no title, no ring (the brand shows, see TensaiTopBar).
    val chatHasMessages = currentRoute?.startsWith("chat/") == true && chatTitle != null

    val startDestination = when (onboardingEntry) {
        OnboardingEntry.None -> TensaiDestination.Chat.route
        is OnboardingEntry.Show -> TensaiDestination.Onboarding.route
    }
    val onboardingStartStep = (onboardingEntry as? OnboardingEntry.Show)?.step ?: OnboardingStep.Welcome

    // TEN-141: the "Load" action of the download-ready notification (see LoadRequests). It goes to the
    // same load screen as every other Load button. Held while the first-run onboarding is up: that
    // flow has its own way to a loaded model.
    val pendingLoad by LoadRequests.pending.collectAsState()
    LaunchedEffect(pendingLoad, currentRoute) {
        val modelId = pendingLoad ?: return@LaunchedEffect
        // Wait for the first destination, and for the onboarding to be over (it is a route, so this
        // fires on its own once the user leaves it).
        if (currentRoute == null || currentRoute.startsWith(TensaiDestination.Onboarding.route)) return@LaunchedEffect
        LoadRequests.consume(modelId)
        // A model deleted since the notification was posted: nothing to load, nothing to say.
        val exists = RuntimeGraph.modelRepository.models.first().any { it.id == modelId }
        // Not returnToChat: on success the root becomes a new draft chat, see loadFinishedOp.
        if (exists) navController.navigate(TensaiDestination.Loading.route(modelId))
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = chrome == TensaiChrome.Main,
        scrimColor = MaterialTheme.tensaiTints.scrim,
        drawerContent = {
            TensaiDrawerContent(
                onOpenConversation = { conversationId ->
                    scope.launch { drawerState.close() }
                    // Replaces the root (TEN-168), see openConversationOp.
                    val openId = backStackEntry?.takeIf { currentRoute?.startsWith("chat/") == true }?.let {
                        createdChatIds[it.id]
                            ?: it.arguments?.getString(TensaiDestination.Chat.ARG_CONVERSATION_ID)
                    }
                    navController.perform(openConversationOp(openId, conversationId))
                },
                currentRoute = currentRoute,
                currentConversationId = backStackEntry?.arguments
                    ?.getString(TensaiDestination.Chat.ARG_CONVERSATION_ID),
                createdConversationId = backStackEntry?.id?.let(createdChatIds::get),
                onNavigate = { route ->
                    scope.launch { drawerState.close() }
                    navController.perform(openSecondaryOp(route))
                },
            )
        },
    ) {
        Scaffold(
            // Default is systemBars, which leaves the IME out: opening the keyboard then pans the
            // whole window up instead of resizing it, hiding half the chat input pill and pushing
            // the top of the conversation under the status bar. safeDrawing folds the IME in, so
            // every screen with a text field resizes above the keyboard. Pairs with
            // windowSoftInputMode=adjustResize in the manifest (TEN-56).
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                when (chrome) {
                    TensaiChrome.Main -> TensaiTopBar(
                        onMenuClick = { scope.launch { drawerState.open() } },
                        title = chatTitle,
                        actions = {
                            if (currentRoute?.startsWith("chat/") == true) {
                                if (chatHasMessages) ContextRing(
                                    state = chatContextRing.usage,
                                    busy = chatContextRing.busy,
                                    onClick = { chatContextRequests.tryEmit(Unit) },
                                )
                                IconButton(onClick = { chatSettingsRequests.tryEmit(Unit) }) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = stringResource(R.string.chat_settings_title),
                                    )
                                }
                            }
                        },
                    )

                    TensaiChrome.Detail -> TensaiDetailTopBar(
                        title = detailTitleForRoute(currentRoute)?.let { stringResource(it) }.orEmpty(),
                        onBackClick = { navController.popBackStack() },
                        actions = {
                            if (currentRoute == TensaiDestination.Device.route) {
                                IconButton(onClick = { deviceCopyRequests.tryEmit(Unit) }) {
                                    Icon(imageVector = Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.device_copy))
                                }
                            }
                            if (currentRoute == TensaiDestination.Models.route) {
                                IconButton(onClick = { modelsImportRequests.tryEmit(Unit) }) {
                                    Icon(imageVector = Icons.Default.Add, contentDescription = stringResource(R.string.models_add))
                                }
                            }
                        },
                    )

                    TensaiChrome.None -> Unit
                }
            },
        ) { innerPadding ->
            TensaiNavGraph(
                navController = navController,
                startDestination = startDestination,
                onboardingStartStep = onboardingStartStep,
                modelsImportRequests = modelsImportRequests,
                chatSettingsRequests = chatSettingsRequests,
                chatContextRequests = chatContextRequests,
                onChatContextRingState = { chatContextRing = it },
                deviceCopyRequests = deviceCopyRequests,
                onChatConversationCreated = { entryId, id -> createdChatIds[entryId] = id },
                createdConversationId = { createdChatIds[it] },
                onChatEmpty = { entryId, empty -> emptyChats[entryId] = empty },
                modifier = Modifier.padding(innerPadding),
            )

            // TEN-126: Back closes an open drawer first, as anywhere else on Android, instead of
            // popping the screen under it. Composed after the NavHost on purpose: the dispatcher
            // gives Back to the callback registered last, and the NavHost registers its own.
            BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
        }
    }
}
