package io.github.hokanosekai.tensai.data

import android.content.Context
import android.util.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One "### Group" of a release's notes: a label and its bullets. */
data class ChangelogGroup(val label: String, val items: List<String>)

/**
 * One release of the in-app changelog (TEN-193).
 *
 * @param version without the leading `v`, as in `versionName`.
 * @param date ISO `yyyy-MM-dd`, formatted by the screen.
 * @param installed whether this is the version the app is running.
 */
data class ChangelogRelease(
    val version: String,
    val title: String?,
    val date: String,
    val intro: String,
    val groups: List<ChangelogGroup>,
    val installed: Boolean,
) {
    /** Whether there is anything to unfold below the intro. */
    val hasGroups: Boolean get() = groups.isNotEmpty()
}

@Serializable
internal data class ChangelogFileDto(val releases: List<ChangelogReleaseDto> = emptyList())

@Serializable
internal data class ChangelogReleaseDto(
    val version: String,
    val title: String? = null,
    val date: String = "",
    val intro: String = "",
    val groups: List<ChangelogGroupDto> = emptyList(),
)

@Serializable
internal data class ChangelogGroupDto(val label: String, val items: List<String> = emptyList())

/**
 * The release notes bundled in `assets/changelog.json`, written at each release from the GitHub
 * notes (see `docs/ci.md`). JSON rather than markdown parsed at runtime: a typo in the file fails
 * the parse (and the tests) instead of silently rendering a broken screen, and there is no
 * heading/bullet grammar to maintain in the app.
 */
object Changelog {

    const val ASSET = "changelog.json"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The releases, newest first (by version, whatever the order of the file), with [installedVersion]
     * flagged. A file that does not parse gives an empty list: a notes screen must not take Settings
     * down with it.
     */
    fun parse(text: String, installedVersion: String): List<ChangelogRelease> = runCatching {
        json.decodeFromString<ChangelogFileDto>(text).releases
            .map { dto ->
                val version = dto.version.removePrefix("v")
                ChangelogRelease(
                    version = version,
                    title = dto.title?.takeIf(String::isNotBlank),
                    date = dto.date,
                    intro = dto.intro,
                    groups = dto.groups.map { ChangelogGroup(it.label, it.items) },
                    installed = version == installedVersion.removePrefix("v"),
                )
            }
            .sortedWith { a, b -> compareVersions(b.version, a.version) }
    }.getOrDefault(emptyList())

    /** [parse] of the bundled asset; empty when it is missing or unreadable. */
    fun load(context: Context, installedVersion: String): List<ChangelogRelease> = runCatching {
        val text = context.assets.open(ASSET).bufferedReader().use { it.readText() }
        parse(text, installedVersion)
    }.onFailure { Log.w("Changelog", "$ASSET could not be read, the screen stays empty", it) }
        .getOrDefault(emptyList())

    /** Numeric, dot-separated; a pre-release suffix (`-rc1`) sorts before the bare version. */
    internal fun compareVersions(a: String, b: String): Int {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val c = (pa.getOrNull(i) ?: 0).compareTo(pb.getOrNull(i) ?: 0)
            if (c != 0) return c
        }
        return (!a.contains('-')).compareTo(!b.contains('-'))
    }
}
