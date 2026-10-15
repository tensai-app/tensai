package io.github.hokanosekai.tensai.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.contextDataStore by preferencesDataStore(name = "tensai_context")

/**
 * The context window the user asks a load for (TEN-150), set globally in Settings and optionally
 * per model in the model's detail screen. What a choice turns into for a given model on a given
 * device is [io.github.hokanosekai.tensai.runtime.LoadPolicy]'s job, not this type's: a setting is a
 * wish, and the policy clamps it to the model's trained context and steps it down when the RAM
 * can't back it.
 *
 * Persisted by [name] (the column [io.github.hokanosekai.tensai.data.models.ModelEntity.contextOverride]
 * and the DataStore key both hold it), so renaming a constant is a migration.
 *
 * @param tokens the fixed size in tokens, `null` for the two choices that depend on the model
 *   ([Auto], [Max]).
 */
enum class ContextSetting(val tokens: Int?) {
    /** The largest context whose KV cache fits the device's RAM budget, up to the trained context. */
    Auto(null),

    /** 4096 tokens. */
    K4(4_096),

    /** 8192 tokens. */
    K8(8_192),

    /** 16384 tokens. */
    K16(16_384),

    /** 32768 tokens. */
    K32(32_768),

    /** The model's trained context, whatever it is. */
    Max(null);

    companion object {
        /**
         * The choices a model with [trainedContext] tokens of trained context is offered: [Auto],
         * every fixed size *below* it, then [Max]. A fixed size at or above the trained context
         * would be [Max] under another name (or a context the model was never trained for), so it
         * isn't listed.
         */
        fun offeredFor(trainedContext: Long): List<ContextSetting> =
            listOf(Auto) + entries.filter { (it.tokens ?: Int.MAX_VALUE) < trainedContext } + Max

        /** The setting persisted under [name], `null` for `null` or a name this build doesn't know. */
        fun parse(name: String?): ContextSetting? = entries.firstOrNull { it.name == name }
    }
}

/**
 * The share of [io.github.hokanosekai.tensai.compat.DeviceProfile.usableRamBytes] that [ContextSetting.Auto]
 * may spend on weights + KV cache + compute buffers (TEN-150). The rest stays for Android and the
 * user's other apps: a model that only just fits is a model the system will kill. Manual sizes and
 * Max are not held to it, they get the full budget.
 *
 * Persisted by [name].
 */
enum class AutoMemoryShare(val percent: Int) {
    P30(30),
    P50(50),
    P70(70),
}

/** The two context preferences together, what every plan and every compatibility verdict is computed from. */
data class ContextPrefs(
    val setting: ContextSetting = ContextSetting.Auto,
    val autoShare: AutoMemoryShare = AutoMemoryShare.P50,
)

/**
 * Persists the global [ContextSetting]. Defaults to [ContextSetting.Auto].
 *
 * Same shape as [BackendRepository]: the [DataStore] is injectable so the round trip is provable
 * in a JVM test. An unknown stored name (a build downgrade) reads as the default.
 */
class ContextRepository(private val dataStore: DataStore<Preferences>) {

    constructor(context: Context) : this(context.contextDataStore)

    private val contextKey = stringPreferencesKey("context_setting")

    val setting: Flow<ContextSetting> = dataStore.data.map { preferences ->
        ContextSetting.parse(preferences[contextKey]) ?: ContextSetting.Auto
    }

    private val shareKey = stringPreferencesKey("auto_memory_share")

    /** The share Auto may spend, [AutoMemoryShare.P50] by default (and for an unknown stored name). */
    val autoShare: Flow<AutoMemoryShare> = dataStore.data.map { preferences ->
        preferences[shareKey]
            ?.let { stored -> AutoMemoryShare.entries.firstOrNull { it.name == stored } }
            ?: AutoMemoryShare.P50
    }

    /** Setting and share in one emission. */
    val prefs: Flow<ContextPrefs> = dataStore.data.map { preferences ->
        ContextPrefs(
            setting = ContextSetting.parse(preferences[contextKey]) ?: ContextSetting.Auto,
            autoShare = preferences[shareKey]
                ?.let { stored -> AutoMemoryShare.entries.firstOrNull { it.name == stored } }
                ?: AutoMemoryShare.P50,
        )
    }

    suspend fun setAutoShare(share: AutoMemoryShare) {
        dataStore.edit { preferences ->
            preferences[shareKey] = share.name
        }
    }

    suspend fun setSetting(setting: ContextSetting) {
        dataStore.edit { preferences ->
            preferences[contextKey] = setting.name
        }
    }
}
