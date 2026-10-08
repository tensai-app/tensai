package io.github.hokanosekai.tensai.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.runtime.mutableStateMapOf
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
import io.github.hokanosekai.tensai.runtime.QueueState
import io.github.hokanosekai.tensai.runtime.residentModel
import io.github.hokanosekai.tensai.runtime.RuntimeGraph
import io.github.hokanosekai.tensai.ui.screens.onboarding.OnboardingEntry
import io.github.hokanosekai.tensai.ui.screens.onboarding.OnboardingStep
import io.github.hokanosekai.tensai.ui.theme.tensaiTints
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** Which chrome (top bar) a given route gets. */
private enum class TensaiChrome { Main, Detail, None }

private fun chromeForRoute(route: String?): TensaiChrome = when {
    route == TensaiDestination.Home.route -> TensaiChrome.Main
    route?.startsWith("chat/") == true -> TensaiChrome.Main
    route == TensaiDestination.Models.route -> TensaiChrome.Detail
    route?.startsWith("model/") == true -> TensaiChrome.Detail
    route == TensaiDestination.Settings.route -> TensaiChrome.Detail
    route == TensaiDestination.Licenses.route -> TensaiChrome.Detail
    route == TensaiDestination.Server.route -> TensaiChrome.Detail
    else -> TensaiChrome.None // loading, onboarding
}

@StringRes
private fun detailTitleForRoute(route: String?): Int? = when {
    route == TensaiDestination.Models.route -> R.string.nav_models
    route?.startsWith("model/") == true -> R.string.nav_model_details
    route == TensaiDestination.Settings.route -> R.string.nav_settings
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
 * picks [TensaiDestination.Home] vs [TensaiDestination.Onboarding] as the start destination. See
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

    val queueState by RuntimeGraph.inferenceQueue.state.collectAsState()
    val modelPillLabel = queueState.residentModel
        ?.let { "${it.name} · ${it.nCtxEffective}" }
        ?: stringResource(R.string.top_bar_no_model)

    // The top bar's "+" on Models (TEN-108) and the picker it opens live on opposite sides of the
    // NavHost: the launcher and the import it starts belong to ModelsScreen's scope. The bar only
    // signals; the screen collects and launches. No replay, so a tap never fires twice.
    val modelsImportRequests = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }

    // Same shape for the chat's params button (TEN-115): the button is top-bar chrome, the sheet it
    // opens belongs to ChatScreen's scope.
    val chatSettingsRequests = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }

    // Draft chats that got their row, by back stack entry id: their route still says "new".
    // Not pruned when an entry leaves the back stack: the only way to list live entries is
    // NavController.currentBackStack, which is restricted to androidx.navigation (lint
    // RestrictedApi). A stale entry is two short strings that no drawer row matches.
    val createdChatIds = remember { mutableStateMapOf<String, String>() }

    val startDestination = when (onboardingEntry) {
        OnboardingEntry.None -> TensaiDestination.Home.route
        is OnboardingEntry.Show -> TensaiDestination.Onboarding.route
    }
    val onboardingStartStep = (onboardingEntry as? OnboardingEntry.Show)?.step ?: OnboardingStep.Welcome

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = chrome == TensaiChrome.Main,
        scrimColor = MaterialTheme.tensaiTints.scrim,
        drawerContent = {
            TensaiDrawerContent(
                onOpenConversation = { conversationId ->
                    scope.launch { drawerState.close() }
                    // Already on it (or on an empty draft, for "New chat"): a second entry would be a
                    // duplicate screen with its own view model.
                    val openId = backStackEntry?.takeIf { currentRoute?.startsWith("chat/") == true }?.let {
                        createdChatIds[it.id]
                            ?: it.arguments?.getString(TensaiDestination.Chat.ARG_CONVERSATION_ID)
                    }
                    if (openId != conversationId) {
                        navController.navigate(TensaiDestination.Chat.route(conversationId))
                    }
                },
                currentRoute = currentRoute,
                currentConversationId = backStackEntry?.arguments
                    ?.getString(TensaiDestination.Chat.ARG_CONVERSATION_ID),
                createdConversationId = backStackEntry?.id?.let(createdChatIds::get),
                onNavigate = { route ->
                    scope.launch { drawerState.close() }
                    navController.navigate(route) { launchSingleTop = true }
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
                        onModelPillClick = { navController.navigate(TensaiDestination.Models.route) },
                        modelLabel = modelPillLabel,
                        actions = {
                            if (currentRoute?.startsWith("chat/") == true) {
                                IconButton(onClick = { chatSettingsRequests.tryEmit(Unit) }) {
                                    Icon(
                                        imageVector = Icons.Default.Tune,
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
                onChatConversationCreated = { entryId, id -> createdChatIds[entryId] = id },
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}
