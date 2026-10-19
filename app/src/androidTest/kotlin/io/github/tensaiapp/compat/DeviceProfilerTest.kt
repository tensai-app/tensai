package io.github.tensaiapp.compat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented: [DeviceProfiler] touches real framework classes (`ActivityManager`, `Build`,
 * sysfs) that aren't worth faking — this just checks the values it returns are plausible on
 * whatever device/emulator runs the suite, not exact numbers.
 */
@RunWith(AndroidJUnit4::class)
class DeviceProfilerTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun profileReturnsPlausibleValues() {
        val profile = DeviceProfiler(context).profile()

        assertTrue(
            "expected totalRamBytes > 4GB, was ${profile.totalRamBytes}",
            profile.totalRamBytes > 4L * 1024 * 1024 * 1024,
        )
        assertTrue(
            "expected availableRamBytes > 0, was ${profile.availableRamBytes}",
            profile.availableRamBytes > 0,
        )
        assertTrue(
            "expected availableRamBytes <= totalRamBytes",
            profile.availableRamBytes <= profile.totalRamBytes,
        )
        assertTrue("expected bigCoreCount >= 1, was ${profile.bigCoreCount}", profile.bigCoreCount >= 1)
        assertTrue("expected a non-blank socModel", profile.socModel.isNotBlank())
    }

    @Test
    fun profileCachesStaticFactsAcrossCalls() {
        val profiler = DeviceProfiler(context)

        val first = profiler.profile()
        val second = profiler.profile()

        assertTrue("socModel should be stable across calls", first.socModel == second.socModel)
        assertTrue("bigCoreCount should be stable across calls", first.bigCoreCount == second.bigCoreCount)
        assertTrue("hasVulkan should be stable across calls", first.hasVulkan == second.hasVulkan)
    }
}
