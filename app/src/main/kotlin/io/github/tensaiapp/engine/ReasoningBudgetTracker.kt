package io.github.tensaiapp.engine

/**
 * Tracks a decode's [ReasoningBudget] token by token, deciding when [LlamaKtEngineImpl] should cut
 * generation short (TEN-61). One instance per decode.
 *
 * ### What this stops the decode for, and what it doesn't (TEN-85)
 *
 * Only for the **total**, `reasoningMaxTokens + responseMaxTokens`. Reaching
 * [ReasoningBudget.reasoningMaxTokens] while the model is still reasoning stops nothing here, and
 * used to: generation was interrupted on the spot, which is how a Gemma-4 reply came back with 811
 * characters of thinking, no answer at all, and `ReasoningBudgetExhausted` on the row. Cutting the
 * power to a model that hasn't finished thinking is not a budget, it is a failure mode — the user
 * asked a question and got a monologue.
 *
 * The reasoning cap is now enforced where it can be enforced *without* ending the decode: inside
 * llama.cpp, by a sampler (`common/reasoning-budget.cpp`, wired up in `tensai_jni.cpp`) that forces
 * the chat template's own closing tag out token by token once the cap is spent, then steps aside so
 * the model writes its answer. [ReasoningBudget.reasoningMaxTokens] is what this layer hands that
 * sampler — see [LlamaKtEngineImpl]'s `libParams` — and the phase counters below no longer decide
 * anything but *which* phase gets blamed when the total runs out.
 *
 * So this class is the outer safety net it always described itself as, and nothing else. It still
 * has to be one: the native sampler needs the model's template to delimit reasoning, and a model
 * whose template says nothing about it (or a raw, non-chat prompt) gets no sampler at all.
 *
 * ### What the two caps mean (TEN-69)
 *
 * [ReasoningBudget.responseMaxTokens] is a **floor the answer is guaranteed**, not a ceiling it is
 * held to. Reasoning may spend at most [ReasoningBudget.reasoningMaxTokens]; the answer gets
 * everything the reasoning left of the two caps' sum. A model that reasons briefly — or not at all
 * — therefore answers into the whole budget rather than into its half of it. That property is what
 * TEN-85 had to preserve and did: the sampler ends the reasoning block at the cap instead of ending
 * the decode, so the tokens reasoning didn't spend are still there for the answer to spend.
 *
 * Until TEN-69 the response was capped at [ReasoningBudget.responseMaxTokens] whatever reasoning
 * had cost, so unspent reasoning tokens were simply lost: a Qwen3 reply that opened and closed an
 * empty `<think>` block in three tokens was cut at 1027 of an available 2048.
 *
 * ### How the phase is decided
 *
 * Preferably from the engine's own split ([onParsed], fed from [DecodeEvent.Parsed]): llama.cpp
 * parses the reply with the parser it built from the model's chat template, so it knows the model
 * has moved on to the answer whatever the template marks reasoning with — `<think>`, harmony's
 * `<|channel|>`, or nothing at all. Crucially it also knows when a model *skipped* reasoning, which
 * no end-marker scan can: a block that never opens never closes either.
 *
 * That split is best-effort by contract ([DecodeEvent.Parsed]: no chat parser, an unrenderable
 * template, a reply that doesn't match its own advertised format), so [ReasoningBudget.endMarker]
 * scanning stays as the floor and drives the phase for as long as no parse has arrived. The first
 * [onParsed] hands the decision over for good — two disagreeing sources would be worse than either.
 *
 * Unlike [io.github.tensaiapp.data.chat.ReplyStreamSplitter], which latches the engine's
 * *first* split and never revisits it, the phase here is re-derived from every [onParsed]. The
 * splitter latches to stop the rendered reply flickering between two answers; nothing is rendered
 * from here, and a parse can retract text a later token disproves ([DecodeEvent.Parsed]), so
 * following the engine back trades one risk for another rather than being strictly safer: staying
 * on a retracted flip would leave the reasoning cap disarmed for the rest of the decode, while
 * following it back can blame a reply that is mostly written on the reasoning cap and cut it early.
 * The first loses a guarantee, the second loses some tokens, so this takes the second.
 *
 * ### Token counting
 *
 * [onToken] is, and must stay, one call per llama.kt `TokenCallback` invocation on the **raw**
 * stream — that is what keeps this class's counts equal to [DecodeStats.tokensGenerated].
 * [onParsed] deliberately counts nothing: it only says *which* counter subsequent [onToken] calls
 * feed. The two callbacks carry different information and neither could do the other's job —
 * [DecodeEvent.Parsed] is skipped for any token that doesn't change the parse, and its `content` is
 * cumulative, markers stripped, so nothing about it can be counted.
 *
 * [ReasoningBudget.endMarker] arrives fragmented across an arbitrary number of tokens — a model
 * rarely emits a closing tag as a single token — so matching holds back only the suffix of
 * accumulated reasoning text that could still grow into the marker (at most `endMarker.length - 1`
 * characters, same trick as
 * [io.github.tensaiapp.data.chat.ThinkingStreamParser]'s own lookahead) and drops
 * everything else as it goes, then drops the whole window once the marker is found — nothing about
 * a long reasoning phase accumulates in memory once its end has been seen.
 */
internal class ReasoningBudgetTracker(private val budget: ReasoningBudget) {

    private enum class Phase { Reasoning, Response }

    private var phase = Phase.Reasoning
    private var reasoningTokens = 0
    private var responseTokens = 0

    /** Whether [onParsed] has arrived, in which case the engine's split decides the phase. */
    private var engineDecidesPhase = false

    /** Suffix of reasoning text seen so far that could still grow into [ReasoningBudget.endMarker]. */
    private val window = StringBuilder()

    /** Everything the decode may spend, across both phases. */
    private val totalMaxTokens = budget.reasoningMaxTokens + budget.responseMaxTokens

    /**
     * Records one streamed token's [text]. Returns the [StopReason] generation should be cut off
     * for, or `null` to keep going — which, since TEN-85, only ever happens on the decode's total.
     * A reasoning phase running past [ReasoningBudget.reasoningMaxTokens] returns `null` like any
     * other token: see this class's KDoc for where that cap is enforced now, and why not here.
     */
    fun onToken(text: String): StopReason? {
        if (phase == Phase.Reasoning) {
            reasoningTokens++
            if (!engineDecidesPhase) {
                window.append(text)
                if (window.indexOf(budget.endMarker) >= 0) {
                    phase = Phase.Response
                    window.setLength(0)
                    return null
                }
                val keep = longestSuffixThatIsMarkerPrefix()
                if (keep < window.length) window.delete(0, window.length - keep)
            }
        } else {
            responseTokens++
        }
        // The answer is held to the total, not to responseMaxTokens: whatever reasoning did not
        // spend is the answer's to use (TEN-69). The one cap left here since TEN-85, and it applies
        // to both phases: a model still reasoning at the total has genuinely spent everything the
        // decode had, and stopping it is the only thing left to do — unlike the reasoning cap,
        // there is no budget to hand on to an answer.
        return if (reasoningTokens + responseTokens >= totalMaxTokens) currentPhaseStopReason() else null
    }

    /**
     * Records the engine's own split of the reply so far ([DecodeEvent.Parsed]) — `content` being
     * non-empty is the engine saying the model is writing its answer, which is what ends the
     * reasoning phase from here on. Counts nothing; see this class's KDoc.
     *
     * Called at most once per token and never for a token that left the parse unchanged, so it
     * cannot be used to tell how far along the generation is — only where it is.
     *
     * Both halves empty means the engine has resolved *nothing* yet, not that it is reporting
     * reasoning, and the two must not be confused: taking it as reasoning would hand the phase over
     * on a parse that said nothing, and the marker scan — switched off for good by the first real
     * parse — would then never see the `</think>` that follows. That is TEN-60 reopened, an answer
     * cut at the reasoning cap. llama.kt does not currently deliver such a call (`tensai_jni.cpp`
     * returns early when both are empty) but [ChatParseCallback] does not promise it won't, and
     * [io.github.tensaiapp.data.chat.ReplyStreamSplitter] guards the same case explicitly.
     */
    fun onParsed(content: String, reasoningContent: String) {
        if (content.isEmpty() && reasoningContent.isEmpty()) return

        engineDecidesPhase = true
        // The marker scan is off for good now; its lookahead is dead weight.
        window.setLength(0)
        phase = if (content.isEmpty()) Phase.Reasoning else Phase.Response
    }

    /**
     * Which [StopReason] this decode would be blamed on if generation stopped right now — [phase]'s
     * own cap, not the caller's outer `nPredict`. Normally unreachable: [onToken] fires before or
     * exactly when the running total reaches `reasoningMaxTokens + responseMaxTokens`, so
     * [LlamaKtEngineImpl] never has to fall back to this (TEN-61's "outer safety net" KDoc). TEN-65
     * can clip the outer `nPredict` below that sum when the context window is tight, though, in
     * which case llama.cpp's own cap wins the race before either phase counter above gets there —
     * this is what [LlamaKtEngineImpl] asks instead of guessing, so the reported reason still names
     * the right phase rather than defaulting to a misleading [StopReason.Complete].
     */
    fun currentPhaseStopReason(): StopReason =
        if (phase == Phase.Reasoning) StopReason.ReasoningBudgetExhausted else StopReason.ResponseBudgetExhausted

    private fun longestSuffixThatIsMarkerPrefix(): Int {
        val marker = budget.endMarker
        val maxLen = minOf(window.length, marker.length - 1)
        for (len in maxLen downTo 1) {
            if (window.endsWith(marker.substring(0, len))) return len
        }
        return 0
    }
}
