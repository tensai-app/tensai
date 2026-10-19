package io.github.tensaiapp.ui.screens.onboarding

import io.github.tensaiapp.compat.ModelFacts
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Wire shape of `catalog.json`. Only the fields the app reads. */
@Serializable
internal data class CatalogFileDto(val version: Int = 1, val models: List<CatalogEntryDto> = emptyList())

/**
 * One entry of the catalog: the facts [io.github.tensaiapp.compat.CompatibilityChecker]
 * needs, offline. [kvHeads] and [headDim] give the attention shape, the KV cache is
 * `2 * blockCount * kvHeads * headDim` elements per token (dense, no sliding window).
 */
@Serializable
internal data class CatalogEntryDto(
    val repo: String,
    val file: String,
    val name: String,
    val quant: String = "Q4_K_M",
    val sizeBytes: Long,
    val params: Long,
    val architecture: String,
    val contextLength: Long,
    val embeddingLength: Long,
    val blockCount: Long,
    val kvHeads: Long,
    val headDim: Long,
    val tools: Boolean = false,
    val thinking: Boolean = false,
    val onboarding: Boolean = false,
)

/**
 * Reads the embedded catalog (TEN-137). The file is a build-time asset, so a malformed one is a bug
 * to catch in the tests, not a runtime state to recover from: [parse] throws
 * [IllegalArgumentException] (or the serialization exception) and [loadEmbedded] lets it through.
 *
 * Lives in `src/main/resources` rather than `assets/` on purpose: it is read through the class
 * loader, so the JVM unit tests and the onboarding (which has no `Context` at that point) read the
 * very file the APK ships.
 */
internal object CatalogParser {

    const val RESOURCE = "catalog.json"

    private val REPO = Regex("[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*")
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): List<CatalogModel> {
        val models = json.decodeFromString<CatalogFileDto>(text).models.map { it.toModel() }
        require(models.isNotEmpty()) { "empty catalog" }
        require(models.map { it.hfPath.lowercase() }.toSet().size == models.size) { "duplicate repo in catalog" }
        return models
    }

    fun loadEmbedded(): List<CatalogModel> {
        val stream = checkNotNull(CatalogParser::class.java.classLoader?.getResourceAsStream(RESOURCE)) {
            "$RESOURCE is missing from the resources"
        }
        return stream.use { parse(it.readBytes().decodeToString()) }
    }

    private fun CatalogEntryDto.toModel(): CatalogModel {
        require(REPO.matches(repo)) { "bad repo '$repo'" }
        require(file.endsWith(".gguf", ignoreCase = true)) { "$repo: '$file' is not a GGUF file" }
        require(name.isNotBlank()) { "$repo: no name" }
        require(sizeBytes > 0 && params > 0 && contextLength > 0) { "$repo: size, params and context must be positive" }
        require(embeddingLength > 0 && blockCount > 0 && kvHeads > 0 && headDim > 0) { "$repo: attention shape must be positive" }
        return CatalogModel(
            name = name,
            hfPath = repo,
            quantLabel = quant,
            facts = ModelFacts(
                sizeBytes = sizeBytes,
                paramCount = params,
                architecture = architecture,
                contextLength = contextLength,
                embeddingLength = embeddingLength,
                blockCount = blockCount,
                kvFullElementsPerToken = 2L * blockCount * kvHeads * headDim,
            ),
            fileName = file,
            supportsTools = tools,
            supportsThinking = thinking,
            onboarding = onboarding,
        )
    }
}
