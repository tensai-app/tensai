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
import io.github.hokanosekai.tensai.ui.screens.ModelDetailPlaceholder
import io.github.hokanosekai.tensai.ui.screens.ModelsScreenPlaceholder
import io.github.hokanosekai.tensai.ui.screens.OnboardingPlaceholder
import io.github.hokanosekai.tensai.ui.screens.ServerScreenPlaceholder
import io.github.hokanosekai.tensai.ui.screens.SettingsScreenPlaceholder

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
            ModelsScreenPlaceholder(
                onModelClick = { modelId -> navController.navigate(TensaiDestination.ModelDetail.route(modelId)) },
            )
        }

        composable(
            route = TensaiDestination.ModelDetail.route,
            arguments = listOf(navArgument(TensaiDestination.ModelDetail.ARG_MODEL_ID) { type = NavType.StringType }),
        ) { backStackEntry ->
            val modelId = backStackEntry.arguments
                ?.getString(TensaiDestination.ModelDetail.ARG_MODEL_ID)
                .orEmpty()
            ModelDetailPlaceholder(modelId = modelId)
        }

        composable(TensaiDestination.Loading.route) {
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
