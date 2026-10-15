package io.github.hokanosekai.tensai.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for [drawerRecents], the drawer's recents cap (TEN-164). */
class DrawerRecentsTest {

    private fun ids(n: Int) = (1..n).toList()

    @Test
    fun limitIsTwenty() {
        assertEquals(20, DrawerRecentsLimit)
    }

    @Test
    fun fewChatsAreAllShownWithoutAllChatsRow() {
        val recents = drawerRecents(ids(3))
        assertEquals(ids(3), recents.shown)
        assertFalse(recents.showAllChats)
    }

    @Test
    fun exactlyTwentyHidesAllChatsRow() {
        val recents = drawerRecents(ids(20))
        assertEquals(20, recents.shown.size)
        assertFalse(recents.showAllChats)
    }

    @Test
    fun twentyOneShowsTheFirstTwentyAndAllChatsRow() {
        val recents = drawerRecents(ids(21))
        assertEquals(ids(20), recents.shown)
        assertTrue(recents.showAllChats)
    }

    @Test
    fun noChatsShowsNothing() {
        val recents = drawerRecents(emptyList<Int>())
        assertTrue(recents.shown.isEmpty())
        assertFalse(recents.showAllChats)
    }
}
