package io.github.hokanosekai.tensai.ui.screens.models

import io.github.hokanosekai.tensai.compat.DeviceProfile
import io.github.hokanosekai.tensai.hf.HfError
import io.github.hokanosekai.tensai.hf.HfException
import io.github.hokanosekai.tensai.hf.HfGate
import io.github.hokanosekai.tensai.hf.HfPage
import io.github.hokanosekai.tensai.hf.HfQuantFile
import io.github.hokanosekai.tensai.hf.HfRepoDetail
import io.github.hokanosekai.tensai.hf.HfRepoSummary
import io.github.hokanosekai.tensai.hf.HfSort
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Search, debounce, cancellation, pagination, filters and links of the Discover state holder
 * (TEN-139), against a fake Hub: no network, no Android. The debounce is 60 ms here.
 */
class DiscoverSearchTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    private val gib = 1024L * 1024 * 1024
    private val phone = DeviceProfile(
        totalRamBytes = 8 * gib,
        availableRamBytes = 4 * gib,
        socModel = "test",
        bigCoreCount = 4,
        hasVulkan = false,
        hasOpenCl = false,
    )
    private val debounce = 60L

    private fun summary(id: String) =
        HfRepoSummary(id, id.substringBefore('/'), id.substringAfter('/'), 1000, 10, null, null, HfGate.None)

    private fun detail(id: String, sizeMib: Long = 1200, tools: Boolean = true) = HfRepoDetail(
        summary = summary(id),
        paramCount = 2_000_000_000,
        architecture = "qwen3",
        contextLength = 32_768,
        supportsTools = tools,
        hasVision = false,
        quants = listOf(HfQuantFile("m-Q4_K_M.gguf", "Q4_K_M", sizeMib * 1024 * 1024, null)),
    )

    private class Fake {
        val searches = Collections.synchronizedList(mutableListOf<Pair<String, HfSort>>())
        val nexts = Collections.synchronizedList(mutableListOf<String>())
        var search: suspend (String, HfSort) -> HfPage = { _, _ -> HfPage(emptyList(), null) }
        var next: suspend (String) -> HfPage = { error("no next page for $it") }
        var detail: (String) -> HfRepoDetail = { error("no detail for $it") }
        val detailCalls = AtomicInteger()
    }

    private fun viewModel(fake: Fake) = DiscoverViewModel(
        scope = scope,
        search = { q, s ->
            fake.searches += q to s
            fake.search(q, s)
        },
        searchNext = {
            fake.nexts += it
            fake.next(it)
        },
        detail = {
            fake.detailCalls.incrementAndGet()
            fake.detail(it)
        },
        profile = { phone },
        debounceMs = debounce,
        retryBackoffMs = 20,
    )

    private suspend fun DiscoverViewModel.await(what: String = "state", predicate: (DiscoverState) -> Boolean): DiscoverState =
        try {
            withTimeout(5_000) { state.first(predicate) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting for $what, last state: ${state.value}")
        }

    private fun page(vararg ids: String, next: String? = null) = HfPage(ids.map(::summary), next)

    private fun started(fake: Fake): DiscoverViewModel = viewModel(fake).also { it.start() }

    private fun results(s: DiscoverState) = s.results as? FeedState.Loaded

    // ---- debounce and cancellation

    @Test
    fun theHomeLoadsThePopularListOfTheCurrentSort() = runBlocking {
        val fake = Fake().apply { search = { _, _ -> page("a/one") } }
        val vm = started(fake)
        vm.await { it.popular is FeedState.Loaded }

        assertEquals(listOf("" to HfSort.Downloads), fake.searches.toList())
        assertNull(vm.state.value.results)
    }

    @Test
    fun typingWaitsForTheDebounceAndSearchesOnlyTheLastText() = runBlocking {
        val fake = Fake().apply { search = { q, _ -> if (q.isEmpty()) page() else page("a/$q") } }
        val vm = started(fake)
        vm.await { it.popular is FeedState.Loaded }

        "qwen".forEachIndexed { i, _ ->
            vm.onQueryChange("qwen".substring(0, i + 1))
            delay(debounce / 4)
        }
        // Skeletons straight away, no request yet.
        assertEquals(FeedState.Loading, vm.state.value.results)
        assertEquals(1, fake.searches.size)

        val s = vm.await { results(it) != null }

        assertEquals(listOf("" to HfSort.Downloads, "qwen" to HfSort.Downloads), fake.searches.toList())
        assertEquals(listOf("a/qwen"), results(s)!!.repos.map { it.id })
    }

    @Test
    fun aNewKeystrokeCancelsTheRequestInFlightAndItsLateAnswerIsDropped() = runBlocking {
        val slowStarted = CompletableDeferred<Unit>()
        val slowCancelled = CompletableDeferred<Boolean>()
        val fake = Fake().apply {
            search = { q, _ ->
                when (q) {
                    "slow" -> {
                        slowStarted.complete(Unit)
                        try {
                            delay(10_000)
                            page("old/answer")
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            slowCancelled.complete(true)
                            throw e
                        }
                    }
                    "fast" -> page("new/answer")
                    else -> page()
                }
            }
        }
        val vm = started(fake)
        vm.await { it.popular is FeedState.Loaded }

        vm.onQueryChange("slow")
        withTimeout(5_000) { slowStarted.await() }
        vm.onQueryChange("fast")

        assertTrue(withTimeout(5_000) { slowCancelled.await() })
        val s = vm.await { results(it) != null }
        assertEquals(listOf("new/answer"), results(s)!!.repos.map { it.id })
    }

    @Test
    fun clearingTheFieldReturnsToTheHomeAndDropsAPendingQuery() = runBlocking {
        val fake = Fake().apply { search = { _, _ -> page("a/one") } }
        val vm = started(fake)
        vm.await { it.popular is FeedState.Loaded }

        vm.onQueryChange("qw")
        vm.onQueryChange("")
        delay(debounce * 3)

        assertNull(vm.state.value.results)
        assertEquals(listOf("" to HfSort.Downloads), fake.searches.toList())
    }

    @Test
    fun aFailedSearchIsReportedAndTryAgainSearchesAtOnce() = runBlocking {
        var online = false
        val fake = Fake().apply {
            search = { q, _ -> if (q.isEmpty() || online) page("a/one") else throw HfException(HfError.Offline) }
        }
        val vm = started(fake)
        vm.await { it.popular is FeedState.Loaded }

        vm.onQueryChange("qwen")
        val failed = vm.await { it.results is FeedState.Failed }
        assertEquals(FeedState.Failed(HfError.Offline), failed.results)

        online = true
        vm.retrySearch()
        assertEquals(FeedState.Loading, vm.state.value.results)
        vm.await { results(it) != null }
        assertEquals(listOf("", "qwen", "qwen"), fake.searches.map { it.first })
    }

    // ---- pagination

    @Test
    fun theNextPageIsFetchedWithTheCursorAndAppended() = runBlocking {
        val fake = Fake().apply {
            search = { q, _ -> if (q.isEmpty()) page() else page("a/1", "a/2", next = "cursor-2") }
            next = { page("a/2", "a/3", next = "cursor-3") }
        }
        val vm = started(fake)
        vm.onQueryChange("qwen")
        vm.await { results(it) != null }

        vm.loadMore()
        val s = vm.await { results(it)?.pages == 2 }

        assertEquals(listOf("cursor-2"), fake.nexts.toList())
        // a/2 came twice (the Hub's pages can overlap) and is listed once.
        assertEquals(listOf("a/1", "a/2", "a/3"), results(s)!!.repos.map { it.id })
        assertEquals("cursor-3", results(s)!!.next)
        assertFalse(results(s)!!.loadingMore)
    }

    @Test
    fun loadMoreOnTheLastPageOrWhileLoadingDoesNothing() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val fake = Fake().apply {
            search = { q, _ -> if (q.isEmpty()) page("p/1") else page("a/1", next = "c2") }
            next = {
                gate.await()
                page("a/2")
            }
        }
        val vm = started(fake)
        vm.await { it.popular is FeedState.Loaded }
        vm.onQueryChange("qwen")
        vm.await { results(it) != null }

        repeat(3) { vm.loadMore() }
        vm.await { results(it)?.loadingMore == true }
        gate.complete(Unit)
        vm.await { results(it)?.pages == 2 }
        // Now on the last page.
        vm.loadMore()
        delay(100)

        assertEquals(1, fake.nexts.size)
    }

    @Test
    fun aFailedNextPageKeepsTheListAndFlagsTheFooterInsteadOfLooping() = runBlocking {
        var fail = true
        val fake = Fake().apply {
            search = { q, _ -> if (q.isEmpty()) page() else page("a/1", next = "c2") }
            next = { if (fail) throw HfException(HfError.Offline) else page("a/2") }
        }
        val vm = started(fake)
        vm.onQueryChange("qwen")
        vm.await { results(it) != null }

        vm.loadMore()
        val failed = vm.await { results(it)?.moreFailed == true }
        assertEquals(listOf("a/1"), results(failed)!!.repos.map { it.id })
        assertFalse(results(failed)!!.loadingMore)

        fail = false
        vm.loadMore()
        val ok = vm.await { results(it)?.pages == 2 }
        assertEquals(listOf("a/1", "a/2"), results(ok)!!.repos.map { it.id })
        assertFalse(results(ok)!!.moreFailed)
    }

    @Test
    fun aPageThatArrivesAfterANewQueryIsDropped() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val fake = Fake().apply {
            search = { q, _ ->
                when (q) {
                    "one" -> page("one/1", next = "c2")
                    "two" -> page("two/1")
                    else -> page()
                }
            }
            next = {
                gate.await()
                page("one/2")
            }
        }
        val vm = started(fake)
        vm.onQueryChange("one")
        vm.await { results(it) != null }
        vm.loadMore()
        vm.await { results(it)?.loadingMore == true }

        vm.onQueryChange("two")
        val two = vm.await { results(it)?.repos?.firstOrNull()?.id == "two/1" }
        gate.complete(Unit)
        delay(100)

        assertEquals(listOf("two/1"), results(vm.state.value)!!.repos.map { it.id })
        assertEquals(two.results, vm.state.value.results)
    }

    @Test
    fun thePopularListPaginatesToo() = runBlocking {
        val fake = Fake().apply {
            search = { _, _ -> page("a/1", next = "c2") }
            next = { page("a/2") }
        }
        val vm = started(fake)
        vm.await { it.popular is FeedState.Loaded }

        vm.loadMore()
        val s = vm.await { (it.popular as? FeedState.Loaded)?.pages == 2 }

        assertEquals(listOf("a/1", "a/2"), (s.popular as FeedState.Loaded).repos.map { it.id })
    }

    // ---- sort and filters

    @Test
    fun changingTheSortAsksTheHubAgainForPopularAndForTheSearch() = runBlocking {
        val fake = Fake().apply { search = { q, _ -> if (q.isEmpty()) page("p/1") else page("a/1") } }
        val vm = started(fake)
        vm.onQueryChange("qwen")
        vm.await { results(it) != null && it.popular is FeedState.Loaded }

        vm.setSort(DiscoverSort.Likes)
        vm.await { results(it) != null && it.popular is FeedState.Loaded }

        assertEquals(DiscoverSort.Likes, vm.state.value.filters.sort)
        assertEquals(
            setOf("" to HfSort.Downloads, "qwen" to HfSort.Downloads, "" to HfSort.Likes, "qwen" to HfSort.Likes),
            fake.searches.toSet(),
        )
    }

    @Test
    fun sizeSortReordersWhatIsLoadedWithoutANewRequest() = runBlocking {
        val fake = Fake().apply {
            search = { _, _ -> page("a/big", "b/small") }
            detail = { id -> detail(id, sizeMib = if (id == "a/big") 3000 else 500) }
        }
        val vm = started(fake)
        vm.await { it.popular is FeedState.Loaded }
        vm.onVisible("a/big")
        vm.onVisible("b/small")
        vm.await { s -> s.slots.values.all { it is CardSlot.Ready } && s.slots.size == 2 }
        assertEquals(listOf("a/big", "b/small"), vm.state.value.popularShown.map { it.id })

        vm.setSort(DiscoverSort.Size)

        assertEquals(listOf("b/small", "a/big"), vm.state.value.popularShown.map { it.id })
        assertEquals(1, fake.searches.size)
    }

    @Test
    fun theFiltersApplyToPopularAndToTheResults() = runBlocking {
        val fake = Fake().apply {
            search = { _, _ -> page("a/tools", "b/plain", "c/huge") }
            detail = { id ->
                when (id) {
                    "a/tools" -> detail(id, tools = true)
                    "b/plain" -> detail(id, tools = false)
                    else -> detail(id, sizeMib = 40_000)
                }
            }
        }
        val vm = started(fake)
        vm.onQueryChange("x")
        vm.await { results(it) != null && it.popular is FeedState.Loaded }
        listOf("a/tools", "b/plain", "c/huge").forEach(vm::onVisible)
        vm.await { s -> s.slots.size == 3 && s.slots.values.all { it is CardSlot.Ready } }

        // Fits my phone is on: the 40 GB repo is out of both lists.
        assertEquals(listOf("a/tools", "b/plain"), vm.state.value.popularShown.map { it.id })
        assertEquals(listOf("a/tools", "b/plain"), vm.state.value.resultsShown.map { it.id })

        vm.setTools(true)
        assertEquals(listOf("a/tools"), vm.state.value.popularShown.map { it.id })
        assertEquals(listOf("a/tools"), vm.state.value.resultsShown.map { it.id })

        vm.setTools(false)
        vm.setFits(false)
        assertEquals(listOf("a/tools", "b/plain", "c/huge"), vm.state.value.resultsShown.map { it.id })
        // Filters are local: no request.
        assertEquals(2, fake.searches.size)
    }

    @Test
    fun theCatalogRespectsToolsAndVision() = runBlocking {
        val vm = started(Fake())
        val all = vm.await { it.recommended.isNotEmpty() }
        assertEquals(all.recommended, all.recommendedShown)

        vm.setVision(true)
        assertTrue(vm.state.value.recommendedShown.isEmpty())

        vm.setVision(false)
        vm.setTools(true)
        assertTrue(vm.state.value.recommendedShown.all { it.tools })
    }

    // ---- links

    @Test
    fun aHuggingFaceLinkOpensTheRepoWithItsFileAndNothingIsSearched() = runBlocking {
        val fake = Fake().apply { search = { _, _ -> page() } }
        val vm = started(fake)
        vm.await { it.popular is FeedState.Loaded }

        vm.onQueryChange("https://huggingface.co/bartowski/Qwen3-GGUF/blob/main/Qwen3-Q4_K_M.gguf")
        val event = vm.await("pending link") { it.pendingOpen != null }.pendingOpen

        assertEquals(DiscoverEvent.OpenRepo("bartowski/Qwen3-GGUF", "Qwen3-Q4_K_M.gguf"), event)
        assertEquals(1, fake.searches.size)
        // Taken once: back from the repo lands on the home.
        vm.consumeOpen()
        assertNull(vm.state.value.pendingOpen)
        assertEquals("", vm.state.value.query)
        assertNull(vm.state.value.results)
    }

    @Test
    fun aRepoLinkHasNoFile() = runBlocking {
        val vm = started(Fake())

        vm.onQueryChange("hf.co/bartowski/Qwen3-GGUF")

        assertEquals(DiscoverEvent.OpenRepo("bartowski/Qwen3-GGUF", null), vm.await("pending link") { it.pendingOpen != null }.pendingOpen)
    }

    @Test
    fun anyOtherUrlShowsTheErrorAtOnceAndMakesNoRequestAtAll() = runBlocking {
        val fake = Fake().apply { search = { _, _ -> page() } }
        val vm = started(fake)
        vm.await { it.popular is FeedState.Loaded }

        vm.onQueryChange("https://example.com/some/model.gguf")

        assertTrue(vm.state.value.linkError)
        assertNull(vm.state.value.results)
        delay(debounce * 3)
        assertEquals(1, fake.searches.size)
        assertEquals(0, fake.detailCalls.get())
        // The error goes with the text.
        vm.onQueryChange("")
        assertFalse(vm.state.value.linkError)
    }

    @Test
    fun aLinkTypedByHandIsNotOpenedHalfWay() = runBlocking {
        val vm = started(Fake())
        val link = "hf.co/bartowski/Qwen3-GGUF"

        // "hf.co/bartowski/Q" would already read as a repo: the debounce keeps it from opening.
        link.indices.drop(8).forEach { i ->
            vm.onQueryChange(link.substring(0, i + 1))
            delay(debounce / 6)
        }

        assertEquals(DiscoverEvent.OpenRepo("bartowski/Qwen3-GGUF", null), vm.await("pending link") { it.pendingOpen != null }.pendingOpen)
    }

    @Test
    fun aPendingLinkIsDroppedByTheNextKeystroke() = runBlocking {
        val vm = started(Fake())
        vm.onQueryChange("hf.co/bartowski/Qwen3-GGUF")
        vm.await { it.pendingOpen != null }

        vm.onQueryChange("qwen")

        assertNull(vm.state.value.pendingOpen)
    }

    // ---- paging cap

    @Test
    fun autoPagingStopsAfterPagesThatAddNoCardAndAUserScrollGoesOnAnyway() = runBlocking {
        var n = 0
        val fake = Fake().apply {
            search = { q, _ -> if (q.isEmpty()) page() else page("r/0", next = "c1") }
            next = { page("r/${++n}", next = "c${n + 1}") }
        }
        val vm = started(fake)
        vm.onQueryChange("qwen")
        vm.await { results(it) != null }

        // Nothing ever becomes a card (no detail asked), so no page adds one.
        var stalled = false
        var guard = 0
        while (!stalled && guard++ < 20) {
            vm.loadMore(auto = true)
            val size = results(vm.state.value)!!.repos.size
            vm.await { s -> results(s)!!.repos.size > size || results(s)!!.stalled }
            stalled = results(vm.state.value)!!.stalled
        }
        assertTrue(stalled)
        val pages = results(vm.state.value)!!.pages
        assertTrue("paged $pages times", pages <= DiscoverViewModel.AUTO_FILL_PAGES + 1)

        // Same count again, or more automatic asks: nothing.
        repeat(3) { vm.loadMore(auto = true) }
        delay(100)
        assertEquals(pages, results(vm.state.value)!!.pages)

        // The user scrolling to the end is always honoured and clears the flag.
        vm.loadMore(auto = false)
        val s = vm.await { results(it)!!.pages == pages + 1 }
        assertFalse(results(s)!!.stalled)
    }

    @Test
    fun theSameFooterDoesNotAskTwiceForOnePageCount() = runBlocking {
        val fake = Fake().apply {
            search = { q, _ -> if (q.isEmpty()) page() else page("r/0", next = "c1") }
            next = { page("r/1", next = "c2") }
        }
        val vm = started(fake)
        vm.onQueryChange("qwen")
        vm.await { results(it) != null }

        vm.loadMore(auto = true)
        vm.await { results(it)?.pages == 2 }
        vm.loadMore(auto = true)
        vm.await { results(it)?.pages == 3 }
        // Back to a size already asked at: ignored.
        fake.nexts.clear()
        repeat(3) { vm.loadMore(auto = true) }
        delay(100)

        assertTrue(fake.nexts.size <= 1)
    }

    // ---- transient detail failures

    @Test
    fun aTransientDetailFailureIsRetriedAfterABackoffAndNeverHidden() = runBlocking {
        val calls = AtomicInteger()
        val fake = Fake().apply {
            search = { _, _ -> page("a/one") }
            detail = { id -> if (calls.incrementAndGet() == 1) throw HfException(HfError.RateLimited(1)) else this@DiscoverSearchTest.detail(id) }
        }
        val vm = started(fake)
        vm.await { it.popular is FeedState.Loaded }

        vm.onVisible("a/one")
        // Skeleton while backing off, then the slot is released so the list asks again.
        vm.await { it.slots["a/one"] == null && calls.get() == 1 }
        vm.onVisible("a/one")
        val s = vm.await { it.slots["a/one"] is CardSlot.Ready }

        assertEquals(2, calls.get())
        assertTrue(s.slots["a/one"] is CardSlot.Ready)
    }

    @Test
    fun aRepoThatKeepsFailingGivesUpButANewSearchClearsIt() = runBlocking {
        var failing = true
        val fake = Fake().apply {
            search = { _, _ -> page("a/one") }
            detail = { id -> if (failing) throw HfException(HfError.Offline) else this@DiscoverSearchTest.detail(id) }
        }
        val vm = started(fake)
        vm.await { it.popular is FeedState.Loaded }

        repeat(6) {
            vm.onVisible("a/one")
            delay(250)
        }
        assertEquals(CardSlot.Hidden, vm.await { it.slots["a/one"] == CardSlot.Hidden }.slots["a/one"])

        failing = false
        vm.onQueryChange("x")
        vm.await { results(it) != null }
        assertNull(vm.state.value.slots["a/one"])
        vm.onVisible("a/one")
        // A search result carries a/one only if the fake says so: popular still lists it.
        val ready = vm.await { it.slots["a/one"] is CardSlot.Ready }
        assertTrue(ready.slots["a/one"] is CardSlot.Ready)
    }

    @Test
    fun aNotFoundDetailIsHiddenForGood() = runBlocking {
        val fake = Fake().apply {
            search = { _, _ -> page("a/one") }
            detail = { throw HfException(HfError.NotFound) }
        }
        val vm = started(fake)
        vm.await { it.popular is FeedState.Loaded }

        vm.onVisible("a/one")

        vm.await { it.slots["a/one"] == CardSlot.Hidden }
        delay(100)
        assertEquals(1, fake.detailCalls.get())
    }

    @Test
    fun aCardWhoseRepoLeftTheListsReleasesItsClaim() = runBlocking {
        val vm = started(Fake().apply { search = { _, _ -> page("a/one") } })
        vm.await { it.popular is FeedState.Loaded }

        vm.onVisible("ghost/repo")

        assertNull(vm.state.value.slots["ghost/repo"])
    }

    @Test
    fun aProfileThatCannotBeReadDoesNotKillTheScope() = runBlocking {
        val vm = DiscoverViewModel(
            scope = scope,
            search = { _, _ -> page("a/one") },
            detail = { detail(it) },
            profile = { throw IllegalStateException("no profile") },
            debounceMs = debounce,
        )
        vm.start()

        val s = vm.await { it.popular is FeedState.Loaded && it.deviceRamBytes > 0 }
        vm.onVisible("a/one")
        vm.await { it.slots["a/one"] is CardSlot.Ready }

        assertTrue(s.deviceRamBytes <= 4L * gib)
        assertTrue(scope.coroutineContext[kotlinx.coroutines.Job]!!.isActive)
    }
}
