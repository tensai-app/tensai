package io.github.hokanosekai.tensai.download

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory [DownloadDao] with the same conditional-update semantics as the SQL. */
class FakeDownloadDao : DownloadDao {
    private val state = MutableStateFlow<Map<String, DownloadEntity>>(emptyMap())

    val rows: List<DownloadEntity> get() = state.value.values.sortedWith(compareBy({ it.queuedAt }, { it.id }))

    fun row(id: String): DownloadEntity? = state.value[id]

    private fun update(id: String, change: (DownloadEntity) -> DownloadEntity): Int {
        val old = state.value[id] ?: return 0
        state.value = state.value + (id to change(old))
        return 1
    }

    override fun observeAll(): Flow<List<DownloadEntity>> = state.map { _ ->
        rows
    }

    override fun observe(id: String): Flow<DownloadEntity?> = state.map { it[id] }

    override suspend fun get(id: String): DownloadEntity? = state.value[id]

    override suspend fun getActive(): List<DownloadEntity> = rows.filter {
        it.status in listOf("queued", "downloading", "paused")
    }

    var failReads: Throwable? = null

    override suspend fun getRunnable(): List<DownloadEntity> {
        failReads?.let { throw it }
        return rows.filter { it.status == "queued" || it.status == "downloading" }
    }

    override suspend fun getAll(): List<DownloadEntity> = rows

    override suspend fun resetStuck(): Int {
        var n = 0
        rows.filter { it.status == "downloading" }.forEach { n += update(it.id) { r -> r.copy(status = "queued") } }
        return n
    }

    override suspend fun scheduleRetry(id: String, ioRetries: Int, notBefore: Long): Int {
        if (state.value[id]?.status !in listOf("queued", "downloading")) return 0
        return update(id) { it.copy(status = "queued", ioRetries = ioRetries, notBefore = notBefore) }
    }

    override suspend fun requeue(id: String, from: List<String>, queuedAt: Long, allowMobile: Boolean): Int {
        if (state.value[id]?.status !in from) return 0
        return update(id) {
            it.copy(status = "queued", error = null, queuedAt = queuedAt, ioRetries = 0, notBefore = 0, allowMobile = allowMobile)
        }
    }

    override suspend fun resetRetry(id: String, allowMobile: Boolean): Int =
        update(id) { it.copy(ioRetries = 0, notBefore = 0, allowMobile = allowMobile) }

    override suspend fun getByFile(repo: String, fileName: String): List<DownloadEntity> =
        rows.filter { it.repo == repo && it.fileName == fileName }

    override suspend fun insert(row: DownloadEntity) {
        state.value = state.value + (row.id to row)
    }

    override suspend fun transition(id: String, from: List<String>, to: String, error: String?, queuedAt: Long?): Int {
        val old = state.value[id] ?: return 0
        if (old.status !in from) return 0
        return update(id) { it.copy(status = to, error = error, queuedAt = queuedAt ?: it.queuedAt) }
    }

    override suspend fun setProgress(id: String, bytesDone: Long): Int {
        if (state.value[id]?.status != "downloading") return 0
        return update(id) { it.copy(bytesDone = bytesDone) }
    }

    override suspend fun syncBytes(id: String, bytesDone: Long): Int = update(id) { it.copy(bytesDone = bytesDone) }

    override suspend fun markDone(id: String, bytesDone: Long, modelId: String): Int {
        if (state.value[id]?.status !in listOf("queued", "downloading", "paused")) return 0
        return update(id) { it.copy(status = "done", error = null, bytesDone = bytesDone, modelId = modelId) }
    }

    override suspend fun delete(id: String): Int {
        if (id !in state.value) return 0
        state.value = state.value - id
        return 1
    }

    override suspend fun deleteDone(): Int {
        val done = state.value.filterValues { it.status == "done" }.keys
        state.value = state.value - done
        return done.size
    }
}
