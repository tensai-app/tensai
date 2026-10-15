package io.github.hokanosekai.tensai.data.models

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import io.github.hokanosekai.tensai.engine.GgufInfo
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Every way [ModelRepository.importModel] can refuse or abandon an import.
 *
 * These carry **structured fields**, and their [Throwable.message] exists for a stack trace, not
 * for a screen (TEN-39). The UI classifies them into an
 * [io.github.hokanosekai.tensai.ui.screens.models.ImportFailure] and writes its own sentence from
 * `strings.xml`; nothing renders `e.message`. That rule is what keeps a `java.io.IOException`'s own
 * words — which is exactly what [ModelImportException] used to splice into its message — out of an
 * error card the user is meant to act on.
 */
sealed class ModelImportFailure(message: String, cause: Throwable? = null) : Exception(message, cause)

/** A model with the same source name and size has already been imported. */
class DuplicateModelException(val fileName: String) :
    ModelImportFailure("\"$fileName\" is already imported")

/** The source file could not be copied into app storage — [cause] is the underlying I/O failure. */
class ModelImportException(val fileName: String, cause: Throwable? = null) :
    ModelImportFailure("failed to copy \"$fileName\"", cause)

/** The copied file's GGUF header could not be read — likely not a valid GGUF file. */
class ModelMetadataException(val fileName: String, cause: Throwable? = null) :
    ModelImportFailure("could not read GGUF metadata for \"$fileName\"", cause)

/** Not enough free space on device storage to hold the model being imported. */
class InsufficientStorageException(
    val fileName: String,
    val neededBytes: Long,
    val freeBytes: Long,
) : ModelImportFailure("\"$fileName\" needs $neededBytes bytes, $freeBytes free")

/** [ModelRepository.deleteModel] was called for a model that is currently loaded. */
class ModelInUseException(message: String) : Exception(message)

/** Disk usage snapshot for the Models screen's storage indicator. */
data class StorageUsage(val modelsBytes: Long, val freeBytes: Long, val totalBytes: Long)

/**
 * Owns imported GGUF files under `filesDir/models/` and their [ModelEntity] rows.
 *
 * @param readMetadata reads a GGUF header without loading model weights — backed by
 *   [io.github.hokanosekai.tensai.engine.LlamaKtEngine.readMetadata] on the same engine instance
 *   [io.github.hokanosekai.tensai.runtime.RuntimeGraph.inferenceQueue] runs on (see
 *   `RuntimeGraph`'s KDoc for why that sharing is safe but not free of contention).
 * @param kvShapeVersion the revision of the native KV-shape derivation the engine currently
 *   carries — see [KvCacheShapeBackfill] for why this is asked of the library rather than written
 *   down here.
 * @param currentLoadedModelId the [ModelEntity.id] of whatever model is currently loaded in the
 *   queue, or `null` if none is — used by [deleteModel]'s in-use guard. Wire this to
 *   [io.github.hokanosekai.tensai.runtime.residentModelId] and nothing else: a *generating* model
 *   is every bit as resident as an idle one, and a derivation matching `QueueState.ModelReady`
 *   alone leaves this guard blind for the whole duration of a reply, which is how TEN-76 could
 *   unlink a multi-gigabyte `.gguf` llama.cpp still had mmapped. This compared by
 *   [ModelEntity.displayName] until TEN-80, for want of anything better on `LoadedModelInfo`, and
 *   the false positive that bought was not as harmless as it looked: two quantisations of the same
 *   model share a GGUF display name, so importing a Q4 and a Q8 to compare them — the ordinary
 *   reason to hold two — made the loaded one lock the other against deletion, with a message
 *   naming a model the user could see was not the one they meant.
 */
class ModelRepository(
    private val context: Context,
    private val modelDao: ModelDao,
    private val readMetadata: suspend (path: String) -> GgufInfo,
    private val kvShapeVersion: () -> Long,
    private val currentLoadedModelId: () -> String?,
    // Root under which imported GGUFs are stored. Defaults to the app's real filesDir; tests MUST
    // pass a temp dir — this class deletes/overwrites files under `models/`, and pointing it at the
    // real filesDir (as an instrumented test's targetContext does) makes those deletes destroy the
    // user's actually-imported models. See TEN-48 (a test tearDown wiped real models this way).
    storageRoot: File = context.filesDir,
) {

    /** All imported models, most recently imported first. */
    val models: Flow<List<ModelEntity>> = modelDao.observeAll()

    private val modelsDir: File = File(storageRoot, "models")
        get() = field.apply { mkdirs() }

    // Everything importModel does once the Uri has been resolved to a name/size/stream, and the
    // only place the import's concurrency, cleanup and cancellation rules live — see
    // ModelImporter's KDoc for why they are over there and not here. Lazy so that merely
    // constructing a repository does not create `models/` on disk.
    private val importer: ModelImporter by lazy {
        ModelImporter(
            modelsDir = modelsDir,
            modelDao = modelDao,
            models = models,
            readMetadata = readMetadata,
        )
    }

    /**
     * Disk figures for the models volume, re-read on every [models] emission, so an import or a
     * delete moves the free-space reading with it.
     *
     * [flowOn] because the `map` is not the cheap projection it looks like: [StatFs] is a
     * synchronous `statvfs`, and reading [modelsDir] runs its `mkdirs()`. Both are disk calls, and
     * both collectors (`ModelsScreen` via `collectAsState`, so on the composition's context, and
     * [io.github.hokanosekai.tensai.ui.screens.settings.SettingsViewModel]) would otherwise pay
     * for them on the main thread. No StrictMode policy is installed in this app, so nothing would
     * have reported it — the symptom would only ever have been a stall on a slow volume.
     */
    fun storageUsage(): Flow<StorageUsage> = models.map { list ->
        val stat = StatFs(modelsDir.absolutePath)
        StorageUsage(
            modelsBytes = list.sumOf { it.sizeBytes },
            freeBytes = stat.availableBytes,
            totalBytes = stat.totalBytes,
        )
    }.flowOn(Dispatchers.IO)

    /**
     * Copies [uri] into app storage (streaming, so [onProgress] reports real bytes-copied
     * progress for large multi-GB files), reads its GGUF metadata, and persists a [ModelEntity].
     *
     * This end only resolves [uri] — a name, a size if the content provider reports one, and a
     * stream — and hands those to [ModelImporter], which owns the rest and the guarantees that go
     * with it: two concurrent imports of the same source name get two files, any abnormal exit
     * deletes the copy first, and a cancellation stays a cancellation. See
     * [DuplicateModelException], [InsufficientStorageException], [ModelImportException] and
     * [ModelMetadataException] for the ways this refuses.
     */
    suspend fun importModel(uri: Uri, onProgress: (Float) -> Unit): ModelEntity = withContext(Dispatchers.IO) {
        importer.import(
            sourceName = queryDisplayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "model.gguf",
            sourceSize = querySize(uri),
            freeBytes = { StatFs(modelsDir.absolutePath).availableBytes },
            openSource = {
                context.contentResolver.openInputStream(uri)
                    ?: throw IOException("Could not open an input stream for $uri")
            },
            onProgress = onProgress,
        )
    }

    /**
     * Registers a finished download (TEN-141) through the same pipeline as [importModel]: [part] is
     * renamed to a unique file under `models/`, its header is read, a [ModelEntity] is stored. See
     * [ModelImporter.adopt] for what it refuses and what it cleans up.
     *
     * @param fileName the Hub's file name; any directory part is dropped.
     * @param origin repo, file, quant, hash and commit the download came from (TEN-142), stored on the
     *   row (a relinked row has them overwritten), null stores none.
     */
    suspend fun registerDownload(part: File, fileName: String, origin: ModelOrigin? = null): RegisteredModel =
        withContext(Dispatchers.IO) {
            importer.adopt(part, fileName.substringAfterLast('/'), origin)
        }

    /**
     * Undoes [registerDownload] for a download that was cancelled while it was being registered. A
     * new model is deleted with its file. A relinked one (the "File missing" row this download
     * replaced) goes back to its previous state and keeps its id and sessions: only the new file goes.
     */
    suspend fun unregisterDownload(registered: RegisteredModel) = withContext(Dispatchers.IO) {
        val previous = registered.previous
        if (previous == null) {
            deleteModel(registered.model.id)
        } else {
            File(registered.model.filePath).delete()
            modelDao.insert(previous)
        }
    }

    /** Where the in-progress download [downloadId] lives: next to the models, so the final rename is atomic. */
    fun downloadPartFile(downloadId: String): File = File(modelsDir, "$downloadId.part")

    /** Every `*.part` file under `models/`, referenced by a download row or not (see `DownloadManager.reconcile`). */
    fun downloadPartFiles(): List<File> = modelsDir.listFiles { f -> f.name.endsWith(".part") }?.toList().orEmpty()

    /** Free bytes on the volume models live on. */
    fun freeBytes(): Long = StatFs(modelsDir.absolutePath).availableBytes

    // Everything backfillKvCacheShape does once it is on a background thread — over in
    // KvCacheShapeBackfill for the same reason the import logic is in ModelImporter: it is pure
    // logic worth testing, and this class can't be built on the JVM. Lazy to match `importer`,
    // and because it is touched exactly once per process.
    private val kvShapeBackfill: KvCacheShapeBackfill by lazy {
        KvCacheShapeBackfill(
            modelDao = modelDao,
            readMetadata = readMetadata,
            currentShapeVersion = kvShapeVersion,
        )
    }

    /**
     * Re-reads the GGUF header of every model whose stored KV-cache shape was derived by a
     * different revision of the native formula than the one now loaded — models imported before
     * TEN-18 added the columns, and models whose numbers came out of a formula that has since been
     * corrected. Header-only, so it costs milliseconds per model even at several GB, and it
     * selects nothing on the start after the one that fixed them.
     *
     * The alternative was to leave those rows wrong until the user re-imported. Not worth it: the
     * pre-TEN-18 fallback is what pinned Gemma-4 to `n_ctx = 256`, an under-count from the first
     * TEN-18 formula is worse than that (it over-promises context on a device that can't back it),
     * and a re-import means copying gigabytes again to recover data already sitting in the file on
     * disk.
     *
     * See [KvCacheShapeBackfill] for how staleness is decided and why the deciding number comes
     * from the native library rather than from this module.
     */
    suspend fun backfillKvCacheShape() = withContext(Dispatchers.IO) {
        kvShapeBackfill.run()
    }

    /**
     * Deletes [id]'s file and row. Throws [ModelInUseException] (without deleting anything) if
     * the model is currently loaded — by [ModelEntity.id], so it is exactly this file that is
     * refused and not merely one sharing its name (see this class's KDoc). No-op if [id] doesn't
     * exist.
     */
    suspend fun deleteModel(id: String) = withContext(Dispatchers.IO) {
        val entity = modelDao.getById(id) ?: return@withContext
        if (currentLoadedModelId() == entity.id) {
            throw ModelInUseException("Cannot delete \"${entity.shownName}\" while it is loaded")
        }
        File(entity.filePath).delete()
        modelDao.delete(entity)
    }

    private fun queryDisplayName(uri: Uri): String? = context.contentResolver
        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else {
                null
            }
        }

    /** Size [uri] reports, `null` if it does not say. Off the main thread: it queries a content provider. */
    suspend fun uriSize(uri: Uri): Long? = withContext(Dispatchers.IO) { runCatching { querySize(uri) }.getOrNull() }

    /** File name [uri] reports, `null` if it does not say. Off the main thread, like [uriSize]. */
    suspend fun uriDisplayName(uri: Uri): String? =
        withContext(Dispatchers.IO) { runCatching { queryDisplayName(uri) }.getOrNull() }

    private fun querySize(uri: Uri): Long? = context.contentResolver
        .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
            } else {
                null
            }
        }
}
