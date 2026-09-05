package io.github.hokanosekai.tensai.ui.screens.models

import io.github.hokanosekai.tensai.data.models.ModelMetadataException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

/**
 * [ImportFailure.of]'s one non-classification: a cancellation.
 *
 * `NoTechnicalJargonTest` covers what the classifier *carries* (nothing of the throwable's own
 * words); this covers what it refuses to accept at all. The two used to be the same bug at two
 * levels — the repository turned a cancelled import into a [ModelMetadataException], and even
 * with that fixed, a raw [CancellationException] reaching here would have been classified as
 * [ImportFailure.Unknown] and shown as a failure the user never had (TEN-53).
 */
class ImportFailureTest {

    @Test
    fun aCancellationIsRefusedRatherThanTurnedIntoSomethingToShowTheUser() {
        val cancellation = CancellationException("the user left the screen")

        try {
            val classified = ImportFailure.of(cancellation)
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
        assertEquals(ImportFailure.Unknown, ImportFailure.of(IOException("EACCES (Permission denied)")))
        assertEquals(ImportFailure.Unknown, ImportFailure.of(IllegalStateException("nobody listed this")))
    }
}
