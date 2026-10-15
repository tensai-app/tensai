package io.github.hokanosekai.tensai.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.hokanosekai.tensai.hf.QuantLabel

/**
 * A GGUF model imported into the app's private storage, persisted so the Models list survives
 * process death. Mirrors the useful fields of [io.github.hokanosekai.tensai.engine.GgufInfo] plus
 * the bookkeeping needed to manage the file on disk.
 *
 * @param id stable identifier, independent of [fileName] (which can change on re-import
 *   collisions) — used as the nav arg for [io.github.hokanosekai.tensai.ui.navigation.TensaiDestination.ModelDetail].
 * @param displayName the GGUF header's own `general.name`, or the imported file's name when it has none.
 *   Stored as is and often a hash or a placeholder: show [shownName], not this.
 * @param fileName name of the file as stored under `filesDir/models/` (see [ModelRepository]).
 * @param filePath absolute path to the file on disk.
 * @param sizeBytes size of the file on disk, in bytes.
 * @param embeddingLength embedding (hidden) dimension — see
 *   [io.github.hokanosekai.tensai.engine.GgufInfo.embeddingLength]. Needed alongside
 *   [blockCount] and [contextLength] to estimate KV-cache memory in
 *   [io.github.hokanosekai.tensai.compat.CompatibilityChecker].
 * @param blockCount number of transformer layers — see [io.github.hokanosekai.tensai.engine.GgufInfo.blockCount].
 * @param importedAt epoch millis when this model was imported.
 * @param kvFullElementsPerToken K+V cache elements per context token across the layers sized by
 *   `n_ctx` — see [io.github.hokanosekai.tensai.engine.GgufInfo.kvFullElementsPerToken]. `0` means
 *   *unknown*, not *none*: rows imported before TEN-18 carry it until
 *   [ModelRepository.backfillKvCacheShape] re-reads their header, and every consumer must route
 *   through [KvCacheSize], which falls back to the old [embeddingLength]-based over-estimate.
 * @param kvSwaElementsPerToken same, for sliding-window layers capped at [slidingWindow] — see
 *   [io.github.hokanosekai.tensai.engine.GgufInfo.kvSwaElementsPerToken].
 * @param slidingWindow sliding-window size in tokens, `0` unless [kvSwaElementsPerToken] is set.
 * @param kvShapeVersion which revision of the native derivation produced the three fields above —
 *   see [io.github.hokanosekai.tensai.engine.GgufInfo.kvShapeVersion]. Stored, never interpreted:
 *   [KvCacheShapeBackfill] re-reads the header of every row whose value *differs* from what the
 *   native library currently reports, which is the only way a row written by a superseded formula
 *   can be told apart from a correct one — the numbers themselves look equally plausible. `0` is
 *   the pre-TEN-72 "written by an unrecorded formula" default (see
 *   [TensaiDbMigrations.MIGRATION_7_8]); the library never reports it.
 * @param sourceRepo Hugging Face repo (`author/name`) a downloaded model came from, null for an
 *   import from the phone (TEN-142). The `source*` columns and [downloadedAt] are one unit, see [origin].
 * @param sourceFile the file's path in that repo, folder included (what `resolve/` takes).
 * @param sourceQuant the quant label the repo listed it under.
 * @param sourceSha256 the hash the Hub announced for it, null when it did not.
 * @param sourceCommit the repo commit the download was pinned to, null when it fell back to `main`.
 * @param downloadedAt epoch millis when the download was registered as this model.
 * @param supportsTools the chat template takes tools, see [ChatTemplateCapabilities] (TEN-154). `false` is also
 *   what rows from before v13 hold until [KvCacheShapeBackfill] re-reads their header.
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
    val kvFullElementsPerToken: Long = 0,
    val kvSwaElementsPerToken: Long = 0,
    val slidingWindow: Long = 0,
    val kvShapeVersion: Long = 0,
    val sourceRepo: String? = null,
    val sourceFile: String? = null,
    val sourceQuant: String? = null,
    val sourceSha256: String? = null,
    val sourceCommit: String? = null,
    val downloadedAt: Long? = null,
    val supportsTools: Boolean = false,
) {
    /** Where this model was downloaded from, null for an import (or a row from before TEN-142). */
    val origin: ModelOrigin?
        get() {
            val repo = sourceRepo ?: return null
            val file = sourceFile ?: return null
            return ModelOrigin(repo, file, sourceQuant.orEmpty(), sourceSha256, sourceCommit, downloadedAt ?: importedAt)
        }

    /**
     * The quant label to show (TEN-148): what the user picked on the Hub ([sourceQuant]) for a
     * download, the GGUF `general.file_type` ([quantLabel]) for an import. The header cannot tell
     * an uploader variant (`Q4_K_L`, `UD-Q4_K_XL`) from its base type, so it reads `Q4_K_M` for both.
     * A blank or [QuantLabel.UNKNOWN] source quant is no better than the header, so it is skipped.
     */
    val displayQuant: String
        get() = sourceQuant?.takeIf { it.isNotBlank() && it != QuantLabel.UNKNOWN } ?: quantLabel

    /**
     * The name to show (TEN-147), as the stored [displayName] is the GGUF `general.name`, which is
     * often a hash, a placeholder or a typo. See [ModelNames.of]. Display only: two quants of one
     * repo share it, the [displayQuant] badge tells them apart.
     */
    val shownName: String
        get() = ModelNames.of(sourceRepo, displayName, fileName)

    /** This row with [origin] written into its `source*` columns (null clears them). */
    fun withOrigin(origin: ModelOrigin?): ModelEntity = copy(
        sourceRepo = origin?.repo,
        sourceFile = origin?.file,
        sourceQuant = origin?.quant,
        sourceSha256 = origin?.sha256,
        sourceCommit = origin?.commit,
        downloadedAt = origin?.downloadedAt,
    )
}

/**
 * Where a downloaded model came from: enough to show "hf.co/<repo> · <quant>" and to download the
 * very same file again. Built from the finished download's row, see
 * [io.github.hokanosekai.tensai.download.DownloadEntity.toOrigin].
 */
data class ModelOrigin(
    val repo: String,
    val file: String,
    val quant: String,
    val sha256: String?,
    val commit: String?,
    val downloadedAt: Long,
)

/** Derivation of the name shown for a model (TEN-147), kept apart from [ModelEntity] to test it on bare strings. */
object ModelNames {
    private val GGUF_SUFFIX = Regex("[-_.]GGUF$", RegexOption.IGNORE_CASE)
    private val HEX = Regex("[0-9a-fA-F]{32,}")
    private val UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private const val MIN_LENGTH = 3
    private val PLACEHOLDERS = setOf(
        "fused", "model", "merged", "unknown", "llama", "gguf", "untitled", "ggml", "converted", "default", "none", "test",
        "new model",
    )

    /**
     * A download shows its repo's name without the owner and without a trailing `-GGUF`: the repo
     * is what the user picked, the header is whatever the uploader's converter wrote. An import
     * shows [generalName] unless [isUsable] refuses it, then the file name minus its quant label.
     */
    fun of(sourceRepo: String?, generalName: String, fileName: String): String {
        val repoName = sourceRepo?.substringAfterLast('/')?.replace(GGUF_SUFFIX, "")?.trim()
        if (!repoName.isNullOrEmpty()) return repoName
        if (isUsable(generalName)) return generalName.trim()
        return QuantLabel.stripFromName(fileName).ifBlank { generalName }
    }

    /** False for a blank, too short, hash-like (long hex run, UUID) or generic placeholder `general.name`. */
    fun isUsable(name: String): Boolean {
        val n = name.trim()
        if (n.length < MIN_LENGTH) return false
        if (n.lowercase() in PLACEHOLDERS) return false
        return !HEX.matches(n) && !UUID.matches(n)
    }
}
