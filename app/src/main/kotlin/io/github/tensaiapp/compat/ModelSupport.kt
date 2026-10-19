package io.github.tensaiapp.compat

import com.tensai.llamakt.LlamaEngine

/**
 * The `general.architecture` values the embedded llama.cpp can build (TEN-167), asked of the native
 * library itself: its own architecture table, so the answer cannot drift from what a load accepts.
 *
 * Null means "could not be read" (native library missing, as on the JVM unit tests, or an empty
 * answer): every caller treats that as supported, because refusing a model on a guess is worse than
 * letting llama.cpp refuse it.
 */
internal object SupportedArchitectures {
    @Volatile
    private var cache: Set<String>? = null

    @Volatile
    private var read = false

    /** Replaced by the unit tests, which have no native library. */
    @Volatile
    internal var source: () -> Set<String>? = { LlamaEngine.supportedArchitectures() }

    fun current(): Set<String>? {
        if (!read) {
            synchronized(this) {
                if (!read) {
                    cache = runCatching { source() }.getOrNull()?.takeIf { it.isNotEmpty() }
                    read = true
                }
            }
        }
        return cache
    }

    /** Forgets the cached answer, for tests. */
    internal fun reset() {
        synchronized(this) {
            cache = null
            read = false
        }
    }
}

/**
 * Whether this build can run a model at all, before any question of memory (TEN-167). Pure: the set
 * of known architectures is a parameter, so the rules run on the JVM.
 */
internal object ModelSupport {

    /**
     * The blocking verdict for a model, or null when nothing says it cannot be loaded. A draft head
     * wins over an unknown architecture: it is the more useful reason (the file is fine, it is just
     * not a model on its own). The architecture is the reliable signal ([DRAFT_ARCHITECTURES], names
     * llama.cpp itself knows as draft-only); the name only catches the unambiguous DFlash tag, since a
     * name-based block has no override.
     *
     * @param names every name the model goes by (display name, file name, repo): [isDraftOnly] looks at each.
     * @param supported [SupportedArchitectures.current]; null = unknown, nothing is refused.
     */
    fun blockingVerdict(architecture: String, names: List<String>, supported: Set<String>? = SupportedArchitectures.current()): Verdict? = when {
        architecture in DRAFT_ARCHITECTURES || names.any(::isDraftOnly) -> Verdict.DraftModel
        !isSupported(architecture, supported) -> Verdict.Unsupported
        else -> null
    }

    /** An unknown architecture (blank, e.g. a Hub repo with no `gguf` block) is never reported unsupported: no guessing. */
    fun isSupported(architecture: String, supported: Set<String>?): Boolean =
        architecture.isBlank() || supported == null || architecture in supported

    /** `general.architecture` values of llama.cpp (llama-arch.cpp) that only exist as speculative-decoding heads. */
    val DRAFT_ARCHITECTURES = setOf("dflash", "eagle3", "gemma4-assistant")

    // "DFlash2" (a version digit) matches, "dflashing" does not.
    private val DFLASH = Regex("(?<![a-z])dflash(?![a-z])")

    /**
     * Whether [name] (a display name, file name or `owner/repo`) carries the DFlash tag, the one name
     * unambiguous enough to block a file on: "Qwen3.8-27B-DFlash2" matches. Case-insensitive, last path
     * segment of a repo id only. "draft", "mtp" and "eagle" are deliberately not read from names
     * ("Story-Draft-v2", full models called "...-MTP", the "Eagle2-8B" VLM are all real); those heads
     * are caught by their architecture instead.
     */
    fun isDraftOnly(name: String): Boolean = DFLASH.containsMatchIn(name.substringAfterLast('/').lowercase())
}
