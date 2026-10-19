package io.github.tensaiapp.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.onboardingDataStore by preferencesDataStore(name = "tensai_onboarding")

/**
 * Whether the user has already walked away from the first-run onboarding without importing a
 * model (TEN-36).
 *
 * This flag does **not** gate the onboarding — having no model does, and that is deliberate: an
 * install with no model can't chat, so the flow comes back until there is one (see
 * [io.github.tensaiapp.ui.screens.onboarding.OnboardingGate]). What it buys is not
 * showing the welcome screen a second time to someone who has already read it: a returning
 * skipper is dropped straight at the import step.
 *
 * Same shape as [ThemeRepository]/[BackendRepository], [DataStore] injectable for the same reason.
 */
class OnboardingRepository(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.onboardingDataStore)

    private val skippedKey = booleanPreferencesKey("skipped")

    val skipped: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[skippedKey] ?: false
    }

    suspend fun setSkipped(skipped: Boolean) {
        dataStore.edit { preferences ->
            preferences[skippedKey] = skipped
        }
    }
}
