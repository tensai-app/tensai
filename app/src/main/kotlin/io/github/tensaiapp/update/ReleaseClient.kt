package io.github.tensaiapp.update

import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/**
 * One release the update check can offer (TEN-227): its tag, its notes, the APK and the `.sha256` published next to
 * it by `release.yml`.
 *
 * @param notes the release body, trimmed to [ReleaseClient.NOTES_MAX_CHARS].
 */
data class Release(
    val tag: String,
    val notes: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    val sha256Url: String,
) {
    /** The tag without its `v`, as the card shows it. */
    val version: String get() = tag.removePrefix("v")
}

/** Where releases come from, a fake in the tests. */
interface ReleaseSource {
    /** The release [url] (a GitHub `releases/latest`) describes, null when it has no APK with its `.sha256`. */
    suspend fun latest(url: String): Release?

    /** The hash [Release.sha256Url] publishes, lowercase hex, null when that file is not a SHA-256. */
    suspend fun sha256(release: Release): String?

    /** Streams the APK into [dest], [onProgress] with bytes so far and the total. Throws [IOException] on any failure. */
    suspend fun download(release: Release, dest: File, onProgress: (Long, Long) -> Unit)
}

/**
 * The GitHub Releases API, over OkHttp. One GET for [latest], one for the `.sha256`, one for the APK: nothing else
 * goes out, and nothing about the user or the device is in them.
 *
 * @param api the client of the small calls, with a call timeout.
 * @param files the APK's, without one (a slow link must not cut a 50 MB download at 30 s).
 * @param httpsOnly refuses an asset URL that is not https. Off only for the MockWebServer tests.
 */
class ReleaseClient(
    private val api: OkHttpClient,
    private val files: OkHttpClient,
    private val httpsOnly: Boolean = true,
) : ReleaseSource {

    override suspend fun latest(url: String): Release? = withContext(Dispatchers.IO) {
        val body = get(api, url, accept = "application/vnd.github+json").use { readText(it, MAX_JSON_BYTES) }
        val dto = json.decodeFromString(ReleaseDto.serializer(), body)
        if (dto.draft) return@withContext null
        // The signed APK is the only .apk asset, see release.yml.
        val apk = dto.assets.singleOrNull { it.name.endsWith(".apk") } ?: return@withContext null
        val sha = dto.assets.firstOrNull { it.name == apk.name + ".sha256" } ?: return@withContext null
        if (!acceptable(apk.url) || !acceptable(sha.url) || apk.size <= 0 || apk.size > MAX_APK_BYTES) return@withContext null
        Release(
            tag = dto.tag,
            notes = trimNotes(dto.body.orEmpty()),
            apkName = apk.name,
            apkUrl = apk.url,
            apkSize = apk.size,
            sha256Url = sha.url,
        )
    }

    override suspend fun sha256(release: Release): String? = withContext(Dispatchers.IO) {
        parseSha256(get(files, release.sha256Url).use { readText(it, MAX_SHA_BYTES) })
    }

    override suspend fun download(release: Release, dest: File, onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        get(files, release.apkUrl).use { response ->
            val source = response.body.source()
            var done = 0L
            dest.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val read = source.read(buffer)
                    if (read < 0) break
                    done += read
                    if (done > release.apkSize) throw IOException("more than the ${release.apkSize} bytes announced")
                    out.write(buffer, 0, read)
                    onProgress(done, release.apkSize)
                }
            }
            if (done != release.apkSize) throw IOException("got $done bytes of ${release.apkSize}")
        }
    }

    private fun acceptable(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return !httpsOnly || parsed.isHttps
    }

    /** Blocking GET, called on IO. A non-2xx is an [IOException]. */
    private fun get(client: OkHttpClient, url: String, accept: String? = null): Response {
        val request = Request.Builder().url(url).apply { if (accept != null) header("Accept", accept) }.build()
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw IOException("HTTP ${response.code}")
        }
        return response
    }

    private fun readText(response: Response, maxBytes: Long): String {
        val source = response.body.source()
        source.request(maxBytes + 1)
        if (source.buffer.size > maxBytes) throw IOException("body over $maxBytes bytes")
        return source.buffer.readUtf8()
    }

    @Serializable
    private data class ReleaseDto(
        @SerialName("tag_name") val tag: String,
        val body: String? = null,
        val draft: Boolean = false,
        val assets: List<AssetDto> = emptyList(),
    )

    @Serializable
    private data class AssetDto(
        val name: String,
        val size: Long = 0,
        @SerialName("browser_download_url") val url: String,
    )

    companion object {
        /** tensai's own releases, the only source a release build ever checks. */
        const val DEFAULT_URL = "https://api.github.com/repos/tensai-app/tensai/releases/latest"

        const val NOTES_MAX_CHARS = 2_000
        private const val MAX_JSON_BYTES = 1_000_000L
        private const val MAX_SHA_BYTES = 4_096L
        private const val MAX_APK_BYTES = 1_000_000_000L

        private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
        private val SHA256 = Regex("[0-9a-fA-F]{64}")

        /** The first word of a `sha256sum` line, `<hex>  <file>`, lowercased; null when it is not 64 hex digits. */
        internal fun parseSha256(text: String): String? =
            text.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { SHA256.matches(it) }?.lowercase()

        /** The body without carriage returns, cut on a line end before [NOTES_MAX_CHARS]. */
        internal fun trimNotes(body: String): String {
            val text = body.replace("\r", "").trim()
            if (text.length <= NOTES_MAX_CHARS) return text
            val cut = text.lastIndexOf('\n', NOTES_MAX_CHARS).takeIf { it > 0 } ?: NOTES_MAX_CHARS
            return text.substring(0, cut).trimEnd() + "\n\n…"
        }
    }
}
