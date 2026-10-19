package io.github.tensaiapp.ui.screens.settings

import io.github.tensaiapp.data.memory.MemoryDao
import io.github.tensaiapp.data.MemorySettings
import io.github.tensaiapp.data.memory.MemoryFactEntity
import io.github.tensaiapp.data.memory.MemoryPrompt
import io.github.tensaiapp.runtime.RuntimeGraph
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
    private val settings: MemorySettings = RuntimeGraph.memorySettings,
    private val memoryPrompt: MemoryPrompt = RuntimeGraph.memoryPrompt,
    private val scope: CoroutineScope = RuntimeGraph.applicationScope,
) {
    /** Newest first. */
    val facts: Flow<List<MemoryFactEntity>> = dao.observeAll()

    /** "Use memory in chats", see [MemorySettings]. */
    val useInChats: Flow<Boolean> = settings.useInChats

    fun setUseInChats(enabled: Boolean) {
        scope.launch { settings.setUseInChats(enabled) }
    }

    fun forget(id: Long) {
        scope.launch {
            dao.delete(id)
            memoryPrompt.invalidate()
        }
    }

    fun forgetEverything() {
        scope.launch {
            dao.deleteAll()
            memoryPrompt.invalidate()
        }
    }
}
