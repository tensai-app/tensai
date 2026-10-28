package io.github.tensaiapp.data.models

import io.github.tensaiapp.engine.GgufInfo
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The half of [ModelRepository.importModel] that starts once the source `Uri` has been resolved
 * to a name, a size and a way to open it: copy into a part file, reserve a destination, rename,
 * read the header, insert the row, and undo the files if any of that goes wrong.
 *
 * Split out of [ModelRepository] by TEN-53 so it can be tested. The three things this class is
 * careful about (a concurrent import picking the same destination, a partial copy surviving a
 * failure, a cancellation arriving as something else) are pure logic that needs neither a device
 * nor a real GGUF — but [ModelRepository] cannot be built off one, since it holds a `Context` and
 * its entry point takes a `Uri`, and neither has a JVM-side constructor. Nothing below touches
 * the Android framework, so `ModelImporterTest` can drive all three to a conclusion on the JVM.
 *
 * @param modelsDir directory the copies live in — see [ModelRepository]'s `storageRoot` KDoc for
 *   why a test must never point this at a real `filesDir`.
 * @param models the current library, read to reject a re-import of something already there.
 */
/**
 * What registering a model produced: the stored [model], and the [previous] row it relinked (a
 * "File missing" model whose file this one replaced), null for a brand new row. A caller that has
 * to undo the registration needs the difference: a new row is deleted, a relinked one goes back to
 * what it was, because its id carries chat sessions.
 */
data class RegisteredModel(val model: ModelEntity, val previous: ModelEntity?)

internal class ModelImporter(
    private val modelsDir: File,
    private val modelDao: ModelDao,
    private val models: Flow<List<ModelEntity>>,
    private val readMetadata: suspend (path: String) -> GgufInfo,
    private val fileExists: (path: String) -> Boolean = { File(it).exists() },
    private val log: (String) -> Unit = {},
) {
    private val commitMutex = Mutex()

    // Names under [modelsDir] an import or adopt of this process is writing and has not committed
    // yet (TEN-176): [sweepOrphans] leaves them alone. Guarded by itself, never held across more
    // I/O than one create or one delete, so it is safe to release from a cancelled coroutine.
    private val claimed = mutableSetOf<String>()

    /**
     * Copies the source into [modelsDir] and persists a [ModelEntity] for it.
     *
     * @param sourceName the source file's own name, used for the destination and for dedup.
     * @param sourceSize the source's size if its content provider reports one, `null` otherwise —
     *   which is what moves both the dedup check and the free-space check around (see below).
     * @param freeBytes free space on the volume, read lazily because it is only consulted when
     *   [sourceSize] is known; there is nothing to compare it against otherwise.
     * @param openSource opens the source for reading. Called once, closed by this function.
     *
     * Every abnormal exit deletes the copy before it propagates, cancellation included, and a
     * cancellation propagates *as* a cancellation — never repackaged as a copy or metadata
     * failure, which would leave the coroutine that was cancelled believing it merely failed.
     */
    suspend fun import(
        sourceName: String,
        sourceSize: Long?,
        freeBytes: () -> Long,
        openSource: () -> InputStream,
        onProgress: (Float) -> Unit,
    ): ModelEntity {
        var relink: ModelEntity? = null
        if (sourceSize != null) {
            when (val match = match(sourceName, sourceSize)) {
                is Match.Duplicate -> throw DuplicateModelException(sourceName)
                is Match.Relink -> relink = match.row
                Match.None -> Unit
            }
            val free = freeBytes()
            if (free < sourceSize) {
                throw InsufficientStorageException(sourceName, neededBytes = sourceSize, freeBytes = free)
            }
        }

        // The copy goes to a part file of its own and becomes a `.gguf` only by a rename once every
        // byte is in (TEN-176). A process killed mid-copy (swiped from recents, low memory) runs no
        // cleanup at all, so what it leaves has to be something [sweepOrphans] knows is unfinished,
        // not a multi-GB file named like a model.
        modelsDir.mkdirs()
        val partFile = File(modelsDir, "${UUID.randomUUID()}$IMPORT_SUFFIX").also { claim(it) }
        var destFile: File? = null

        // From here on *every* way out other than the successful one has to remove what is on
        // disk: hence one catch on Throwable around the lot rather than a `delete()` repeated at
        // each throw site, which is how the old code let a SecurityException (SAF permission
        // revoked mid-copy) strand a multi-GB partial file with no row pointing at it and no way
        // for the user to find it (TEN-53). The catches inside classify; this one cleans up, and
        // re-throws whatever it caught untouched.
        var committed = false
        try {
            val copiedBytes = try {
                copyInto(partFile, sourceSize, openSource, onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                throw ModelImportException(sourceName, e)
            }

            // Covers content providers that don't report a size upfront (checked pre-copy above).
            if (sourceSize == null) {
                when (val match = match(sourceName, copiedBytes)) {
                    is Match.Duplicate -> throw DuplicateModelException(sourceName)
                    is Match.Relink -> relink = match.row
                    Match.None -> Unit
                }
            }

            val dest = try {
                reserveDestFile(sourceName)
            } catch (e: IOException) {
                throw ModelImportException(sourceName, e)
            }
            destFile = dest
            moveOnto(partFile, dest, sourceName)
            return commit(sourceName, dest, copiedBytes, relink, origin = null) { committed = true }.model
        } catch (e: Throwable) {
            if (!committed) {
                partFile.delete()
                destFile?.delete()
            }
            throw e
        } finally {
            release(partFile)
            destFile?.let(::release)
        }
    }

    /**
     * Registers a file that was *downloaded* (TEN-141) through the same pipeline as an import:
     * [partFile] is renamed atomically to a unique name under [modelsDir], its GGUF header is read
     * and a [ModelEntity] is stored. No copy, so a multi-GB download costs no second pass over the
     * disk (nor double the space).
     *
     * Same guarantees as [import]: a duplicate (same name and size, file on disk) is refused before
     * anything moves ([DuplicateModelException], [partFile] untouched), and any failure after the
     * rename deletes the destination. A row whose file went missing is relinked, so re-downloading
     * a "File missing" model keeps its id and its sessions.
     *
     * @param sourceName the name to give it (the Hub's file name without directories).
     * @param origin where it came from (TEN-142), stored on the row; a relinked row has its origin
     *   overwritten by it.
     */
    suspend fun adopt(partFile: File, sourceName: String, origin: ModelOrigin? = null): RegisteredModel {
        val size = partFile.length()
        var relink: ModelEntity? = null
        when (val match = match(sourceName, size)) {
            is Match.Duplicate -> throw DuplicateModelException(sourceName)
            is Match.Relink -> relink = match.row
            Match.None -> Unit
        }
        val destFile = try {
            reserveDestFile(sourceName)
        } catch (e: IOException) {
            throw ModelImportException(sourceName, e)
        }
        // A death between the rename and the insert leaves a `.gguf` no row references: the next
        // start's [sweepOrphans] deletes it and the download, back in the queue without its part,
        // starts over. Inserting first instead would leave a row pointing at an empty reservation,
        // which the next attempt takes for a duplicate already on disk.
        var committed = false
        try {
            moveOnto(partFile, destFile, sourceName)
            return commit(sourceName, destFile, size, relink, origin) { committed = true }
        } catch (e: Throwable) {
            if (!committed) restorePartOrDelete(e, destFile, partFile)
            throw e
        } finally {
            release(destFile)
        }
    }

    /**
     * Deletes the files under [modelsDir] that a dead process left behind (TEN-176): `.gguf` files
     * no [ModelEntity.filePath] references and unfinished import parts. Called at start; cheap and
     * idempotent, so safe to call again.
     *
     * Conservative on purpose, since a wrong answer here deletes a multi-GB model: only regular
     * files (a symlink is never followed) directly in [modelsDir], only the two kinds this class
     * writes (download `.part` files are `DownloadManager.reconcile`'s), never one claimed by an
     * import or adopt running now. A failed library read throws before anything is deleted.
     *
     * A file is referenced when a row's [ModelEntity.filePath] has its *name*, whatever the
     * directory: names under [modelsDir] are unique ([reserveDestFile]), and rows restored onto
     * another data directory (a transfer onto a work profile, `/data/user/10` while the rows say
     * `/data/user/0`) must keep their files, which a full-path match would delete. An empty library
     * deletes no `.gguf` at all: that is what a destroyed table (downgrade, broken migration) looks
     * like, and those files are better kept than lost.
     *
     * Under [commitMutex], so no row can be inserted between the read and the deletes: a file not
     * referenced by the rows read here and not claimed is not about to be.
     */
    suspend fun sweepOrphans() = commitMutex.withLock {
        val candidates = modelsDir.listFiles().orEmpty().filter { file ->
            (file.name.endsWith(".gguf") || file.name.endsWith(IMPORT_SUFFIX)) &&
                Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
        }
        if (candidates.isEmpty()) return@withLock
        val rows = models.first()
        val referenced = rows.mapTo(HashSet()) { File(it.filePath).name }
        candidates.filter { it.name !in referenced }.forEach { file ->
            val size = file.length()
            if (rows.isEmpty() && !file.name.endsWith(IMPORT_SUFFIX)) {
                log("kept unreferenced model file ${file.name} ($size bytes): the library is empty")
                return@forEach
            }
            synchronized(claimed) {
                if (file.name !in claimed && file.delete()) log("deleted orphan model file ${file.name} ($size bytes)")
            }
        }
    }

    /** Same directory, same volume: a rename, atomic, replacing the empty reservation at [dest]. */
    private fun moveOnto(source: File, dest: File, sourceName: String) {
        try {
            Files.move(source.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            throw ModelImportException(sourceName, e)
        }
    }

    private fun claim(file: File) = synchronized(claimed) { claimed += file.name }

    private fun release(file: File) = synchronized(claimed) { claimed -= file.name }

    /**
     * After a failed [adopt]: a file that is not a model (bad header) or whose rename failed is
     * deleted. Any other failure (a database error on insert, say) is not the file's fault, so
     * it goes back to [partFile]: the caller keeps a finished download it can register again.
     */
    private fun restorePartOrDelete(cause: Throwable, destFile: File, partFile: File) {
        val fileIsAtFault = cause is ModelMetadataException || cause is ModelImportException
        if (!fileIsAtFault && destFile.length() > 0) {
            try {
                Files.move(destFile.toPath(), partFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                return
            } catch (_: IOException) {
            }
        }
        destFile.delete()
    }

    /** Header, validity check, row: everything after the bytes are in place at [destFile]. */
    private suspend fun commit(
        sourceName: String,
        destFile: File,
        copiedBytes: Long,
        relink: ModelEntity?,
        origin: ModelOrigin?,
        markCommitted: () -> Unit,
    ): RegisteredModel {
        val info = try {
            readMetadata(destFile.absolutePath)
        } catch (e: CancellationException) {
            // Before the CancellationException clause existed, leaving the screen mid-import
            // came back as "this is probably not a GGUF file": the catch below swallowed the
            // cancellation and answered with a verdict on the file instead (TEN-53).
            throw e
        } catch (e: Exception) {
            throw ModelMetadataException(sourceName, e)
        }

        // A header that parses is not a model that loads (TEN-98). A GGUF with zero tensors
        // reads as paramCount 0, and llama.cpp needs `<arch>.block_count` for every LLM arch,
        // so blockCount 0 means it can never load either, yet the old code imported it and
        // the Models screen called it "Runs well" (the TEN-91 fixture, 260 bytes). Same
        // classification as an unparseable header, so the same message and the same cleanup.
        // Real models clear it easily: Gemma-4 E2B 4.6e9 params / 35 blocks, Phi-4-mini
        // 3.8e9 / 32, Qwen3-1.7B 1.7e9 / 28.
        if (info.paramCount <= 0 || info.blockCount <= 0) {
            throw ModelMetadataException(
                sourceName,
                IllegalStateException(
                    "GGUF has no usable model: paramCount=${info.paramCount}, blockCount=${info.blockCount}",
                ),
            )
        }

        val entity = ModelEntity(
            id = UUID.randomUUID().toString(),
            displayName = info.name.takeIf { it.isNotBlank() } ?: sourceName.removeSuffix(".gguf"),
            fileName = sourceName,
            filePath = destFile.absolutePath,
            sizeBytes = copiedBytes,
            architecture = info.architecture,
            quantLabel = info.quantLabel,
            paramCount = info.paramCount,
            contextLength = info.contextLength,
            embeddingLength = info.embeddingLength,
            blockCount = info.blockCount,
            supportsThinking = ChatTemplateCapabilities.supportsThinking(info),
            supportsTools = ChatTemplateCapabilities.supportsTools(info),
            hasChatTemplate = ChatTemplateCapabilities.hasChatTemplate(info),
            importedAt = System.currentTimeMillis(),
            kvFullElementsPerToken = info.kvFullElementsPerToken,
            kvSwaElementsPerToken = info.kvSwaElementsPerToken,
            slidingWindow = info.slidingWindow,
            // Stamped at import so a model that arrives current is never re-read by
            // KvCacheShapeBackfill: the row already says which formula produced the three
            // numbers above it. Omitting this would default to 0 and hand every new import
            // straight back to the backfill on the next start.
            kvShapeVersion = info.kvShapeVersion,
        ).withOrigin(origin)
        // The commit. NonCancellable so that leaving the screen cannot land between "the row
        // is written" and "the copy is kept": once this block starts it runs to the end, and
        // [committed] stops the catch below from deleting a file a row now points at.
        val stored: RegisteredModel = withContext(NonCancellable) {
            commitMutex.withLock {
                // A re-import of a file whose row survived it (same name and size, file gone)
                // relinks: the row keeps its id, so sessions saved against it stay attached.
                // Re-read under the lock: two concurrent relinks of one row would otherwise
                // both write it and leave the loser's copy with no row. The second one finds
                // the first one's file on disk and is refused as the duplicate it now is.
                val target = relink?.let { modelDao.getById(it.id) }
                if (target != null && target.filePath != destFile.absolutePath && fileExists(target.filePath)) {
                    throw DuplicateModelException(sourceName)
                }
                val toStore = if (target == null) {
                    entity
                } else {
                    entity.copy(id = target.id, displayName = target.displayName, importedAt = target.importedAt, contextOverride = target.contextOverride, chatTemplateOverride = target.chatTemplateOverride)
                        // A re-import of the same file keeps where it was first downloaded from.
                        .let { if (origin == null) it.withOrigin(target.origin) else it }
                }
                // One statement: REPLACE on the primary key (see ModelDao.insert). A failure
                // leaves the old row exactly as it was.
                modelDao.insert(toStore)
                markCommitted()
                RegisteredModel(toStore, previous = target)
            }
        }
        return stored
    }

    private sealed interface Match {
        data object None : Match

        /** A row with this name and size whose file is on disk: nothing to import. */
        data object Duplicate : Match

        /** A row with this name and size whose file is gone: the import re-links it. */
        data class Relink(val row: ModelEntity) : Match
    }

    private suspend fun match(fileName: String, sizeBytes: Long): Match {
        val same = models.first().filter { it.fileName == fileName && it.sizeBytes == sizeBytes }
        if (same.any { fileExists(it.filePath) }) return Match.Duplicate
        return same.firstOrNull()?.let { Match.Relink(it) } ?: Match.None
    }

    /**
     * Creates and returns an unused file under [modelsDir] — [sourceName] if it is free, then
     * `name-1.gguf`, `name-2.gguf`, and so on.
     *
     * The file is *created* here, not merely named. [File.createNewFile] tests and creates in one
     * atomic step (`O_CREAT | O_EXCL`), which is the whole point: the previous version checked
     * [File.exists] and only opened the stream minutes later, at the start of the copy, so two
     * imports of the same source name — a double-tapped import button, or one coroutine per file
     * from a multi-select — could both walk away holding `model.gguf` and then overwrite each
     * other's bytes (TEN-53).
     *
     * A mutex was the other option on the table and it is the weaker one here. To close the
     * window it would have to be held across the copy, which is minutes on a multi-GB model, and
     * an import that blocks every other import for that long is a worse bug than the one being
     * fixed. Releasing it after picking a name instead fixes nothing, because a name that nothing
     * has created is still free for the next caller to pick the moment the lock is dropped —
     * reserving means creating either way. And a mutex only binds this process: it would not stop
     * a second `ModelRepository` instance, or anything else writing into `models/`, from landing
     * on the same name. `SessionRepository.commitMutex` (TEN-38) is a real precedent for a
     * different problem: it guards a check against an in-memory epoch counter, which no
     * filesystem primitive can make atomic, and it deliberately keeps the long engine dump
     * outside the lock. There is no in-memory state to guard here — only a file.
     *
     * What it costs is an empty file left behind if a later step fails, which is exactly what
     * [import]'s cleanup removes; the reservation is not a special case there, it is the same
     * `destFile.delete()` that clears a half-written copy.
     *
     * The file is claimed in the same step it is created (TEN-176), so [sweepOrphans] never sees it
     * unclaimed before its row exists.
     */
    private fun reserveDestFile(sourceName: String): File {
        modelsDir.mkdirs()
        val base = sourceName.removeSuffix(".gguf")
        var candidate = File(modelsDir, sourceName)
        var suffix = 0
        while (!synchronized(claimed) { candidate.createNewFile().also { if (it) claimed += candidate.name } }) {
            suffix++
            candidate = File(modelsDir, "$base-$suffix.gguf")
        }
        return candidate
    }

    /** Streams the source into [destFile], reporting real bytes-copied progress. */
    private suspend fun copyInto(
        destFile: File,
        totalBytes: Long?,
        openSource: () -> InputStream,
        onProgress: (Float) -> Unit,
    ): Long {
        var copied = 0L
        openSource().use { source ->
            destFile.outputStream().use { sink ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    // Copying several GB is minutes of blocking reads with no suspension point of
                    // its own, so without this check a coroutine cancelled the moment the user
                    // left the screen would go on copying to the very end before anything noticed
                    // — and the cancellation would only surface at readMetadata, after the cost
                    // had already been paid. One volatile read per 256 KB buys prompt cancellation.
                    coroutineContext.ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                    copied += read
                    if (totalBytes != null && totalBytes > 0) {
                        onProgress((copied.toFloat() / totalBytes).coerceIn(0f, 1f))
                    }
                }
            }
        }
        onProgress(1f)
        return copied
    }

    companion object {
        private const val COPY_BUFFER_SIZE = 256 * 1024

        /** An import being copied. Not `.part`: those are download rows', swept by `DownloadManager.reconcile`. */
        const val IMPORT_SUFFIX = ".import"
    }
}
