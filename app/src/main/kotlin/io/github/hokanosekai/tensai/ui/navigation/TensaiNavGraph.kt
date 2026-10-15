package io.github.hokanosekai.tensai.ui.navigation

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
import io.github.hokanosekai.tensai.ui.screens.HomeScreen
import io.github.hokanosekai.tensai.ui.screens.ServerScreen
import io.github.hokanosekai.tensai.ui.screens.chat.ChatScreen
import io.github.hokanosekai.tensai.ui.screens.loading.LoadingScreen
import io.github.hokanosekai.tensai.ui.screens.models.ModelDetailScreen
import io.github.hokanosekai.tensai.ui.screens.models.ModelsScreen
import io.github.hokanosekai.tensai.ui.screens.models.RepoScreen
import io.github.hokanosekai.tensai.ui.screens.onboarding.OnboardingScreen
import io.github.hokanosekai.tensai.ui.screens.onboarding.OnboardingStep
import io.github.hokanosekai.tensai.ui.screens.settings.DeviceScreen
import io.github.hokanosekai.tensai.ui.screens.settings.LicensesScreen
import io.github.hokanosekai.tensai.ui.screens.settings.SettingsScreen

/**
 * The app's destination graph. Chrome (drawer, top bar) is owned by [TensaiShell] — screens here
 * are body content only.
 *
 * @param modelsImportRequests taps on the Models top bar's "+" (owned by [TensaiShell]), forwarded
 *   to [ModelsScreen], which owns the picker.
 * @param chatSettingsRequests taps on the chat top bar's params button (owned by [TensaiShell]),
 *   forwarded to [ChatScreen], which owns the sheet.
 * @param deviceCopyRequests taps on the "This device" top bar's copy icon (owned by [TensaiShell]),
 *   forwarded to [DeviceScreen], which owns the report.
 * @param onChatConversationCreated a chat opened as a draft got its row: back stack entry id and the
 *   new conversation id, for the drawer's selection.
 * @param onboardingStartStep step the onboarding opens at when [startDestination] is it — see
 *   [io.github.hokanosekai.tensai.ui.screens.onboarding.OnboardingGate].
 */
@Composable
fun TensaiNavGraph(
    navController: NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier,
    onboardingStartStep: OnboardingStep = OnboardingStep.Welcome,
    modelsImportRequests: Flow<Unit> = emptyFlow(),
    chatSettingsRequests: Flow<Unit> = emptyFlow(),
    deviceCopyRequests: Flow<Unit> = emptyFlow(),
    onChatConversationCreated: (entryId: String, conversationId: String) -> Unit = { _, _ -> },
) {
    // Set by "Open Settings" on the repo screen: Settings opens with the token sheet up, once.
    var openTokenSheet by remember { mutableStateOf(false) }
    // Set by the onboarding's "Browse Hugging Face": the next Models entry opens on Discover, once.
    var modelsOnDiscover by remember { mutableStateOf(false) }
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable(TensaiDestination.Onboarding.route) {
            OnboardingScreen(
                startStep = onboardingStartStep,
                // A model came out of the flow: straight into its load, which continues into a
                // new chat on success (see the Loading destination below) — that's the ticket's
                // "blank device to first chat without leaving the flow". Skipping stops at Home.
                // Home is pushed in both cases rather than loading landing on an empty stack:
                // `LoadingScreen`'s own back/"see smaller models" actions pop, and popping the
                // only entry would drop the user out of the app. The onboarding itself is always
                // popped — it is re-entered by relaunching with no model, never by going back.
                onDone = { modelId ->
                    navController.navigate(TensaiDestination.Home.route) {
                        popUpTo(TensaiDestination.Onboarding.route) { inclusive = true }
                    }
                    if (modelId != null) {
                        navController.navigate(TensaiDestination.Loading.route(modelId))
                    }
                },
                // Same exit as Skip (Home underneath, flag persisted by the screen), then Discover.
                onBrowseHuggingFace = {
                    modelsOnDiscover = true
                    navController.navigate(TensaiDestination.Home.route) {
                        popUpTo(TensaiDestination.Onboarding.route) { inclusive = true }
                    }
                    navController.navigate(TensaiDestination.Models.route)
                },
            )
        }

        composable(TensaiDestination.Home.route) {
            HomeScreen(
                // A new chat carrying the prompt: ChatScreen sends it, and that first send is what
                // creates the conversation (TEN-106). Home is left on the stack: back from that
                // first chat returns to it.
                onNavigateToChat = { prompt ->
                    navController.navigate(TensaiDestination.Chat.route(TensaiDestination.Chat.NEW_CONVERSATION_ID, prompt))
                },
                onNavigateToModels = { navController.navigate(TensaiDestination.Models.route) },
                onNavigateToLoad = { modelId -> navController.navigate(TensaiDestination.Loading.route(modelId)) },
            )
        }

        composable(
            route = TensaiDestination.Chat.route,
            arguments = listOf(
                navArgument(TensaiDestination.Chat.ARG_CONVERSATION_ID) { type = NavType.StringType },
                navArgument(TensaiDestination.Chat.ARG_PROMPT) {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                },
            ),
        ) { backStackEntry ->
            val conversationId = backStackEntry.arguments
                ?.getString(TensaiDestination.Chat.ARG_CONVERSATION_ID)
                .orEmpty()
            val initialPrompt = backStackEntry.arguments?.getString(TensaiDestination.Chat.ARG_PROMPT)

            // The sentinel opens a draft, with no row until its first send (TEN-106): see
            // TensaiDestination.Chat.NEW_CONVERSATION_ID.
            ChatScreen(
                conversationId = conversationId.takeUnless { it == TensaiDestination.Chat.NEW_CONVERSATION_ID },
                onNavigateToModels = { navController.navigate(TensaiDestination.Models.route) },
                initialPrompt = initialPrompt,
                settingsRequests = chatSettingsRequests,
                onConversationCreated = { onChatConversationCreated(backStackEntry.id, it) },
            )
        }

        composable(TensaiDestination.Models.route) {
            val startOnDiscover = remember { modelsOnDiscover }
            LaunchedEffect(Unit) { modelsOnDiscover = false }
            ModelsScreen(
                startOnDiscover = startOnDiscover,
                onModelClick = { modelId -> navController.navigate(TensaiDestination.ModelDetail.route(modelId)) },
                onLoadModelClick = { modelId -> navController.navigate(TensaiDestination.Loading.route(modelId)) },
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
                onLoadModel = { modelId -> navController.navigate(TensaiDestination.Loading.route(modelId)) },
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
                onLoadClick = { navController.navigate(TensaiDestination.Loading.route(it)) },
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
            arguments = listOf(navArgument(TensaiDestination.Loading.ARG_MODEL_ID) { type = NavType.StringType }),
        ) { backStackEntry ->
            val modelId = backStackEntry.arguments
                ?.getString(TensaiDestination.Loading.ARG_MODEL_ID)
                .orEmpty()
            LoadingScreen(
                modelId = modelId,
                onLoaded = {
                    navController.navigate(TensaiDestination.Chat.route(TensaiDestination.Chat.NEW_CONVERSATION_ID)) {
                        popUpTo(TensaiDestination.Loading.route) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() },
                onSeeSmallerModels = {
                    val poppedToModels = navController.popBackStack(TensaiDestination.Models.route, false)
                    if (!poppedToModels) navController.popBackStack()
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
                onNavigateToDevice = { navController.navigate(TensaiDestination.Device.route) },
                openTokenSheet = openTokenSheet,
                onTokenSheetOpened = { openTokenSheet = false },
            )
        }

        composable(TensaiDestination.Device.route) {
            DeviceScreen(copyRequests = deviceCopyRequests)
        }

        composable(TensaiDestination.Licenses.route) {
            LicensesScreen()
        }

        composable(TensaiDestination.Server.route) {
            ServerScreen()
        }
    }
}
