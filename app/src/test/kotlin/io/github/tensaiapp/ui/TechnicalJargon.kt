package io.github.tensaiapp.ui

/**
 * The shape of things a user must never be shown, as patterns (TEN-39).
 *
 * The ticket's acceptance criterion — "no error state shows a stacktrace or technical jargon" — is
 * the most checkable thing in it, so it is checked rather than asserted in a review comment. Two
 * tests share this: `NoTechnicalJargonTest` runs it over every string in `strings.xml`, and
 * `ChatViewModelTest` runs it over what a failed decode actually persists and surfaces.
 *
 * These patterns describe *machine* vocabulary — identifiers, frames, codes — not merely difficult
 * words. "Quantization" and "KV cache" are domain terms this app is entitled to use next to a
 * `Q4_K_M` badge; `llama_decode failed (rc=-1)` is not something anyone can act on, and that is the
 * line drawn here.
 */
object TechnicalJargon {

    private val patterns: List<Pair<String, Regex>> = listOf(
        // "IOException", "OutOfMemoryError" — an exception type named at the user.
        "an exception class name" to Regex("""\b[A-Za-z_]\w*(Exception|Throwable)\b"""),
        // "Error:" as a prefix, or a bare "…Error" type. Plain "error" as a word is fine.
        "a raw error prefix or error type" to Regex("""(\bError:|\b[A-Za-z_]\w+Error\b)"""),
        // "at com.tensai.llamakt.LlamaEngine.decode(" — a stack frame.
        "a stack frame" to Regex("""\bat\s+[a-z][\w$]*(\.[\w$]+)+\s*\("""),
        // "java.lang.String", "com.tensai.llamakt.LlamaEngine" — a fully qualified name.
        "a package or class path" to Regex("""\b(java|javax|kotlin|kotlinx|android|androidx|com|io|org|net)\.[a-z][\w$]*\.[\w$]+"""),
        // "llama_decode", "llama_model_load" — native symbols.
        "a native symbol" to Regex("""\b[a-z]+_[a-z]+(_[a-z]+)*\s*(\(|failed|:)"""),
        // "rc=-1", "errno 12", "0xdeadbeef" — machine codes.
        "an error code" to Regex("""(\brc\s*=|\berrno\b|\b0x[0-9a-fA-F]{4,}\b)"""),
        // The JVM's own words for absence, leaking into a sentence.
        "a JVM null" to Regex("""\bnull\b"""),
        "the word stacktrace" to Regex("""(?i)\bstack\s?trace\b"""),
    )

    /**
     * Every offending fragment in [text], with the rule it broke — empty when [text] is clean.
     * Returns the matches rather than a boolean so a failure message can name what it found.
     */
    fun offendersIn(text: String): List<String> = patterns.mapNotNull { (rule, regex) ->
        regex.find(text)?.let { match -> "${match.value} ($rule)" }
    }
}
