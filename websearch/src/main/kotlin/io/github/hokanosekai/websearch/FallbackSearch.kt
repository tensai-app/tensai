package io.github.hokanosekai.websearch

/**
 * Tries [primary] then each of [fallbacks] in order and returns the first non-empty answer.
 * [SearchResult.source] tells which provider actually answered.
 *
 * Moves on after [SearchError.Blocked], [SearchError.RateLimited], [SearchError.QuotaExceeded],
 * [SearchError.Network] and [SearchError.FormatChanged]. Anything else (bad key) is the user's
 * to fix and is rethrown at once, earlier errors attached as suppressed.
 *
 * When nothing returned results: if at least one provider answered (empty), the result is
 * empty, since "no hit" is a real answer and beats a primary that was merely blocked. If every
 * provider failed, the primary's error is thrown with the others attached as suppressed.
 */
class FallbackSearch(
    private val primary: SearchProvider,
    private val fallbacks: List<SearchProvider>,
) : SearchProvider {
    override val name: String get() = primary.name

    override suspend fun search(query: String, limit: Int): List<SearchResult> {
        val errors = mutableListOf<SearchError>()
        var answeredEmpty = false
        for (provider in listOf(primary) + fallbacks) {
            try {
                val results = provider.search(query, limit)
                if (results.isNotEmpty()) return results
                answeredEmpty = true
            } catch (e: SearchError) {
                if (!e.isRecoverable()) {
                    errors.forEach { e.addSuppressed(it) }
                    throw e
                }
                errors += e
            }
        }
        if (answeredEmpty || errors.isEmpty()) return emptyList()
        val first = errors.first()
        errors.drop(1).forEach { first.addSuppressed(it) }
        throw first
    }

    private fun SearchError.isRecoverable(): Boolean = when (this) {
        is SearchError.Blocked,
        is SearchError.RateLimited,
        is SearchError.Network,
        is SearchError.QuotaExceeded,
        is SearchError.FormatChanged,
        -> true
        else -> false
    }
}
