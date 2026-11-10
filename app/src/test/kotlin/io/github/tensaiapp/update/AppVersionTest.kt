package io.github.tensaiapp.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {

    @Test
    fun `tags and versionNames parse, anything else does not`() {
        assertEquals(AppVersion(0, 3, 7), AppVersion.parse("v0.3.7"))
        assertEquals(AppVersion(0, 3, 7), AppVersion.parse("0.3.7"))
        assertEquals(AppVersion(0, 3, 7, rc = 1), AppVersion.parse("v0.3.7-rc1"))
        assertNull(AppVersion.parse("0.3"))
        assertNull(AppVersion.parse("v0.3.7-beta1"))
        assertNull(AppVersion.parse("0.3.7-debug"))
        assertNull(AppVersion.parse(""))
    }

    @Test
    fun `a candidate sorts before its stable, which sorts before the next patch`() {
        val ordered = listOf("v0.3.6", "v0.3.7-rc1", "v0.3.7-rc2", "v0.3.7-rc10", "v0.3.7", "v0.3.8", "v0.4.0", "v1.0.0")
            .map { AppVersion.parse(it)!! }
        assertEquals(ordered, ordered.shuffled(kotlin.random.Random(7)).sorted())
        assertTrue(AppVersion.parse("0.3.10")!! > AppVersion.parse("0.3.9")!!)
    }

    @Test
    fun `a stable build is offered a newer stable and never a candidate`() {
        assertTrue(AppVersion.isOffered("0.3.6", "v0.3.7"))
        assertFalse(AppVersion.isOffered("0.3.6", "v0.3.7-rc1"))
        assertFalse(AppVersion.isOffered("0.3.6", "v0.4.0-rc1"))
        assertFalse(AppVersion.isOffered("0.3.7", "v0.3.7"))
        assertFalse(AppVersion.isOffered("0.3.7", "v0.3.6"))
    }

    @Test
    fun `a candidate build is offered its stable or a newer one`() {
        assertTrue(AppVersion.isOffered("0.3.7-rc1", "v0.3.7"))
        assertTrue(AppVersion.isOffered("0.3.7-rc1", "v0.3.8"))
        assertTrue(AppVersion.isOffered("0.3.7-rc1", "v0.3.7-rc2"))
        assertFalse(AppVersion.isOffered("0.3.7-rc2", "v0.3.7-rc1"))
        assertFalse(AppVersion.isOffered("0.3.7-rc1", "v0.3.6"))
    }

    @Test
    fun `an unreadable version on either side offers nothing`() {
        assertFalse(AppVersion.isOffered("", "v0.3.7"))
        assertFalse(AppVersion.isOffered("0.3.6", "nightly"))
    }
}
