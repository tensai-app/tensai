package io.github.hokanosekai.tensai.ui.screens.settings

import io.github.hokanosekai.tensai.data.memory.MemoryDao
import io.github.hokanosekai.tensai.data.memory.MemoryFactEntity
import io.github.hokanosekai.tensai.runtime.RuntimeGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Settings > Memory (TEN-176): the facts the `memory` tool stored, and the two ways to delete them.
 *
 * @param scope where deletes run, [RuntimeGraph.applicationScope] for the reason [SettingsViewModel] gives:
 *   leaving the screen must not cancel a delete half way.
 */
class MemoryViewModel(
    private val dao: MemoryDao = RuntimeGraph.memoryDao,
    private val scope: CoroutineScope = RuntimeGraph.applicationScope,
) {
    /** Newest first. */
    val facts: Flow<List<MemoryFactEntity>> = dao.observeAll()

    fun forget(id: Long) {
        scope.launch { dao.delete(id) }
    }

    fun forgetEverything() {
        scope.launch { dao.deleteAll() }
    }
}
