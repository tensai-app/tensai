package io.github.hokanosekai.tensai.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.github.hokanosekai.tensai.runtime.QueueState
import io.github.hokanosekai.tensai.runtime.RuntimeGraph
import io.github.hokanosekai.tensai.ui.theme.tensaiTints
import kotlinx.coroutines.launch

/** Which chrome (top bar) a given route gets. */
private enum class TensaiChrome { Main, Detail, None }

private fun chromeForRoute(route: String?): TensaiChrome = when {
    route == TensaiDestination.Home.route -> TensaiChrome.Main
    route?.startsWith("chat/") == true -> TensaiChrome.Main
    route == TensaiDestination.Models.route -> TensaiChrome.Detail
    route?.startsWith("model/") == true -> TensaiChrome.Detail
    route == TensaiDestination.Settings.route -> TensaiChrome.Detail
    route == TensaiDestination.Server.route -> TensaiChrome.Detail
    else -> TensaiChrome.None // loading, onboarding
}

private fun detailTitleForRoute(route: String?): String = when {
    route == TensaiDestination.Models.route || route?.startsWith("model/") == true -> "Models"
    route == TensaiDestination.Settings.route -> "Settings"
    route == TensaiDestination.Server.route -> "Server"
    else -> ""
}

/**
 * Navigation shell: drawer + top bar wrapping [TensaiNavGraph]. Owns the [androidx.navigation.NavHostController]
 * and the drawer's [DrawerState][androidx.compose.material3.DrawerState].
 *
 * Note: Material3's [ModalNavigationDrawer] only exposes [androidx.compose.material3.DrawerDefaults.scrimColor] —
 * there's no public hook to swap its slide animation for [io.github.hokanosekai.tensai.ui.theme.TensaiMotion].
 *
 * @param hasModels whether at least one model has been imported — picks [TensaiDestination.Home]
 * vs [TensaiDestination.Onboarding] as the start destination. See `MainActivity` for how this is
 * resolved from [io.github.hokanosekai.tensai.data.models.ModelRepository] before first composition.
 */
@Composable
fun TensaiShell(hasModels: Boolean) {
    val navController = rememberNavController()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val chrome = chromeForRoute(currentRoute)

    val queueState by RuntimeGraph.inferenceQueue.state.collectAsState()
    val modelPillLabel = (queueState as? QueueState.ModelReady)?.info
        ?.let { "${it.name} · ${it.nCtxEffective}" }
        ?: "no model"

    val startDestination = if (hasModels) TensaiDestination.Home.route else TensaiDestination.Onboarding.route

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = chrome == TensaiChrome.Main,
        scrimColor = MaterialTheme.tensaiTints.scrim,
        drawerContent = {
            TensaiDrawerContent(
                onOpenConversation = { conversationId ->
                    scope.launch { drawerState.close() }
                    navController.navigate(TensaiDestination.Chat.route(conversationId))
                },
                onNavigate = { route ->
                    scope.launch { drawerState.close() }
                    navController.navigate(route) { launchSingleTop = true }
                },
            )
        },
    ) {
        Scaffold(
            topBar = {
                when (chrome) {
                    TensaiChrome.Main -> TensaiTopBar(
                        onMenuClick = { scope.launch { drawerState.open() } },
                        onModelPillClick = { navController.navigate(TensaiDestination.Models.route) },
                        modelLabel = modelPillLabel,
                    )

                    TensaiChrome.Detail -> TensaiDetailTopBar(
                        title = detailTitleForRoute(currentRoute),
                        onBackClick = { navController.popBackStack() },
                    )

                    TensaiChrome.None -> Unit
                }
            },
        ) { innerPadding ->
            TensaiNavGraph(
                navController = navController,
                startDestination = startDestination,
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}
