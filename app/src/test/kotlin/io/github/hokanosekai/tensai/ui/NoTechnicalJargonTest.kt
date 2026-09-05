package io.github.hokanosekai.tensai.ui

import io.github.hokanosekai.tensai.data.models.DuplicateModelException
import io.github.hokanosekai.tensai.data.models.InsufficientStorageException
import io.github.hokanosekai.tensai.data.models.ModelImportException
import io.github.hokanosekai.tensai.data.models.ModelMetadataException
import io.github.hokanosekai.tensai.engine.ModelLoadException
import io.github.hokanosekai.tensai.ui.screens.loading.LoadFailure
import io.github.hokanosekai.tensai.ui.screens.models.ImportFailure
import java.io.File
import java.io.IOException
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEN-39's acceptance criterion, as a test: **no error state shows a stacktrace or technical
 * jargon.**
 *
 * User-facing text in this app comes from two places, and both are covered here.
 *
 * 1. `strings.xml`, read from the source tree — every string the app can render, checked against
 *    [TechnicalJargon]. A jargon-carrying string added there fails the build rather than reaching a
 *    screen.
 * 2. The two classifiers that stand between a thrown exception and a screen —
 *    [ImportFailure] and [LoadFailure]. These are the actual leak this ticket closed: the import
 *    and load paths used to render `e.message` verbatim, and the messages reaching them come from
 *    `java.io` and the JNI boundary. Each is fed a deliberately jargon-laden cause and must not let
 *    a syllable of it through.
 *
 * What this cannot check is a string composed at runtime from something other than these — hence
 * `ChatViewModelTest.noJargonFromAFailedDecodeReachesTheUserAnywhere`, which takes the chat path
 * end to end for the same reason.
 */
class NoTechnicalJargonTest {

    /** A cause of the kind the import and load paths really do receive. */
    private val jargonCause = IOException(
        "EACCES (Permission denied) at java.io.FileOutputStream.write(FileOutputStream.java:0)",
    )

    @Test
    fun everyUserFacingStringIsFreeOfMachineVocabulary() {
        val offenders = userFacingStrings().mapNotNull { (name, value) ->
            val found = TechnicalJargon.offendersIn(value)
            if (found.isEmpty()) null else "$name = \"$value\" -> $found"
        }

        assertEquals(
            "strings.xml must not put machine vocabulary in front of a user",
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * A guard on the guard: if the string file ever stops being found — moved, renamed, a different
     * working directory under a future AGP — the test above would pass on an empty list and quietly
     * stop checking anything.
     */
    @Test
    fun theStringsFileIsActuallyBeingRead() {
        val strings = userFacingStrings()

        assertTrue("expected to have found strings.xml and read it", strings.size > 20)
        assertTrue(
            "expected a known string, so a wrong-file read cannot pass",
            strings.any { it.first == "home_subtitle" },
        )
    }

    @Test
    fun anImportFailureCarriesNoneOfTheExceptionsOwnWords() {
        val failures = listOf(
            ImportFailure.of(ModelImportException("qwen3-1.7b-q4km.gguf", jargonCause)),
            ImportFailure.of(ModelMetadataException("qwen3-1.7b-q4km.gguf", jargonCause)),
            ImportFailure.of(DuplicateModelException("qwen3-1.7b-q4km.gguf")),
            ImportFailure.of(InsufficientStorageException("qwen3-1.7b-q4km.gguf", 4_000_000_000, 1_000_000)),
            ImportFailure.of(jargonCause),
        )

        // toString() on a data class spells out every field, so this catches any field that starts
        // smuggling the throwable through — not just the ones that exist today.
        failures.forEach { failure ->
            assertEquals(
                "$failure must carry no trace of the exception it came from",
                emptyList<String>(),
                TechnicalJargon.offendersIn(failure.toString()),
            )
        }
    }

    /** An import failure the repository never names must still be classified, not dropped. */
    @Test
    fun anUnrecognisedImportFailureIsStillClassified() {
        assertEquals(ImportFailure.Unknown, ImportFailure.of(SecurityException("permission denial")))
    }

    @Test
    fun aLoadFailureCarriesNothingAtAll() {
        val failures = listOf(
            LoadFailure.of(ModelLoadException("llama_model_load: error loading model", jargonCause)),
            LoadFailure.of(jargonCause),
        )

        failures.forEach { failure ->
            assertEquals(
                emptyList<String>(),
                TechnicalJargon.offendersIn(failure.toString()),
            )
        }
        assertEquals(LoadFailure.UnreadableModel, failures[0])
        assertEquals(LoadFailure.Unknown, failures[1])
    }

    /**
     * Sanity check on the detector itself: a test that can't fail proves nothing, and this one is
     * the only evidence behind the ticket's most checkable criterion.
     */
    @Test
    fun theDetectorCatchesWhatItIsMeantTo() {
        val realOffenders = listOf(
            "Error: llama_decode failed",
            "Failed to copy: java.io.IOException: EACCES",
            "at com.tensai.llamakt.LlamaEngine.decode(LlamaEngine.kt:214)",
            "load failed (rc=-1)",
            "thinking was null",
            "IllegalStateException while loading",
        )

        realOffenders.forEach { offender ->
            assertTrue(
                "the detector missed \"$offender\", which makes every other assertion here worthless",
                TechnicalJargon.offendersIn(offender).isNotEmpty(),
            )
        }

        // And does not fire on the app's own legitimate copy.
        listOf(
            "Import didn't finish",
            "needs ~4.1 GB, your phone has 8.0 GB",
            "That reply didn't come through. Try again?",
            "Only 0.8 GB left, and the models here take 2.1 GB each on average.",
            "This file couldn't be read as a model. Re-importing it usually fixes that.",
        ).forEach { copy ->
            assertEquals(
                "the detector must not fire on ordinary copy",
                emptyList<String>(),
                TechnicalJargon.offendersIn(copy),
            )
        }
    }

    /**
     * Every `<string>` in the app's default locale, as name-to-value pairs. Plurals are read too:
     * they are just as user-facing, and just as capable of holding a leaked message.
     *
     * Read off the source tree rather than through `R`/`Resources` so this stays a plain JVM test
     * with no Android runtime behind it — the same reason every other pure rule in this project
     * ([io.github.hokanosekai.tensai.ui.screens.HomePill], `OnboardingGate`, `KvCacheSize`) is
     * testable without a device.
     */
    private fun userFacingStrings(): List<Pair<String, String>> {
        val file = STRINGS_CANDIDATES.map(::File).firstOrNull { it.isFile }
            ?: error(
                "strings.xml not found from working directory ${File("").absolutePath} — " +
                    "tried ${STRINGS_CANDIDATES.joinToString()}",
            )

        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val out = mutableListOf<Pair<String, String>>()

        val strings = document.getElementsByTagName("string")
        for (i in 0 until strings.length) {
            val node = strings.item(i)
            val name = node.attributes?.getNamedItem("name")?.nodeValue ?: continue
            out += name to node.textContent
        }

        val plurals = document.getElementsByTagName("item")
        for (i in 0 until plurals.length) {
            val node = plurals.item(i)
            val quantity = node.attributes?.getNamedItem("quantity")?.nodeValue ?: continue
            out += "plural[$quantity]" to node.textContent
        }

        return out
    }

    private companion object {
        /** Gradle runs unit tests from the module directory; the second entry covers a repo-root run. */
        val STRINGS_CANDIDATES = listOf(
            "src/main/res/values/strings.xml",
            "app/src/main/res/values/strings.xml",
        )
    }
}
