package io.github.tensaiapp.ui.navigation

/** How many conversations the drawer's "Recents" lists before pointing at the Chats page (TEN-164). */
internal const val DrawerRecentsLimit = 20

/**
 * What the drawer's "Recents" block shows: the first [limit] of [all] (already newest first), and
 * whether the "All chats" row follows, which is only when some conversations are left out.
 */
internal data class DrawerRecents<T>(val shown: List<T>, val showAllChats: Boolean)

internal fun <T> drawerRecents(all: List<T>, limit: Int = DrawerRecentsLimit): DrawerRecents<T> =
    DrawerRecents(shown = all.take(limit), showAllChats = all.size > limit)
