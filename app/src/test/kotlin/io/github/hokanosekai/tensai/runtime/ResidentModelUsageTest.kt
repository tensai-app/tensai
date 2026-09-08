package io.github.hokanosekai.tensai.runtime

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard that stops a fifth site: **nothing asks "which model is loaded" by casting
 * [QueueState] to [QueueState.ModelReady].**
 *
 * `as? QueueState.ModelReady` was written four separate times — the delete guard's wiring, the
 * Models screen's "loaded" chip, the drawer's new-chat model id, the thinking toggle — and every
 * one of them was wrong the same way: a model stays resident across a generation, so the cast
 * yields `null` for the whole duration of a reply. One of the four could delete a mmapped `.gguf`
 * (TEN-76); the other three were TEN-46 and TEN-58, fixed one at a time while the expression stayed
 * available to write again.
 *
 * The expression has no correct use left. Extracting the loaded model out of the queue's state is
 * what [residentModel] and [residentModelName] are for; asking whether the queue is *idle enough*
 * to start work is `is`/`!is QueueState.ModelReady`, which does not carry `info` out and is
 * deliberately untouched by this test (see [InferenceQueue.generate] and [InferenceQueue.saveSession]).
 *
 * Scans the source tree rather than the bytecode: this is a rule about what someone can write, and
 * the failure has to name the file they wrote it in. Same approach — and the same self-check
 * against silently reading nothing — as `NoTechnicalJargonTest`.
 */
class ResidentModelUsageTest {

    @Test
    fun noProductionCodeCastsQueueStateToModelReadyToFindTheLoadedModel() {
        val offenders = mainSources().flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> line.contains(BANNED) }
                .map { (index, line) -> "${file.path}:${index + 1}: ${line.trim()}" }
        }

        assertEquals(
            "`$BANNED` reads as \"no model loaded\" for the whole duration of a generation. Use " +
                "`residentModel`/`residentModelName` instead — or, if the question really is " +
                "whether the queue is idle, `is QueueState.ModelReady`, which carries no `info` out.",
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * A guard on the guard: if the source tree ever stops being found — a different working
     * directory under a future AGP, a module rename — the test above passes on an empty list and
     * quietly stops checking anything.
     */
    @Test
    fun theSourceTreeIsActuallyBeingRead() {
        val sources = mainSources()

        assertTrue("expected to have found the main source tree, got ${sources.size} files", sources.size > 20)
        assertTrue(
            "expected InferenceQueue.kt among them, so a wrong-directory scan cannot pass",
            sources.any { it.name == "InferenceQueue.kt" },
        )
    }

    private fun mainSources(): List<File> {
        val root = MAIN_SOURCE_CANDIDATES.map(::File).firstOrNull { it.isDirectory }
            ?: error(
                "main sources not found from working directory ${File("").absolutePath} — " +
                    "tried ${MAIN_SOURCE_CANDIDATES.joinToString()}",
            )
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private companion object {
        const val BANNED = "as? QueueState.ModelReady"

        val MAIN_SOURCE_CANDIDATES = listOf(
            "src/main/kotlin",
            "app/src/main/kotlin",
        )
    }
}
