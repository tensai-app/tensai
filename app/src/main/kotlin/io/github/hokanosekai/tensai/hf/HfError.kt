package io.github.hokanosekai.tensai.hf

/** What went wrong with a Hugging Face call, for the UI to map to a message. */
sealed interface HfError {
    /** No connection, DNS failure, timeout. */
    data object Offline : HfError

    /** 404. */
    data object NotFound : HfError

    /**
     * 401 or 403 with no usable token, or 403 with one: a gated repo (the Hub answers 401 for an unknown
     * repo when anonymous, so "Gated" is the honest reading of the code, not proof). With a token,
     * 403 means the account has no access yet (licence not accepted).
     */
    data class Gated(val code: Int) : HfError

    /**
     * The token the user configured was refused: the Hub answered 401 to a call that carried it, after
     * an anonymous 401/403. The token is invalid or revoked; a valid one without access is [Gated] (403).
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
