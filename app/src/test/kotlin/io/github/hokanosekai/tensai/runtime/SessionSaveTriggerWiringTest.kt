package io.github.hokanosekai.tensai.runtime

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TEN-78's three save triggers, pinned where they are wired rather than where they run.
 *
 * The defect in all three was a single token in a call site inside an Android `Activity` or
 * `Service` — the wrong scope, a statement outside a `launch`, a collector never started. None of
 * them is reachable from a JVM unit test: this module has no Robolectric and adding one is out of
 * scope. The behaviour they gate *is* covered — [BackgroundSessionSaveTest] for the third trigger's
 * logic, `SessionRepositoryTest` and `InferenceQueueTest` for what a save does once it fires — and
 * what is left over is exactly "is it plugged in", which is a fact about the source text.
 *
 * So this reads the source, in the style `ResidentModelUsageTest` and `NoTechnicalJargonTest`
 * already established here, with the same self-check against silently scanning nothing. A test like
 * this cannot prove the trigger fires on a phone; it proves the specific line that stopped it from
 * firing is not back. That is the failure mode these three had — each was written once, correct in
 * intent, and wrong in one word — and it is the one worth a guard.
 */
class SessionSaveTriggerWiringTest {

    /**
     * Trigger 1 — backgrounding. `lifecycleScope` is cancelled on `onDestroy`, and leaving by the
     * back button runs `onStop` and `onDestroy` back to back: the save was cancelled within
     * milliseconds of starting, every time, so the app's most ordinary exit never persisted a
     * thing. `applicationScope` is what the swap hook and every `ChatViewModel` already use.
     */
    @Test
    fun mainActivityOnStopSavesOnTheApplicationScope() {
        val onStop = blockAfter(source("MainActivity.kt").readText(), "override fun onStop()")

        assertTrue(
            "MainActivity.onStop must launch its session save on RuntimeGraph.applicationScope; " +
                "got:\n$onStop",
            onStop.contains("RuntimeGraph.applicationScope.launch"),
        )
        assertTrue(
            "…and must not use lifecycleScope for it: that scope dies with the Activity, which is " +
                "the same moment the save is asked for. Got:\n$onStop",
            !onStop.contains("lifecycleScope"),
        )
    }

    /**
     * Trigger 2 — swiping the task away. `stopSelf()` alongside the `launch` dropped the foreground
     * protection while the unload it had just started was still running, and that unload is what
     * fires `onBeforeModelSwap`'s multi-hundred-MB state dump. Nothing corrupts (temp file, then
     * rename) — the save is simply lost, on the exit path where it is worth the most.
     */
    @Test
    fun inferenceServiceStopsItselfOnlyAfterTheUnloadItStarted() {
        val onTaskRemoved = blockAfter(source("InferenceService.kt").readText(), "override fun onTaskRemoved(")
        // The coroutine, not the method: in the broken version `stopSelf()` sat *after* the launch
        // and therefore still read as "after unloadModel()" in method order, while running before
        // it in wall-clock order. Inside-the-block is the property that matters, so that is the
        // one asserted.
        val coroutine = blockAfter(onTaskRemoved, "launch")
        val unloadAt = coroutine.indexOf("unloadModel()")
        val stopAt = coroutine.indexOf("stopSelf()")

        assertTrue("expected onTaskRemoved to still unload the model; got:\n$onTaskRemoved", unloadAt >= 0)
        assertTrue(
            "stopSelf() must be inside the launch that unloads, not alongside it — alongside, the " +
                "foreground protection is dropped while the session dump the unload triggered is " +
                "still being written. Got:\n$onTaskRemoved",
            stopAt >= 0,
        )
        assertTrue(
            "…and after the unload it is waiting on. Got:\n$coroutine",
            stopAt > unloadAt,
        )
    }

    /**
     * Trigger 3 — a generation finishing in the background. Unlike the other two this one is new
     * code, so what can go missing is the collector itself: [saveSessionAfterBackgroundedGeneration]
     * is a suspend function that nothing calls unless `RuntimeGraph.init` launches it, and an app
     * that never starts it is byte-for-byte the app that has the bug.
     */
    @Test
    fun runtimeGraphStartsTheEndOfGenerationSaver() {
        val runtimeGraph = source("RuntimeGraph.kt").readText()

        assertTrue(
            "RuntimeGraph.init must launch saveSessionAfterBackgroundedGeneration on " +
                "applicationScope, or TEN-78's third case is exactly as open as it was",
            runtimeGraph.contains("saveSessionAfterBackgroundedGeneration("),
        )
    }

    /** The guard on the guard — see [ResidentModelUsageTest]'s equivalent for why this is here. */
    @Test
    fun theSourceTreeIsActuallyBeingRead() {
        assertTrue(
            "expected to have found MainActivity.kt from working directory ${File("").absolutePath}",
            source("MainActivity.kt").readText().contains("class MainActivity"),
        )
    }

    private fun source(fileName: String): File {
        val root = MAIN_SOURCE_CANDIDATES.map(::File).firstOrNull { it.isDirectory }
            ?: error(
                "main sources not found from working directory ${File("").absolutePath} — " +
                    "tried ${MAIN_SOURCE_CANDIDATES.joinToString()}",
            )
        return root.walkTopDown().firstOrNull { it.isFile && it.name == fileName }
            ?: error("$fileName not found under ${root.path}")
    }

    /**
     * The braced block that follows the first occurrence of [marker] in [text], from its opening
     * brace to the matching close — brace-counted rather than regex'd, so a nested `launch { }`
     * (which is the entire point of two of these tests) is part of the block rather than the end
     * of it, and so the same helper can then be pointed at that `launch` in turn.
     */
    private fun blockAfter(text: String, marker: String): String {
        val declarationAt = text.indexOf(marker)
        require(declarationAt >= 0) { "no `$marker` found" }
        val open = text.indexOf('{', declarationAt)
        require(open >= 0) { "no block after `$marker`" }

        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open, index + 1)
                }
            }
        }
        error("unbalanced braces after `$marker`")
    }

    private companion object {
        val MAIN_SOURCE_CANDIDATES = listOf(
            "src/main/kotlin",
            "app/src/main/kotlin",
        )
    }
}
