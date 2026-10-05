package io.github.hokanosekai.tensai.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * TEN-41: every translation carries exactly the default locale's translatable keys, with exactly
 * the same format placeholders.
 *
 * Lint's MissingTranslation only covers the key set, and only when lint runs. A placeholder that
 * drifts (a `%1$s` turned into `%s`, a `%2$d` dropped) compiles, passes lint and then throws or
 * prints garbage at runtime in that one locale. This catches both in a plain JVM test, read off
 * the source tree like [NoTechnicalJargonTest] does.
 */
class StringsParityTest {

    @Test
    fun everyTranslationHasTheDefaultKeysAndPlaceholders() {
        val default = read(File(resDir(), "values/strings.xml"))
        val translatable = default.filterValues { it.translatable }

        val translations = translationFiles()
        assertTrue("expected at least one values-xx/strings.xml", translations.isNotEmpty())

        translations.forEach { file ->
            val translated = read(file)
            val locale = file.parentFile.name

            assertEquals("$locale: keys missing or extra", translatable.keys.sorted(), translated.keys.sorted())

            translatable.forEach { (key, entry) ->
                assertEquals(
                    "$locale: placeholders of $key",
                    entry.placeholders,
                    translated.getValue(key).placeholders,
                )
            }
        }
    }

    @Test
    fun noTranslationPutsMachineVocabularyInFrontOfAUser() {
        translationFiles().forEach { file ->
            val offenders = read(file).mapNotNull { (key, entry) ->
                val found = entry.texts.flatMap(TechnicalJargon::offendersIn)
                if (found.isEmpty()) null else "$key -> $found"
            }
            assertEquals("${file.parentFile.name}: jargon in user-facing strings", emptyList<String>(), offenders)
        }
    }

    private class Entry(val translatable: Boolean, val texts: List<String>) {
        /** Sorted, so a plural's items (which may each use a different subset) compare as a set. */
        val placeholders: List<String> = texts
            .flatMap { text -> PLACEHOLDER.findAll(text).map { it.value }.filter { it != "%%" } }
            .distinct()
            .sorted()
    }

    private fun read(file: File): Map<String, Entry> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val root = document.documentElement
        val out = mutableMapOf<String, Entry>()
        val children = root.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i) as? Element ?: continue
            val name = node.getAttribute("name")
            val translatable = node.getAttribute("translatable") != "false"
            val texts = when (node.tagName) {
                "string" -> listOf(node.textContent)
                "plurals" -> node.getElementsByTagName("item").let { items ->
                    (0 until items.length).map { items.item(it).textContent }
                }
                else -> continue
            }
            out[name] = Entry(translatable, texts)
        }
        return out
    }

    private fun resDir(): File = RES_CANDIDATES.map(::File).firstOrNull { File(it, "values/strings.xml").isFile }
        ?: error("res dir not found from ${File("").absolutePath}, tried ${RES_CANDIDATES.joinToString()}")

    private fun translationFiles(): List<File> = resDir()
        .listFiles { dir -> dir.isDirectory && dir.name.startsWith("values-") }
        .orEmpty()
        .map { File(it, "strings.xml") }
        .filter { it.isFile }

    private companion object {
        /** Gradle runs unit tests from the module directory; the second entry covers a repo-root run. */
        val RES_CANDIDATES = listOf("src/main/res", "app/src/main/res")

        /** `%%`, or a java.util.Formatter conversion with optional `n$` index, flags, width and precision. */
        val PLACEHOLDER = Regex("""%%|%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[a-zA-Z]""")
    }
}
