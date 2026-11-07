package io.github.tensaiapp.ui.screens.settings

import io.github.tensaiapp.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEN-120: the licenses screen cannot go stale in silence. A font added to `res/font`, a submodule
 * added under llama.kt, or a license text left unused each fail here until the hand list in
 * [ThirdPartyLicenses] says something about it. The Maven side is generated at build time, this
 * checks the report is there and that [parseGeneratedLicenses] reads it.
 */
class ThirdPartyLicensesTest {

    private val covered = ThirdPartyLicenses.flatMap { it.covers }.toSet()

    @Test
    fun everyEmbeddedFontHasAnEntry() {
        val fonts = File(findDir(RES_CANDIDATES), "font").listFiles { f -> f.isFile }.orEmpty()
        assertTrue("expected the fonts of res/font, found none", fonts.isNotEmpty())

        val missing = fonts.map { "font:${it.nameWithoutExtension}" }.filterNot { it in covered }
        assertEquals("fonts without a ThirdPartyLicenses entry (covers = font:<name>)", emptyList<String>(), missing)
    }

    @Test
    fun everyLlamaKtSubmoduleHasAnEntry() {
        val dir = findDir(SUBMODULE_CANDIDATES)
        val submodules = dir.listFiles { f -> f.isDirectory }.orEmpty().map { it.name }
        assertTrue("expected the submodules of $dir, found none (not checked out?)", submodules.isNotEmpty())

        val missing = submodules.filterNot { it in covered }
        assertEquals("submodules without a ThirdPartyLicenses entry (covers = <directory name>)", emptyList<String>(), missing)
    }

    @Test
    fun everyLicenseTextIsUsedAndNotEmpty() {
        val rawDir = File(findDir(RES_CANDIDATES), "raw")
        val onDisk = rawDir.listFiles { f -> f.name.startsWith("license_") }.orEmpty()
        assertTrue("expected license texts in res/raw", onDisk.isNotEmpty())
        onDisk.forEach { assertTrue("${it.name} is empty", it.readText().isNotBlank()) }

        val used = ThirdPartyLicenses.map { it.textRes }.toSet()
        val unused = R.raw::class.java.fields
            .filter { it.name.startsWith("license_") && it.getInt(null) !in used }
            .map { it.name }
        assertEquals("license texts no entry points at", emptyList<String>(), unused)
        assertEquals("a raw file without a generated resource id, rebuild?", onDisk.size,
            R.raw::class.java.fields.count { it.name.startsWith("license_") })
    }

    @Test
    fun llamaKtComesFirst() {
        assertEquals("llama.kt", ThirdPartyLicenses.first().name)
    }

    @Test
    fun generatedReportIsPresentAndReadable() {
        val report = REPORT_ROOTS.map(::File).filter { it.isDirectory }
            .flatMap { root -> root.listFiles { f -> f.isDirectory }.orEmpty().map { File(it, "res/raw/aboutlibraries.json") } }
            .firstOrNull { it.isFile }
            ?: error("aboutlibraries.json not generated under any of ${REPORT_ROOTS.joinToString()}")

        val libraries = parseGeneratedLicenses(report.readText(), covered)

        val names = libraries.map { it.name }
        assertTrue("expected Kotlin stdlib in $names", names.any { it.contains("Kotlin Stdlib") })
        assertTrue("expected AndroidX in $names", libraries.any { it.author == "The Android Open Source Project" })
        assertEquals(
            "libraries under a license whose text we do not ship (add it to SPDX_TEXTS)",
            emptyList<String>(),
            libraries.filter { it.textRes == null }.map { "${it.name}: ${it.license}" },
        )
        assertFalse("commonmark is carried by the hand list", names.any { it.contains("commonmark", ignoreCase = true) })
        assertFalse("a BOM is not in the APK", names.any { it.contains("bom", ignoreCase = true) })
    }

    /**
     * `maven:<group>:<artifact>` drops that artifact only, its group's other artifacts stay listed, even one
     * whose id starts like it; `maven:<group>` drops the group, not a group whose name merely starts like it.
     */
    @Test
    fun aCoverDropsExactlyWhatItNames() {
        val json = """
            {"libraries":[
              {"uniqueId":"androidx.datastore:datastore-preferences-external-protobuf","name":"Preferences External Protobuf","licenses":[]},
              {"uniqueId":"androidx.datastore:datastore-preferences","name":"Preferences DataStore","licenses":[]},
              {"uniqueId":"androidx.datastore:datastore-preferences-core","name":"Preferences DataStore Core","licenses":[]},
              {"uniqueId":"org.jsoup:jsoup","name":"jsoup","licenses":[]},
              {"uniqueId":"org.jsoupx:other","name":"Other","licenses":[]}
            ],"licenses":{}}
        """.trimIndent()
        val covers = setOf("maven:androidx.datastore:datastore-preferences-external-protobuf", "maven:org.jsoup")

        val names = parseGeneratedLicenses(json, covers).map { it.name }

        assertEquals(listOf("Other", "Preferences DataStore", "Preferences DataStore Core"), names)
    }

    private fun findDir(candidates: List<String>): File = candidates.map(::File).firstOrNull { it.isDirectory }
        ?: error("not found from ${File("").absolutePath}, tried ${candidates.joinToString()}")

    private companion object {
        /** Gradle runs unit tests from the module directory; the second entry covers a repo-root run. */
        val RES_CANDIDATES = listOf("src/main/res", "app/src/main/res")
        val SUBMODULE_CANDIDATES = listOf("../third_party/llama.kt/third_party", "third_party/llama.kt/third_party")
        val REPORT_ROOTS = listOf("build/generated/aboutLibraries", "app/build/generated/aboutLibraries")
    }
}
