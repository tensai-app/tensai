package io.github.tensaiapp.data

import android.content.Context
import android.os.Looper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.tensaiapp.agent.DefaultTierGrants
import io.github.tensaiapp.agent.TierGrants
import io.github.tensaiapp.agent.ToolTier
import io.github.tensaiapp.hf.KeystoreCipher
import io.github.tensaiapp.hf.TokenStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.toolSettingsDataStore by preferencesDataStore(name = "tensai_tools")
private val Context.searchKeysDataStore by preferencesDataStore(name = "tensai_search_keys")

/** Which engine answers `web_search` (v0.3.1, TEN-180). Persisted by [name]. */
enum class SearchProviderChoice(val needsKey: Boolean) {
    /** Queried from the app, no key. The default. */
    DuckDuckGo(false),

    /** Brave Search API, with a key. */
    Brave(true),

    /** Tavily, with a key. */
    Tavily(true),
}

/**
 * What the user decided in Settings > Tools (TEN-174): which tiers the model may use, and which search
 * provider the Internet tools go through. Each tier is its own boolean, absent meaning [DefaultTierGrants]'
 * answer, so a tier added later starts at its default instead of off.
 *
 * It is the [TierGrants] the [io.github.tensaiapp.agent.ToolRegistry] reads, and that read is
 * synchronous: [granted] answers from a snapshot that [setGranted] and a collected [grants] keep current, and
 * a cold one is read once from the store on a background thread (on the main thread it answers the defaults
 * instead, the registry is read from the agent's coroutines).
 *
 * @param onMain whether the calling thread is the main one.
 */
class ToolSettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val onMain: () -> Boolean = { false },
) : TierGrants {

    constructor(context: Context) : this(
        context.toolSettingsDataStore,
        onMain = { Looper.myLooper() == Looper.getMainLooper() },
    )

    @Volatile private var snapshot: Set<ToolTier>? = null

    /** The granted tiers, re-emitted when one changes. */
    val grants: Flow<Set<ToolTier>> = dataStore.data.map { read(it) }

    val searchProvider: Flow<SearchProviderChoice> = dataStore.data.map { preferences ->
        preferences[providerKey]?.let { name -> SearchProviderChoice.entries.firstOrNull { it.name == name } }
            ?: SearchProviderChoice.DuckDuckGo
    }

    override fun granted(): Set<ToolTier> {
        snapshot?.let { return it }
        if (onMain()) return DefaultTierGrants.granted()
        return runBlocking { grants.first() }
    }

    suspend fun setGranted(tier: ToolTier, granted: Boolean) {
        // [read] refreshes the snapshot, so the next turn sees the change before the flow re-emits.
        read(dataStore.edit { it[tierKey(tier)] = granted })
    }

    suspend fun setSearchProvider(provider: SearchProviderChoice) {
        dataStore.edit { it[providerKey] = provider.name }
    }

    private fun read(preferences: Preferences): Set<ToolTier> {
        val defaults = DefaultTierGrants.granted()
        return ToolTier.entries.filter { preferences[tierKey(it)] ?: (it in defaults) }.toSet().also { snapshot = it }
    }

    private fun tierKey(tier: ToolTier) = booleanPreferencesKey("granted_${tier.name.lowercase()}")

    private companion object {
        val providerKey = stringPreferencesKey("search_provider")
    }
}

/**
 * The API keys of the search providers that need one, each sealed like the Hugging Face token (see
 * [TokenStore]) under the Keystore key `tensai_search_key`. Stored now so the choice survives; nothing
 * reads them until `:websearch` exists (TEN-180).
 */
class SearchKeyStores(context: Context) {
    private val dataStore = context.searchKeysDataStore
    private val cipher = KeystoreCipher(alias = "tensai_search_key")
    private val stores = SearchProviderChoice.entries.filter { it.needsKey }.associateWith { provider ->
        TokenStore(
            dataStore = dataStore,
            cipher = cipher,
            onMain = { Looper.myLooper() == Looper.getMainLooper() },
            keyName = "${provider.name.lowercase()}_key_sealed",
        )
    }

    /** The store of [provider]'s key, `null` for a provider with no key. */
    fun of(provider: SearchProviderChoice): TokenStore? = stores[provider]
}
