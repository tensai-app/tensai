package io.github.hokanosekai.tensai.ui.screens.settings

import io.github.hokanosekai.tensai.agent.Tool
import io.github.hokanosekai.tensai.agent.ToolRegistry
import io.github.hokanosekai.tensai.agent.ToolTier
import io.github.hokanosekai.tensai.data.SearchKeyStores
import io.github.hokanosekai.tensai.data.SearchProviderChoice
import io.github.hokanosekai.tensai.data.ToolSettingsRepository
import io.github.hokanosekai.tensai.hf.TokenStore
import io.github.hokanosekai.tensai.runtime.RuntimeGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The tiers of the Settings > Tools screens (TEN-174). Four [ToolTier]s, three cards: the tools that need an
 * Android permission (v0.3.1) sit under "Phone actions", where Android asks for each permission at first use.
 * A card's switch turns all of its tiers on or off together.
 */
enum class ToolGroup(val tiers: List<ToolTier>) {
    OnPhone(listOf(ToolTier.Local)),
    PhoneActions(listOf(ToolTier.PhoneAction, ToolTier.AndroidPermission)),
    Internet(listOf(ToolTier.Network)),
    ;

    fun isOn(granted: Set<ToolTier>): Boolean = tiers.all { it in granted }

    /** The registered tools of this card, granted or not. */
    fun toolsOf(registered: List<Tool>): List<Tool> = registered.filter { it.tier in tiers }

    companion object {
        /** How many of the cards are on, the "2 of 3 on" of the Settings row. */
        fun countOn(granted: Set<ToolTier>): Int = entries.count { it.isOn(granted) }
    }
}

/**
 * Drives Settings > Tools and the tier screens (TEN-174): reads the grants and the search provider from
 * [ToolSettingsRepository], lists the tools [ToolRegistry] holds per card, and keeps the search providers'
 * API keys in [SearchKeyStores].
 *
 * @param scope where writes run, [RuntimeGraph.applicationScope] for the reason [SettingsViewModel] gives.
 */
class ToolsViewModel(
    private val settings: ToolSettingsRepository = RuntimeGraph.toolSettings,
    private val registry: ToolRegistry = RuntimeGraph.toolRegistry,
    private val keys: SearchKeyStores? = null,
    private val scope: CoroutineScope = RuntimeGraph.applicationScope,
) {
    private val keyStores: SearchKeyStores get() = keys ?: RuntimeGraph.searchKeys

    val grants: Flow<Set<ToolTier>> = settings.grants

    /** How many cards are on, for the Settings row. */
    val groupsOn: Flow<Int> = settings.grants.map { ToolGroup.countOn(it) }.distinctUntilChanged()

    val searchProvider: Flow<SearchProviderChoice> = settings.searchProvider

    /** Every registered tool, in registration order. */
    fun registeredTools(): List<Tool> = registry.registered()

    fun setGroup(group: ToolGroup, on: Boolean) {
        scope.launch { group.tiers.forEach { settings.setGranted(it, on) } }
    }

    fun setSearchProvider(provider: SearchProviderChoice) {
        scope.launch { settings.setSearchProvider(provider) }
    }

    /** [provider]'s key as `••••abcd`, `null` when none is set or the provider has no key. */
    fun keyMask(provider: SearchProviderChoice): Flow<String?> {
        val store = keyStores.of(provider) ?: return emptyFlow()
        return store.hasToken.distinctUntilChanged().map { has -> if (has) store.get()?.let(TokenStore::mask) else null }
    }

    /**
     * Stores [raw] as [provider]'s key, then tells [onResult] whether it did (`false`: not well formed, or it could
     * not be stored). Runs on [scope]: leaving the screen (Back right after typing) does not cancel the write.
     */
    fun saveKey(provider: SearchProviderChoice, raw: String, onResult: (Boolean) -> Unit) {
        scope.launch { onResult(keyStores.of(provider)?.set(raw) ?: false) }
    }

    fun removeKey(provider: SearchProviderChoice) {
        scope.launch { keyStores.of(provider)?.clear() }
    }
}
