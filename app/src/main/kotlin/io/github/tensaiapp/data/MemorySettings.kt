package io.github.tensaiapp.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.memorySettingsDataStore by preferencesDataStore(name = "tensai_memory")

/**
 * "Use memory in chats" (TEN-189): whether the saved facts are put in every conversation's prompt.
 * On by default. Same shape as [ThemeRepository], [DataStore] injectable for the same reason.
 */
class MemorySettings(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.memorySettingsDataStore)

    private val useInChatsKey = booleanPreferencesKey("use_in_chats")

    val useInChats: Flow<Boolean> = dataStore.data.map { it[useInChatsKey] ?: true }

    suspend fun useInChatsNow(): Boolean = useInChats.first()

    suspend fun setUseInChats(enabled: Boolean) {
        dataStore.edit { it[useInChatsKey] = enabled }
    }
}
