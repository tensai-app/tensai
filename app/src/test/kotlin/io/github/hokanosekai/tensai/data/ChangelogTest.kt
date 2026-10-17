package io.github.hokanosekai.tensai.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing of `changelog.json` and the rule behind the dot on Settings' Version row (TEN-193). */
class ChangelogTest {

    private val sample = """
        {
          "releases": [
            { "version": "0.2.0", "date": "2026-10-16", "intro": "Two.",
              "groups": [ { "label": "Chat", "items": ["a", "b"] } ] },
            { "version": "0.10.0", "title": "Ten", "date": "2026-11-01", "intro": "Ten." },
            { "version": "0.3.0", "title": " ", "date": "2026-10-17", "intro": "Three." }
          ]
        }
    """.trimIndent()

    @Test
    fun releasesComeNewestFirstWhateverTheFileOrder() {
        // Numeric, not lexicographic: 0.10.0 is after 0.3.0.
        assertEquals(listOf("0.10.0", "0.3.0", "0.2.0"), Changelog.parse(sample, "0.3.0").map { it.version })
    }

    @Test
    fun onlyTheInstalledVersionIsFlagged() {
        val releases = Changelog.parse(sample, "0.3.0")
        assertEquals(listOf("0.3.0"), releases.filter { it.installed }.map { it.version })
        assertTrue("a leading v on the installed name is ignored", Changelog.parse(sample, "v0.2.0").single { it.installed }.version == "0.2.0")
        assertTrue(Changelog.parse(sample, "9.9.9").none { it.installed })
    }

    @Test
    fun groupsTitlesAndIntroAreRead() {
        val two = Changelog.parse(sample, "").single { it.version == "0.2.0" }
        assertEquals("Two.", two.intro)
        assertNull(two.title)
        assertEquals(listOf(ChangelogGroup("Chat", listOf("a", "b"))), two.groups)
        assertEquals("Ten", Changelog.parse(sample, "").first().title)
        assertNull("a blank title is no title", Changelog.parse(sample, "").single { it.version == "0.3.0" }.title)
        assertFalse(Changelog.parse(sample, "").first().hasGroups)
    }

    @Test
    fun aPreReleaseSortsBeforeItsRelease() {
        val json = """{"releases":[{"version":"1.0.0-rc1"},{"version":"1.0.0"}]}"""
        assertEquals(listOf("1.0.0", "1.0.0-rc1"), Changelog.parse(json, "").map { it.version })
    }

    @Test
    fun invalidOrEmptyInputIsAnEmptyList() {
        assertTrue(Changelog.parse("", "0.3.0").isEmpty())
        assertTrue(Changelog.parse("not json", "0.3.0").isEmpty())
        assertTrue(Changelog.parse("""{"releases":[{"title":"no version"}]}""", "0.3.0").isEmpty())
        assertTrue(Changelog.parse("{}", "0.3.0").isEmpty())
    }

    @Test
    fun theBundledAssetParsesAndHoldsTheInstalledVersionFirst() {
        val asset = File("src/main/assets/${Changelog.ASSET}").readText()
        val versionName = requireNotNull(
            Regex("""(?m)^\s*versionName\s*=\s*"([^"]+)"""").find(File("build.gradle.kts").readText()),
        ) { "versionName not found in build.gradle.kts" }.groupValues[1]
        val releases = Changelog.parse(asset, versionName)
        assertTrue(releases.isNotEmpty())
        assertEquals("the newest entry is the version being built", versionName, releases.first().version)
        assertTrue(releases.first().installed)
        assertTrue(releases.all { it.date.matches(Regex("""\d{4}-\d{2}-\d{2}""")) })
    }

    @Test
    fun theDotShowsWhenTheInstalledVersionWasNotOpened() {
        assertTrue("fresh install", ChangelogRepository.isUnseen("0.3.0", null))
        assertTrue("after an update", ChangelogRepository.isUnseen("0.3.0", "0.2.0"))
        assertFalse("notes opened", ChangelogRepository.isUnseen("0.3.0", "0.3.0"))
        assertFalse("unreadable version", ChangelogRepository.isUnseen("", null))
    }
}
