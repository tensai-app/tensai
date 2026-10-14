package io.github.hokanosekai.tensai.hf

/** What went wrong with a Hugging Face call, for the UI to map to a message. */
sealed interface HfError {
    /** No connection, DNS failure, timeout. */
    data object Offline : HfError

    /** 404. */
    data object NotFound : HfError

    /**
     * 401 or 403: a gated repo, a bad token, or a repo that does not exist (the Hub answers 401 for
     * an unknown repo when anonymous, so "Gated" is the honest reading of the code, not proof).
     */
    data class Gated(val code: Int) : HfError

    /**
     * The token the user configured was refused (401) but the same call works anonymously: the
     * token is invalid or revoked, the repo is not gated. Retry without a token or ask for a new one.
     */
    data object InvalidToken : HfError

    /** 429. [retryAfterSeconds] from the `Retry-After` header when present. */
    data class RateLimited(val retryAfterSeconds: Long?) : HfError

    /** Any other non-2xx status. */
    data class Http(val code: Int) : HfError

    /** 2xx with a body that is not what the API documents. */
    data class Parse(val detail: String) : HfError
}

/** Thrown by [HfClient] suspend functions; [error] is the typed reason. */
class HfException(val error: HfError, cause: Throwable? = null) : Exception(error.toString(), cause)
