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
import io.github.hokanosekai.tensai.ui.screens.onboarding.OnboardingCatalog
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Discover home's state machine against a fake search and fake details: no network, no Android. */
class DiscoverViewModelTest {

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

    private fun summary(id: String) =
        HfRepoSummary(id, id.substringBefore('/'), id.substringAfter('/'), 1000, 10, null, null, HfGate.None)

    private fun detail(id: String, sizeMib: Long) = HfRepoDetail(
        summary = summary(id),
        paramCount = 2_000_000_000,
        architecture = "qwen3",
        contextLength = 32_768,
        supportsTools = true,
        hasVision = false,
        quants = listOf(HfQuantFile("m-Q4_K_M.gguf", "Q4_K_M", sizeMib * 1024 * 1024, null)),
    )

    private class Fake(val page: (String) -> HfPage) {
        val queries = java.util.Collections.synchronizedList(mutableListOf<Pair<String, HfSort>>())
        val nextCalls = java.util.Collections.synchronizedList(mutableListOf<String>())
        var next: (String) -> HfPage = { error("no next page for $it") }
        val searches = AtomicInteger()
        val detailCalls = java.util.Collections.synchronizedList(mutableListOf<String>())
        var details: (String) -> HfRepoDetail = { error("no detail for $it") }
    }

    private fun viewModel(fake: Fake) = DiscoverViewModel(
        scope = scope,
        search = { query, sort ->
            fake.searches.incrementAndGet()
            fake.queries += query to sort
            fake.page(query)
        },
        searchNext = { cursor ->
            fake.nextCalls += cursor
            fake.next(cursor)
        },
        debounceMs = 60,
        detail = {
            fake.detailCalls += it
            fake.details(it)
        },
        profile = { phone },
        catalog = { OnboardingCatalog.all },
    )

    private suspend fun DiscoverViewModel.await(what: String = "state", predicate: (DiscoverState) -> Boolean): DiscoverState =
        try {
            withTimeout(5_000) { state.first(predicate) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("timed out waiting for $what, last state: ${state.value}")
        }

    private val repos = listOf("alice/small-GGUF", "bob/medium-GGUF", "carol/huge-GGUF")

    private fun fakeWith(vararg ids: String) = Fake { HfPage(ids.map(::summary), null) }.also { fake ->
        fake.details = { id -> detail(id, sizeMib = if (id.contains("huge")) 40_000 else 1200) }
    }

    @Test
    fun theRecommendedSectionIsRatedAsSoonAsTheProfileIsRead() = runBlocking {
        val vm = viewModel(fakeWith(*repos.toTypedArray()))
        vm.start()

        val s = vm.await { it.recommended.isNotEmpty() && it.popular is FeedState.Loaded }

        assertEquals(8 * gib, s.deviceRamBytes)
        assertEquals(DiscoverLogic.recommended(OnboardingCatalog.all, phone), s.recommended)
    }

    @Test
    fun popularStartsLoadingThenListsTheSearchOrder() = runBlocking {
        val vm = viewModel(fakeWith(*repos.toTypedArray()))
        assertEquals(FeedState.Loading, vm.state.value.popular)

        vm.start()
        val s = vm.await { it.popular is FeedState.Loaded }

        assertEquals(repos, s.popularShown.map { it.id })
    }

    @Test
    fun aCardIsASkeletonUntilItsDetailArrivesThenAVerdictCard() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val fake = fakeWith(*repos.toTypedArray())
        fake.details = { id -> runBlocking { gate.await() }; detail(id, 1200) }
        val vm = viewModel(fake)
        vm.start()
        vm.await { it.popular is FeedState.Loaded }

        vm.onVisible("alice/small-GGUF")

        assertEquals(CardSlot.Loading, vm.state.value.slots["alice/small-GGUF"])
        gate.complete(Unit)
        val s = vm.await { it.slots["alice/small-GGUF"] is CardSlot.Ready }
        val card = (s.slots["alice/small-GGUF"] as CardSlot.Ready).card
        assertEquals("small", card.name)
        assertEquals("Q4_K_M", card.quant)
    }

    @Test
    fun detailIsFetchedOncePerCardHoweverOftenItComesOnScreen() = runBlocking {
        val fake = fakeWith(*repos.toTypedArray())
        val vm = viewModel(fake)
        vm.start()
        vm.await { it.popular is FeedState.Loaded }

        repeat(3) { vm.onVisible("bob/medium-GGUF") }
        vm.await { it.slots["bob/medium-GGUF"] is CardSlot.Ready }
        vm.onVisible("bob/medium-GGUF")

        assertEquals(listOf("bob/medium-GGUF"), fake.detailCalls.toList())
    }

    @Test
    fun aRepoThatDoesNotFitDisappearsFromThePopularList() = runBlocking {
        val vm = viewModel(fakeWith(*repos.toTypedArray()))
        vm.start()
        vm.await { it.popular is FeedState.Loaded }

        repos.forEach(vm::onVisible)
        val s = vm.await { st -> repos.all { st.slots[it] !is CardSlot.Loading && it in st.slots } }

        // Its card exists (the "Fits my phone" filter, on by default, is what hides it).
        assertEquals(io.github.hokanosekai.tensai.compat.Verdict.WontFit, (s.slots["carol/huge-GGUF"] as CardSlot.Ready).card.verdict)
        assertEquals(listOf("alice/small-GGUF", "bob/medium-GGUF"), s.popularShown.map { it.id })
    }

    @Test
    fun aFailedDetailHidesThatCardOnly() = runBlocking {
        val fake = fakeWith(*repos.toTypedArray())
        fake.details = { id -> if (id.startsWith("alice")) throw HfException(HfError.NotFound) else detail(id, 1200) }
        val vm = viewModel(fake)
        vm.start()
        vm.await { it.popular is FeedState.Loaded }

        vm.onVisible("alice/small-GGUF")
        vm.onVisible("bob/medium-GGUF")
        val s = vm.await { it.slots["alice/small-GGUF"] == CardSlot.Hidden && it.slots["bob/medium-GGUF"] is CardSlot.Ready }

        // carol is still a skeleton here (never on screen): kept until its detail says otherwise.
        assertEquals(listOf("bob/medium-GGUF", "carol/huge-GGUF"), s.popularShown.map { it.id })
    }

    @Test
    fun aRepoAlreadyRecommendedIsNotListedTwice() = runBlocking {
        val inCatalog = OnboardingCatalog.all.first().hfPath
        val vm = viewModel(fakeWith(inCatalog.uppercase(), "alice/small-GGUF"))
        vm.start()

        val s = vm.await { it.popular is FeedState.Loaded && it.recommended.isNotEmpty() }

        assertEquals(listOf("alice/small-GGUF"), s.popularShown.map { it.id })
    }

    @Test
    fun offlineIsReportedAndTryAgainLoadsAgain() = runBlocking {
        var online = false
        val fake = Fake { if (online) HfPage(listOf(summary("alice/small-GGUF")), null) else throw HfException(HfError.Offline) }
        val vm = viewModel(fake)
        vm.start()

        val failed = vm.await { it.popular is FeedState.Failed && it.recommended.isNotEmpty() }
        assertEquals(FeedState.Failed(HfError.Offline), failed.popular)
        // The catalog part still came up: it needs no network.
        assertTrue(failed.recommended.isNotEmpty())

        online = true
        vm.load()
        val ok = vm.await { it.popular is FeedState.Loaded }

        assertEquals(listOf("alice/small-GGUF"), ok.popularShown.map { it.id })
        assertEquals(2, fake.searches.get())
    }

    @Test
    fun anyOtherHubErrorIsReportedAsFailed() = runBlocking {
        val vm = viewModel(Fake { throw HfException(HfError.RateLimited(30)) })
        vm.start()

        assertEquals(FeedState.Failed(HfError.RateLimited(30)), vm.await { it.popular is FeedState.Failed }.popular)
    }

    @Test
    fun startingTwiceSearchesOnce() = runBlocking {
        val fake = fakeWith(*repos.toTypedArray())
        val vm = viewModel(fake)
        vm.start()
        vm.await { it.popular is FeedState.Loaded }
        vm.start()

        assertEquals(1, fake.searches.get())
    }

    @Test
    fun reloadingForgetsTheCards() = runBlocking {
        val fake = fakeWith(*repos.toTypedArray())
        val vm = viewModel(fake)
        vm.start()
        vm.await { it.popular is FeedState.Loaded }
        vm.onVisible("bob/medium-GGUF")
        vm.await { it.slots["bob/medium-GGUF"] is CardSlot.Ready }

        vm.load()
        val s = vm.await { it.popular is FeedState.Loaded }

        assertTrue(s.slots.isEmpty())
    }
}
