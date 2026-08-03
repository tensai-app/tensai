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
import io.github.hokanosekai.tensai.ui.screens.LoadingScreenPlaceholder
import io.github.hokanosekai.tensai.ui.screens.OnboardingPlaceholder
import io.github.hokanosekai.tensai.ui.screens.ServerScreenPlaceholder
import io.github.hokanosekai.tensai.ui.screens.SettingsScreenPlaceholder
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
        ) {
            // TODO(TEN-30): drive the real InferenceQueue.loadModel(...) call for this model's
            // path here instead of just showing the fake looping glyph.
            LoadingScreenPlaceholder()
        }

        composable(TensaiDestination.Settings.route) {
            SettingsScreenPlaceholder()
        }

        composable(TensaiDestination.Server.route) {
            ServerScreenPlaceholder()
        }
    }
}
