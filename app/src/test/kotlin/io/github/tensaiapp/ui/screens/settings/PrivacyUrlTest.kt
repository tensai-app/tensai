package io.github.tensaiapp.ui.screens.settings

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Settings > About > Privacy (TEN-167) opens `settings_privacy_url`, a translated string so the
 * page follows the same locale resolution as the rest of the UI. A translation may only change the
 * locale path: every value stays on the site's privacy page, and French points at `/fr/`.
 * Read off the source tree like `StringsParityTest`.
 */
class PrivacyUrlTest {

    @Test
    fun everyLocaleOpensThePrivacyPageOfTheSite() {
        val urls = privacyUrls()
        assertTrue("expected values/ and values-fr/, got ${urls.keys}", urls.keys.containsAll(listOf("values", "values-fr")))

        urls.forEach { (locale, url) ->
            assertTrue("$locale: $url", url.startsWith("https://tensai.page/") && url.endsWith("/privacy/"))
        }
    }

    @Test
    fun frenchOpensTheFrenchPage() {
        assertEquals("https://tensai.page/fr/privacy/", privacyUrls().getValue("values-fr"))
    }

    /** `settings_privacy_url` by `values*` folder name, for every strings.xml that defines it. */
    private fun privacyUrls(): Map<String, String> = resDir()
        .listFiles { dir -> dir.isDirectory && dir.name.startsWith("values") }
        .orEmpty()
        .map { File(it, "strings.xml") }
        .filter { it.isFile }
        .mapNotNull { file -> privacyUrl(file)?.let { file.parentFile.name to it } }
        .toMap()

    private fun privacyUrl(file: File): String? {
        val strings = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        return (0 until strings.length)
            .map { strings.item(it) as Element }
            .firstOrNull { it.getAttribute("name") == "settings_privacy_url" }
            ?.textContent
    }

    private fun resDir(): File = RES_CANDIDATES.map(::File).firstOrNull { File(it, "values/strings.xml").isFile }
        ?: error("res dir not found from ${File("").absolutePath}, tried ${RES_CANDIDATES.joinToString()}")

    private companion object {
        /** Gradle runs unit tests from the module directory; the second entry covers a repo-root run. */
        val RES_CANDIDATES = listOf("src/main/res", "app/src/main/res")
    }
}
