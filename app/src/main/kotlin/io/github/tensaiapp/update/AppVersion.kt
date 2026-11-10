package io.github.tensaiapp.update

/**
 * A tensai version as tags and `versionName` write it: `0.3.7`, `v0.3.7`, `0.3.7-rc1` (TEN-227).
 * A release candidate sorts before its stable: `0.3.7-rc1 < 0.3.7-rc2 < 0.3.7 < 0.3.8`.
 *
 * @param rc the candidate number, null for a stable.
 */
data class AppVersion(val major: Int, val minor: Int, val patch: Int, val rc: Int? = null) : Comparable<AppVersion> {

    val isStable: Boolean get() = rc == null

    override fun compareTo(other: AppVersion): Int = compareValuesBy(
        this,
        other,
        { it.major },
        { it.minor },
        { it.patch },
        // A stable is above every candidate of the same number.
        { it.rc ?: Int.MAX_VALUE },
    )

    companion object {
        private val PATTERN = Regex("""v?(\d{1,6})\.(\d{1,6})\.(\d{1,6})(?:-rc(\d{1,6}))?""")

        /** Null for anything else (a `-beta`, a build suffix): such a version is never compared, so never offered. */
        fun parse(text: String): AppVersion? {
            val match = PATTERN.matchEntire(text.trim()) ?: return null
            val (major, minor, patch, rc) = match.destructured
            return AppVersion(major.toInt(), minor.toInt(), patch.toInt(), rc.takeIf { it.isNotEmpty() }?.toInt())
        }

        /**
         * Whether [candidate] (a release tag) is offered to a build of [installed] (its `versionName`): newer, and a
         * stable build is never offered a candidate. A candidate build is offered its own stable or a newer one. Either
         * one unreadable offers nothing.
         */
        fun isOffered(installed: String, candidate: String): Boolean {
            val current = parse(installed) ?: return false
            val next = parse(candidate) ?: return false
            if (current.isStable && !next.isStable) return false
            return next > current
        }
    }
}
