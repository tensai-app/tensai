package io.github.tensaiapp.ui.screens.settings

import android.content.Context
import android.util.Log
import androidx.annotation.RawRes
import com.mikepenz.aboutlibraries.Libs
import io.github.tensaiapp.R

/** One Maven dependency of the APK, read from the report the AboutLibraries plugin generates. */
data class GeneratedLicense(
    /** `group:artifact` from the report, unique where name and version are not. */
    val uniqueId: String,
    val name: String,
    val version: String,
    val license: String,
    val author: String,
    val url: String,
    /** The license text, a raw resource of ours; null for a license [SPDX_TEXTS] does not know. */
    @RawRes val textRes: Int?,
    val licenseUrl: String,
)

/**
 * The generated report (`R.raw.aboutlibraries`, written at build time from the resolved
 * dependency graph, nothing is fetched at runtime), or an empty list when it cannot be read: a
 * notice screen must not take Settings down with it.
 */
fun loadGeneratedLicenses(context: Context): List<GeneratedLicense> = runCatching {
    val json = context.resources.openRawResource(R.raw.aboutlibraries).bufferedReader().use { it.readText() }
    parseGeneratedLicenses(json, ThirdPartyLicenses.flatMap { it.covers }.toSet())
}.onFailure { Log.w("Licenses", "aboutlibraries.json could not be read, the Libraries list stays empty", it) }
    .getOrDefault(emptyList())

/**
 * @param covered the `maven:<group>` entries of [ThirdPartyLicenses], dropped here because the hand
 *   list already carries them (commonmark: BSD-2-Clause, whose text is only valid with its copyright line).
 */
fun parseGeneratedLicenses(json: String, covered: Set<String> = emptySet()): List<GeneratedLicense> {
    val coveredGroups = covered.filter { it.startsWith("maven:") }.map { it.removePrefix("maven:") + ":" }
    return Libs.Builder().withJson(json).build().libraries
        // A BOM only pins versions, nothing of it is in the APK.
        .filterNot { it.uniqueId.substringAfter(':').endsWith("bom") }
        .filterNot { library -> coveredGroups.any { library.uniqueId.startsWith(it) } }
        .map { library ->
            val license = library.licenses.firstOrNull()
            GeneratedLicense(
                uniqueId = library.uniqueId,
                name = library.name.ifBlank { library.uniqueId },
                version = library.artifactVersion.orEmpty(),
                license = license?.name.orEmpty(),
                author = library.developers.firstOrNull()?.name
                    ?: library.organization?.name.orEmpty(),
                url = library.website ?: library.scm?.url.orEmpty(),
                textRes = SPDX_TEXTS[license?.spdxId],
                licenseUrl = license?.url.orEmpty(),
            )
        }
        .sortedBy { it.name.lowercase() }
}

/**
 * The texts of the licenses the Maven dependencies come under, by SPDX id. The plugin runs with
 * `offlineMode` (see `app/build.gradle.kts`), which keeps the build free of network calls and its
 * output stable, and leaves the texts out of the report: they are ours, verbatim. A new license
 * showing up in the report has no entry here and `ThirdPartyLicensesTest` says so.
 */
private val SPDX_TEXTS = mapOf(
    "Apache-2.0" to R.raw.license_apache_2_0,
)
