package io.github.hokanosekai.tensai.download

import io.github.hokanosekai.tensai.hf.TokenFormat
import io.github.hokanosekai.tensai.hf.bearer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * The streaming half of a model download: one file, one `.part`, resumable. Pure JVM (OkHttp +
 * `java.io`), so every rule below is driven by `DownloadEngineTest` against a MockWebServer and a
 * temp dir. The runner decides *when* to call it.
 *
 * - **Resume.** A part file of N bytes asks for `Range: bytes=N-`. `206` with a `Content-Range`
 *   starting at N appends. `200` (server ignored the range) restarts from zero using that body.
 *   A `206` starting elsewhere restarts. `416` with the part already at full size means it was
 *   complete; otherwise restart.
 * - **Integrity is a separate pass.** [download] only moves bytes and checks the size is exactly
 *   [totalBytes] (a smaller body is a retryable I/O error, a larger one is final). [verifySha256]
 *   then reads the finished file once. Nothing is hashed while streaming, so a resume costs no
 *   re-hash: the one-pass cost is paid once, before the file is registered.
 * - **Disk.** Every [DISK_CHECK_EVERY] bytes the free space is compared to what is still to come,
 *   so a disk that fills (this app or another) stops the download with [DownloadError.DiskFull].
 * - **Errors.** 401/403 (see [DownloadError.Gated], [DownloadError.InvalidToken], [DownloadError.LicenseNotAccepted]) and 404 and other non-retryable codes throw [DownloadException]; network
 *   failures, 429, 5xx and a short body throw [IOException] and leave the part for the next try.
 *
 * Cancelling the coroutine cancels the HTTP call, including mid-body.
 *
 * @param freeBytes free space on the volume holding the part file.
 * @param tokenProvider the user's HF token or null. Sent only after an anonymous 401/403, so public
 *   files never carry it; a malformed value counts as none. OkHttp drops `Authorization` on a
 *   redirect to another host, so it never reaches the CDN.
 */
class DownloadEngine(
    private val http: OkHttpClient,
    private val freeBytes: () -> Long,
    private val tokenProvider: () -> String? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
    private val progressIntervalMs: Long = 500,
    private val diskMarginBytes: Long = DISK_MARGIN,
) {

    /**
     * Downloads [url] into [part] (appending when it already holds a valid prefix) and returns its
     * size, which equals [totalBytes] or this throws.
     *
     * @param onProgress bytes on disk so far, at most every [progressIntervalMs]; always called once
     *   at the start (resume point) and once at the end.
     */
    suspend fun download(
        url: String,
        part: File,
        totalBytes: Long,
        onProgress: suspend (bytesDone: Long) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        part.parentFile?.mkdirs()
        var done = if (part.exists()) part.length() else 0L
        if (done > totalBytes) {
            part.delete()
            done = 0
        }

        var restarted = false
        // Every request starts anonymously: public files never see the token. Only after a 401/403 is
        // the same request repeated with it (see [handle]), and then it stays on for this call.
        val token = TokenFormat.usable(tokenProvider())
        var authed = false
        while (true) {
            val request = Request.Builder().url(url)
                .apply { if (done > 0) header("Range", "bytes=$done-") }
                .apply { if (authed) bearer(token) }
                .build()
            when (val step = execute(request, authed, token != null, done, totalBytes, part, onProgress)) {
                is Step.NeedsToken -> authed = true
                is Step.Finished -> {
                    if (step.bytes < totalBytes) throw IOException("body ended at ${step.bytes} of $totalBytes")
                    return@withContext step.bytes
                }
                is Step.Restart -> {
                    // One restart per call: a server that keeps contradicting itself is an error, not a loop.
                    if (restarted) throw IOException("server keeps ignoring Range")
                    restarted = true
                    part.delete()
                    done = 0
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    /**
     * One pass over the finished [file]: its SHA-256 must be [expected] (case-insensitive). On a
     * mismatch the file is deleted and [DownloadError.ChecksumMismatch] thrown. A null [expected]
     * skips the check (the caller relies on the pinned commit and the exact size instead).
     */
    suspend fun verifySha256(file: File, expected: String?) {
        if (expected == null) return
        val actual = withContext(Dispatchers.IO) {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER_SIZE)
            FileInputStream(file).use { input ->
                while (true) {
                    coroutineContext.ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        if (!expected.equals(actual, ignoreCase = true)) {
            file.delete()
            throw DownloadException(DownloadError.ChecksumMismatch)
        }
    }

    private sealed interface Step {
        data class Finished(val bytes: Long) : Step
        data object Restart : Step

        /** Refused anonymously and a token exists: repeat the request with it. */
        data object NeedsToken : Step
    }

    private suspend fun execute(
        request: Request,
        authed: Boolean,
        hasToken: Boolean,
        startAt: Long,
        totalBytes: Long,
        part: File,
        onProgress: suspend (Long) -> Unit,
    ): Step = coroutineScope {
        val call = http.newCall(request)
        // Same pattern as HfClient.fetch: a child that only waits to be cancelled, and cancels the
        // live call when it is, so a blocked read is interrupted too.
        val watcher = launch {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            val response = call.execute()
            response.use { handle(it, authed, hasToken, startAt, totalBytes, part, onProgress) }
        } catch (e: IOException) {
            // A cancelled call surfaces as an IOException: that is a cancellation, not "offline".
            coroutineContext.ensureActive()
            throw e
        } finally {
            watcher.cancel()
        }
    }

    private suspend fun handle(
        response: Response,
        authed: Boolean,
        hasToken: Boolean,
        startAt: Long,
        totalBytes: Long,
        part: File,
        onProgress: suspend (Long) -> Unit,
    ): Step {
        var fromZero = false
        when (val code = response.code) {
            200 -> {
                // 200 to a ranged request: the server ignored the Range and the body is the whole
                // file. Use it as is: drop what we had, no second request.
                if (startAt > 0) fromZero = true
            }
            206 -> {
                val from = CONTENT_RANGE.find(response.header("Content-Range").orEmpty())?.groupValues?.get(1)?.toLongOrNull()
                if (from != startAt) return if (startAt > 0) Step.Restart else throw IOException("unexpected Content-Range")
            }
            416 -> {
                // Asked past the end: either the part is already whole, or it is junk.
                return if (startAt == totalBytes) Step.Finished(startAt) else Step.Restart
            }
            // The Hub answers 401 to a token it does not accept and 403 to a valid token without access.
            401, 403 -> return when {
                !authed && hasToken -> Step.NeedsToken
                !authed -> throw DownloadException(DownloadError.Gated)
                code == 401 -> throw DownloadException(DownloadError.InvalidToken)
                else -> throw DownloadException(DownloadError.LicenseNotAccepted)
            }
            404 -> throw DownloadException(DownloadError.NotFound)
            408, 425, 429 -> throw IOException("HTTP $code")
            in 500..599 -> throw IOException("HTTP $code")
            else -> if (code !in 200..299) throw DownloadException(DownloadError.Http(code)) else Unit
        }

        val body = response.body ?: throw IOException("empty body")
        if (fromZero) part.delete()
        var done = if (fromZero) 0L else startAt
        val buffer = ByteArray(BUFFER_SIZE)
        var lastReport = clock()
        var nextDiskCheck = done
        onProgress(done)
        val source = body.byteStream()
        val sink = FileOutputStream(part, !fromZero)
        try {
            while (true) {
                coroutineContext.ensureActive()
                val read = source.read(buffer)
                if (read < 0) break
                if (done + read > totalBytes) {
                    sink.close()
                    part.delete()
                    throw DownloadException(DownloadError.SizeMismatch)
                }
                if (done >= nextDiskCheck) {
                    nextDiskCheck = done + DISK_CHECK_EVERY
                    checkDisk(totalBytes - done)
                }
                try {
                    sink.write(buffer, 0, read)
                } catch (e: IOException) {
                    // A write that fails with the volume out of room is the disk, not the network.
                    if (freeBytes() < diskMarginBytes) throw DownloadException(DownloadError.DiskFull)
                    throw e
                }
                done += read
                val now = clock()
                if (now - lastReport >= progressIntervalMs) {
                    lastReport = now
                    sink.flush()
                    onProgress(done)
                }
            }
        } finally {
            sink.close()
        }
        onProgress(done)
        return Step.Finished(done)
    }

    private fun checkDisk(remaining: Long) {
        if (freeBytes() < remaining + diskMarginBytes) throw DownloadException(DownloadError.DiskFull)
    }

    companion object {
        const val BUFFER_SIZE = 256 * 1024
        const val DISK_CHECK_EVERY = 16L * 1024 * 1024
        const val DISK_MARGIN = 32L * 1024 * 1024
        private val CONTENT_RANGE = Regex("bytes (\\d+)-\\d+/(?:\\d+|\\*)")
    }
}
