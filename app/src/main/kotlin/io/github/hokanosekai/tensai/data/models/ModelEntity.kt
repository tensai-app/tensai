package io.github.hokanosekai.tensai.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A GGUF model imported into the app's private storage, persisted so the Models list survives
 * process death. Mirrors the useful fields of [io.github.hokanosekai.tensai.engine.GgufInfo] plus
 * the bookkeeping needed to manage the file on disk.
 *
 * @param id stable identifier, independent of [fileName] (which can change on re-import
 *   collisions) — used as the nav arg for [io.github.hokanosekai.tensai.ui.navigation.TensaiDestination.ModelDetail].
 * @param displayName human-readable name — the GGUF header's own name when present, otherwise
 *   derived from the imported file's name.
 * @param fileName name of the file as stored under `filesDir/models/` (see [ModelRepository]).
 * @param filePath absolute path to the file on disk.
 * @param sizeBytes size of the file on disk, in bytes.
 * @param embeddingLength embedding (hidden) dimension — see
 *   [io.github.hokanosekai.tensai.engine.GgufInfo.embeddingLength]. Needed alongside
 *   [blockCount] and [contextLength] to estimate KV-cache memory in
 *   [io.github.hokanosekai.tensai.compat.CompatibilityChecker].
 * @param blockCount number of transformer layers — see [io.github.hokanosekai.tensai.engine.GgufInfo.blockCount].
 * @param importedAt epoch millis when this model was imported.
 */
@Entity(tableName = "models")
data class ModelEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val fileName: String,
    val filePath: String,
    val sizeBytes: Long,
    val architecture: String,
    val quantLabel: String,
    val paramCount: Long,
    val contextLength: Long,
    val embeddingLength: Long,
    val blockCount: Long,
    val supportsThinking: Boolean,
    val importedAt: Long,
)
