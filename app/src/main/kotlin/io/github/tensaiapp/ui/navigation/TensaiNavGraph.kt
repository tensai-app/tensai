package io.github.tensaiapp.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import io.github.tensaiapp.ui.screens.ServerScreen
import io.github.tensaiapp.ui.screens.chat.ChatScreen
import io.github.tensaiapp.ui.screens.chats.ChatsScreen
import io.github.tensaiapp.ui.screens.chat.ContextRingState
import io.github.tensaiapp.ui.screens.loading.LoadingScreen
import io.github.tensaiapp.ui.screens.models.ModelDetailScreen
import io.github.tensaiapp.ui.screens.models.ModelsScreen
import io.github.tensaiapp.ui.screens.models.RepoScreen
import io.github.tensaiapp.ui.screens.onboarding.OnboardingScreen
import io.github.tensaiapp.ui.screens.onboarding.OnboardingStep
import io.github.tensaiapp.ui.screens.settings.DeviceScreen
import io.github.tensaiapp.ui.screens.settings.MemoryScreen
import io.github.tensaiapp.ui.screens.settings.ChangelogScreen
import io.github.tensaiapp.ui.screens.settings.LicensesScreen
import io.github.tensaiapp.ui.screens.settings.SettingsScreen
import io.github.tensaiapp.ui.screens.settings.ToolGroup
import io.github.tensaiapp.ui.screens.settings.ToolGroupScreen
import io.github.tensaiapp.ui.screens.settings.ToolsScreen

/**
 * The app's destination graph. Chrome (drawer, top bar) is owned by [TensaiShell] — screens here
 * are body content only.
 *
 * @param modelsImportRequests taps on the Models top bar's "+" (owned by [TensaiShell]), forwarded
 *   to [ModelsScreen], which owns the picker.
 * @param chatSettingsRequests taps on the chat top bar's params button (owned by [TensaiShell]),
 *   forwarded to [ChatScreen], which owns the sheet.
 * @param chatContextRequests taps on the chat top bar's context ring (owned by [TensaiShell]),
 *   forwarded to [ChatScreen].
 * @param onChatContextRingState what [ChatScreen] reports for [TensaiShell] to draw the ring with.
 * @param deviceCopyRequests taps on the "This device" top bar's copy icon (owned by [TensaiShell]),
 *   forwarded to [DeviceScreen], which owns the report.
 * @param onChatConversationCreated a chat opened as a draft got its row: back stack entry id and the
 *   new conversation id, for the drawer's selection.
 * @param onChatEmpty whether a chat has no message yet: back stack entry id and the flag, for the
 *   shell's top bar (brand and no ring while empty).
 * @param createdConversationId the row a draft entry got, by back stack entry id (see `onChatConversationCreated`).
 * @param onboardingStartStep step the onboarding opens at when [startDestination] is it — see
 *   [io.github.tensaiapp.ui.screens.onboarding.OnboardingGate].
 */
@Composable
fun TensaiNavGraph(
    navController: NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier,
    onboardingStartStep: OnboardingStep = OnboardingStep.Welcome,
    modelsImportRequests: Flow<Unit> = emptyFlow(),
    chatSettingsRequests: Flow<Unit> = emptyFlow(),
    chatContextRequests: Flow<Unit> = emptyFlow(),
    onChatContextRingState: (ContextRingState) -> Unit = {},
    deviceCopyRequests: Flow<Unit> = emptyFlow(),
    onChatConversationCreated: (entryId: String, conversationId: String) -> Unit = { _, _ -> },
    onChatEmpty: (entryId: String, empty: Boolean) -> Unit = { _, _ -> },
    createdConversationId: (entryId: String) -> String? = { null },
) {
    // Set by "Open Settings" on the repo screen: Settings opens with the token sheet up, once.
    var openTokenSheet by remember { mutableStateOf(false) }
    // Set by the onboarding's "Browse Hugging Face": the next Models entry opens on Discover, once.
    var modelsOnDiscover by remember { mutableStateOf(false) }
    // Set by the chat model picker's "Import a GGUF file": the next Models entry opens the file picker, once.
    var modelsOnImport by remember { mutableStateOf(false) }
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable(TensaiDestination.Onboarding.route) {
            OnboardingScreen(
                startStep = onboardingStartStep,
                // A model came out of the flow: straight into its load, which continues into a
                // new chat on success (see the Loading destination below): blank device to first chat
                // without leaving the flow. Skipping stops at the draft chat.
                // The draft is pushed in both cases rather than loading landing on an empty stack:
                // `LoadingScreen`'s own back/"see smaller models" actions pop, and popping the
                // only entry would drop the user out of the app. The onboarding itself is always
                // popped, it is re-entered by relaunching with no model, never by going back.
                onDone = { modelId ->
                    navController.navigate(TensaiDestination.Chat.route(TensaiDestination.Chat.NEW_CONVERSATION_ID)) {
                        popUpTo(TensaiDestination.Onboarding.route) { inclusive = true }
                    }
                    if (modelId != null) {
                        navController.navigate(TensaiDestination.Loading.route(modelId))
                    }
                },
                // Same exit as Skip (draft chat underneath, flag persisted by the screen), then Discover.
                onBrowseHuggingFace = {
                    modelsOnDiscover = true
                    navController.navigate(TensaiDestination.Chat.route(TensaiDestination.Chat.NEW_CONVERSATION_ID)) {
                        popUpTo(TensaiDestination.Onboarding.route) { inclusive = true }
                    }
                    navController.navigate(TensaiDestination.Models.route)
                },
            )
        }

        composable(
            route = TensaiDestination.Chat.route,
            arguments = listOf(
                // The default is what the NavHost's start destination (the route pattern) opens on: a draft.
                navArgument(TensaiDestination.Chat.ARG_CONVERSATION_ID) {
                    type = NavType.StringType
                    defaultValue = TensaiDestination.Chat.NEW_CONVERSATION_ID
                },
            ),
        ) { backStackEntry ->
            val conversationId = backStackEntry.arguments
                ?.getString(TensaiDestination.Chat.ARG_CONVERSATION_ID)
                .orEmpty()

            // The sentinel opens a draft, with no row until its first send (TEN-106): see
            // TensaiDestination.Chat.NEW_CONVERSATION_ID.
            ChatScreen(
                conversationId = conversationId.takeUnless { it == TensaiDestination.Chat.NEW_CONVERSATION_ID },
                onNavigateToModels = { navController.navigate(TensaiDestination.Models.route) },
                onNavigateToSettings = { navController.navigate(TensaiDestination.Settings.route) },
                // returnToChat: the load comes back to this conversation, not to a new chat.
                // singleTop: two quick picks must not stack two loading screens over the chat.
                onNavigateToLoad = { modelId ->
                    navController.navigate(TensaiDestination.Loading.route(modelId, returnToChat = true)) { launchSingleTop = true }
                },
                onNavigateToDiscover = {
                    modelsOnDiscover = true
                    navController.navigate(TensaiDestination.Models.route)
                },
                onNavigateToImport = {
                    modelsOnImport = true
                    navController.navigate(TensaiDestination.Models.route)
                },
                settingsRequests = chatSettingsRequests,
                contextRequests = chatContextRequests,
                onContextRingState = onChatContextRingState,
                onConversationCreated = { onChatConversationCreated(backStackEntry.id, it) },
                onEmptyChanged = { onChatEmpty(backStackEntry.id, it) },
            )
        }

        // Pushed like every other destination: a conversation opened from here lands on top of this
        // page, so back returns to the list (TEN-165). It draws its own top bar (selection mode).
        composable(TensaiDestination.Chats.route) {
            ChatsScreen(
                onBack = { navController.popBackStack() },
                // The root is the entry under this page. Opening it again pops back to it rather than stacking a copy.
                onOpenConversation = { id ->
                    val root = navController.previousBackStackEntry
                    val rootId = root?.let {
                        createdConversationId(it.id) ?: it.arguments?.getString(TensaiDestination.Chat.ARG_CONVERSATION_ID)
                    }
                    navController.perform(openFromChatsOp(rootId, id))
                },
                onNewChat = { navController.navigate(TensaiDestination.Chat.route(TensaiDestination.Chat.NEW_CONVERSATION_ID)) },
            )
        }

        composable(TensaiDestination.Models.route) {
            val startOnDiscover = remember { modelsOnDiscover }
            val startImport = remember { modelsOnImport }
            LaunchedEffect(Unit) {
                modelsOnDiscover = false
                modelsOnImport = false
            }
            ModelsScreen(
                startOnDiscover = startOnDiscover,
                startImport = startImport,
                onModelClick = { modelId -> navController.navigate(TensaiDestination.ModelDetail.route(modelId)) },
                onLoadModelClick = { modelId -> navController.navigate(TensaiDestination.Loading.route(modelId)) { launchSingleTop = true } },
                // singleTop: a double tap on a card must not stack two copies of the screen.
                onOpenRepo = { repoId, file ->
                    navController.navigate(TensaiDestination.Repo.route(repoId, file)) { launchSingleTop = true }
                },
                // Pushed, not popped-to, mirroring how Settings pushes Models: back from Settings
                // returns to the list the user was looking at when the banner sent them there.
                onNavigateToStorage = { navController.navigate(TensaiDestination.Settings.route) },
                importRequests = modelsImportRequests,
            )
        }

        composable(
            route = TensaiDestination.Repo.route,
            arguments = listOf(
                navArgument(TensaiDestination.Repo.ARG_ID) { type = NavType.StringType },
                navArgument(TensaiDestination.Repo.ARG_FILE) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val repoId = backStackEntry.arguments?.getString(TensaiDestination.Repo.ARG_ID).orEmpty()
            RepoScreen(
                repoId = repoId,
                preselectFile = backStackEntry.arguments?.getString(TensaiDestination.Repo.ARG_FILE),
                onLoadModel = { modelId -> navController.navigate(TensaiDestination.Loading.route(modelId)) { launchSingleTop = true } },
                // A fresh Models entry opens on "On device", where the downloads and the free space are.
                onManageModels = { navController.navigate(TensaiDestination.Models.route) },
                onOpenSettings = {
                    openTokenSheet = true
                    navController.navigate(TensaiDestination.Settings.route)
                },
            )
        }

        composable(
            route = TensaiDestination.ModelDetail.route,
            arguments = listOf(navArgument(TensaiDestination.ModelDetail.ARG_MODEL_ID) { type = NavType.StringType }),
        ) { backStackEntry ->
            val modelId = backStackEntry.arguments
                ?.getString(TensaiDestination.ModelDetail.ARG_MODEL_ID)
                .orEmpty()
            ModelDetailScreen(
                modelId = modelId,
                onLoadClick = { navController.navigate(TensaiDestination.Loading.route(it)) { launchSingleTop = true } },
                onDeleted = { navController.popBackStack() },
                // "Download again" refused for lack of space: the library is where models are removed.
                onManageModels = { navController.navigate(TensaiDestination.Models.route) },
                onOpenRepo = { repoId, file ->
                    navController.navigate(TensaiDestination.Repo.route(repoId, file)) { launchSingleTop = true }
                },
            )
        }

        composable(
            route = TensaiDestination.Loading.route,
            arguments = listOf(
                navArgument(TensaiDestination.Loading.ARG_MODEL_ID) { type = NavType.StringType },
                navArgument(TensaiDestination.Loading.ARG_RETURN_TO_CHAT) {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
        ) { backStackEntry ->
            val modelId = backStackEntry.arguments
                ?.getString(TensaiDestination.Loading.ARG_MODEL_ID)
                .orEmpty()
            val returnToChat = backStackEntry.arguments?.getBoolean(TensaiDestination.Loading.ARG_RETURN_TO_CHAT) == true
            LoadingScreen(
                modelId = modelId,
                onLoaded = { navController.perform(loadFinishedOp(returnToChat)) },
                onBack = { navController.popBackStack() },
                onSeeSmallerModels = {
                    // From a chat, the Models entry further down may sit under that conversation:
                    // popping to it would lose the chat, so the list opens on top of it instead.
                    if (returnToChat) {
                        navController.popBackStack()
                        navController.navigate(TensaiDestination.Models.route) { launchSingleTop = true }
                    } else {
                        // No Models underneath (notification, onboarding): the list opens in its place.
                        val poppedToModels = navController.popBackStack(TensaiDestination.Models.route, false)
                        if (!poppedToModels) {
                            navController.popBackStack()
                            navController.navigate(TensaiDestination.Models.route) { launchSingleTop = true }
                        }
                    }
                },
            )
        }

        composable(TensaiDestination.Settings.route) {
            SettingsScreen(
                // Pushed, not popped-to: Settings stays on the stack so back from the models list
                // returns to the group the user left, rather than wherever they entered Settings
                // from.
                onNavigateToModels = { navController.navigate(TensaiDestination.Models.route) },
                onNavigateToLicenses = { navController.navigate(TensaiDestination.Licenses.route) },
                onNavigateToChangelog = { navController.navigate(TensaiDestination.Changelog.route) },
                onNavigateToDevice = { navController.navigate(TensaiDestination.Device.route) },
                onNavigateToMemory = { navController.navigate(TensaiDestination.Memory.route) },
                onNavigateToTools = { navController.navigate(TensaiDestination.Tools.route) },
                openTokenSheet = openTokenSheet,
                onTokenSheetOpened = { openTokenSheet = false },
            )
        }

        composable(TensaiDestination.Device.route) {
            DeviceScreen(copyRequests = deviceCopyRequests)
        }

        composable(TensaiDestination.Memory.route) {
            MemoryScreen()
        }

        composable(TensaiDestination.Tools.route) {
            ToolsScreen(
                onOpenGroup = { group ->
                    navController.navigate(
                        when (group) {
                            ToolGroup.OnPhone -> TensaiDestination.ToolsOnPhone.route
                            ToolGroup.PhoneActions -> TensaiDestination.ToolsPhoneActions.route
                            ToolGroup.Internet -> TensaiDestination.ToolsInternet.route
                        },
                    )
                },
            )
        }

        composable(TensaiDestination.ToolsOnPhone.route) { ToolGroupScreen(ToolGroup.OnPhone) }
        composable(TensaiDestination.ToolsPhoneActions.route) { ToolGroupScreen(ToolGroup.PhoneActions) }
        composable(TensaiDestination.ToolsInternet.route) { ToolGroupScreen(ToolGroup.Internet) }

        composable(TensaiDestination.Changelog.route) {
            ChangelogScreen()
        }

        composable(TensaiDestination.Licenses.route) {
            LicensesScreen()
        }

        composable(TensaiDestination.Server.route) {
            ServerScreen()
        }
    }
}
