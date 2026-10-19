package io.github.tensaiapp.data.session

/**
 * Which persisted [SessionEntity] rows to keep — same "pure policy object" shape as
 * [io.github.tensaiapp.runtime.LoadPolicy], deliberately free of Room, Android `Context`,
 * or file I/O so it's unit-testable with plain [SessionEntity] instances and no device.
 *
 * TEN-34 shipped this with two PROVISIONAL caps guessed from the ticket's own back-of-envelope
 * figure ("a saved state at 8192 tokens, q8_0, is roughly 200 MB"): a 300 MiB total-bytes ceiling
 * and a 3-session count ceiling. TEN-63's on-device measurement (Qwen3-1.7B and Phi 4 Mini,
 * CPH2251, `nCtx` 4096, F16 KV cache) replaced the guess and found both caps wrong in ways no
 * single pair of constants can fix:
 *
 * - Session size grows **linearly with tokens actually saved**, not with `nCtx`: Qwen3-1.7B
 *   measured a flat ~114.7 KiB/token across three very different sample sizes (708 tokens →
 *   81 211 141 B, 1846 tokens → 211 744 293 B, 3236 tokens → 371 182 853 B).
 * - The cost per token is **model-dependent**: Phi 4 Mini measured ~68 KiB/token (722 tokens →
 *   50 286 660 B), a 1.7x spread from Qwen3-1.7B's ~114.7 KiB/token — a single byte threshold is
 *   structurally wrong once more than one model is in play.
 * - A single Qwen3-1.7B session at a full 4096-token context alone would weigh ~448 MiB, already
 *   50% over the old 300 MiB total cap on its own — the count cap of 3 was never reachable in
 *   practice, the byte cap always bit first, and it bit on the *first* session kept, not the
 *   third.
 *
 * Restoring a session instead of re-prefilling separately measured a 23.5x TTFT win (107 645 ms
 * cold vs. 4 582 ms restored), and that win is entirely about the one conversation actually being
 * reopened — nothing measured motivated keeping more than that on disk at a time. So TEN-63
 * dropped both caps for a single rule: keep only the session that was just written, evict every
 * other one.
 *
 * TEN-62 reintroduces exactly one more slot — LRU-2, keep [justWritten] plus whichever *other*
 * session was most recently accessed, evict the rest — because switching conversations without
 * swapping models needs to save the outgoing conversation's session and restore the incoming
 * one's in the same breath (see `ChatViewModel`'s conversation-switch handling); TEN-63's
 * single-session rule made that impossible, since saving the outgoing session would immediately
 * evict the incoming one's file before it could ever be restored. Two is the minimum that makes
 * that sequence possible at all, and, per the measurements above, the worst case is bounded and
 * known: two full Qwen3-1.7B sessions at a full 4096-token context, ~448 MiB apiece, is ~900 MiB
 * on disk — the actual, typical case (a real conversation's token count, not a maxed-out context)
 * runs ~160–420 MiB for the pair. Nothing here motivates a third slot, a byte budget, or a
 * caller-configurable cap: exactly two, hard-coded, matches exactly the one transition TEN-62
 * needs and no more.
 */
object SessionStoragePolicy {

    /**
     * Every [SessionEntity.conversationId] to evict, keeping at most two rows: [justWritten] (the
     * session [SessionRepository.save] just persisted) and, among every other row, whichever has
     * the greatest [SessionEntity.lastAccessedAt] — everything else is stale by definition,
     * regardless of size, and gets evicted. Ties on [SessionEntity.lastAccessedAt] keep whichever
     * tied row appears first in [sessions] — deterministic, but not meaningful beyond that; two
     * rows are never expected to share an access timestamp in practice.
     */
    fun evictionPlan(sessions: List<SessionEntity>, justWritten: String): List<String> {
        val others = sessions.filter { it.conversationId != justWritten }
        val keptOther = others.maxByOrNull { it.lastAccessedAt }?.conversationId
        return others.mapNotNull { it.conversationId.takeIf { id -> id != keptOther } }
    }
}
