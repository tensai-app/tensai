package io.github.hokanosekai.tensai.ui.screens.models

import io.github.hokanosekai.tensai.compat.Verdict
import io.github.hokanosekai.tensai.hf.HfGate
import io.github.hokanosekai.tensai.hf.HfRepoSummary
import io.github.hokanosekai.tensai.hf.HfSort
import org.junit.Assert.assertEquals
import org.junit.Test

/** Filters and sort of the Discover lists (TEN-139), on cards built by hand. */
class DiscoverFiltersTest {

    private fun card(
        id: String,
        verdict: Verdict = Verdict.RunsWell,
        sizeMib: Long = 1000,
        tools: Boolean = false,
        vision: Boolean = false,
    ) = RepoCardModel(
        id, id.substringAfter('/'), id.substringBefore('/'), 2_000_000_000, verdict, "Q4_K_M",
        sizeMib * 1024 * 1024, tools, vision, false, 10, 1,
    )

    private fun repo(id: String) =
        HfRepoSummary(id, id.substringBefore('/'), id.substringAfter('/'), 10, 1, null, null, HfGate.None)

    private val repos = listOf("a/big", "b/small", "c/tools", "d/loading", "e/vision", "f/hidden").map(::repo)
    private val slots = mapOf(
        "a/big" to CardSlot.Ready(card("a/big", Verdict.WontFit, sizeMib = 9000)),
        "b/small" to CardSlot.Ready(card("b/small", sizeMib = 300)),
        "c/tools" to CardSlot.Ready(card("c/tools", sizeMib = 800, tools = true)),
        "d/loading" to CardSlot.Loading,
        "e/vision" to CardSlot.Ready(card("e/vision", Verdict.TightFit, sizeMib = 1500, vision = true)),
        "f/hidden" to CardSlot.Hidden,
    )

    private fun ids(filters: DiscoverFilters, exclude: Set<String> = emptySet()) =
        DiscoverLogic.shown(repos, slots, filters, exclude).map { it.id }

    @Test
    fun fitsMyPhoneIsOnByDefaultAndHidesWontFitButKeepsSkeletons() {
        assertEquals(listOf("b/small", "c/tools", "d/loading", "e/vision"), ids(DiscoverFilters()))
    }

    @Test
    fun turningFitsOffBringsTheBigOnesBackAndAHiddenRepoStaysOut() {
        assertEquals(
            listOf("a/big", "b/small", "c/tools", "d/loading", "e/vision"),
            ids(DiscoverFilters(fits = false)),
        )
    }

    @Test
    fun toolsAndVisionNeedTheBadgeAndKeepTheCardsStillLoading() {
        // A card with no detail yet stays (it may qualify): the skeleton goes once the detail says no.
        assertEquals(listOf("c/tools", "d/loading"), ids(DiscoverFilters(tools = true)))
        assertEquals(listOf("d/loading", "e/vision"), ids(DiscoverFilters(vision = true)))
        assertEquals(listOf("d/loading"), ids(DiscoverFilters(tools = true, vision = true)))
    }

    @Test
    fun sizeSortsTheLoadedCardsSmallestFirstAndTheLoadingOnesLast() {
        assertEquals(
            listOf("b/small", "c/tools", "e/vision", "d/loading"),
            ids(DiscoverFilters(sort = DiscoverSort.Size)),
        )
    }

    @Test
    fun theHubOrderIsKeptForTheOtherSorts() {
        assertEquals(ids(DiscoverFilters()), ids(DiscoverFilters(sort = DiscoverSort.Likes)))
        assertEquals(ids(DiscoverFilters()), ids(DiscoverFilters(sort = DiscoverSort.Recent)))
    }

    @Test
    fun excludedIdsAreMatchedCaseInsensitively() {
        assertEquals(listOf("c/tools", "d/loading", "e/vision"), ids(DiscoverFilters(), exclude = setOf("b/small")))
        val upper = DiscoverLogic.shown(listOf(repo("B/Small")), mapOf("B/Small" to CardSlot.Loading), DiscoverFilters(), setOf("b/small"))
        assertEquals(emptyList<String>(), upper.map { it.id })
    }

    @Test
    fun sortsMapToTheHubsOrders() {
        assertEquals(HfSort.Downloads, DiscoverSort.Downloads.api)
        assertEquals(HfSort.Likes, DiscoverSort.Likes.api)
        assertEquals(HfSort.LastModified, DiscoverSort.Recent.api)
        // The Hub has no size sort: Size reorders what is loaded and asks for the most downloaded.
        assertEquals(HfSort.Downloads, DiscoverSort.Size.api)
    }
}
