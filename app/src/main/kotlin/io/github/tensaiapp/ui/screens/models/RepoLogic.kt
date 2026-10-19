package io.github.tensaiapp.ui.screens.models

import io.github.tensaiapp.compat.Compatibility
import io.github.tensaiapp.compat.Verdict
import io.github.tensaiapp.download.DownloadError
import io.github.tensaiapp.download.DownloadItem
import io.github.tensaiapp.download.DownloadStatus
import io.github.tensaiapp.hf.HfError
import io.github.tensaiapp.hf.HfQuantFile
import io.github.tensaiapp.hf.HfRecommendation
import io.github.tensaiapp.hf.QuantLabel
import io.github.tensaiapp.hf.RatedQuant

/** The access card of a repo that could not be loaded: what the user has to do. */
internal enum class GateCard { NeedsToken, InvalidToken, NeedsLicense }

/** A model of the library whose file is on disk, as far as the repo screen needs to recognise it. */
internal data class LibraryFile(val modelId: String, val fileName: String, val sizeBytes: Long)

/** What the pinned button of the repo screen is, for the file that is selected. */
internal sealed interface RepoAction {
    /** Nothing selectable (no quant). */
    data object None : RepoAction

    /** "Download · 1.2 GB". */
    data class Download(val sizeBytes: Long) : RepoAction

    /** Running: bytes, speed and time left (both 0 / null until two readings exist). */
    data class Downloading(
        val id: String,
        val doneBytes: Long,
        val totalBytes: Long,
        val bytesPerSecond: Long,
        val etaSeconds: Long?,
    ) : RepoAction

    data class Paused(val id: String, val doneBytes: Long, val totalBytes: Long) : RepoAction

    /** Waiting for [ahead] downloads before it. */
    data class Queued(val id: String, val ahead: Int) : RepoAction

    data class Failed(val id: String, val error: DownloadError?) : RepoAction

    /** The file is in the library: "Load model". */
    data class Load(val modelId: String) : RepoAction

    /** The file cannot run in this app ([Verdict.isBlocked], TEN-167): nothing to download. [architecture] names the unsupported one. */
    data class Blocked(val verdict: Verdict, val architecture: String) : RepoAction

    /** A Hugging Face token is needed ([invalid] false) or the one set was refused ([invalid] true). */
    data class NeedsToken(val invalid: Boolean) : RepoAction

    /** The token is fine but the licence is not accepted on the account (403): accept it on the site, then retry [id]. */
    data class NeedsLicense(val id: String) : RepoAction
}

/** Pure rules behind the repo screen (TEN-140): no Android, no network, so they run on the JVM. */
internal object RepoLogic {

    /** The library model that is [quant]: same file name (folder dropped) and size. Null when there is none. */
    fun onDeviceModelId(quant: HfQuantFile, library: List<LibraryFile>): String? {
        val name = quant.fileName.substringAfterLast('/')
        return library.firstOrNull { it.fileName == name && it.sizeBytes == quant.sizeBytes }?.modelId
    }

    /**
     * The file selected when the screen opens: the one the pasted link pointed at (exact path, else the
     * same file name in another folder), else the recommended quant (which may only be the nearest to
     * fitting, see [HfRecommendation.isRecommended]), else the smallest.
     */
    fun preselect(rated: List<RatedQuant>, recommendation: HfRecommendation?, requested: String?): String? {
        if (requested != null) {
            val byPath = rated.firstOrNull { it.quant.fileName == requested }
            val byName = rated.firstOrNull { it.quant.fileName.substringAfterLast('/').equals(requested.substringAfterLast('/'), ignoreCase = true) }
            (byPath ?: byName)?.let { return it.quant.fileName }
        }
        return recommendation?.quant?.fileName ?: rated.firstOrNull()?.quant?.fileName
    }

    /** The download of [fileName] in [repo] that still means something: the latest one that is not done. */
    fun itemFor(repo: String, fileName: String, queue: List<DownloadItem>): DownloadItem? =
        queue.lastOrNull { it.repo.equals(repo, ignoreCase = true) && it.fileName == fileName && it.status != DownloadStatus.Done }

    /** Downloads that run or wait before [item] in the queue (paused ones are not in the way). */
    fun queueAhead(item: DownloadItem, queue: List<DownloadItem>): Int {
        val index = queue.indexOfFirst { it.id == item.id }
        if (index <= 0) return 0
        return queue.subList(0, index).count { it.status == DownloadStatus.Queued || it.status == DownloadStatus.Downloading }
    }

    /**
     * The button for [quant]. In order: already in the library (Load); a token problem, whether the
     * download said so or the repo is [gated] and there is no token ([hasToken]); the state of the
     * download in the queue; else, unless the file is blocked (unsupported architecture, draft head), a plain Download.
     */
    fun action(
        quant: HfQuantFile?,
        gated: Boolean,
        hasToken: Boolean,
        onDeviceModelId: String?,
        item: DownloadItem?,
        ahead: Int,
        bytesPerSecond: Long,
        blocked: Compatibility? = null,
    ): RepoAction {
        if (quant == null) return RepoAction.None
        val blockedAction = blocked?.takeIf { it.verdict.isBlocked }?.let { RepoAction.Blocked(it.verdict, it.architecture) }
        if (onDeviceModelId != null) return blockedAction ?: RepoAction.Load(onDeviceModelId)
        if (item != null && item.status == DownloadStatus.Failed) {
            when (item.error) {
                // With a token now set, an old "needs a token" failure is just a failure to retry.
                DownloadError.Gated -> if (!hasToken) return RepoAction.NeedsToken(invalid = false)
                DownloadError.LicenseNotAccepted -> return RepoAction.NeedsLicense(item.id)
                DownloadError.InvalidToken -> return RepoAction.NeedsToken(invalid = true)
                else -> Unit
            }
        }
        if (item != null) {
            when (item.status) {
                DownloadStatus.Downloading -> {
                    val remaining = (item.sizeBytes - item.bytesDone).coerceAtLeast(0)
                    val eta = if (bytesPerSecond > 0) (remaining + bytesPerSecond - 1) / bytesPerSecond else null
                    return RepoAction.Downloading(item.id, item.bytesDone, item.sizeBytes, bytesPerSecond, eta)
                }
                DownloadStatus.Paused -> return RepoAction.Paused(item.id, item.bytesDone, item.sizeBytes)
                DownloadStatus.Queued -> return RepoAction.Queued(item.id, ahead)
                DownloadStatus.Failed -> return RepoAction.Failed(item.id, item.error)
                DownloadStatus.Done -> Unit
            }
        }
        if (blockedAction != null) return blockedAction
        if (gated && !hasToken) return RepoAction.NeedsToken(invalid = false)
        return RepoAction.Download(quant.sizeBytes)
    }

    /** Which gate card a failed repo load shows, or null when the failure is not about access. */
    fun gateFor(error: HfError, hasToken: Boolean): GateCard? = when (error) {
        HfError.InvalidToken -> GateCard.InvalidToken
        is HfError.Gated -> if (hasToken && error.code == 403) GateCard.NeedsLicense else GateCard.NeedsToken
        else -> null
    }

    /**
     * What a quant row calls the file: its label (`Q4_K_M`), or the file name when the label does not
     * tell the files of the repo apart (two `Q4_K_M` from different folders, or no label at all).
     */
    fun rowLabel(file: HfQuantFile, all: List<HfQuantFile>): String =
        if (file.quant == QuantLabel.UNKNOWN || all.count { it.quant == file.quant } > 1) file.fileName else file.quant

    /** 32_768 -> `32K`, 1_048_576 -> `1M`, 512 -> `512`: the trained context in tokens. */
    fun formatContext(tokens: Long): String = when {
        tokens >= 1_048_576 -> "${(tokens + 524_288) / 1_048_576}M"
        tokens >= 1024 -> "${(tokens + 512) / 1024}K"
        else -> tokens.toString()
    }

    // ---- README

    /** Characters of the README the collapsed card renders (cut at a paragraph), and the most the expanded one does. */
    const val README_PREVIEW_CHARS = 1_200
    const val README_MAX_CHARS = 12_000

    /** A line longer than this is prose, never "just tags": the check does not read it. */
    private const val TAGS_LINE_MAX = 400

    /**
     * The README as the card shows it: the YAML front matter (the model card's metadata block) is cut
     * off, HTML comments go, and so do lines that are nothing but HTML tags (`<p align="center">`,
     * `<img ...>`), which the Markdown renderer would print as text. Code blocks are left alone.
     */
    fun cleanReadme(raw: String): String {
        var text = raw.replace("\r\n", "\n")
        if (text.startsWith("---\n") || text == "---") {
            val end = text.indexOf("\n---", startIndex = 3)
            if (end >= 0) text = text.substring(end + 4).substringAfter('\n', "")
        }
        text = stripComments(text)
        var inFence = false
        val kept = ArrayList<String>()
        for (line in text.split('\n')) {
            val head = line.trimStart()
            if (head.startsWith("```") || head.startsWith("~~~")) inFence = !inFence
            if (!inFence && isTagsOnly(line)) continue
            kept += line
        }
        return collapseBlankLines(kept.joinToString("\n")).trim()
    }

    /** [text] without its `<!-- -->` comments, found by a single scan; an unterminated one runs to the end, as in HTML. */
    internal fun stripComments(text: String): String {
        var from = text.indexOf("<!--")
        if (from < 0) return text
        val out = StringBuilder(text.length)
        var copied = 0
        while (from >= 0) {
            out.append(text, copied, from)
            val end = text.indexOf("-->", from + 4)
            if (end < 0) return out.toString()
            copied = end + 3
            from = text.indexOf("<!--", copied)
        }
        out.append(text, copied, text.length)
        return out.toString()
    }

    /** A non-blank line made of nothing but HTML tags (`<p align="center">`, `<img ...>`), read in one pass. */
    internal fun isTagsOnly(line: String): Boolean {
        if (line.length > TAGS_LINE_MAX || line.isBlank()) return false
        var i = 0
        val n = line.length
        while (i < n) {
            while (i < n && line[i].isWhitespace()) i++
            if (i >= n) break
            if (line[i] != '<') return false
            i++
            if (i < n && line[i] == '/') i++
            if (i >= n || !line[i].isLetter()) return false
            val close = line.indexOf('>', i)
            if (close < 0) return false
            i = close + 1
        }
        return true
    }

    private fun collapseBlankLines(text: String): String {
        val out = StringBuilder(text.length)
        var newlines = 0
        for (c in text) {
            if (c == '\n') {
                if (++newlines <= 2) out.append(c)
            } else {
                newlines = 0
                out.append(c)
            }
        }
        return out.toString()
    }

    /** [text] cut to [limit] characters at the last paragraph break before it (a hard cut when there is none). */
    fun slice(text: String, limit: Int): String {
        if (text.length <= limit) return text
        val cut = text.lastIndexOf("\n\n", startIndex = limit)
        return text.substring(0, if (cut > limit / 2) cut else limit).trimEnd()
    }
}

/**
 * Download speed from the progress the queue reports, smoothed: an exponential average over the
 * readings, since the progress is written in bursts. Feed it every emission of the queue.
 */
internal class SpeedMeter(private val weight: Double = 0.3) {
    private var id: String? = null
    private var lastBytes = 0L
    private var lastAt = 0L
    private var speed = 0.0
    private var primed = false

    /**
     * Bytes per second of the download [id] now at [bytes] ([nowMs] on any monotonic-enough clock),
     * 0 until a second reading exists, and after [id] is null or changes. A reading with no new bytes
     * is not a stop: the progress is throttled, so the gap just widens until the next write.
     */
    fun sample(id: String?, bytes: Long, nowMs: Long): Long {
        if (id == null) {
            this.id = null
            speed = 0.0
            return 0
        }
        if (id != this.id || bytes < lastBytes) {
            this.id = id
            lastBytes = bytes
            lastAt = nowMs
            speed = 0.0
            primed = false
            return 0
        }
        if (bytes == lastBytes) {
            if (nowMs - lastAt > STALL_MS) speed = 0.0
            return speed.toLong()
        }
        val dt = nowMs - lastAt
        if (dt <= 0) return speed.toLong()
        // The first reading a screen gets can be a stale snapshot (the last progress persisted before a
        // pause, or before the screen opened), so the first jump spans more time than `dt` says: seen
        // on device as "103 MB/s · 3 s left". It only re-bases; speeds start from the second jump.
        if (!primed) {
            primed = true
            lastBytes = bytes
            lastAt = nowMs
            return 0
        }
        val instant = (bytes - lastBytes) * 1000.0 / dt
        speed = if (speed == 0.0) instant else weight * instant + (1 - weight) * speed
        lastBytes = bytes
        lastAt = nowMs
        return speed.toLong()
    }

    private companion object {
        /** No new byte for this long: the speed reads 0 instead of the last good figure. */
        const val STALL_MS = 15_000L
    }
}
