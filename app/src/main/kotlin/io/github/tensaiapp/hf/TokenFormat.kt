package io.github.tensaiapp.hf

import okhttp3.Request

/**
 * What a Hugging Face token may look like, checked before it is stored, validated against the Hub
 * or put in a header: printable ASCII without spaces, 8 to 256 characters. Real tokens are `hf_`
 * plus 34 letters and digits; this only has to keep junk (and anything that could break a header)
 * out, not to recognise a token.
 */
object TokenFormat {
    private val shape = Regex("^[\\x21-\\x7E]{8,256}$")

    fun isValid(token: String): Boolean = shape.matches(token)

    /** [token] when it is well formed, else null: a malformed stored value behaves as "no token". */
    fun usable(token: String?): String? = token?.takeIf(::isValid)
}

/**
 * Adds `Authorization: Bearer` when [token] is [usable][TokenFormat.usable]. [okhttp3.Request.Builder.header]
 * puts the offending value in its exception message, so a refusal is swallowed here and nothing
 * about the token is ever thrown or logged.
 */
internal fun Request.Builder.bearer(token: String?): Request.Builder {
    val usable = TokenFormat.usable(token) ?: return this
    try {
        header("Authorization", "Bearer $usable")
    } catch (_: IllegalArgumentException) {
        // Deliberately silent, see above.
    }
    return this
}
