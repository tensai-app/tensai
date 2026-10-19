package io.github.tensaiapp.download

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import io.github.tensaiapp.data.models.ModelOrigin
import kotlinx.coroutines.flow.Flow

/** Where a download is in its life. Stored by [code] so renaming a constant never rewrites rows. */
enum class DownloadStatus(val code: String) {
    Queued("queued"),
    Downloading("downloading"),
    Paused("paused"),
    Failed("failed"),
    Done("done");

    companion object {
        fun fromCode(code: String): DownloadStatus = entries.firstOrNull { it.code == code } ?: Failed
    }
}

/**
 * Why a download failed, for the UI to map to a message (never render [code]).
 *
 * A network loss is not in here: it is an [java.io.IOException] the worker retries with backoff
 * while the partial file stays on disk. [Network] only appears once the retries ran out.
 */
sealed interface DownloadError {
    val code: String

    /** Refused anonymously and no token set: the repo needs a token (and an accepted licence). */
    data object Gated : DownloadError { override val code = "gated" }

    /** A valid token, still 403: the licence is not accepted on that account. */
    data object LicenseNotAccepted : DownloadError { override val code = "license" }

    /** The configured token was refused (401 to a request that carried it). */
    data object InvalidToken : DownloadError { override val code = "invalid_token" }

    data object NotFound : DownloadError { override val code = "not_found" }

    /** The sha256 of what was downloaded is not the one the Hub announced. File deleted. */
    data object ChecksumMismatch : DownloadError { override val code = "checksum" }

    /** The server sent more bytes than the announced size. File deleted. */
    data object SizeMismatch : DownloadError { override val code = "size" }

    /** The disk filled up. The partial file is kept, [DownloadManager.retry] resumes it. */
    data object DiskFull : DownloadError { override val code = "disk_full" }

    /** Retried until the attempt budget ran out. The partial file is kept. */
    data object Network : DownloadError { override val code = "network" }

    /** A model with this name and size is already imported. */
    data object AlreadyImported : DownloadError { override val code = "already_imported" }

    /** The downloaded file is not a loadable GGUF. */
    data object InvalidModel : DownloadError { override val code = "invalid_model" }

    /** A bug or an unclassified failure; the log has the cause. */
    data object Unknown : DownloadError { override val code = "unknown" }

    /** Any other non-success status. */
    data class Http(val status: Int) : DownloadError { override val code = "http:$status" }

    companion object {
        private val simple = listOf(
            Gated, LicenseNotAccepted, InvalidToken, NotFound, ChecksumMismatch, SizeMismatch, DiskFull, Network, AlreadyImported, InvalidModel, Unknown,
        )

        fun fromCode(code: String?): DownloadError? = when {
            code == null -> null
            code.startsWith("http:") -> Http(code.removePrefix("http:").toIntOrNull() ?: 0)
            else -> simple.firstOrNull { it.code == code } ?: Network
        }
    }
}

/** Thrown by [DownloadEngine] for failures that are final (no retry). */
class DownloadException(val error: DownloadError, message: String? = null) : Exception(message ?: error.code)

/**
 * One row of the download queue.
 *
 * @param queuedAt FIFO key: set at enqueue, and again by resume/retry (a resumed download goes
 *   to the back, behind what was already waiting).
 * @param bytesDone persisted progress, throttled; the `.part` file's own length is the truth on resume.
 * @param modelId the [io.github.tensaiapp.data.models.ModelEntity] it became, once [DownloadStatus.Done].
 * @param commitSha the repo commit the URL is pinned to (`resolve/<sha>/<file>`), null falls back to `main`.
 * @param allowMobile false for a Wi-Fi-only row: the drainer leaves it queued while the network is metered.
 * @param ioRetries consecutive transient network failures; the row fails as [DownloadError.Network] at the limit.
 * @param notBefore epoch ms before which the drainer must not pick this row (backoff), 0 for none.
 */
@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val id: String,
    val repo: String,
    val fileName: String,
    val quant: String,
    val displayName: String,
    val sizeBytes: Long,
    val sha256: String?,
    val status: String,
    val bytesDone: Long,
    val error: String?,
    val queuedAt: Long,
    val modelId: String?,
    val commitSha: String? = null,
    val allowMobile: Boolean = false,
    val ioRetries: Int = 0,
    val notBefore: Long = 0,
)

/** What the rest of the app sees of a [DownloadEntity]. */
data class DownloadItem(
    val id: String,
    val repo: String,
    val fileName: String,
    val quant: String,
    val displayName: String,
    val sizeBytes: Long,
    val sha256: String?,
    val status: DownloadStatus,
    val bytesDone: Long,
    val error: DownloadError?,
    val modelId: String?,
) {
    val fraction: Float get() = if (sizeBytes > 0) (bytesDone.toFloat() / sizeBytes).coerceIn(0f, 1f) else 0f
}

internal fun DownloadEntity.toItem() = DownloadItem(
    id = id,
    repo = repo,
    fileName = fileName,
    quant = quant,
    displayName = displayName,
    sizeBytes = sizeBytes,
    sha256 = sha256,
    status = DownloadStatus.fromCode(status),
    bytesDone = bytesDone,
    error = DownloadError.fromCode(error),
    modelId = modelId,
)

/** What a model downloaded from this row remembers of it (TEN-142). */
internal fun DownloadEntity.toOrigin(downloadedAt: Long) = ModelOrigin(
    repo = repo,
    file = fileName,
    quant = quant,
    sha256 = sha256,
    commit = commitSha,
    downloadedAt = downloadedAt,
)

/**
 * Every state change is a conditional `UPDATE ... WHERE status IN (...)` returning the row count,
 * so a pause pressed while the worker is finishing cannot be overwritten by a stale write.
 */
@Dao
interface DownloadDao {

    @Query("SELECT * FROM downloads ORDER BY queuedAt ASC, id ASC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    fun observe(id: String): Flow<DownloadEntity?>

    @Query("SELECT * FROM downloads WHERE id = :id")
    suspend fun get(id: String): DownloadEntity?

    /** What the drainer may run: queued, plus downloading rows a killed process left behind. */
    @Query("SELECT * FROM downloads WHERE status IN ('queued', 'downloading') ORDER BY queuedAt ASC, id ASC")
    suspend fun getRunnable(): List<DownloadEntity>

    /** Every row, once, for reconcile. */
    @Query("SELECT * FROM downloads")
    suspend fun getAll(): List<DownloadEntity>

    /** Puts rows a dead process left in `downloading` back to `queued`. Only when no drainer runs. */
    @Query("UPDATE downloads SET status = 'queued' WHERE status = 'downloading'")
    suspend fun resetStuck(): Int

    /** After a transient failure: back to queued with the retry count and the earliest next try. */
    @Query(
        "UPDATE downloads SET status = 'queued', ioRetries = :ioRetries, notBefore = :notBefore " +
            "WHERE id = :id AND status IN ('queued', 'downloading')",
    )
    suspend fun scheduleRetry(id: String, ioRetries: Int, notBefore: Long): Int

    /** Fresh start for a resumed or retried row: no pending backoff, retry budget back. */
    @Query("UPDATE downloads SET ioRetries = 0, notBefore = 0, allowMobile = :allowMobile WHERE id = :id")
    suspend fun resetRetry(id: String, allowMobile: Boolean): Int

    /**
     * Back to the end of the queue in one statement: status, error, order, retry budget and network
     * policy change together or not at all (a crash between two writes cannot leave a queued row with
     * a spent budget). Only from one of [from].
     */
    @Query(
        "UPDATE downloads SET status = 'queued', error = NULL, queuedAt = :queuedAt, ioRetries = 0, " +
            "notBefore = 0, allowMobile = :allowMobile WHERE id = :id AND status IN (:from)",
    )
    suspend fun requeue(id: String, from: List<String>, queuedAt: Long, allowMobile: Boolean): Int

    /** Queued, downloading or paused: everything that still owns bytes or a place in the queue. */
    @Query("SELECT * FROM downloads WHERE status IN ('queued', 'downloading', 'paused') ORDER BY queuedAt ASC, id ASC")
    suspend fun getActive(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE repo = :repo AND fileName = :fileName")
    suspend fun getByFile(repo: String, fileName: String): List<DownloadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: DownloadEntity)

    /** Moves [id] to [to] only if it is currently in one of [from]; [queuedAt] null keeps the old one. */
    @Query(
        "UPDATE downloads SET status = :to, error = :error, queuedAt = COALESCE(:queuedAt, queuedAt) " +
            "WHERE id = :id AND status IN (:from)",
    )
    suspend fun transition(id: String, from: List<String>, to: String, error: String?, queuedAt: Long?): Int

    @Query("UPDATE downloads SET bytesDone = :bytesDone WHERE id = :id AND status = 'downloading'")
    suspend fun setProgress(id: String, bytesDone: Long): Int

    /** Sets the progress whatever the status: used when a download stops, to show what is on disk. */
    @Query("UPDATE downloads SET bytesDone = :bytesDone WHERE id = :id")
    suspend fun syncBytes(id: String, bytesDone: Long): Int

    @Query(
        "UPDATE downloads SET status = 'done', error = NULL, bytesDone = :bytesDone, modelId = :modelId " +
            "WHERE id = :id AND status IN ('queued', 'downloading', 'paused')",
    )
    suspend fun markDone(id: String, bytesDone: Long, modelId: String): Int

    @Query("DELETE FROM downloads WHERE id = :id")
    suspend fun delete(id: String): Int

    /** Drops the finished rows: the model they produced is the record, a done row is only clutter (TEN-142). */
    @Query("DELETE FROM downloads WHERE status = 'done'")
    suspend fun deleteDone(): Int
}
