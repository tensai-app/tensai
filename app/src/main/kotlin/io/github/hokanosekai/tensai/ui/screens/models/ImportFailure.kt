package io.github.hokanosekai.tensai.ui.screens.models

import io.github.hokanosekai.tensai.data.models.DuplicateModelException
import io.github.hokanosekai.tensai.data.models.InsufficientStorageException
import io.github.hokanosekai.tensai.data.models.ModelImportException
import io.github.hokanosekai.tensai.data.models.ModelMetadataException
import kotlinx.coroutines.CancellationException

/**
 * What the user is told when an import doesn't finish (TEN-39) — one case per way
 * [io.github.hokanosekai.tensai.data.models.ModelRepository.importModel] can fail, carrying only
 * the facts a sentence needs.
 *
 * The whole point is what these do **not** carry: no [Throwable], no `cause`, no `message`. The
 * import path crosses `java.io`, SAF content providers and the GGUF parser, and every one of them
 * words its failures for a developer reading a stack trace ("EACCES (Permission denied)",
 * "Failed to read magic at offset 0"). Splicing that into an error card is the exact thing this
 * ticket's acceptance criterion forbids, and it used to happen: [ModelImportException] wrapped
 * `e.message` into its own. Classifying at the boundary means the technical detail stays on the
 * exception's `cause` where a stack trace can still find it, and the screen renders a string
 * resource keyed by the *kind* of failure instead.
 *
 * `NoTechnicalJargonTest` holds that line: it feeds each of these a deliberately jargon-laden
 * cause and asserts none of it survives into the result.
 */
sealed interface ImportFailure {

    /** This exact file (same name, same size) is already in the library — nothing was copied. */
    data class AlreadyImported(val fileName: String) : ImportFailure

    /**
     * The device hasn't got room for the file. [neededBytes] is the source's own size and
     * [freeBytes] what the volume had left, both as measured before the copy started.
     */
    data class NotEnoughSpace(val neededBytes: Long, val freeBytes: Long) : ImportFailure

    /** The file copied fine but its GGUF header didn't parse — it is probably not a GGUF at all. */
    data class NotAModelFile(val fileName: String) : ImportFailure

    /** The copy itself broke partway (source went away, storage error, …). The partial copy is gone. */
    data class CopyInterrupted(val fileName: String) : ImportFailure

    /**
     * Anything the repository didn't name. Deliberately carries nothing: an unclassified throwable
     * is precisely the one whose text there is no reason to trust in front of a user.
     */
    data object Unknown : ImportFailure

    companion object {
        /**
         * Classifies an [importModel][io.github.hokanosekai.tensai.data.models.ModelRepository.importModel]
         * failure. Total over every throwable that *is* a failure — with one exception, in both
         * senses of the word.
         *
         * A [CancellationException] is re-thrown rather than classified (TEN-53). It would
         * otherwise land on [Unknown] and put "the import failed for an unknown reason" in front
         * of a user who cancelled the import themselves by leaving the screen — and worse, having
         * turned the cancellation into a return value, this would have swallowed it, leaving the
         * calling coroutine to carry on as if it had not been cancelled. Both call sites already
         * catch and re-throw it before reaching here; this is the backstop for the third one that
         * forgets, since a classifier taking `Throwable` is exactly the shape that invites it.
         */
        fun of(throwable: Throwable): ImportFailure = when (throwable) {
            is CancellationException -> throw throwable
            is DuplicateModelException -> AlreadyImported(throwable.fileName)
            is InsufficientStorageException ->
                NotEnoughSpace(neededBytes = throwable.neededBytes, freeBytes = throwable.freeBytes)
            is ModelMetadataException -> NotAModelFile(throwable.fileName)
            is ModelImportException -> CopyInterrupted(throwable.fileName)
            else -> Unknown
        }
    }
}
