package io.github.hokanosekai.tensai.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.hokanosekai.tensai.engine.BackendHint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.backendDataStore by preferencesDataStore(name = "tensai_backend")

/**
 * Which compute backend the user wants their models loaded on — set in the first-run onboarding
 * (TEN-36), read by every load through [io.github.hokanosekai.tensai.ui.screens.loading.LoadModelViewModel].
 *
 * Deliberately *not* [BackendHint]: [Auto] here means "whatever
 * [io.github.hokanosekai.tensai.compat.CompatibilityChecker] recommends for this model on this
 * device", which is a per-model answer the checker's architecture table can give, while
 * [BackendHint.Auto] means "let the engine pick" and throws that table away. See [resolve].
 */
enum class BackendPreference {
    /** Follow the compatibility checker's per-model recommendation. */
    Auto,

    /** Always force CPU-only inference. */
    Cpu,

    /** Always prefer GPU offload. */
    Gpu;

    /** The [BackendHint] to load with, given the checker's [recommended] backend for that model. */
    fun resolve(recommended: BackendHint): BackendHint = when (this) {
        Auto -> recommended
        Cpu -> BackendHint.Cpu
        Gpu -> BackendHint.Gpu
    }
}

/**
 * Persists the user's [BackendPreference]. Defaults to [BackendPreference.Auto].
 *
 * Same shape as [ThemeRepository], with one addition: the [DataStore] is injectable so the
 * round trip can be proven in a JVM unit test against a temp file, without a device.
 */
class BackendRepository(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.backendDataStore)

    private val backendKey = stringPreferencesKey("backend_preference")

    val preference: Flow<BackendPreference> = dataStore.data.map { preferences ->
        preferences[backendKey]
            ?.let { runCatching { BackendPreference.valueOf(it) }.getOrNull() }
            ?: BackendPreference.Auto
    }

    suspend fun setPreference(preference: BackendPreference) {
        dataStore.edit { preferences ->
            preferences[backendKey] = preference.name
        }
    }
}
