package io.github.hokanosekai.tensai.ui.navigation

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class DrawerSelectionTest {
    @Test
    fun destinationsSelectTheirOwnRow() {
        assertEquals("models", drawerSelectedKey(TensaiDestination.Models.route, null))
        assertEquals("server", drawerSelectedKey(TensaiDestination.Server.route, null))
        assertEquals("settings", drawerSelectedKey(TensaiDestination.Settings.route, null))
    }

    @Test
    fun savedChatSelectsItsRecentRow() {
        assertEquals(recentKey("abc"), drawerSelectedKey(TensaiDestination.Chat.route, "abc"))
    }

    @Test
    fun draftThatWasSentSelectsItsRecentRow() {
        assertEquals(
            recentKey("abc"),
            drawerSelectedKey(TensaiDestination.Chat.route, TensaiDestination.Chat.NEW_CONVERSATION_ID, "abc"),
        )
    }

    @Test
    fun homeAndNewChatSelectNothing() {
        assertNull(drawerSelectedKey(TensaiDestination.Home.route, null))
        assertNull(drawerSelectedKey(TensaiDestination.Chat.route, TensaiDestination.Chat.NEW_CONVERSATION_ID))
        assertNull(drawerSelectedKey(null, null))
    }
}
