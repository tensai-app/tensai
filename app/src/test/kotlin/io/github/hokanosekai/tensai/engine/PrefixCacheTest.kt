package io.github.hokanosekai.tensai.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PrefixCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun cache(keep: Int = 2) = PrefixCache(tmp.newFolder(), keep)

    private fun PrefixCache.write(key: String, ageMs: Long = 0): java.io.File {
        val file = fileFor(key)
        val temp = tempFor(file)
        temp.writeText("state")
        // A rename keeps the mtime, so the age is in place when commit evicts.
        temp.setLastModified(System.currentTimeMillis() - ageMs)
        assertTrue(commit(temp, file))
        return file
    }

    @Test
    fun keyIsStableAndFollowsBothInputs() {
        val c = cache()
        val k = c.key("model-a|4096", "system+tools")
        assertEquals(k, c.key("model-a|4096", "system+tools"))
        assertNotEquals(k, c.key("model-b|4096", "system+tools"))
        assertNotEquals(k, c.key("model-a|4096", "system+tools2"))
        assertEquals(32, k.length)
    }

    @Test
    fun theBoundaryBetweenIdentityAndPrefixCannotBeShifted() {
        val c = cache()
        assertNotEquals(c.key("ab", "c"), c.key("a", "bc"))
    }

    @Test
    fun commitKeepsOnlyTheNewestFiles() {
        val c = cache(keep = 2)
        val old = c.write("old", ageMs = 120_000)
        c.write("mid", ageMs = 60_000)
        val new = c.write("new")
        assertFalse(old.exists())
        assertTrue(c.fileFor("mid").exists())
        assertTrue(new.exists())
    }

    @Test
    fun touchSavesAnOldFileFromEviction() {
        val c = cache(keep = 1)
        val a = c.write("a", ageMs = 60_000)
        c.touch(a)
        c.write("b", ageMs = 30_000)
        assertTrue(a.exists())
        assertFalse(c.fileFor("b").exists())
    }

    @Test
    fun commitLeavesNoTempBehind() {
        val c = cache()
        val file = c.fileFor("k")
        val temp = c.tempFor(file)
        temp.writeText("state")
        assertTrue(c.commit(temp, file))
        assertFalse(temp.exists())
        assertEquals("state", file.readText())
    }

    @Test
    fun initSweepsTempFilesLeftByAKilledSave() {
        val dir = tmp.newFolder()
        val orphan = java.io.File(dir, "abc.kv.tmp").also { it.writeText("half") }
        val real = java.io.File(dir, "abc.kv").also { it.writeText("state") }
        PrefixCache(dir)
        assertFalse(orphan.exists())
        assertTrue(real.exists())
    }
}
