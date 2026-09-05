package io.github.hokanosekai.tensai.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.hokanosekai.tensai.ui.theme.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.themeDataStore by preferencesDataStore(name = "tensai_theme")

/**
 * Persists the user's [ThemeMode] preference. Defaults to [ThemeMode.System].
 *
 * The [DataStore] is injectable (TEN-36) so the round trip can be proven in a JVM unit test
 * against a temp file; the `Context` constructor is what the app itself uses.
 */
class ThemeRepository(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.themeDataStore)

    private val themeModeKey = stringPreferencesKey("theme_mode")

    val themeMode: Flow<ThemeMode> = dataStore.data.map { preferences ->
        preferences[themeModeKey]
            ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
            ?: ThemeMode.System
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { preferences ->
            preferences[themeModeKey] = mode.name
        }
    }
}
