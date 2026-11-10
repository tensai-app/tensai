package io.github.tensaiapp.update

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.updateDataStore by preferencesDataStore(name = "tensai_update")

/** What the user answered to "May tensai check for updates?". [Unasked] until the card or Settings is answered. */
enum class UpdateConsent { Unasked, Allowed, Denied }

/**
 * The update check's state that outlives the process (TEN-227): the opt-in, when the last check went out, the
 * skipped tag, and the debug build's release source.
 *
 * Same shape as [io.github.tensaiapp.data.OnboardingRepository], [DataStore] injectable for the same reason.
 */
class UpdatePreferences(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.updateDataStore)

    private val allowedKey = booleanPreferencesKey("allowed")
    private val lastCheckKey = longPreferencesKey("last_check_ms")
    private val skippedKey = stringPreferencesKey("skipped_tag")
    private val debugSourceKey = stringPreferencesKey("debug_source_url")

    val consent: Flow<UpdateConsent> = dataStore.data.map {
        when (it[allowedKey]) {
            null -> UpdateConsent.Unasked
            true -> UpdateConsent.Allowed
            false -> UpdateConsent.Denied
        }
    }

    /** The releases-API URL a debug build checks, null when unset; a release build never reads it, see [UpdateManager.sourceUrl]. */
    val debugSourceUrl: Flow<String?> = dataStore.data.map { it[debugSourceKey] }

    suspend fun consentNow(): UpdateConsent = consent.first()

    suspend fun setAllowed(allowed: Boolean) {
        dataStore.edit { it[allowedKey] = allowed }
    }

    suspend fun lastCheckMs(): Long? = dataStore.data.first()[lastCheckKey]

    suspend fun setLastCheckMs(ms: Long) {
        dataStore.edit { it[lastCheckKey] = ms }
    }

    suspend fun skippedTag(): String? = dataStore.data.first()[skippedKey]

    suspend fun setSkippedTag(tag: String) {
        dataStore.edit { it[skippedKey] = tag }
    }

    /** Blank clears it. A new source also clears the last check time, so the next start asks it at once. */
    suspend fun setDebugSourceUrl(url: String) {
        dataStore.edit {
            if (url.isBlank()) it.remove(debugSourceKey) else it[debugSourceKey] = url.trim()
            it.remove(lastCheckKey)
        }
    }
}
