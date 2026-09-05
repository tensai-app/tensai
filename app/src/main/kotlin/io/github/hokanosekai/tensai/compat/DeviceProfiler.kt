package io.github.hokanosekai.tensai.compat

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.tensai.llamakt.LlamaEngine

/**
 * Facts about this device relevant to [CompatibilityChecker].
 *
 * @param totalRamBytes total physical RAM, as reported by [ActivityManager.MemoryInfo.totalMem].
 * @param availableRamBytes RAM currently free, as reported by [ActivityManager.MemoryInfo.availMem].
 *   Unlike the other fields, this legitimately changes between calls (other apps, GC, …) — see
 *   [DeviceProfiler.profile].
 * @param socModel best-effort SoC identifier ([Build.SOC_MODEL] on API 31+, [Build.HARDWARE]
 *   otherwise/on failure) — informational only, not used in any threshold today.
 * @param bigCoreCount number of "big" cores on a big.LITTLE SoC — see
 *   [LlamaEngine.detectBigCoreCount]'s KDoc for the detection heuristic.
 * @param hasVulkan whether a Vulkan backend device is available.
 * @param hasOpenCl whether an OpenCL backend device is available.
 */
data class DeviceProfile(
    val totalRamBytes: Long,
    val availableRamBytes: Long,
    val socModel: String,
    val bigCoreCount: Int,
    val hasVulkan: Boolean,
    val hasOpenCl: Boolean,
)

/**
 * Reads [DeviceProfile] facts for the current device.
 *
 * SoC identity, big-core count and available backends are hardware facts that don't change for
 * the process's lifetime, so they are computed once (lazily) and cached. RAM is re-read on every
 * [profile] call instead: [DeviceProfile.availableRamBytes] drifts with system memory pressure,
 * and [CompatibilityChecker] needs a current reading, not a stale one from first launch.
 */
class DeviceProfiler(context: Context) {

    private val activityManager = context.applicationContext
        .getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    private val staticFacts: StaticFacts by lazy { computeStaticFacts() }

    /** Current [DeviceProfile] — cheap to call repeatedly (see class KDoc). */
    fun profile(): DeviceProfile {
        val memInfo = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        return DeviceProfile(
            totalRamBytes = memInfo.totalMem,
            availableRamBytes = memInfo.availMem,
            socModel = staticFacts.socModel,
            bigCoreCount = staticFacts.bigCoreCount,
            hasVulkan = staticFacts.hasVulkan,
            hasOpenCl = staticFacts.hasOpenCl,
        )
    }

    private data class StaticFacts(
        val socModel: String,
        val bigCoreCount: Int,
        val hasVulkan: Boolean,
        val hasOpenCl: Boolean,
    )

    private fun computeStaticFacts(): StaticFacts {
        val socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Build.SOC_MODEL.takeIf { it.isNotBlank() && it != Build.UNKNOWN } ?: Build.HARDWARE
        } else {
            Build.HARDWARE
        }

        // detectBigCoreCount() returns 0 when /sys/devices/system/cpu is unreadable — the same
        // sysfs parse llama.kt's own LlamaEngine.load() uses to pin inference threads to big
        // cores (see its KDoc), reused here rather than duplicated. Falls back to the JVM's
        // reported core count so callers always see >= 1.
        val bigCoreCount = LlamaEngine.detectBigCoreCount()
            .takeIf { it > 0 }
            ?: Runtime.getRuntime().availableProcessors()

        // A fresh, unloaded LlamaEngine is enough: availableBackends() is documented as static
        // (doesn't require a model to be loaded), so this never touches the shared engine
        // instance RuntimeGraph.inferenceQueue runs on.
        //
        // free()'d in a finally even though an engine that was never load()ed holds no native
        // handle today (llama.kt only allocates one in load(), and nativeFree(0) returns
        // immediately): this instance is created here and belongs to nobody else, so it is
        // released here. That keeps the question of who owns the engine's lifetime (TEN-66's
        // question, and it is about the *shared* instance) out of this function entirely.
        val engine = LlamaEngine()
        val backends = try {
            engine.availableBackends()
        } catch (_: Exception) {
            emptyList()
        } finally {
            runCatching { engine.free() }
        }
        val hasVulkan = backends.any { it.description.equals("Vulkan", ignoreCase = true) }
        val hasOpenCl = backends.any { it.description.equals("OpenCL", ignoreCase = true) }

        return StaticFacts(socModel, bigCoreCount, hasVulkan, hasOpenCl)
    }
}
