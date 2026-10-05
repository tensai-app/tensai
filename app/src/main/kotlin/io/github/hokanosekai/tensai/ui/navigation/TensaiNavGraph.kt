package io.github.hokanosekai.tensai.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.github.hokanosekai.tensai.runtime.RuntimeGraph
import io.github.hokanosekai.tensai.ui.screens.HomeScreen
import io.github.hokanosekai.tensai.ui.screens.ServerScreen
import io.github.hokanosekai.tensai.ui.screens.chat.ChatScreen
import io.github.hokanosekai.tensai.ui.screens.loading.LoadingScreen
import io.github.hokanosekai.tensai.ui.screens.models.ModelDetailScreen
import io.github.hokanosekai.tensai.ui.screens.models.ModelsScreen
import io.github.hokanosekai.tensai.ui.screens.onboarding.OnboardingScreen
import io.github.hokanosekai.tensai.ui.screens.onboarding.OnboardingStep
import io.github.hokanosekai.tensai.ui.screens.settings.SettingsScreen

/**
 * The app's destination graph. Chrome (drawer, top bar) is owned by [TensaiShell] — screens here
 * are body content only.
 *
 * @param onboardingStartStep step the onboarding opens at when [startDestination] is it — see
 *   [io.github.hokanosekai.tensai.ui.screens.onboarding.OnboardingGate].
 */
@Composable
fun TensaiNavGraph(
    navController: NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier,
    onboardingStartStep: OnboardingStep = OnboardingStep.Welcome,
) {
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
            )
        }

        composable(TensaiDestination.Home.route) {
            HomeScreen(
                // The conversation already exists by the time this runs (Home created it), so
                // unlike the load path below this navigates straight at a real id — no sentinel to
                // resolve. Home is left on the stack: back from that first chat returns to it.
                onNavigateToChat = { conversationId, prompt ->
                    navController.navigate(TensaiDestination.Chat.route(conversationId, prompt))
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

            if (conversationId == TensaiDestination.Chat.NEW_CONVERSATION_ID) {
                // Resolve the sentinel into a real, persisted conversation, then replace this
                // backstack entry with the resolved id — see TensaiDestination.Chat.NEW_CONVERSATION_ID's
                // KDoc. Nothing to render meanwhile; this is expected to resolve within one frame.
                LaunchedEffect(Unit) {
                    val id = RuntimeGraph.chatRepository.createConversation(modelId = null)
                    navController.navigate(TensaiDestination.Chat.route(id)) {
                        popUpTo(TensaiDestination.Chat.route) { inclusive = true }
                    }
                }
            } else {
                ChatScreen(
                    conversationId = conversationId,
                    onNavigateToModels = { navController.navigate(TensaiDestination.Models.route) },
                    initialPrompt = initialPrompt,
                )
            }
        }

        composable(TensaiDestination.Models.route) {
            ModelsScreen(
                onModelClick = { modelId -> navController.navigate(TensaiDestination.ModelDetail.route(modelId)) },
                onLoadModelClick = { modelId -> navController.navigate(TensaiDestination.Loading.route(modelId)) },
                // Pushed, not popped-to, mirroring how Settings pushes Models: back from Settings
                // returns to the list the user was looking at when the banner sent them there.
                onNavigateToStorage = { navController.navigate(TensaiDestination.Settings.route) },
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
            )
        }

        composable(TensaiDestination.Server.route) {
            ServerScreen()
        }
    }
}
