package io.github.hokanosekai.tensai.hf

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement

/** Wire shapes of the Hub API, only the fields we read, and the mapping to the domain. */
@Serializable
internal data class SearchItemDto(
    val id: String,
    val downloads: Long = 0,
    val likes: Long = 0,
    val lastModified: String? = null,
    val tags: List<String> = emptyList(),
    val gated: JsonElement? = null,
)

@Serializable
internal data class DetailDto(
    val id: String,
    val downloads: Long = 0,
    val likes: Long = 0,
    val lastModified: String? = null,
    val tags: List<String> = emptyList(),
    val gated: JsonElement? = null,
    val cardData: CardDto? = null,
    val gguf: GgufDto? = null,
    val siblings: List<JsonElement> = emptyList(),
    val sha: String? = null,
)

@Serializable
internal data class CardDto(val license: JsonElement? = null)

@Serializable
internal data class GgufDto(
    val total: Long = 0,
    val architecture: String? = null,
    val context_length: Long = 0,
    val chat_template: String? = null,
)

@Serializable
internal data class SiblingDto(val rfilename: String, val size: Long? = null, val lfs: LfsDto? = null)

@Serializable
internal data class LfsDto(val sha256: String? = null)

internal object HfParser {
    val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    private val TOOLS = Regex("\\btools\\b|tool_call")

    fun parseSearch(body: String): List<HfRepoSummary> =
        json.decodeFromString<List<SearchItemDto>>(body).map { it.toSummary() }

    fun parseDetail(body: String): HfRepoDetail = json.decodeFromString<DetailDto>(body).toDetail()

    private fun gate(e: JsonElement?): HfGate {
        val p = e as? JsonPrimitive ?: return HfGate.None
        p.booleanOrNull?.let { return if (it) HfGate.Manual else HfGate.None }
        return when (p.contentOrNull?.lowercase()) {
            null, "false" -> HfGate.None
            "auto" -> HfGate.Auto
            else -> HfGate.Manual // unknown value: assume the strict one
        }
    }

    private fun summary(
        id: String,
        downloads: Long,
        likes: Long,
        lastModified: String?,
        tags: List<String>,
        gated: JsonElement?,
        cardLicense: String?,
    ) = HfRepoSummary(
        id = id,
        author = id.substringBefore('/', ""),
        name = id.substringAfter('/'),
        downloads = downloads,
        likes = likes,
        lastModified = lastModified?.let { runCatching { Instant.parse(it) }.getOrNull() },
        license = cardLicense ?: tags.firstOrNull { it.startsWith("license:") }?.removePrefix("license:"),
        gate = gate(gated),
    )

    private fun SearchItemDto.toSummary() = summary(id, downloads, likes, lastModified, tags, gated, null)

    private fun licenseOf(card: CardDto?): String? = when (val l = card?.license) {
        is JsonPrimitive -> l.contentOrNull
        is JsonArray -> (l.firstOrNull() as? JsonPrimitive)?.contentOrNull
        else -> null
    }

    private val COMMIT_SHA = Regex("[0-9a-f]{40}")

    private fun DetailDto.toDetail(): HfRepoDetail {
        // One malformed sibling must not kill the whole repo: it is skipped.
        val parsed = siblings.mapNotNull { runCatching { json.decodeFromJsonElement<SiblingDto>(it) }.getOrNull() }
        val files = parsed.filter { QuantLabel.isGguf(it.rfilename) }
        // A file with no known size cannot be judged (it would look free), so it is not offered.
        val quants = files
            .filter { !QuantLabel.isMmproj(it.rfilename) && !QuantLabel.isSplit(it.rfilename) }
            .filter { (it.size ?: 0) > 0 }
            .map { HfQuantFile(it.rfilename, QuantLabel.parse(it.rfilename), it.size ?: 0, it.lfs?.sha256) }
            .sortedBy { it.sizeBytes }
        val template = gguf?.chat_template.orEmpty()
        return HfRepoDetail(
            summary = summary(id, downloads, likes, lastModified, tags, gated, licenseOf(cardData)),
            paramCount = gguf?.total ?: 0,
            architecture = gguf?.architecture.orEmpty(),
            contextLength = gguf?.context_length ?: 0,
            supportsTools = TOOLS.containsMatchIn(template),
            hasVision = files.any { QuantLabel.isMmproj(it.rfilename) },
            quants = quants,
            sha = sha?.takeIf { COMMIT_SHA.matches(it) },
        )
    }
}
