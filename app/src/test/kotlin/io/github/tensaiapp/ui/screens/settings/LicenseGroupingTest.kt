package io.github.tensaiapp.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TEN-125: which card a license lands in, the badge label, and the link the sheet opens. */
class LicenseGroupingTest {

    private fun lib(name: String, author: String, license: String = "The Apache Software License, Version 2.0", id: String = "g:$name") =
        GeneratedLicense(uniqueId = id, name = name, version = "1.0", license = license, author = author, url = "", textRes = null, licenseUrl = "")

    @Test
    fun realHandListGroupsInOrderWithLlamaKtFirst() {
        val groups = groupLicenses(ThirdPartyLicenses, emptyList())
        assertEquals(listOf(LicenseGroup.Inference, LicenseGroup.Fonts, LicenseGroup.App), groups.map { it.first })
        assertEquals("llama.kt", groups.first().second.first().name)
        assertEquals(listOf("Figtree", "JetBrains Mono"), groups[1].second.map { it.name })
        assertEquals(listOf("commonmark-java", "jsoup"), groups[2].second.map { it.name })
    }

    @Test
    fun generatedLibrariesSplitByAuthorAndSortByName() {
        val groups = groupLicenses(
            ThirdPartyLicenses,
            listOf(
                lib("Room-Runtime", "The Android Open Source Project"),
                lib("Okio", "Square"),
                lib("Activity", "The Android Open Source Project"),
                lib("kotlinx", "JetBrains"),
            ),
        ).toMap()
        assertEquals(listOf("Activity", "Room-Runtime"), groups.getValue(LicenseGroup.AndroidX).map { it.name })
        // hand-listed entries (commonmark, jsoup) stay first (hand list), generated ones follow by name, case-insensitive.
        assertEquals(listOf("commonmark-java", "jsoup", "kotlinx", "Okio"), groups.getValue(LicenseGroup.App).map { it.name })
    }

    @Test
    fun emptyGroupsAreDropped() {
        val groups = groupLicenses(ThirdPartyLicenses, listOf(lib("Okio", "Square")))
        assertTrue(groups.none { it.second.isEmpty() })
        assertTrue(LicenseGroup.AndroidX !in groups.map { it.first })
    }

    @Test
    fun rowKeysAreUniqueAcrossGroups() {
        val rows = groupLicenses(ThirdPartyLicenses, listOf(lib("A", "x"), lib("B", "The Android Open Source Project")))
            .flatMap { it.second }
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
    }

    @Test
    fun everyHandListLicenseGetsAShortLabel() {
        val labels = ThirdPartyLicenses.map { licenseLabel(it.license) }
        val known = setOf("MIT", "MIT-0", "ISC", "Apache 2.0", "Apache 2.0 + LLVM", "OFL 1.1", "BSD-2")
        assertEquals("hand-list licenses with no short label", emptyList<String>(), labels.filterNot { it in known })
        assertEquals("OFL 1.1", licenseLabel("SIL OFL 1.1"))
        assertEquals("BSD-2", licenseLabel("BSD 2-Clause"))
        assertEquals("BSD-2", licenseLabel("BSD-2-Clause"))
        assertEquals("MIT-0", licenseLabel("MIT-0"))
        assertEquals("ISC", licenseLabel("ISC"))
        assertEquals("MIT", licenseLabel("MIT"))
        assertEquals("Apache 2.0", licenseLabel("The Apache Software License, Version 2.0"))
        assertEquals("Apache 2.0 + LLVM", licenseLabel("Apache 2.0 with LLVM exception"))
        assertEquals("Weird-1.0", licenseLabel("Weird-1.0"))
    }

    @Test
    fun sameNameAndVersionKeepDistinctKeys() {
        val rows = groupLicenses(
            ThirdPartyLicenses,
            listOf(lib("Core", "x", id = "a:core"), lib("Core", "x", id = "b:core")),
        ).flatMap { it.second }
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
    }

    @Test
    fun linkGetsHttpsOnlyWhenSchemeIsMissing() {
        assertEquals("https://github.com/ggml-org/llama.cpp", licenseLink("github.com/ggml-org/llama.cpp"))
        assertEquals("https://x.dev", licenseLink("https://x.dev"))
        assertEquals("HTTP://x.dev", licenseLink(" HTTP://x.dev "))
    }

    @Test
    fun linkIsNullForWhatABrowserCannotOpen() {
        listOf("git@github.com:foo/bar.git", "scm:git:git://x.org/a.git", "ssh://git@x.org/a", "ftp://x.org", "").forEach {
            assertEquals(it, null, licenseLink(it))
        }    }
}
