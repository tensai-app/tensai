package io.github.tensaiapp.engine

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

    private val config = ModelLoadConfig(path = "/m.gguf", modelId = "m", nCtx = 4096)

    /** The identity before TEN-188, which every file written until then is keyed by. */
    private fun oldIdentity(kv: KvCacheType, backend: String) =
        listOf(config.modelId, config.path, 10L, 20L, config.nCtx, kv, backend, config.chatTemplate).joinToString("|")

    @Test
    fun cpuIdentityNeverMatchesAnF16File() {
        val (kv, tuning) = VulkanTuning.kvForLoad(0, KvCacheType.F16, VulkanLoadTuning.NONE)
        val identity = PrefixCache.identity(config, 10L, 20L, "CPU: ARM", kv, tuning)
        assertNotEquals(oldIdentity(KvCacheType.F16, "CPU: ARM"), identity)
        assertNotEquals(PrefixCache.identity(config, 10L, 20L, "CPU: ARM", KvCacheType.F16, VulkanLoadTuning.NONE), identity)
    }

    @Test
    fun gpuIdentityIsUnchanged() {
        val (kv, tuning) = VulkanTuning.kvForLoad(99, KvCacheType.F16, VulkanTuning.CURRENT)
        assertEquals(oldIdentity(KvCacheType.F16, "Vulkan0"), PrefixCache.identity(config, 10L, 20L, "Vulkan0", kv, tuning))
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
