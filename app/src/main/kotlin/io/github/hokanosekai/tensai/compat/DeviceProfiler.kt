package io.github.hokanosekai.tensai.compat

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.tensai.llamakt.BackendInfo
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
    /** ggml device name of the Vulkan backend ("Mali-G68 MC4"), `null` when absent or unnamed. */
    val vulkanDeviceName: String? = null,
    /** ggml device name of the OpenCL backend, `null` when absent or unnamed. */
    val openClDeviceName: String? = null,
    /** Logical cores the JVM sees, 0 when unknown. */
    val cpuCoreCount: Int = 0,
) {

    /**
     * The RAM a model load is budgeted against — [totalRamBytes] less [SYSTEM_RAM_MARGIN_BYTES],
     * never negative. The single memory basis for both [CompatibilityChecker.check] (does this
     * model fit at all?) and [io.github.hokanosekai.tensai.runtime.LoadPolicy] (how much context
     * can we afford?).
     *
     * **Not [availableRamBytes], deliberately, and this is the whole of TEN-77.** Three things
     * measured on the reference device (OPPO CPH2251, Gemma-4-E2B-It, 2.9 GB file, 2026-09-08)
     * rule `availMem` out as a sizing input:
     * - It is not reproducible. Two loads of the same model by the same code on the same phone
     *   planned `n_ctx` 256 and 4096 — a factor of 16, decided by nothing but what else the system
     *   happened to be holding at that second.
     * - It does not predict whether the model fits. The load succeeded with `availMem` far below
     *   the weights' own size, because llama.cpp mmaps them: they are file-backed and evictable,
     *   so they are not spent out of free RAM at all.
     * - Budgeting the KV cache against it answers a question nobody asked. At `n_ctx` 256 that
     *   model's caches measured 0.80 MiB + 1.59 MiB; extrapolated to 4096 the whole thing is
     *   ~17 MiB, against 2.9 GB of weights. Shrinking the context to protect free RAM gives back
     *   noise and costs the user the conversation.
     *
     * [totalRamBytes] is a constant of the device, so anything derived from this is reproducible
     * for a given (model, phone) pair — which is what makes a persisted session's `n_ctx` stable
     * enough to ever match again (see [io.github.hokanosekai.tensai.data.session.SessionModelKey]).
     *
     * [availableRamBytes] survives for the one question it does answer honestly: whether the
     * system is under pressure *right now*, which [CompatibilityChecker] uses to soften a verdict
     * to [Verdict.TightFit]. It sizes nothing.
     */
    val usableRamBytes: Long
        get() = (totalRamBytes - SYSTEM_RAM_MARGIN_BYTES).coerceAtLeast(0)

    companion object {
        /**
         * Reserved for the OS and the rest of the running system — not available to the model.
         * Lived in [CompatibilityChecker] until TEN-77; it is here because two layers now have to
         * agree on it, and a private copy each is exactly how a model came to pass the
         * compatibility check and then be planned at a context the check had ruled out.
         */
        const val SYSTEM_RAM_MARGIN_BYTES = 1_536L * 1024 * 1024
    }
}

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
            vulkanDeviceName = staticFacts.vulkanDeviceName,
            openClDeviceName = staticFacts.openClDeviceName,
            cpuCoreCount = Runtime.getRuntime().availableProcessors(),
        )
    }

    private data class StaticFacts(
        val socModel: String,
        val bigCoreCount: Int,
        val hasVulkan: Boolean,
        val hasOpenCl: Boolean,
        val vulkanDeviceName: String?,
        val openClDeviceName: String?,
    )

    /** The driver's hardware name, `null` when blank or just the ggml id ("Vulkan0"). */
    private fun BackendInfo.hardwareName(): String? =
        deviceDescription.trim().takeIf { it.isNotEmpty() && !it.equals(name, ignoreCase = true) }

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
        io.github.hokanosekai.tensai.engine.VulkanTuning.applyEnv()
        val engine = LlamaEngine()
        val backends = try {
            engine.availableBackends()
        } catch (_: Exception) {
            emptyList()
        } finally {
            runCatching { engine.free() }
        }
        val vulkan = backends.firstOrNull { it.description.equals("Vulkan", ignoreCase = true) }
        val openCl = backends.firstOrNull { it.description.equals("OpenCL", ignoreCase = true) }

        return StaticFacts(
            socModel, bigCoreCount, vulkan != null, openCl != null,
            vulkan?.hardwareName(), openCl?.hardwareName(),
        )
    }
}
