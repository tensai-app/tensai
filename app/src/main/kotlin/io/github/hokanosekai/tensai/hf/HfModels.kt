package io.github.hokanosekai.tensai.hf

import java.time.Instant

/*
 * Hugging Face data layer (TEN-136). PRIVACY CONTRACT, for the whole `hf` package: "Nothing leaves
 * the device" still holds with the INTERNET permission. The network is used ONLY to fetch model
 * listings, model metadata, READMEs and model files from huggingface.co (and the CDN hosts it
 * redirects file downloads to). No telemetry, no analytics, no crash reporting, no other host, and
 * nothing about the user or the device is sent: the only request inputs are a search string, a repo
 * path, and the optional Hugging Face token the user typed in, sent as `Authorization` to
 * huggingface.co and never across a redirect to another host (OkHttp strips it).
 */

/** Gating of a repo: whether the user has to accept a licence on the website before downloading. */
enum class HfGate {
    None,

    /** Accepted by clicking on the website, access granted at once. */
    Auto,

    /** Needs the repo author's approval. */
    Manual,
}

/** Sort orders of the repo search. [apiValue] is the `sort` query parameter. */
enum class HfSort(val apiValue: String) {
    Downloads("downloads"),
    Likes("likes"),
    LastModified("lastModified"),
}

/**
 * One repo as a search result lists it.
 *
 * @param id repo path, `author/name`.
 * @param license SPDX-ish id from the model card or the `license:` tag, null when the repo says none.
 */
data class HfRepoSummary(
    val id: String,
    val author: String,
    val name: String,
    val downloads: Long,
    val likes: Long,
    val lastModified: Instant?,
    val license: String?,
    val gate: HfGate,
) {
    val isGated: Boolean get() = gate != HfGate.None
}

/**
 * A downloadable single-file GGUF of a repo.
 *
 * @param fileName path of the file in the repo (`rfilename`, may hold a folder), what
 *   [HfClient.fileUrl] takes.
 * @param quant label parsed from the name, see [QuantLabel]; [QuantLabel.UNKNOWN] when none.
 * @param sha256 LFS hash for the integrity check after download, null if the API gave none.
 */
data class HfQuantFile(
    val fileName: String,
    val quant: String,
    val sizeBytes: Long,
    val sha256: String?,
)

/**
 * A repo with everything the detail screen and the compatibility check need.
 *
 * @param paramCount total parameters from the GGUF header, 0 when the API had no `gguf` block.
 * @param quants single-file GGUFs only (no split parts, no `mmproj`), smallest first.
 * @param supportsTools / [supportsThinking] read off the repo's chat template, see
 *   [io.github.hokanosekai.tensai.data.models.ChatTemplateCapabilities].
 * @param hasChatTemplate false only when the Hub summarised the GGUF header and found no `chat_template` in it
 *   (TEN-159): a repo with no `gguf` block at all is unknown, not template-less.
 * @param hasVision the repo ships an `mmproj` file (excluded from [quants]).
 * @param sha commit the listing describes (40 hex), null if the API gave none. Downloads pin their URL to it
 *   so the file cannot change between attempts.
 */
data class HfRepoDetail(
    val summary: HfRepoSummary,
    val paramCount: Long,
    val architecture: String,
    val contextLength: Long,
    val supportsTools: Boolean,
    val supportsThinking: Boolean = false,
    val hasChatTemplate: Boolean = true,
    val hasVision: Boolean,
    val quants: List<HfQuantFile>,
    val sha: String? = null,
)

/** Pure filename rules, kept apart from the JSON so they are testable on their own. */
object QuantLabel {
    const val UNKNOWN = "GGUF"

    // Separator between the parts of a label: `Q4_K_M`, `q4-k-m`, `Q4.K.M`. Letters and digits
    // around the match are refused so a label is not read out of a longer token.
    private const val SEP = "[_\\-.]"
    private val LABEL = Regex(
        "(?<![A-Za-z0-9])(?:" +
            "IQ[1-4]$SEP(?:XXS|XS|S|M|NL)" + // IQ4_XS, IQ3_M
            "|TQ[12]${SEP}0" + // TQ1_0, TQ2_0
            "|Q[2-8]${SEP}0$SEP(?:4${SEP}4|4${SEP}8|8${SEP}8)" + // Q4_0_4_4, Q4_0_4_8, Q4_0_8_8 (ARM repacks)
            "|Q[2-8]${SEP}K(?:$SEP(?:XL|XS|[SML]))?" + // Q4_K_M, Q6_K, Q3_K_XL
            "|Q[2-8]$SEP[01](?:$SEP(?:XL|L))?" + // Q8_0, Q4_1, Q8_0_XL (uploader variant, output tensors kept wider)
            "|BF16|FP16|F16|FP32|F32|MXFP4" + // unquantized / 4-bit float
            "|Q[2-8]" + // bare `q4`, `q8`
            ")(?![A-Za-z0-9])",
        RegexOption.IGNORE_CASE,
    )
    // Uploader tags sitting between the name and the quant: `UD` (unsloth), `imatrix`, `i1` (mradermacher).
    private val UPLOADER_TAGS = Regex("(?:${SEP}(?:UD|imatrix|imat|i1))+\\z", RegexOption.IGNORE_CASE)
    private val SPLIT = Regex("-\\d{5}-of-\\d{5}\\.gguf$", RegexOption.IGNORE_CASE)

    /** Canonical label (`Q4_K_M`, `Q8_0`, `IQ4_XS`, `F16` (also `fp16`), `BF16`, `TQ1_0`, `Q4_0_4_4`, bare `Q4`) of [fileName], or [UNKNOWN]. The last match wins. */
    fun parse(fileName: String): String {
        val base = fileName.substringAfterLast('/').removeSuffix(".gguf").removeSuffix(".GGUF")
        val m = LABEL.findAll(base).lastOrNull() ?: return UNKNOWN
        return m.value.uppercase().replace('-', '_').replace('.', '_')
            .replace("FP16", "F16").replace("FP32", "F32")
    }

    /**
     * [fileName] without its folder, `.gguf` and a *trailing* quant label plus the separator before it:
     * `Foo-Bar-Q4_K_M.gguf` -> `Foo-Bar`. Uploader tags right before it (`UD`, `imat`, `imatrix`, `i1`) go
     * too. A label in the middle of the name is left alone, as is a name
     * that is nothing but a label.
     */
    fun stripFromName(fileName: String): String {
        val file = fileName.substringAfterLast('/')
        val base = if (file.endsWith(".gguf", ignoreCase = true)) file.dropLast(5) else file
        val m = LABEL.findAll(base).lastOrNull() ?: return base
        if (m.range.last != base.lastIndex) return base
        val stem = base.substring(0, m.range.first).trimEnd('_', '-', '.').replace(UPLOADER_TAGS, "")
        return stem.trimEnd('_', '-', '.').ifBlank { base }
    }

    // ARM repacks that llama.cpp removed from GGUF files (use Q4_0, repacked at runtime): they never load.
    private val UNSUPPORTED = setOf("Q4_0_4_4", "Q4_0_4_8", "Q4_0_8_8")

    /** [label] (as [parse] returns it) is a quant our llama.cpp can no longer read from a GGUF file. */
    fun isUnsupported(label: String) = label in UNSUPPORTED

    /** [fileName] is a GGUF whose quant is [isUnsupported]. */
    fun isUnsupportedFile(fileName: String) = isUnsupported(parse(fileName))

    // About 2 bits per weight or less (IQ2_XXS is 2.06 and TQ2_0 2.06, but they break down the same way): a 7B at IQ1_S barely talks.
    private val VERY_LOW_QUALITY = setOf("IQ1_S", "IQ1_M", "IQ2_XXS", "TQ1_0", "TQ2_0")

    /** [label] (as [parse] returns it) is a quant too lossy to be worth running (TEN-159): listed with a warning, never recommended. */
    fun isVeryLowQuality(label: String) = label in VERY_LOW_QUALITY

    fun isGguf(fileName: String) = fileName.endsWith(".gguf", ignoreCase = true)

    /** `-00001-of-00003.gguf` parts: always too big for a phone and not loadable one by one. */
    fun isSplit(fileName: String) = SPLIT.containsMatchIn(fileName)

    /** Vision projector (multimodal), not a model on its own. */
    fun isMmproj(fileName: String) = fileName.substringAfterLast('/').contains("mmproj", ignoreCase = true)
}
