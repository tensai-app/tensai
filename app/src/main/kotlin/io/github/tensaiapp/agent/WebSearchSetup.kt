package io.github.tensaiapp.agent

import io.github.tensaiapp.data.SearchProviderChoice
import io.github.tensaiapp.websearch.Brave
import io.github.tensaiapp.websearch.DuckDuckGoHtml
import io.github.tensaiapp.websearch.FallbackSearch
import io.github.tensaiapp.websearch.HttpFetcher
import io.github.tensaiapp.websearch.Marginalia
import io.github.tensaiapp.websearch.SearchError
import io.github.tensaiapp.websearch.SearchProvider
import io.github.tensaiapp.websearch.Tavily
import io.github.tensaiapp.websearch.Wikipedia

/** What Settings holds for `web_search`: the chosen provider and, for one that needs it, its API key. */
class SearchSelection(val choice: SearchProviderChoice, val key: String?)

/** The name shown to the user and sent to the model for a [SearchProvider.name]. */
fun providerDisplayName(name: String): String = when (name) {
    "duckduckgo" -> "DuckDuckGo"
    "wikipedia" -> "Wikipedia"
    "tavily" -> "Tavily"
    "brave" -> "Brave"
    "marginalia" -> "Marginalia"
    else -> name
}

private val SearchProviderChoice.displayName: String
    get() = when (this) {
        SearchProviderChoice.DuckDuckGo -> "DuckDuckGo"
        SearchProviderChoice.Brave -> "Brave"
        SearchProviderChoice.Tavily -> "Tavily"
    }

/**
 * Builds the providers of `web_search` from the Settings choice (TEN-181).
 *
 * The chain never moves *towards* a keyed provider: DuckDuckGo failing falls to Wikipedia then Marginalia, which
 * need no key, and never to a Tavily or Brave key the user did not pick. A keyed provider that is out of quota or
 * blocked may fall to the free ones, since that costs nothing. A rejected key is not hidden by a fallback, it
 * stops the chain so the model can tell the user to fix it.
 *
 * @param wikipediaUserAgent the descriptive User-Agent Wikimedia requires.
 */
class SearchProviders(
    private val http: HttpFetcher,
    private val wikipediaUserAgent: String,
    private val wikipediaLanguage: () -> String = { "en" },
) {
    /** The chosen provider alone, what the Test button runs. `null` when it needs a key that is not set. */
    fun single(selection: SearchSelection): SearchProvider? = when (selection.choice) {
        SearchProviderChoice.DuckDuckGo -> DuckDuckGoHtml(http)
        SearchProviderChoice.Tavily -> selection.key?.takeIf { it.isNotBlank() }?.let { Tavily(http, it) }
        SearchProviderChoice.Brave -> selection.key?.takeIf { it.isNotBlank() }?.let { Brave(http, it) }
    }

    /** The chain `web_search` runs, `null` when the chosen provider needs a key that is not set. */
    fun chain(selection: SearchSelection): FallbackSearch? {
        val primary = single(selection) ?: return null
        val free = listOf(Wikipedia(http, wikipediaUserAgent, wikipediaLanguage()), Marginalia(http))
        val fallbacks = if (selection.choice == SearchProviderChoice.DuckDuckGo) free else listOf(DuckDuckGoHtml(http)) + free
        return FallbackSearch(primary, fallbacks)
    }

    companion object {
        fun displayName(choice: SearchProviderChoice): String = choice.displayName
    }
}

/**
 * Model-facing text for a [SearchError], one sentence that says what happened and what to do next. [who] is the
 * provider or site it came from.
 */
fun SearchError.toModelMessage(who: String): String = when (this) {
    is SearchError.Blocked -> "$who blocked the request ($reason). Do not retry now; tell the user the search is unavailable."
    is SearchError.RateLimited -> "$who is rate limiting requests" + (retryAfterSeconds?.let { ", retry in ${it}s" } ?: "") + ". Do not retry now."
    is SearchError.QuotaExceeded -> "$who quota is used up ($detail). Tell the user."
    is SearchError.Unauthorized -> "$who rejected the API key. Ask the user to fix the key in Settings > Tools > Internet."
    is SearchError.FormatChanged -> "$who answered in a format this app does not understand ($detail). Tell the user the search is unavailable."
    is SearchError.Network -> "network error reaching $who ($detail). Tell the user to check the connection."
    is SearchError.Unsupported -> "$who returned content that cannot be read as text ($detail)."
    is SearchError.UnsafeUrl -> "this address is not allowed ($reason): only public https pages can be read."
}

/**
 * Runs one real query through the provider chosen in Settings, for the Test button. The provider alone, no
 * fallback: a green test must mean *this* provider answered.
 */
class SearchTester(
    private val providers: SearchProviders,
    private val selection: suspend (SearchProviderChoice) -> SearchSelection,
    private val clock: () -> Long = System::nanoTime,
) {
    sealed interface Outcome {
        /** The provider answered, in [millis] ms (an empty answer still counts: it answered). */
        data class Answered(val millis: Long) : Outcome

        /** The provider needs a key and none is stored. */
        data object NoKey : Outcome

        data class Failed(val error: SearchError) : Outcome
    }

    suspend fun run(choice: SearchProviderChoice): Outcome {
        val provider = providers.single(selection(choice)) ?: return Outcome.NoKey
        val start = clock()
        return try {
            provider.search(QUERY, 1)
            Outcome.Answered((clock() - start) / 1_000_000)
        } catch (e: SearchError) {
            Outcome.Failed(e)
        }
    }

    private companion object {
        const val QUERY = "open source"
    }
}
