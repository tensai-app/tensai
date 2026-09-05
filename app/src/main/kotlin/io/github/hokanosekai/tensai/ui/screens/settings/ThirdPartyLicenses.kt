package io.github.hokanosekai.tensai.ui.screens.settings

/**
 * One row of Settings > About's open-source notice.
 *
 * @param name the dependency, as it calls itself.
 * @param license SPDX-ish short name, as written in the source table.
 * @param author the copyright holder the upstream table names.
 * @param url project homepage.
 */
data class ThirdPartyLicense(
    val name: String,
    val license: String,
    val author: String,
    val url: String,
)

/**
 * The native dependencies tensai ships, transcribed from `third_party/llama.kt/THIRD_PARTY_LICENSES.md`
 * — same three entries, same columns, same order.
 *
 * Transcribed rather than parsed: that file belongs to the llama.kt submodule, is not packaged
 * into the APK, and reading it at runtime is not an option. This list is therefore a copy that has
 * to be re-checked when the submodule bumps — which is why it keeps the upstream file's exact
 * shape, so a diff between the two is a diff a human can do in one look.
 *
 * These are the *native* dependencies, the ones whose code ends up in `libtensai_jni.so`. The
 * app's own Apache/MIT-licensed AndroidX and Kotlin dependencies are not here; a full dependency
 * report is a build-time artifact, not a hand-written list, and pretending otherwise would produce
 * a notice that silently goes stale.
 */
val ThirdPartyLicenses = listOf(
    ThirdPartyLicense(
        name = "llama.cpp",
        license = "MIT",
        author = "ggml-org",
        url = "github.com/ggml-org/llama.cpp",
    ),
    ThirdPartyLicense(
        name = "llama.rn",
        license = "MIT",
        author = "Jhen-Jie Hong",
        url = "github.com/mybigday/llama.rn",
    ),
    ThirdPartyLicense(
        name = "OpenCL-ICD-Loader",
        license = "Apache 2.0",
        author = "KhronosGroup",
        url = "github.com/KhronosGroup/OpenCL-ICD-Loader",
    ),
)
