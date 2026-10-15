package io.github.hokanosekai.tensai.ui.screens.settings

import androidx.annotation.StringRes
import io.github.hokanosekai.tensai.R
import io.github.hokanosekai.tensai.data.BackendPreference
import java.util.Locale

/** One ggml backend device, as `LlamaEngine.availableBackends()` reports it. */
data class DeviceBackend(
    /** Backend registry name: "CPU", "Vulkan", "OpenCL". */
    val backend: String,
    /** ggml device id ("Vulkan0"). */
    val deviceName: String,
    /** "cpu" | "gpu" | "igpu" | "accel". */
    val type: String,
    /** Driver-reported hardware name, blank when the backend gives none. */
    val deviceDescription: String,
)

/**
 * Raw facts for Settings > This device (TEN-158). Everything is nullable or empty when it could
 * not be read: [deviceSections] turns that into "Unknown" so the screen never invents a value.
 * Plain data, no Android types, so the formatting below runs on the JVM.
 */
data class DeviceInfo(
    val socModel: String? = null,
    val socManufacturer: String? = null,
    val hardware: String? = null,
    val coreCount: Int = 0,
    val bigCoreCount: Int = 0,
    /** Max frequency of each core in kHz (sysfs `cpuinfo_max_freq`), empty when unreadable. */
    val coreMaxFreqsKhz: List<Long> = emptyList(),
    /** `llama_print_system_info()`, empty when the native side said nothing. */
    val llamaSystemInfo: String = "",
    val totalRamBytes: Long = 0,
    val usableRamBytes: Long = 0,
    val availableRamBytes: Long = 0,
    /** Free space on the volume holding the app's files, `null` when unreadable. */
    val storageFreeBytes: Long? = null,
    val gpuName: String? = null,
    /** `PackageManager` FEATURE_VULKAN_HARDWARE_VERSION, VK_MAKE_VERSION-encoded, `null` when absent. */
    val vulkanVersion: Int? = null,
    val hasVulkan: Boolean = false,
    val hasOpenCl: Boolean = false,
    val backends: List<DeviceBackend> = emptyList(),
    val backendPreference: BackendPreference = BackendPreference.Auto,
    val androidRelease: String? = null,
    val sdkInt: Int = 0,
    val abi: String? = null,
    val appVersionName: String? = null,
    val appVersionCode: Long? = null,
    /** Short commit of the llama.kt submodule this build compiled, `null` when not baked in. */
    val llamaKtCommit: String? = null,
)

/** One labelled value. [label] is a string resource so the copy text and the screen cannot drift. */
data class DeviceRow(@StringRes val label: Int, val value: String, val labelText: String? = null)

/** The row's title: [DeviceRow.labelText] when the title is data (a backend name), else the resource. */
fun DeviceRow.title(string: (Int) -> String): String = labelText ?: string(label)

data class DeviceSection(@StringRes val title: Int, val rows: List<DeviceRow>)

/** A CPU feature ggml-cpu reports, with the name the user knows it by. */
internal data class CpuFeatureSpec(val label: String, val ggmlNames: List<String>)

internal val CPU_FEATURES = listOf(
    CpuFeatureSpec("NEON", listOf("NEON")),
    CpuFeatureSpec("ARM_FMA", listOf("ARM_FMA")),
    CpuFeatureSpec("FP16 vector arith", listOf("FP16_VA")),
    CpuFeatureSpec("DOTPROD", listOf("DOTPROD")),
    CpuFeatureSpec("I8MM", listOf("MATMUL_INT8")),
    CpuFeatureSpec("SVE", listOf("SVE")),
    CpuFeatureSpec("SME", listOf("SME")),
)

/**
 * Parses `llama_print_system_info()` ("CPU : NEON = 1 | ARM_FMA = 1 | ... | Vulkan : ...") into
 * the flags of the CPU backend. Only the `CPU :` section counts: other backends reuse names.
 * Empty map when there is no CPU section.
 */
internal fun parseCpuFlags(systemInfo: String): Map<String, Boolean> {
    val start = Regex("""(^|\|\s*)CPU\s*:""").find(systemInfo) ?: return emptyMap()
    val rest = systemInfo.substring(start.range.last + 1)
    val flags = LinkedHashMap<String, Boolean>()
    for (part in rest.split('|')) {
        val kv = part.split('=')
        if (kv.size != 2) break // the next backend's "Name : first = 1" chunk, or the end
        val key = kv[0].trim()
        if (key.isEmpty() || key.contains(':')) break
        flags[key] = kv[1].trim() != "0"
    }
    return flags
}

/** The [CPU_FEATURES] the CPU has, by display label, in table order. `null` when nothing was parsed. */
internal fun enabledCpuFeatures(systemInfo: String): List<String>? {
    val flags = parseCpuFlags(systemInfo)
    if (flags.isEmpty()) return null
    return CPU_FEATURES.filter { spec -> spec.ggmlNames.any { flags[it] == true } }.map { it.label }
}

/** 2_400_000 kHz -> "2.40 GHz", 800_000 -> "800 MHz". */
internal fun formatFrequencyKhz(khz: Long): String =
    if (khz >= 1_000_000) String.format(Locale.US, "%.2f GHz", khz / 1_000_000.0)
    else String.format(Locale.US, "%d MHz", khz / 1_000)

/** GB with one decimal ("7.4 GB"), MB under 0.1 GB; [Locale.US], like the rest of the mono values. */
internal fun formatDeviceBytes(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    if (gb >= 0.1) return String.format(Locale.US, "%.1f GB", gb)
    return String.format(Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0))
}

/** The clusters of a sysfs frequency list: [2.40 GHz x2, 2.00 GHz x6] -> "2 x 2.40 GHz + 6 x 2.00 GHz", fastest first. */
internal fun formatClusters(freqsKhz: List<Long>): String? {
    if (freqsKhz.isEmpty()) return null
    return freqsKhz.groupingBy { it }.eachCount().entries
        .sortedByDescending { it.key }
        .joinToString(" + ") { "${it.value} x ${formatFrequencyKhz(it.key)}" }
}

/** VK_MAKE_VERSION unpacked: 4_202_496 -> "1.1.0". */
internal fun formatVulkanVersion(encoded: Int): String =
    "${encoded ushr 22}.${(encoded ushr 12) and 0x3ff}.${encoded and 0xfff}"

/** The numbers of a `cpuinfo_max_freq` read per core, ignoring what is not a number. */
internal fun parseCpuFreqs(texts: List<String>): List<Long> = texts.mapNotNull { it.trim().toLongOrNull() }

/**
 * Builds the screen's (and the clipboard's) content. Every value is a string; whatever [info]
 * does not know is [unknown], never an empty cell. [string] resolves a resource id, which keeps this
 * free of `Context`.
 */
fun deviceSections(info: DeviceInfo, string: (Int) -> String): List<DeviceSection> {
    val unknown = string(R.string.device_unknown)
    fun String?.orUnknown() = this?.takeIf { it.isNotBlank() } ?: unknown
    fun Long.sizeOrUnknown() = if (this > 0) formatDeviceBytes(this) else unknown
    fun Boolean.yesNo() = string(if (this) R.string.device_yes else R.string.device_no)

    val soc = listOfNotNull(info.socManufacturer?.takeIf { it.isNotBlank() && !it.equals("unknown", true) }, info.socModel?.takeIf { it.isNotBlank() && !it.equals("unknown", true) })
        .joinToString(" ")
        .ifBlank { info.hardware.orEmpty() }
    val features = enabledCpuFeatures(info.llamaSystemInfo)
    val cores = when {
        info.coreCount <= 0 -> unknown
        info.bigCoreCount in 1 until info.coreCount ->
            "${info.coreCount} (${info.bigCoreCount} big + ${info.coreCount - info.bigCoreCount} little)"
        else -> info.coreCount.toString()
    }

    val gpuTargets = info.backends.filter { it.type == "gpu" || it.type == "igpu" }
    val socName = soc.takeIf { it.isNotBlank() }
    val backendRows = if (info.backends.isEmpty()) {
        listOf(DeviceRow(R.string.device_backends_list, unknown))
    } else {
        info.backends.map { b -> DeviceRow(R.string.device_backend_entry, backendValue(b, socName, string), labelText = b.backend) }
    }

    return listOf(
        DeviceSection(
            R.string.device_group_cpu,
            listOf(
                DeviceRow(R.string.device_soc, soc.orUnknown()),
                DeviceRow(R.string.device_cpu_cores, cores),
                DeviceRow(R.string.device_cpu_clusters, formatClusters(info.coreMaxFreqsKhz).orUnknown()),
                DeviceRow(
                    R.string.device_cpu_features,
                    when {
                        features == null -> unknown
                        features.isEmpty() -> string(R.string.device_none)
                        else -> features.joinToString(", ")
                    },
                ),
            ),
        ),
        DeviceSection(
            R.string.device_group_memory,
            listOf(
                DeviceRow(R.string.device_ram_total, info.totalRamBytes.sizeOrUnknown()),
                DeviceRow(R.string.device_ram_usable, info.usableRamBytes.sizeOrUnknown()),
                DeviceRow(R.string.device_ram_available, info.availableRamBytes.sizeOrUnknown()),
                DeviceRow(R.string.device_storage_free, info.storageFreeBytes?.sizeOrUnknown() ?: unknown),
            ),
        ),
        DeviceSection(
            R.string.device_group_gpu,
            listOf(
                DeviceRow(R.string.device_gpu_name, info.gpuName.orUnknown()),
                DeviceRow(R.string.device_vulkan_api, info.vulkanVersion?.takeIf { it > 0 }?.let(::formatVulkanVersion).orUnknown()),
                DeviceRow(R.string.device_vulkan_available, info.hasVulkan.yesNo()),
                DeviceRow(R.string.device_opencl, info.hasOpenCl.yesNo()),
            ),
        ),
        DeviceSection(
            R.string.device_group_backends,
            backendRows + listOf(
                DeviceRow(R.string.device_backend_preference, string(info.backendPreference.titleRes)),
                DeviceRow(
                    R.string.device_backend_gpu_target,
                    gpuTargets.firstOrNull()?.let { listOfNotNull(it.backend, it.hardwareName()).joinToString(" \u00b7 ") } ?: string(R.string.device_backend_cpu_only),
                ),
            ),
        ),
        DeviceSection(
            R.string.device_group_software,
            listOf(
                DeviceRow(
                    R.string.device_android,
                    if (info.sdkInt > 0) "${info.androidRelease.orUnknown()} (API ${info.sdkInt})" else unknown,
                ),
                DeviceRow(R.string.device_abi, info.abi.orUnknown()),
                DeviceRow(
                    R.string.device_app_version,
                    info.appVersionName?.takeIf { it.isNotBlank() }
                        ?.let { name -> info.appVersionCode?.let { "$name ($it)" } ?: name }
                        .orUnknown(),
                ),
                DeviceRow(R.string.device_llama_kt, info.llamaKtCommit.orUnknown()),
            ),
        ),
    )
}

/** Plain text for a bug report: a `## Section` header per group, `label: value` per row. */
fun deviceReportText(sections: List<DeviceSection>, string: (Int) -> String): String =
    sections.joinToString("\n\n") { section ->
        "## ${string(section.title)}\n" + section.rows.joinToString("\n") { "${it.title(string)}: ${it.value}" }
    }

/** The driver's hardware name, `null` when blank or just the ggml id ("Vulkan0") or backend name. */
private fun DeviceBackend.hardwareName(): String? =
    deviceDescription.trim().takeIf { it.isNotEmpty() && !it.equals(deviceName, true) && !it.equals(backend, true) }

/** "Mali-G68 MC4 \u00b7 integrated GPU": the device and its kind, never the ggml id or the raw type. */
private fun backendValue(b: DeviceBackend, soc: String?, string: (Int) -> String): String {
    val kind = when (b.type) {
        "igpu" -> string(R.string.device_kind_igpu)
        "gpu" -> string(R.string.device_kind_gpu)
        "accel" -> string(R.string.device_kind_accel)
        else -> null
    }
    val hw = b.hardwareName()
    return when {
        kind == null -> hw ?: soc ?: string(R.string.device_kind_cpu)
        else -> listOfNotNull(hw, kind).joinToString(" \u00b7 ")
    }
}
