package io.github.hokanosekai.tensai.engine

/**
 * How many tokens are actually left to generate into, once the prompt itself has taken its share of
 * the context window (TEN-65). [LlamaKtEngineImpl.decode] used to hand [SamplingConfig.maxTokens]
 * (or the reasoning/response sum) straight to llama.cpp as `nPredict`, with no regard for how much
 * of [LoadedModelInfo.nCtxEffective] the prompt itself had already used up. Once a long-enough
 * conversation's prompt got close to `nCtxEffective`, that meant asking for a reply with essentially
 * no room left to write it in — llama.cpp would return having generated nothing, and the app
 * reported that as a normal, complete (empty) reply.
 *
 * Kept as its own object (mirroring [ReasoningBudgetTracker]) purely so the arithmetic — the one
 * part of this actually worth a unit test — doesn't need a native engine or a dispatcher to
 * exercise.
 */
internal object ContextBudget {

    /**
     * Held back from the raw `nCtxEffective - promptTokens` figure as a hedge against
     * [LlamaKtEngine.tokenize] (used to count a request's prompt tokens) undercounting whatever
     * llama.cpp's own tokenizer actually feeds the model for that same text — e.g. a leading BOS
     * token some tokenizer entry points add and others don't. Better to lose a handful of tokens off
     * the reply than to once again promise more room than there actually is.
     */
    const val SafetyMarginTokens = 8

    /**
     * Below this many tokens of [available] room, there isn't enough left to write anything a user
     * would recognise as a reply — treated the same as zero room at all (TEN-65: "nulle ou
     * dérisoire").
     */
    const val MinResponseTokens = 8

    /**
     * What the window keeps back from the prompt: a send is refused once [available] drops under
     * [MinResponseTokens], i.e. once the prompt passes `nCtxEffective - ReservedTokens`. The context
     * sheet (TEN-161) shows it as "Reserved for reply".
     */
    const val ReservedTokens = SafetyMarginTokens + MinResponseTokens

    /**
     * Tokens actually available to generate into, given [nCtxEffective] and [promptTokens] — never
     * negative, even if [promptTokens] alone already exceeds [nCtxEffective].
     */
    fun available(nCtxEffective: Int, promptTokens: Int): Int =
        (nCtxEffective - promptTokens - SafetyMarginTokens).coerceAtLeast(0)

    /** Whether [available] room (as returned by [available]) is worth generating into at all. */
    fun hasRoom(available: Int): Boolean = available >= MinResponseTokens
}
