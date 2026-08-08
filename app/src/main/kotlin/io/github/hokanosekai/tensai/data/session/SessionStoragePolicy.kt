package io.github.hokanosekai.tensai.data.session

/**
 * Storage caps + LRU eviction for persisted [SessionEntity] rows (TEN-34) — same "pure policy
 * object" shape as [io.github.hokanosekai.tensai.runtime.LoadPolicy], deliberately free of Room,
 * Android `Context`, or file I/O so it's unit-testable with plain [SessionEntity] instances and no
 * device.
 */
object SessionStoragePolicy {

    /**
     * Ceiling on the combined [SessionEntity.sizeBytes] of every persisted session.
     *
     * PROVISIONAL — not measured on any real device. The ticket's own back-of-envelope figure ("a
     * saved state at 8192 tokens, q8_0, is roughly 200 MB") is the only basis for this number; it
     * has to be confirmed against real session files once a device is available (see TEN-34's QA
     * checklist) before this constant should be trusted for anything user-facing.
     */
    const val DEFAULT_MAX_TOTAL_BYTES: Long = 300L * 1024 * 1024

    /**
     * Ceiling on how many conversations may have a persisted session at once.
     *
     * PROVISIONAL, same caveat as [DEFAULT_MAX_TOTAL_BYTES] — three sessions at the estimated
     * ~200 MB each is what motivates [DEFAULT_MAX_TOTAL_BYTES]'s figure, but neither number is
     * measured.
     */
    const val DEFAULT_MAX_SESSION_COUNT: Int = 3

    /**
     * Which [SessionEntity.conversationId]s to evict so [sessions] fits within [maxTotalBytes] and
     * [maxSessionCount], oldest [SessionEntity.lastAccessedAt] first.
     *
     * [justWritten] (the session [SessionRepository.save] just persisted, and so always the most
     * recently accessed one *logically*, even if a clock quirk made two [SessionEntity.lastAccessedAt]
     * values tie) is never evicted, regardless of caps — persisting a session only to immediately
     * evict it again would defeat the point of saving it. If [justWritten] alone exceeds
     * [maxTotalBytes], it is still kept; the caps are a target for the *rest* of the collection, not
     * a hard ceiling enforced against the newest entry.
     *
     * Sessions not present in [sessions] at all (i.e. never evicted by a previous pass, never
     * written) are obviously never returned — this only ever names ids that are already in
     * [sessions].
     */
    fun evictionPlan(
        sessions: List<SessionEntity>,
        justWritten: String,
        maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
        maxSessionCount: Int = DEFAULT_MAX_SESSION_COUNT,
    ): List<String> {
        val byRecency = sessions.sortedByDescending { it.lastAccessedAt }
        val ordered = byRecency.firstOrNull { it.conversationId == justWritten }
            ?.let { listOf(it) + byRecency.filter { s -> s.conversationId != justWritten } }
            ?: byRecency

        var runningBytes = 0L
        var runningCount = 0
        val evict = mutableListOf<String>()
        for (session in ordered) {
            val isJustWritten = session.conversationId == justWritten
            val nextBytes = runningBytes + session.sizeBytes
            val nextCount = runningCount + 1
            if (!isJustWritten && (nextBytes > maxTotalBytes || nextCount > maxSessionCount)) {
                evict += session.conversationId
            } else {
                runningBytes = nextBytes
                runningCount = nextCount
            }
        }
        return evict
    }
}
