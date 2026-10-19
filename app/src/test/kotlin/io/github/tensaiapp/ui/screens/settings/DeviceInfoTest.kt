package io.github.tensaiapp.ui.screens.settings

import io.github.tensaiapp.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The formatting behind Settings > This device (TEN-158) and its clipboard text. */
class DeviceInfoTest {

    private val sysInfo = "CPU : NEON = 1 | ARM_FMA = 1 | FP16_VA = 1 | DOTPROD = 1 | SVE = 0 | " +
        "MATMUL_INT8 = 1 | LLAMAFILE = 1 | OPENMP = 1 | REPACK = 1 | " +
        "Vulkan : FA = 1 | "

    // Ids resolve to themselves: the tests are about structure, not translations.
    private val string: (Int) -> String = { id ->
        when (id) {
            R.string.device_unknown -> "Unknown"
            R.string.device_yes -> "Yes"
            R.string.device_no -> "No"
            R.string.device_none -> "None"
            R.string.device_backend_cpu_only -> "CPU only"
            R.string.settings_backend_auto -> "Auto"
            else -> "s$id"
        }
    }

    private fun rows(info: DeviceInfo, title: Int) =
        deviceSections(info, string).first { it.title == title }.rows.associate { it.label to it.value }

    @Test
    fun frequenciesUseGhzThenMhz() {
        assertEquals("2.40 GHz", formatFrequencyKhz(2_400_000))
        assertEquals("2.05 GHz", formatFrequencyKhz(2_050_000))
        assertEquals("800 MHz", formatFrequencyKhz(800_000))
    }

    @Test
    fun clustersGroupByFrequencyFastestFirst() {
        val freqs = listOf(2_000_000L, 2_000_000, 2_000_000, 2_000_000, 2_000_000, 2_000_000, 2_400_000, 2_400_000)
        assertEquals("2 x 2.40 GHz + 6 x 2.00 GHz", formatClusters(freqs))
        assertNull(formatClusters(emptyList()))
    }

    @Test
    fun sizesAreGbOrMbWithAPointNotAComma() {
        assertEquals("7.0 GB", formatDeviceBytes(7L * 1024 * 1024 * 1024))
        assertEquals("0.5 GB", formatDeviceBytes(512L * 1024 * 1024))
        assertEquals("50 MB", formatDeviceBytes(50L * 1024 * 1024))
    }

    @Test
    fun vulkanVersionUnpacksTheVkMakeVersionEncoding() {
        assertEquals("1.1.0", formatVulkanVersion((1 shl 22) or (1 shl 12)))
        assertEquals("1.3.128", formatVulkanVersion((1 shl 22) or (3 shl 12) or 128))
    }

    @Test
    fun frequencyFilesParseAndGarbageIsDropped() {
        assertEquals(listOf(2_400_000L, 2_000_000L), parseCpuFreqs(listOf("2400000\n", "oops", " 2000000 ", "")))
    }

    @Test
    fun cpuFlagsReadOnlyTheCpuSection() {
        val flags = parseCpuFlags(sysInfo)
        assertEquals(true, flags["NEON"])
        assertEquals(false, flags["SVE"])
        assertNull(flags["FA"]) // belongs to Vulkan
    }

    @Test
    fun featureListFollowsTheTableOrderAndLabels() {
        assertEquals(
            listOf("NEON", "ARM_FMA", "FP16 vector arith", "DOTPROD", "I8MM"),
            enabledCpuFeatures(sysInfo),
        )
        assertNull(enabledCpuFeatures(""))
        assertEquals(emptyList<String>(), enabledCpuFeatures("CPU : NEON = 0 | "))
    }

    @Test
    fun anEmptyInfoIsUnknownEverywhereItCannotBeRead() {
        val sections = deviceSections(DeviceInfo(), string)
        val values = sections.flatMap { it.rows }.associate { it.label to it.value }
        assertEquals("Unknown", values[R.string.device_soc])
        assertEquals("Unknown", values[R.string.device_cpu_cores])
        assertEquals("Unknown", values[R.string.device_cpu_features])
        assertEquals("Unknown", values[R.string.device_ram_total])
        assertEquals("Unknown", values[R.string.device_gpu_name])
        assertEquals("Unknown", values[R.string.device_vulkan_api])
        assertEquals("Unknown", values[R.string.device_llama_kt])
        assertEquals("Unknown", values[R.string.device_android])
    }

    @Test
    fun aRealDeviceFillsTheRows() {
        val info = DeviceInfo(
            socManufacturer = "Mediatek", socModel = "MT6877",
            coreCount = 8, bigCoreCount = 2,
            coreMaxFreqsKhz = listOf(2_000_000L, 2_400_000L),
            llamaSystemInfo = sysInfo,
            totalRamBytes = 7L * 1024 * 1024 * 1024,
            gpuName = "Mali-G68 MC4", vulkanVersion = (1 shl 22) or (1 shl 12), hasVulkan = true,
            backends = listOf(
                DeviceBackend("CPU", "CPU", "cpu", ""),
                DeviceBackend("Vulkan", "Vulkan0", "igpu", "Mali-G68 MC4"),
            ),
            sdkInt = 33, androidRelease = "13", abi = "arm64-v8a",
            appVersionName = "0.1.0", appVersionCode = 1, llamaKtCommit = "fe1b9e2",
        )
        assertEquals("Mediatek MT6877", rows(info, R.string.device_group_cpu)[R.string.device_soc])
        assertEquals("8 (2 big + 6 little)", rows(info, R.string.device_group_cpu)[R.string.device_cpu_cores])
        assertEquals("1.1.0", rows(info, R.string.device_group_gpu)[R.string.device_vulkan_api])
        assertEquals("Yes", rows(info, R.string.device_group_gpu)[R.string.device_vulkan_available])
        assertEquals("No", rows(info, R.string.device_group_gpu)[R.string.device_opencl])
        assertEquals("Vulkan \u00b7 Mali-G68 MC4", rows(info, R.string.device_group_backends)[R.string.device_backend_gpu_target])
        assertEquals("13 (API 33)", rows(info, R.string.device_group_software)[R.string.device_android])
        assertEquals("0.1.0 (1)", rows(info, R.string.device_group_software)[R.string.device_app_version])
    }

    @Test
    fun backendRowsAreTitledByBackendAndNeverShowIdsOrRawTypes() {
        val info = DeviceInfo(
            socManufacturer = "Mediatek", socModel = "MT6877",
            backends = listOf(
                DeviceBackend("CPU", "CPU", "cpu", "CPU"),
                DeviceBackend("Vulkan", "Vulkan0", "igpu", "Mali-G68 MC4"),
                DeviceBackend("OpenCL", "GPUOpenCL", "gpu", ""),
            ),
        )
        val rows = deviceSections(info, string).first { it.title == R.string.device_group_backends }.rows
        val shown = rows.take(3).map { it.title(string) to it.value }
        assertEquals(
            listOf(
                "CPU" to "Mediatek MT6877",
                "Vulkan" to "Mali-G68 MC4 \u00b7 s${R.string.device_kind_igpu}",
                "OpenCL" to "s${R.string.device_kind_gpu}",
            ),
            shown,
        )
        assertTrue(rows.none { "Vulkan0" in it.value || "igpu" in it.value })
    }

    @Test
    fun withoutAGpuBackendTheOffloadTargetSaysCpuOnly() {
        val info = DeviceInfo(backends = listOf(DeviceBackend("CPU", "CPU", "cpu", "")))
        assertEquals("CPU only", rows(info, R.string.device_group_backends)[R.string.device_backend_gpu_target])
    }

    @Test
    fun copyTextHasAHeaderPerSectionAndALinePerRow() {
        val sections = listOf(
            DeviceSection(R.string.device_group_memory, listOf(DeviceRow(R.string.device_ram_total, "7.0 GB"))),
            DeviceSection(R.string.device_group_gpu, listOf(DeviceRow(R.string.device_gpu_name, "Mali"))),
        )
        val text = deviceReportText(sections, string)
        assertEquals(
            "## s${R.string.device_group_memory}\ns${R.string.device_ram_total}: 7.0 GB\n\n" +
                "## s${R.string.device_group_gpu}\ns${R.string.device_gpu_name}: Mali",
            text,
        )
        assertTrue(!text.contains('\u2014'))
    }
}
