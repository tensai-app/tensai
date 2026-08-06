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
import io.github.hokanosekai.tensai.ui.screens.OnboardingPlaceholder
import io.github.hokanosekai.tensai.ui.screens.ServerScreenPlaceholder
import io.github.hokanosekai.tensai.ui.screens.SettingsScreenPlaceholder
import io.github.hokanosekai.tensai.ui.screens.chat.ChatScreen
import io.github.hokanosekai.tensai.ui.screens.loading.LoadingScreen
import io.github.hokanosekai.tensai.ui.screens.models.ModelDetailScreen
import io.github.hokanosekai.tensai.ui.screens.models.ModelsScreen

/**
 * The app's destination graph. Chrome (drawer, top bar) is owned by [TensaiShell] — screens here
 * are body content only.
 */
@Composable
fun TensaiNavGraph(
    navController: NavHostController,
    startDestination: String,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
    ) {
        composable(TensaiDestination.Onboarding.route) {
            OnboardingPlaceholder(
                onContinue = {
                    navController.navigate(TensaiDestination.Home.route) {
                        popUpTo(TensaiDestination.Onboarding.route) { inclusive = true }
                    }
                },
            )
        }

        composable(TensaiDestination.Home.route) {
            HomeScreen()
        }

        composable(
            route = TensaiDestination.Chat.route,
            arguments = listOf(navArgument(TensaiDestination.Chat.ARG_CONVERSATION_ID) { type = NavType.StringType }),
        ) { backStackEntry ->
            val conversationId = backStackEntry.arguments
                ?.getString(TensaiDestination.Chat.ARG_CONVERSATION_ID)
                .orEmpty()

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
                )
            }
        }

        composable(TensaiDestination.Models.route) {
            ModelsScreen(
                onModelClick = { modelId -> navController.navigate(TensaiDestination.ModelDetail.route(modelId)) },
                onLoadModelClick = { modelId -> navController.navigate(TensaiDestination.Loading.route(modelId)) },
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
            SettingsScreenPlaceholder()
        }

        composable(TensaiDestination.Server.route) {
            ServerScreenPlaceholder()
        }
    }
}
