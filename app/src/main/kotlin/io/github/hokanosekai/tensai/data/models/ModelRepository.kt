package io.github.hokanosekai.tensai.data.models

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import io.github.hokanosekai.tensai.engine.GgufInfo
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** A model with the same source name and size has already been imported. */
class DuplicateModelException(message: String) : Exception(message)

/** The source file could not be copied into app storage. */
class ModelImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The copied file's GGUF header could not be read — likely not a valid GGUF file. */
class ModelMetadataException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Not enough free space on device storage to hold the model being imported. */
class InsufficientStorageException(message: String) : Exception(message)

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
 * @param currentLoadedModelName the display name of whatever model is currently loaded in the
 *   queue, or `null` if none is — used by [deleteModel]'s in-use guard. Note:
 *   `LoadedModelInfo` (what `QueueState.ModelReady` carries) has no file path, only a name, so this
 *   guard compares by [ModelEntity.displayName]. Two distinct imported files that happen to share a
 *   display name would produce a false positive; harmless in V1 (blocks a delete that should have
 *   been allowed) rather than the reverse (deleting a file still in use).
 */
class ModelRepository(
    private val context: Context,
    private val modelDao: ModelDao,
    private val readMetadata: suspend (path: String) -> GgufInfo,
    private val currentLoadedModelName: () -> String?,
) {

    /** All imported models, most recently imported first. */
    val models: Flow<List<ModelEntity>> = modelDao.observeAll()

    private val modelsDir: File
        get() = File(context.filesDir, "models").apply { mkdirs() }

    fun storageUsage(): Flow<StorageUsage> = models.map { list ->
        val stat = StatFs(modelsDir.absolutePath)
        StorageUsage(
            modelsBytes = list.sumOf { it.sizeBytes },
            freeBytes = stat.availableBytes,
            totalBytes = stat.totalBytes,
        )
    }

    /**
     * Copies [uri] into app storage (streaming, so [onProgress] reports real bytes-copied
     * progress for large multi-GB files), reads its GGUF metadata, and persists a [ModelEntity].
     *
     * On any failure the partial copy is deleted before the exception propagates — see
     * [DuplicateModelException], [InsufficientStorageException], [ModelImportException] and
     * [ModelMetadataException].
     */
    suspend fun importModel(uri: Uri, onProgress: (Float) -> Unit): ModelEntity = withContext(Dispatchers.IO) {
        val sourceName = queryDisplayName(uri) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "model.gguf"
        val sourceSize = querySize(uri)

        if (sourceSize != null) {
            val duplicate = models.first().any { it.fileName == sourceName && it.sizeBytes == sourceSize }
            if (duplicate) {
                throw DuplicateModelException("\"$sourceName\" is already imported")
            }

            val freeBytes = StatFs(modelsDir.absolutePath).availableBytes
            if (freeBytes < sourceSize) {
                throw InsufficientStorageException(
                    "Not enough free storage to import \"$sourceName\" " +
                        "(${formatBytes(sourceSize)} needed, ${formatBytes(freeBytes)} free)",
                )
            }
        }

        val destFile = uniqueDestFile(sourceName)
        val copiedBytes = try {
            copyToFile(uri, destFile, sourceSize, onProgress)
        } catch (e: IOException) {
            destFile.delete()
            throw ModelImportException("Failed to copy \"$sourceName\": ${e.message}", e)
        }

        // Covers content providers that don't report a size upfront (checked pre-copy above).
        if (sourceSize == null) {
            val duplicate = models.first().any { it.fileName == sourceName && it.sizeBytes == copiedBytes }
            if (duplicate) {
                destFile.delete()
                throw DuplicateModelException("\"$sourceName\" is already imported")
            }
        }

        val info = try {
            readMetadata(destFile.absolutePath)
        } catch (e: Exception) {
            destFile.delete()
            throw ModelMetadataException(
                "Could not read GGUF metadata for \"$sourceName\" — is it a valid GGUF file?",
                e,
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
            supportsThinking = info.supportsThinking,
            importedAt = System.currentTimeMillis(),
        )
        modelDao.insert(entity)
        entity
    }

    /**
     * Deletes [id]'s file and row. Throws [ModelInUseException] (without deleting anything) if
     * the model is currently loaded — see this class's KDoc for the by-name comparison caveat.
     * No-op if [id] doesn't exist.
     */
    suspend fun deleteModel(id: String) = withContext(Dispatchers.IO) {
        val entity = modelDao.getById(id) ?: return@withContext
        if (currentLoadedModelName() == entity.displayName) {
            throw ModelInUseException("Cannot delete \"${entity.displayName}\" while it is loaded")
        }
        File(entity.filePath).delete()
        modelDao.delete(entity)
    }

    private fun uniqueDestFile(sourceName: String): File {
        var candidate = File(modelsDir, sourceName)
        if (!candidate.exists()) return candidate
        val base = sourceName.removeSuffix(".gguf")
        var suffix = 1
        do {
            candidate = File(modelsDir, "$base-$suffix.gguf")
            suffix++
        } while (candidate.exists())
        return candidate
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

    private fun copyToFile(uri: Uri, destFile: File, totalBytes: Long?, onProgress: (Float) -> Unit): Long {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open an input stream for $uri")
        var copied = 0L
        input.use { source ->
            destFile.outputStream().use { sink ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
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

    private fun formatBytes(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        if (gb >= 1.0) return "%.1f GB".format(gb)
        val mb = bytes / (1024.0 * 1024.0)
        return "%.0f MB".format(mb)
    }

    private companion object {
        const val COPY_BUFFER_SIZE = 256 * 1024
    }
}
