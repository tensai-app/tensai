package io.github.hokanosekai.tensai.ui.screens.models

import io.github.hokanosekai.tensai.compat.DeviceProfile
import io.github.hokanosekai.tensai.hf.HfClient
import io.github.hokanosekai.tensai.hf.HfError
import io.github.hokanosekai.tensai.hf.HfException
import io.github.hokanosekai.tensai.hf.HfPage
import io.github.hokanosekai.tensai.hf.HfRepoDetail
import io.github.hokanosekai.tensai.hf.HfRepoSummary
import io.github.hokanosekai.tensai.hf.HfSort
import io.github.hokanosekai.tensai.runtime.RuntimeGraph
import io.github.hokanosekai.tensai.ui.screens.onboarding.CatalogModel
import io.github.hokanosekai.tensai.ui.screens.onboarding.OnboardingCatalog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Popular on Hugging Face": the most downloaded GGUF repos, [Loaded] once the search answered. */
internal sealed interface PopularState {
    data object Loading : PopularState
    data class Loaded(val repos: List<HfRepoSummary>) : PopularState
    data class Failed(val error: HfError) : PopularState
}

/** One popular repo's card: a skeleton until its detail arrives, then the card, or nothing if it does not fit. */
internal sealed interface CardSlot {
    data object Loading : CardSlot
    data object Hidden : CardSlot
    data class Ready(val card: RepoCardModel) : CardSlot
}

/**
 * State of the Discover home (no query).
 *
 * @param recommended the catalog rated for this device, there as soon as the device profile is read.
 * @param slots per repo id, filled lazily as cards come on screen (see [DiscoverViewModel.onVisible]).
 */
internal data class DiscoverState(
    val deviceRamBytes: Long = 0,
    val recommended: List<RepoCardModel> = emptyList(),
    val popular: PopularState = PopularState.Loading,
    val slots: Map<String, CardSlot> = emptyMap(),
) {
    /**
     * The repos "Popular on Hugging Face" lists: the search order, minus the ones already under
     * "Recommended" and minus those whose detail said they do not fit. A repo whose detail has not
     * arrived is still in (as a skeleton).
     */
    val popularShown: List<HfRepoSummary>
        get() {
            val loaded = (popular as? PopularState.Loaded)?.repos ?: return emptyList()
            val taken = recommended.mapTo(HashSet()) { it.id.lowercase() }
            return loaded.filter { it.id.lowercase() !in taken && slots[it.id] != CardSlot.Hidden }
        }
}

/**
 * State holder of the Discover tab (TEN-137), plain class like the other screens' (the project has no
 * androidx ViewModel). Created by [ModelsScreen] so it outlives a tab switch, driven on its [scope].
 *
 * Network goes through [search] and [detail] only, which default to [RuntimeGraph.hfClient]: one
 * search for the first page, then one detail call per card that comes on screen ([onVisible]), lazily.
 * [HfClient] caches and merges those, so scrolling back costs nothing.
 *
 * @param profile read once, off the main thread.
 */
internal class DiscoverViewModel(
    private val scope: CoroutineScope,
    private val search: suspend () -> HfPage = { RuntimeGraph.hfClient.search("", HfSort.Downloads) },
    private val detail: suspend (String) -> HfRepoDetail = { RuntimeGraph.hfClient.detail(it) },
    private val profile: () -> DeviceProfile = { RuntimeGraph.deviceProfiler.profile() },
    private val catalog: () -> List<CatalogModel> = { OnboardingCatalog.all },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state = MutableStateFlow(DiscoverState())
    val state: StateFlow<DiscoverState> = _state.asStateFlow()

    private val deviceProfile: Deferred<DeviceProfile> = scope.async(dispatcher, start = CoroutineStart.LAZY) { profile() }
    private var started = false
    private var loadJob: Job? = null

    /** First call loads the home, later ones do nothing: a tab switch must not refetch. */
    fun start() {
        if (started) return
        started = true
        load()
    }

    /** "Try again", and the first load: recommended (offline) first, then the popular search. */
    fun load() {
        loadJob?.cancel()
        _state.update { it.copy(popular = PopularState.Loading, slots = emptyMap()) }
        loadJob = scope.launch {
            val device = deviceProfile.await()
            val recommended = withContext(dispatcher) { DiscoverLogic.recommended(catalog(), device) }
            _state.update { it.copy(deviceRamBytes = device.totalRamBytes, recommended = recommended) }
            val popular = try {
                PopularState.Loaded(search().items)
            } catch (e: HfException) {
                PopularState.Failed(e.error)
            }
            _state.update { it.copy(popular = popular) }
        }
    }

    /**
     * [repoId]'s card came on screen: fetch its detail once. The slot is [CardSlot.Loading] until it
     * arrives, then [CardSlot.Ready], or [CardSlot.Hidden] when the repo does not fit or the call failed.
     */
    fun onVisible(repoId: String) {
        var claimed = false
        _state.update {
            claimed = repoId !in it.slots
            if (claimed) it.copy(slots = it.slots + (repoId to CardSlot.Loading)) else it
        }
        if (!claimed) return
        val summary = (_state.value.popular as? PopularState.Loaded)?.repos?.firstOrNull { it.id == repoId } ?: return
        scope.launch {
            val slot = try {
                val repo = detail(repoId)
                val card = withContext(dispatcher) { DiscoverLogic.cardFor(summary, repo, deviceProfile.await()) }
                if (card != null) CardSlot.Ready(card) else CardSlot.Hidden
            } catch (e: HfException) {
                CardSlot.Hidden
            }
            // A reload in between cleared the slots: its answer is stale.
            _state.update { if (repoId in it.slots) it.copy(slots = it.slots + (repoId to slot)) else it }
        }
    }
}
