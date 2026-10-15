package io.github.hokanosekai.tensai.ui.screens.settings

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.core.content.pm.PackageInfoCompat
import com.tensai.llamakt.LlamaEngine
import io.github.hokanosekai.tensai.BuildConfig
import io.github.hokanosekai.tensai.compat.DeviceProfile
import io.github.hokanosekai.tensai.data.BackendPreference
import io.github.hokanosekai.tensai.runtime.RuntimeGraph
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * State holder for Settings > This device (TEN-158): gathers [DeviceInfo] once, off the main
 * thread, and exposes it. `null` until the first read lands. Read-only, no permission, nothing
 * about other apps: every source is the platform's own description of this phone and this process.
 *
 * @param read the actual gathering, injectable so the holder is testable without a device.
 */
class DeviceInfoViewModel(
    private val scope: CoroutineScope = RuntimeGraph.applicationScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val read: suspend () -> DeviceInfo,
) {
    private val _info = MutableStateFlow<DeviceInfo?>(null)
    val info: StateFlow<DeviceInfo?> = _info.asStateFlow()

    init {
        scope.launch(dispatcher) { _info.value = runCatching { read() }.getOrElse { DeviceInfo() } }
    }
}

/**
 * Where each fact comes from:
 * - SoC: `Build.SOC_MANUFACTURER` / `SOC_MODEL` (API 31+), `Build.HARDWARE` otherwise (ro.board.platform on most devices).
 * - Cores: `DeviceProfile.cpuCoreCount` / `bigCoreCount` (llama.kt's sysfs heuristic); per-core max
 *   frequency from `/sys/devices/system/cpu/cpuN/cpufreq/cpuinfo_max_freq`, readable by the app.
 * - CPU features: llama.cpp's `llama_print_system_info()` via [LlamaEngine.systemInfo], i.e. what ggml-cpu itself uses.
 * - RAM: `DeviceProfile` (ActivityManager); storage: `StatFs` on `filesDir`.
 * - GPU: ggml device description (Vulkan, else OpenCL) from the loaded backends; Vulkan API version
 *   from `PackageManager.FEATURE_VULKAN_HARDWARE_VERSION`. Driver version and vendor id would need a
 *   `vkGetPhysicalDeviceProperties` call that neither ggml-vulkan nor the platform exposes cheaply,
 *   so they are not shown rather than guessed.
 */
internal suspend fun readDeviceInfo(context: Context?): DeviceInfo {
    val profile: DeviceProfile? = runCatching { RuntimeGraph.deviceProfiler.profile() }.getOrNull()
    val backends = runCatching {
        val engine = LlamaEngine()
        try {
            engine.availableBackends().map { DeviceBackend(it.description, it.name, it.type, it.deviceDescription) }
        } finally {
            runCatching { engine.free() }
        }
    }.getOrDefault(emptyList())

    val freqs = runCatching {
        val cpus = File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu\\d+")) }.orEmpty()
        parseCpuFreqs(cpus.sortedBy { it.name.drop(3).toInt() }.mapNotNull {
            runCatching { File(it, "cpufreq/cpuinfo_max_freq").readText() }.getOrNull()
        })
    }.getOrDefault(emptyList())

    val vulkanVersion = runCatching {
        context?.packageManager?.systemAvailableFeatures
            ?.firstOrNull { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION }?.version
    }.getOrNull()

    val storageFree = runCatching {
        val dir = context?.filesDir ?: Environment.getDataDirectory()
        StatFs(dir.path).availableBytes
    }.getOrNull()

    val soc = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val packageInfo = runCatching { context?.packageManager?.getPackageInfo(context.packageName, 0) }.getOrNull()

    return DeviceInfo(
        socModel = if (soc) Build.SOC_MODEL else null,
        socManufacturer = if (soc) Build.SOC_MANUFACTURER else null,
        hardware = Build.HARDWARE,
        coreCount = profile?.cpuCoreCount ?: Runtime.getRuntime().availableProcessors(),
        bigCoreCount = profile?.bigCoreCount ?: 0,
        coreMaxFreqsKhz = freqs,
        llamaSystemInfo = runCatching { LlamaEngine.systemInfo() }.getOrDefault(""),
        totalRamBytes = profile?.totalRamBytes ?: 0,
        usableRamBytes = profile?.usableRamBytes ?: 0,
        availableRamBytes = profile?.availableRamBytes ?: 0,
        storageFreeBytes = storageFree,
        gpuName = profile?.vulkanDeviceName ?: profile?.openClDeviceName,
        vulkanVersion = vulkanVersion,
        hasVulkan = profile?.hasVulkan ?: false,
        hasOpenCl = profile?.hasOpenCl ?: false,
        backends = backends,
        backendPreference = runCatching { RuntimeGraph.backendRepository.preference.first() }.getOrDefault(BackendPreference.Auto),
        androidRelease = Build.VERSION.RELEASE,
        sdkInt = Build.VERSION.SDK_INT,
        abi = Build.SUPPORTED_ABIS.firstOrNull(),
        appVersionName = packageInfo?.versionName,
        appVersionCode = packageInfo?.let { PackageInfoCompat.getLongVersionCode(it) },
        llamaKtCommit = BuildConfig.LLAMA_KT_COMMIT.takeIf { it.isNotBlank() },
    )
}
