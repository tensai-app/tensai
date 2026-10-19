package io.github.tensaiapp.ui.screens.settings

import androidx.annotation.RawRes

/** The cards of the licenses screen, in the order they are shown. */
enum class LicenseGroup { Inference, Fonts, App, AndroidX }

/**
 * What a row and its sheet show, whichever list it comes from.
 *
 * @param license short badge label ([licenseLabel]).
 * @param textRes the license text, a raw resource; null when only [fallbackText] (a URL) exists.
 */
data class LicenseRow(
    val key: String,
    val group: LicenseGroup,
    val name: String,
    val author: String,
    val license: String,
    val url: String,
    val copyright: String,
    @RawRes val textRes: Int?,
    val fallbackText: String = "",
)

/** The Jetpack libraries all name this author in the generated report. */
private const val AOSP = "The Android Open Source Project"

/**
 * Hand list, then Maven report, as the cards of the screen: Inference (llama.kt first), Fonts, App
 * (commonmark and every non-AndroidX library) and AndroidX (the ~75 Jetpack artifacts, which would
 * drown the rest in one card). Empty groups are dropped; the order inside a group is the order of
 * the hand list, and by name for the generated ones.
 */
fun groupLicenses(
    components: List<ThirdPartyLicense>,
    libraries: List<GeneratedLicense>,
): List<Pair<LicenseGroup, List<LicenseRow>>> {
    val rows = components.map { entry ->
        LicenseRow(
            key = "component:${entry.name}",
            group = entry.group,
            name = entry.name,
            author = entry.author,
            license = licenseLabel(entry.license),
            url = entry.url,
            copyright = entry.copyright,
            textRes = entry.textRes,
        )
    } + libraries.sortedBy { it.name.lowercase() }.map { library ->
        LicenseRow(
            key = "library:${library.uniqueId}",
            group = if (library.author == AOSP) LicenseGroup.AndroidX else LicenseGroup.App,
            name = library.name,
            author = library.author.ifBlank { library.version },
            license = licenseLabel(library.license),
            url = library.url,
            copyright = library.author,
            textRes = library.textRes,
            fallbackText = library.licenseUrl,
        )
    }
    return LicenseGroup.entries.map { group -> group to rows.filter { it.group == group } }
        .filter { it.second.isNotEmpty() }
}

/** Short SPDX-ish badge text: "Apache 2.0", "OFL 1.1", "BSD-2", "MIT-0"; an unknown name stays as is. */
fun licenseLabel(name: String): String {
    val n = name.trim()
    val lower = n.lowercase()
    return when {
        n.isEmpty() -> ""
        "apache" in lower && "llvm" in lower -> "Apache 2.0 + LLVM"
        "apache" in lower -> "Apache 2.0"
        "ofl" in lower || "open font" in lower -> "OFL 1.1"
        lower.startsWith("bsd") && "2" in lower -> "BSD-2"
        lower.startsWith("bsd") && "3" in lower -> "BSD-3"
        lower == "mit-0" || lower == "mit no attribution" -> "MIT-0"
        lower == "mit" || lower == "mit license" -> "MIT"
        lower == "isc" || lower == "isc license" -> "ISC"
        else -> n
    }
}

/**
 * A link for `ACTION_VIEW`: the hand list stores hosts without a scheme. Null for what a browser cannot
 * open (scp-style `git@host:path`, `scm:git:`, `ssh:`...): any `:` before the first `/` that is not
 * an http(s) scheme.
 */
fun licenseLink(url: String): String? {
    val u = url.trim()
    if (u.isEmpty()) return null
    if (u.startsWith("http://", ignoreCase = true) || u.startsWith("https://", ignoreCase = true)) return u
    val colon = u.indexOf(':')
    val slash = u.indexOf('/')
    if (colon >= 0 && (slash < 0 || colon < slash)) return null
    return "https://$u"
}
