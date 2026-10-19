package io.github.tensaiapp.data.memory

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Same contract as the Room DAO: auto ids from 1, newest first, NOCASE text lookup. */
class FakeMemoryDao : MemoryDao {
    val rows = mutableListOf<MemoryFactEntity>()
    private var next = 1L

    override suspend fun insert(fact: MemoryFactEntity): Long {
        val saved = fact.copy(id = next++)
        rows += saved
        return saved.id
    }

    private fun newestFirst() = rows.sortedWith(compareByDescending<MemoryFactEntity> { it.createdAt }.thenByDescending { it.id })

    override suspend fun recent(limit: Int) = newestFirst().take(limit)
    override fun observeAll(): Flow<List<MemoryFactEntity>> = flowOf(newestFirst())
    override suspend fun count() = rows.size
    override fun observeCount(): Flow<Int> = flowOf(rows.size)
    override suspend fun findIdByText(text: String) = rows.firstOrNull { it.text.equals(text, ignoreCase = true) }?.id
    override suspend fun delete(id: Long) = if (rows.removeAll { it.id == id }) 1 else 0
    override suspend fun deleteAll() = rows.clear()
}
