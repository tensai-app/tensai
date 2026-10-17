package io.github.hokanosekai.tensai.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.changelogDataStore by preferencesDataStore(name = "tensai_changelog")

/**
 * The last version whose release notes the user opened (TEN-193), what drives the dot on
 * Settings' Version row after an update.
 *
 * Same shape as [OnboardingRepository], [DataStore] injectable for the same reason.
 */
class ChangelogRepository(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.changelogDataStore)

    private val seenKey = stringPreferencesKey("seen_version")

    val seenVersion: Flow<String?> = dataStore.data.map { it[seenKey] }

    /** Whether the notes of [installedVersion] are still unopened, see [isUnseen]. */
    fun hasUnseen(installedVersion: String): Flow<Boolean> = seenVersion.map { isUnseen(installedVersion, it) }

    suspend fun markSeen(version: String) {
        dataStore.edit { it[seenKey] = version }
    }

    companion object {
        /**
         * The dot rule: the installed `versionName` differs from the last one whose notes were
         * opened. Never on an unreadable (blank) version; on a fresh install nothing was opened yet,
         * so it shows until the first visit.
         */
        fun isUnseen(installedVersion: String, seenVersion: String?): Boolean =
            installedVersion.isNotBlank() && installedVersion != seenVersion
    }
}
