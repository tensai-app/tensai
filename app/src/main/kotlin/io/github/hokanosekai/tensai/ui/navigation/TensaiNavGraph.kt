package io.github.hokanosekai.tensai.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import io.github.hokanosekai.tensai.ui.screens.ChatScreenPlaceholder
import io.github.hokanosekai.tensai.ui.screens.HomeScreen
import io.github.hokanosekai.tensai.ui.screens.OnboardingPlaceholder
import io.github.hokanosekai.tensai.ui.screens.ServerScreenPlaceholder
import io.github.hokanosekai.tensai.ui.screens.SettingsScreenPlaceholder
import io.github.hokanosekai.tensai.ui.screens.loading.LoadingScreen
import io.github.hokanosekai.tensai.ui.screens.models.ModelDetailScreen
import io.github.hokanosekai.tensai.ui.screens.models.ModelsScreen

/**
 * Sentinel [TensaiDestination.Chat.ARG_CONVERSATION_ID] used when a load's success navigates to a
 * brand new conversation rather than resuming a saved one — the real chat screen (TEN-32) resolves
 * this into an actual conversation instead of just echoing it, as `ChatScreenPlaceholder` does today.
 */
private const val NEW_CONVERSATION_ID = "new"

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
            ChatScreenPlaceholder(conversationId = conversationId)
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
                    navController.navigate(TensaiDestination.Chat.route(NEW_CONVERSATION_ID)) {
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
