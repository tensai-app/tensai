package io.github.hokanosekai.tensai.ui.screens.models

import io.github.hokanosekai.tensai.compat.DeviceProfile
import io.github.hokanosekai.tensai.hf.HfError
import io.github.hokanosekai.tensai.hf.HfException
import io.github.hokanosekai.tensai.hf.HfPage
import io.github.hokanosekai.tensai.hf.HfRepoDetail
import io.github.hokanosekai.tensai.hf.HfRepoSummary
import io.github.hokanosekai.tensai.hf.HfSort
import io.github.hokanosekai.tensai.runtime.RuntimeGraph
import io.github.hokanosekai.tensai.ui.screens.onboarding.CatalogModel
import io.github.hokanosekai.tensai.ui.screens.onboarding.OnboardingCatalog
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A paginated list of repos: the Discover home's "Popular" (empty query) or a search's results.
 * [Loaded] holds every page fetched so far.
 */
internal sealed interface FeedState {
    data object Loading : FeedState

    /**
     * @param next cursor of the next page, null on the last one.
     * @param loadingMore the next page is in flight.
     * @param moreFailed the last "next page" call failed: the list offers "Try again" rather than retrying on its own.
     * @param pages pages fetched, the first one included.
     * @param stalled automatic paging gave up: [DiscoverViewModel.AUTO_FILL_PAGES] pages in a row added no card to the list. Only the user scrolling to the end asks for more now.
     */
    data class Loaded(
        val repos: List<HfRepoSummary>,
        val next: String? = null,
        val loadingMore: Boolean = false,
        val moreFailed: Boolean = false,
        val pages: Int = 1,
        val stalled: Boolean = false,
    ) : FeedState

    data class Failed(val error: HfError) : FeedState
}

/** One repo's card: a skeleton until its detail arrives, then the card, or nothing if the repo is not a usable chat model. */
internal sealed interface CardSlot {
    data object Loading : CardSlot
    data object Hidden : CardSlot
    data class Ready(val card: RepoCardModel) : CardSlot
}

/**
 * State of the Discover tab.
 *
 * @param recommended the catalog rated for this device, there as soon as the device profile is read.
 * @param popular the empty-query feed, see [popularShown].
 * @param query the text of the search field.
 * @param results the feed of the current text search, null when the query is not a plain text one.
 * @param linkError the field holds a URL that is not a Hugging Face repo or file link.
 * @param pendingOpen a pasted repo link waiting to be opened: held in the state, taken once by [DiscoverViewModel.consumeOpen], and dropped by the next keystroke so it never arrives late.
 * @param slots per repo id, filled lazily as cards come on screen (see [DiscoverViewModel.onVisible]);
 *   shared by the popular list and the results, a card does not depend on the query.
 */
internal data class DiscoverState(
    val deviceRamBytes: Long = 0,
    val recommended: List<RepoCardModel> = emptyList(),
    val popular: FeedState = FeedState.Loading,
    val slots: Map<String, CardSlot> = emptyMap(),
    val filters: DiscoverFilters = DiscoverFilters(),
    val query: String = "",
    val results: FeedState? = null,
    val linkError: Boolean = false,
    val pendingOpen: DiscoverEvent.OpenRepo? = null,
) {
    /** "Recommended for your device" under the Tools / Vision chips (the catalog is already offline-rated, it all fits). */
    val recommendedShown: List<RepoCardModel>
        get() = recommended.filter { DiscoverLogic.matches(it, filters) }

    /**
     * The repos "Popular on Hugging Face" lists: the search order, minus the ones already under
     * "Recommended" and minus those the filters or their detail rule out. A repo whose detail has not
     * arrived is still in (as a skeleton).
     */
    val popularShown: List<HfRepoSummary>
        get() {
            val loaded = (popular as? FeedState.Loaded)?.repos ?: return emptyList()
            val taken = recommended.mapTo(HashSet()) { it.id.lowercase() }
            return DiscoverLogic.shown(loaded, slots, filters, taken)
        }

    /** The search results under the filters. */
    val resultsShown: List<HfRepoSummary>
        get() = DiscoverLogic.shown((results as? FeedState.Loaded)?.repos.orEmpty(), slots, filters)
}

/** What the pane has to do once. */
internal sealed interface DiscoverEvent {
    /** A pasted Hugging Face link: open this repo, with [file] preselected when the link was a file link. */
    data class OpenRepo(val id: String, val file: String?) : DiscoverEvent
}

/**
 * State holder of the Discover tab (TEN-137, TEN-139), plain class like the other screens' (the
 * project has no androidx ViewModel). Created by [ModelsScreen] so it outlives a tab switch, driven
 * on its [scope].
 *
 * Network goes through [search], [searchNext] and [detail] only, which default to
 * [RuntimeGraph.hfClient]: one search for a first page (20 repos), [searchNext] for the following
 * ones when the list reaches its end ([loadMore]), then one detail call per card that comes on screen
 * ([onVisible]), lazily. [io.github.hokanosekai.tensai.hf.HfClient] caches and merges those, so
 * scrolling back costs nothing.
 *
 * **Typing.** [onQueryChange] reads the field: text becomes a search after [debounceMs] without a
 * keystroke (the previous query's request is cancelled the moment a new key comes in, and a late
 * answer of a replaced query is dropped); a Hugging Face link becomes an [DiscoverEvent.OpenRepo]
 * after the same delay (so a link typed by hand is not opened half way); any other link shows the
 * inline error and costs no request.
 *
 * @param profile read once, off the main thread.
 */
internal class DiscoverViewModel(
    private val scope: CoroutineScope,
    private val search: suspend (query: String, sort: HfSort) -> HfPage = { query, sort -> RuntimeGraph.hfClient.search(query, sort) },
    private val searchNext: suspend (cursor: String) -> HfPage = { RuntimeGraph.hfClient.searchNext(it) },
    private val detail: suspend (String) -> HfRepoDetail = { RuntimeGraph.hfClient.detail(it) },
    private val profile: () -> DeviceProfile = { RuntimeGraph.deviceProfiler.profile() },
    private val catalog: () -> List<CatalogModel> = { OnboardingCatalog.all },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val debounceMs: Long = SEARCH_DEBOUNCE_MS,
    private val retryBackoffMs: Long = DETAIL_RETRY_BACKOFF_MS,
) {
    private val _state = MutableStateFlow(DiscoverState())
    val state: StateFlow<DiscoverState> = _state.asStateFlow()

    private val deviceProfile: Deferred<DeviceProfile> = scope.async(dispatcher, start = CoroutineStart.LAZY) { safeProfile(profile) }
    private var started = false
    private var loadJob: Job? = null

    private enum class Kind { Popular, Results }

    // A feed's answer is applied only if its generation is still the current one: cancelling a job
    // does not stop a result that is already on its way to _state.update.
    private val generations = mapOf(Kind.Popular to AtomicInteger(), Kind.Results to AtomicInteger())
    private val firstJobs = HashMap<Kind, Job>()
    private val autoStreaks = HashMap<Kind, Int>()
    private val autoReady = HashMap<Kind, Int>()
    private val autoSizes = HashMap<Kind, Int>()

    // Detail calls that failed for a reason that may pass (offline, rate limit, 5xx), by repo id.
    private val detailAttempts = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val gaveUp: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val moreJobs = HashMap<Kind, Job>()

    /** First call loads the home, later ones do nothing: a tab switch must not refetch. */
    fun start() {
        if (started) return
        started = true
        load()
    }

    /** "Try again" on the home, and the first load: recommended (offline) first, then the popular search. */
    fun load() {
        loadJob?.cancel()
        gaveUp.clear()
        detailAttempts.clear()
        _state.update { it.copy(slots = emptyMap()) }
        startFeed(Kind.Popular, query = "", debounce = 0)
        loadJob = scope.launch {
            val device = deviceProfile.await()
            val recommended = withContext(dispatcher) { DiscoverLogic.recommended(catalog(), device) }
            _state.update { it.copy(deviceRamBytes = device.totalRamBytes, recommended = recommended) }
        }
    }

    /** The search field changed to [raw]. See the class KDoc. */
    fun onQueryChange(raw: String) {
        val parsed = DiscoverQueryParser.parse(raw)
        cancelFeed(Kind.Results)
        _state.update {
            it.copy(
                query = raw,
                linkError = parsed is DiscoverQuery.Foreign,
                results = if (parsed is DiscoverQuery.Text) FeedState.Loading else null,
                pendingOpen = null,
            )
        }
        when (parsed) {
            is DiscoverQuery.Text -> startFeed(Kind.Results, parsed.query, debounceMs)
            is DiscoverQuery.Repo -> firstJobs[Kind.Results] = scope.launch {
                delay(debounceMs)
                _state.update { it.copy(pendingOpen = DiscoverEvent.OpenRepo(parsed.id, parsed.file)) }
            }
            DiscoverQuery.Empty, DiscoverQuery.Foreign -> Unit
        }
    }

    /** "Try again" on a failed search: no debounce, the user asked. */
    fun retrySearch() {
        val parsed = DiscoverQueryParser.parse(_state.value.query) as? DiscoverQuery.Text ?: return
        _state.update { it.copy(results = FeedState.Loading) }
        startFeed(Kind.Results, parsed.query, debounce = 0)
    }

    fun setFits(on: Boolean) = _state.update { it.copy(filters = it.filters.copy(fits = on)) }

    fun setTools(on: Boolean) = _state.update { it.copy(filters = it.filters.copy(tools = on)) }

    fun setVision(on: Boolean) = _state.update { it.copy(filters = it.filters.copy(vision = on)) }

    /** A different [sort]: asks the Hub again when its API order changes ([DiscoverSort.Size] reorders what is loaded). */
    fun setSort(sort: DiscoverSort) {
        val old = _state.value.filters.sort
        if (sort == old) return
        _state.update { it.copy(filters = it.filters.copy(sort = sort)) }
        if (sort.api == old.api) return
        startFeed(Kind.Popular, query = "", debounce = 0)
        (DiscoverQueryParser.parse(_state.value.query) as? DiscoverQuery.Text)?.let {
            _state.update { s -> s.copy(results = FeedState.Loading) }
            startFeed(Kind.Results, it.query, debounce = 0)
        }
    }

    /**
     * The list reached its end: fetch the next page of the search if there is one, of the popular list otherwise.
     *
     * [auto] is a footer that came into view by itself (the list is short, or cards dropped out under the
     * filters), as opposed to the user scrolling to the end. Automatic paging asks once per page count and
     * stops after [AUTO_FILL_PAGES] pages in a row that added no card to the list ([FeedState.Loaded.stalled]);
     * a user scroll always goes through and starts the count again.
     */
    fun loadMore(auto: Boolean = false) = loadMore(if (_state.value.results != null) Kind.Results else Kind.Popular, auto)

    /** The pasted link in [DiscoverState.pendingOpen] was taken: clears it and the field. */
    fun consumeOpen() {
        _state.update { it.copy(pendingOpen = null, query = "", linkError = false) }
    }

    /**
     * [repoId]'s card came on screen: fetch its detail once. The slot is [CardSlot.Loading] until it
     * arrives, then [CardSlot.Ready], or [CardSlot.Hidden] when the repo is not a usable chat model or the call failed.
     */
    fun onVisible(repoId: String) {
        var claimed = false
        _state.update {
            claimed = repoId !in it.slots
            if (claimed) it.copy(slots = it.slots + (repoId to CardSlot.Loading)) else it
        }
        if (!claimed) return
        val current = _state.value
        val summary = listOf(current.popular, current.results)
            .firstNotNullOfOrNull { feed -> (feed as? FeedState.Loaded)?.repos?.firstOrNull { it.id == repoId } }
            ?: run {
                // The repo left the lists (a new query): give the claim back so a later sighting fetches it.
                _state.update { it.copy(slots = it.slots - repoId) }
                return
            }
        scope.launch {
            val slot: CardSlot? = try {
                val repo = detail(repoId)
                val card = withContext(dispatcher) { DiscoverLogic.cardFor(summary, repo, deviceProfile.await()) }
                if (card != null) CardSlot.Ready(card) else CardSlot.Hidden
            } catch (e: HfException) {
                // Offline, rate limit, 5xx may pass: retried. Not found, gated or unreadable will not.
                if (e.error is HfError.Offline || e.error is HfError.RateLimited || e.error is HfError.Http) null else CardSlot.Hidden
            }
            if (slot == null) {
                retryDetailLater(repoId)
                return@launch
            }
            detailAttempts.remove(repoId)
            // A reload in between cleared the slots: its answer is stale.
            _state.update { if (repoId in it.slots) it.copy(slots = it.slots + (repoId to slot)) else it }
        }
    }

    /**
     * A transient detail failure: after a backoff (doubling) the slot is removed, which the list reads as
     * "not fetched yet" and asks again. After [DETAIL_MAX_ATTEMPTS] failures the card is hidden until the
     * feed is reloaded (a new search, a sort change, Try again clear it).
     */
    private suspend fun retryDetailLater(repoId: String) {
        val attempt = detailAttempts.merge(repoId, 1, Int::plus) ?: 1
        if (attempt >= DETAIL_MAX_ATTEMPTS) {
            gaveUp += repoId
            _state.update { if (repoId in it.slots) it.copy(slots = it.slots + (repoId to CardSlot.Hidden)) else it }
            return
        }
        delay(retryBackoffMs * (1L shl (attempt - 1)))
        _state.update { if (it.slots[repoId] == CardSlot.Loading) it.copy(slots = it.slots - repoId) else it }
    }

    private fun cancelFeed(kind: Kind) {
        generations.getValue(kind).incrementAndGet()
        autoStreaks.remove(kind)
        autoReady.remove(kind)
        autoSizes.remove(kind)
        firstJobs.remove(kind)?.cancel()
        moreJobs.remove(kind)?.cancel()
    }

    /** Replaces [kind]'s feed with the first page for [query], after [debounce] ms. */
    private fun startFeed(kind: Kind, query: String, debounce: Long) {
        cancelFeed(kind)
        val generation = generations.getValue(kind).get()
        // A new feed is a fresh start for the cards that gave up on a flaky call, too.
        val retry = gaveUp.toSet()
        gaveUp.clear()
        retry.forEach { detailAttempts.remove(it) }
        _state.update { it.withFeed(kind, FeedState.Loading).copy(slots = it.slots - retry) }
        firstJobs[kind] = scope.launch {
            if (debounce > 0) delay(debounce)
            val feed = try {
                search(query, _state.value.filters.sort.api).let { FeedState.Loaded(it.items, it.nextCursor) }
            } catch (e: HfException) {
                FeedState.Failed(e.error)
            }
            _state.update { if (generations.getValue(kind).get() == generation) it.withFeed(kind, feed) else it }
        }
    }

    private fun loadMore(kind: Kind, auto: Boolean) {
        val feed = _state.value.feedOf(kind) as? FeedState.Loaded ?: return
        val cursor = feed.next ?: return
        // `loadingMore` only: it is cleared in the same update that appends the page, while the job
        // can still read as active for a moment after it, which dropped the next automatic ask.
        if (feed.loadingMore) return
        val ready = readyCards(_state.value, kind)
        if (auto) {
            // Once per page count, and only while pages keep adding cards.
            if (autoSizes[kind] == feed.repos.size) return
            autoSizes[kind] = feed.repos.size
            val streak = if (ready > (autoReady[kind] ?: 0)) 0 else (autoStreaks[kind] ?: 0) + 1
            autoStreaks[kind] = streak
            autoReady[kind] = ready
            if (streak >= AUTO_FILL_PAGES) {
                _state.update { it.updateLoaded(kind) { loaded -> loaded.copy(stalled = true) } }
                return
            }
        } else {
            autoStreaks[kind] = 0
            autoReady[kind] = ready
        }
        val generation = generations.getValue(kind).get()
        _state.update { it.updateLoaded(kind) { loaded -> loaded.copy(loadingMore = true, moreFailed = false, stalled = false) } }
        moreJobs[kind] = scope.launch {
            val page = try {
                searchNext(cursor)
            } catch (e: HfException) {
                null
            }
            _state.update { s ->
                if (generations.getValue(kind).get() != generation) return@update s
                s.updateLoaded(kind) { loaded ->
                    if (page == null) {
                        loaded.copy(loadingMore = false, moreFailed = true)
                    } else {
                        val have = loaded.repos.mapTo(HashSet()) { it.id }
                        loaded.copy(
                            repos = loaded.repos + page.items.filter { it.id !in have },
                            next = page.nextCursor,
                            loadingMore = false,
                            pages = loaded.pages + 1,
                        )
                    }
                }
            }
        }
    }

    private fun readyCards(state: DiscoverState, kind: Kind): Int =
        (if (kind == Kind.Popular) state.popularShown else state.resultsShown).count { state.slots[it.id] is CardSlot.Ready }

    private fun DiscoverState.feedOf(kind: Kind): FeedState? = if (kind == Kind.Popular) popular else results

    private fun DiscoverState.withFeed(kind: Kind, feed: FeedState): DiscoverState =
        if (kind == Kind.Popular) copy(popular = feed) else copy(results = feed)

    private fun DiscoverState.updateLoaded(kind: Kind, change: (FeedState.Loaded) -> FeedState.Loaded): DiscoverState {
        val loaded = feedOf(kind) as? FeedState.Loaded ?: return this
        return withFeed(kind, change(loaded))
    }

    companion object {
        /** Pause in typing after which a query is searched. */
        const val SEARCH_DEBOUNCE_MS = 350L

        /** Pages in a row that add no visible card after which automatic paging stops. */
        const val AUTO_FILL_PAGES = 5

        const val DETAIL_RETRY_BACKOFF_MS = 2_000L
        private const val DETAIL_MAX_ATTEMPTS = 4
    }
}
