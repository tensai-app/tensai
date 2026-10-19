package io.github.tensaiapp.ui.screens.loading

import io.github.tensaiapp.engine.ModelLoadException
import io.github.tensaiapp.runtime.QueueBusyException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

/**
 * [LoadFailure.of]'s one non-classification: a cancellation. The load-side twin of
 * `ImportFailureTest` (TEN-73 after TEN-53) — `NoTechnicalJargonTest` covers what the classifier
 * *carries*, this covers what it refuses to accept at all.
 *
 * Worth stating what this is and is not: unlike TEN-53's, the defect here was never reachable from
 * the app. Every load runs on `RuntimeGraph.applicationScope`, which is never cancelled — leaving
 * the Loading screen deliberately does not stop the load (see [LoadModelViewModel]'s KDoc). So this
 * pins a backstop rather than a bug a user could hit, and it is the same backstop the import side
 * already has.
 */
class LoadFailureTest {

    @Test
    fun aCancellationIsRefusedRatherThanTurnedIntoSomethingToShowTheUser() {
        val cancellation = CancellationException("the load's scope was cancelled")

        try {
            val classified = LoadFailure.of(cancellation)
            fail("of() classified a cancellation as $classified instead of re-throwing it")
        } catch (rethrown: CancellationException) {
            // Same instance, not a fresh one: whatever job this cancellation belongs to has to
            // keep seeing its own.
            assertSame(cancellation, rethrown)
        }
    }

    /** The guard is on cancellation specifically, not on "anything the classifier doesn't know". */
    @Test
    fun everyOtherUnrecognisedThrowableIsStillClassifiedRatherThanThrown() {
        assertEquals(LoadFailure.Unknown, LoadFailure.of(IOException("unable to allocate backend buffer")))
        assertEquals(LoadFailure.Unknown, LoadFailure.of(IllegalStateException("nobody listed this")))
    }

    /** And the classifications it does make are untouched by the new clause. */
    @Test
    fun theTwoNamedCasesStillClassify() {
        assertEquals(LoadFailure.UnreadableModel, LoadFailure.of(ModelLoadException("truncated gguf")))
        assertEquals(LoadFailure.EngineBusy, LoadFailure.of(QueueBusyException("queue is not ready")))
    }

    @Test
    fun theTwoKnownLlamaCppReasonsAreClassified() {
        val unknownArch = ModelLoadException(
            "x",
            nativeReason = "llama_model_load: error loading model: error loading model architecture: unknown model architecture: 'spark2_5'",
        )
        assertEquals(LoadFailure.UnsupportedArchitecture, LoadFailure.of(unknownArch))
        assertEquals("spark2_5", LoadFailure.unknownArchitectureOf(unknownArch))

        val draftHead = ModelLoadException(
            "x",
            nativeReason = "llama_model_load: error loading model: done_getting_tensors: wrong number of tensors; expected 81, got 58",
        )
        assertEquals(LoadFailure.IncompleteModel, LoadFailure.of(draftHead))
        assertEquals(null, LoadFailure.unknownArchitectureOf(draftHead))
    }

    @Test
    fun anUnsupportedArchitectureMessageIsClassifiedToo() {
        val e = ModelLoadException("x", nativeReason = "llama_model_load: error loading model: unsupported model architecture: 'clip'")
        assertEquals(LoadFailure.UnsupportedArchitecture, LoadFailure.of(e))
        assertEquals("clip", LoadFailure.unknownArchitectureOf(e))
    }

    @Test
    fun anyOtherReasonStaysUnreadable() {
        assertEquals(LoadFailure.UnreadableModel, LoadFailure.of(ModelLoadException("x", nativeReason = "tensor 'token_embd.weight' has wrong shape")))
    }

    @Test
    fun onlyTheDeterministicFailuresSkipTheRetry() {
        assertEquals(
            setOf(LoadFailure.UnsupportedArchitecture, LoadFailure.IncompleteModel),
            LoadFailure.entries.filter { it.isDeterministic }.toSet(),
        )
    }
}
