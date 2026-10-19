package io.github.tensaiapp.download

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.downloadDataStore by preferencesDataStore(name = "tensai_downloads")

/**
 * Download settings. Only one for now: whether a download may start on mobile data without asking
 * (Settings, TEN-144). Default false, meaning a metered network gets a confirmation first.
 */
class DownloadPreferences(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.downloadDataStore)

    private val allowMobileKey = booleanPreferencesKey("allow_mobile_data")

    val allowMobileData: Flow<Boolean> = dataStore.data.map { it[allowMobileKey] ?: false }

    suspend fun isMobileDataAllowed(): Boolean = allowMobileData.first()

    suspend fun setAllowMobileData(allowed: Boolean) {
        dataStore.edit { it[allowMobileKey] = allowed }
    }
}
