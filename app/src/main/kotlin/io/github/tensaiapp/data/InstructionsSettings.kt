package io.github.tensaiapp.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.instructionsDataStore by preferencesDataStore(name = "tensai_instructions")

/**
 * "Your instructions": free text the user wants in every chat, the second layer of the system message,
 * after the base prompt and before a conversation's own instructions (see
 * [io.github.tensaiapp.agent.SystemPrompt]). Empty by default.
 *
 * Backed up with the rest of the app's data, like the memory facts: it is the user's own text, nothing
 * secret and nothing tied to this device (only the token files are excluded, see `backup_rules.xml`).
 *
 * Capped at [MAX_CHARS], about [MAX_TOKENS] tokens: it is in every prompt, tool prefix included, and a
 * phone context of a few thousand tokens already gives most of itself to the tool block.
 */
class InstructionsSettings(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.instructionsDataStore)

    private val globalKey = stringPreferencesKey("global")

    val global: Flow<String> = dataStore.data.map { it[globalKey].orEmpty() }

    /** Stores [text] cut to [MAX_CHARS], or removes it when blank. */
    suspend fun setGlobal(text: String) {
        dataStore.edit {
            if (text.isBlank()) it.remove(globalKey) else it[globalKey] = text.take(MAX_CHARS)
        }
    }

    companion object {
        /** About [MAX_TOKENS] tokens at 4 characters each, the bound the memory block uses too. */
        const val MAX_CHARS = 1200
        const val MAX_TOKENS = 300

        /** The counter's figure: an estimate at 4 characters a token, no model needed to show it. */
        fun estimatedTokens(text: String): Int = (text.length + 3) / 4
    }
}
