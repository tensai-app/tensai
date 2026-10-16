package io.github.hokanosekai.tensai.ui.navigation

import androidx.navigation.NavController

/**
 * What a navigation does to the back stack (TEN-168). The stack is always one conversation at the
 * root, the current one, with secondary screens (Models, Settings, Chats, Loading...) pushed on top.
 * The decisions live here as plain values so they can be proven without a [NavController]; the
 * call sites only [perform] them.
 */
internal sealed interface NavOp {

    /** Nothing to do. */
    data object None : NavOp

    /** A secondary screen on top of what is showing: back returns to it. */
    data class Push(val route: String) : NavOp

    /** One step back. */
    data object Pop : NavOp

    /** The whole stack goes, [route] becomes the only entry: the new root. */
    data class ReplaceRoot(val route: String) : NavOp
}

/**
 * Drawer "New chat" or a "Recents" row. Replaces the root; tapping what is already open does
 * nothing, a second entry would be a duplicate screen with its own view model.
 *
 * From a conversation opened on top of the Chats page, the replacement collapses the stack to the
 * new root alone: Chats is not kept underneath (back from the new conversation leaves the app, like
 * from any root). The page is a detour, and the drawer is the app's way to start over from a
 * conversation.
 *
 * @param openConversationId the conversation on screen: its id, [TensaiDestination.Chat.NEW_CONVERSATION_ID]
 *   for a draft with no row, or `null` when no chat is on top.
 */
internal fun openConversationOp(openConversationId: String?, targetId: String): NavOp =
    if (openConversationId == targetId) NavOp.None
    else NavOp.ReplaceRoot(TensaiDestination.Chat.route(targetId))

/** A drawer row to Models, Settings, Chats or Server: on top of the current conversation. */
internal fun openSecondaryOp(route: String): NavOp = NavOp.Push(route)

/**
 * The load screen is done. From a chat that is still on the stack ([returnToChat]: picker, context
 * sheet) it goes back to that conversation. From anywhere else (Models, a model's page, a repo, the
 * notification, the onboarding) the root becomes a new draft chat and nothing stays underneath.
 */
internal fun loadFinishedOp(returnToChat: Boolean): NavOp =
    if (returnToChat) NavOp.Pop
    else NavOp.ReplaceRoot(TensaiDestination.Chat.route(TensaiDestination.Chat.NEW_CONVERSATION_ID))

/**
 * A conversation tapped on the Chats page, which sits on top of the root. Pushed on top of the page so
 * back returns to the list (TEN-165), except the root itself: that pops back to it, no duplicate.
 *
 * @param rootConversationId the conversation under the page, as [openConversationOp] reads it.
 */
internal fun openFromChatsOp(rootConversationId: String?, targetId: String): NavOp =
    if (rootConversationId == targetId) NavOp.Pop else NavOp.Push(TensaiDestination.Chat.route(targetId))

internal fun NavController.perform(op: NavOp) {
    when (op) {
        NavOp.None -> Unit
        // singleTop: a double tap must not stack two copies of the screen.
        is NavOp.Push -> navigate(op.route) { launchSingleTop = true }
        NavOp.Pop -> popBackStack()
        // Every screen off, the graph entry kept: popping the graph too (inclusive) brings its start
        // destination back under the new root, and on a first launch that start is the onboarding
        // (back from the first chat then reopened "Welcome to tensai").
        is NavOp.ReplaceRoot -> navigate(op.route) { popUpTo(graph.id) }
    }
}
