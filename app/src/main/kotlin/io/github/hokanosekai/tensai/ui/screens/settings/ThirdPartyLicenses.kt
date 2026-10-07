package io.github.hokanosekai.tensai.ui.screens.settings

import androidx.annotation.RawRes
import io.github.hokanosekai.tensai.R

/**
 * One entry of Settings > About > Open-source licenses, for what the Maven report cannot see.
 *
 * @param name the component, as it calls itself.
 * @param license short name, as the upstream states it.
 * @param author the copyright holder the upstream names.
 * @param url project homepage.
 * @param textRes the full license text, a raw resource shown verbatim in the detail.
 * @param copyright shown as a "Copyright ..." line above the text, for the shared Apache text.
 * @param covers what in the repository this entry accounts for, checked by `ThirdPartyLicensesTest`
 *   so that adding a font or a submodule without an entry fails the build: `font:<resource name>`
 *   for a file in `res/font`, `<directory name>` for a submodule under
 *   `third_party/llama.kt/third_party`. `maven:<group>` removes that group from the generated
 *   list (see [parseGeneratedLicenses]) because this entry carries it instead.
 */
data class ThirdPartyLicense(
    val name: String,
    val license: String,
    val author: String,
    val url: String,
    @RawRes val textRes: Int,
    /** Copyright holder shown above the text when the text itself has no line to fill in (Apache). */
    val copyright: String = "",
    val covers: Set<String> = emptySet(),
)

/**
 * What tensai ships that is not a Maven dependency: llama.kt first, then the code compiled into
 * `libllamakt.so`, the native libraries packaged next to it, and the embedded fonts.
 *
 * Hand-kept, and re-checked on a llama.kt / llama.cpp bump. How each native entry got here, in
 * `llama-kt/src/main/cpp` (the tree `scripts/bootstrap.sh` vendors, which `CMakeLists.txt` builds):
 * - compiled in: `nlohmann/json.hpp` (included by `common/`), `stb_image.h` and `miniaudio.h`
 *   (both with their `*_IMPLEMENTATION` in `tools/mtmd/mtmd-helper.cpp`), `anyascii.c` (listed in
 *   the CMake sources), the `rn-*` glue and `jsi/ThreadPool.cpp` (llama.rn), the YaRN code in
 *   `ggml-cpu/ops.cpp`, the Vulkan and SPIR-V headers under `vulkan-hpp/` and `spirv-headers/`
 *   (included by `ggml-vulkan.cpp`), the OpenCL headers (include path in CMake).
 * - not compiled in, so not listed: cpp-httplib (no source includes it), sheredom/subprocess.h
 *   (behind `MTMD_VIDEO`, which CMake never defines), minja (llama.cpp's own `common/jinja/`
 *   replaced it, that is llama.cpp's MIT code).
 * - packaged in the APK, checked in the listing of `app-debug.apk`: `libOpenCL.so` (built from
 *   OpenCL-ICD-Loader) and `libc++_shared.so` (the NDK's LLVM libc++).
 */
val ThirdPartyLicenses = listOf(
    ThirdPartyLicense(
        name = "llama.kt",
        license = "Apache 2.0",
        author = "hokanosekai",
        url = "github.com/hokanosekai/llama.kt",
        textRes = R.raw.license_apache_2_0_llama_kt,
    ),
    ThirdPartyLicense(
        name = "llama.cpp",
        license = "MIT",
        author = "The ggml authors",
        url = "github.com/ggml-org/llama.cpp",
        textRes = R.raw.license_mit_llama_cpp,
        covers = setOf("llama.cpp"),
    ),
    ThirdPartyLicense(
        name = "llama.rn",
        license = "MIT",
        author = "Jhen-Jie Hong",
        url = "github.com/mybigday/llama.rn",
        textRes = R.raw.license_mit_llama_rn,
    ),
    ThirdPartyLicense(
        name = "JSON for Modern C++",
        license = "MIT",
        author = "Niels Lohmann",
        url = "github.com/nlohmann/json",
        textRes = R.raw.license_mit_nlohmann_json,
    ),
    ThirdPartyLicense(
        name = "stb_image",
        license = "MIT",
        author = "Sean Barrett",
        url = "github.com/nothings/stb",
        textRes = R.raw.license_mit_stb_image,
    ),
    ThirdPartyLicense(
        name = "miniaudio",
        license = "MIT-0",
        author = "David Reid",
        url = "github.com/mackron/miniaudio",
        textRes = R.raw.license_mit_0_miniaudio,
    ),
    ThirdPartyLicense(
        name = "anyascii",
        license = "ISC",
        author = "Hunter WB",
        url = "github.com/anyascii/anyascii",
        textRes = R.raw.license_isc_anyascii,
    ),
    ThirdPartyLicense(
        name = "YaRN",
        license = "MIT",
        author = "Jeffrey Quesnelle, Bowen Peng",
        url = "github.com/jquesnelle/yarn",
        textRes = R.raw.license_mit_yarn,
    ),
    ThirdPartyLicense(
        name = "Vulkan-Headers, Vulkan-Hpp",
        license = "Apache 2.0",
        author = "The Khronos Group (Vulkan-Hpp: Apache 2.0 or MIT)",
        url = "github.com/KhronosGroup/Vulkan-Headers",
        textRes = R.raw.license_apache_2_0,
        copyright = "2015-2026 The Khronos Group Inc.",
    ),
    ThirdPartyLicense(
        name = "SPIRV-Headers",
        license = "MIT",
        author = "The Khronos Group",
        url = "github.com/KhronosGroup/SPIRV-Headers",
        textRes = R.raw.license_mit_spirv_headers,
    ),
    ThirdPartyLicense(
        name = "OpenCL-Headers",
        license = "Apache 2.0",
        author = "The Khronos Group",
        url = "github.com/KhronosGroup/OpenCL-Headers",
        textRes = R.raw.license_apache_2_0,
        copyright = "The Khronos Group Inc.",
        covers = setOf("OpenCL-Headers"),
    ),
    ThirdPartyLicense(
        name = "OpenCL-ICD-Loader",
        license = "Apache 2.0",
        author = "The Khronos Group",
        url = "github.com/KhronosGroup/OpenCL-ICD-Loader",
        textRes = R.raw.license_apache_2_0,
        copyright = "The Khronos Group Inc.",
        covers = setOf("OpenCL-ICD-Loader"),
    ),
    ThirdPartyLicense(
        name = "LLVM libc++",
        license = "Apache 2.0 with LLVM exception",
        author = "The LLVM Project",
        url = "github.com/llvm/llvm-project",
        textRes = R.raw.license_apache_2_0_llvm_exception,
    ),
    ThirdPartyLicense(
        name = "commonmark-java",
        license = "BSD 2-Clause",
        author = "Robin Stocker",
        url = "github.com/commonmark/commonmark-java",
        textRes = R.raw.license_bsd_2_clause_commonmark,
        covers = setOf("maven:org.commonmark"),
    ),
    ThirdPartyLicense(
        name = "Figtree",
        license = "SIL OFL 1.1",
        author = "The Figtree Project Authors",
        url = "github.com/erikdkennedy/figtree",
        textRes = R.raw.license_ofl_figtree,
        covers = setOf("font:figtree"),
    ),
    ThirdPartyLicense(
        name = "JetBrains Mono",
        license = "SIL OFL 1.1",
        author = "The JetBrains Mono Project Authors",
        url = "github.com/JetBrains/JetBrainsMono",
        textRes = R.raw.license_ofl_jetbrains_mono,
        covers = setOf("font:jetbrains_mono"),
    ),
)
