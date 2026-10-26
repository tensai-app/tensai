package io.github.tensaiapp.bench

import io.github.tensaiapp.engine.CpuLoadKnobs
import io.github.tensaiapp.engine.DecodeStats
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * A saved decode curve run (TEN-190), debug builds only: what it ran on and with, and every point it measured, one
 * JSON file `filesDir/bench/decode-<startedAtMs>.json` ([DecodeRunStore]). Read back leniently, like [BenchRun].
 *
 * @param name what the user renamed it to, `null` for the default name.
 * @param partial the run ended before the last of [DecodeRunConfig.points]: [stopReason] says why.
 * @param stopReason [BenchRun.StoppedByUser] for Cancel, else the reason the log gave, `null` for a full run.
 * @param points in run order, the smallest context first.
 */
@Serializable
data class DecodeRun(
    val kind: String = Kind,
    val version: Int = Version,
    val id: String,
    val name: String? = null,
    @SerialName("started_at_ms") val startedAtMs: Long,
    @SerialName("finished_at_ms") val finishedAtMs: Long,
    val config: DecodeRunConfig,
    val partial: Boolean = false,
    @SerialName("stop_reason") val stopReason: String? = null,
    val points: List<DecodeRunPoint>,
) {
    companion object {
        const val Kind = "tensai.bench.decode_run"
        const val Version = 1
        const val Prefix = "decode-"

        /** The id, and file name, of a run started at [startedAtMs]: one bench at a time, so the millisecond is unique. */
        fun idFor(startedAtMs: Long): String = "$Prefix$startedAtMs"

        /** The run that measured [points] of [config]'s. Fewer is a partial run, [stopReason] saying why. */
        fun of(startedAtMs: Long, finishedAtMs: Long, config: DecodeRunConfig, points: List<DecodeRunPoint>, stopReason: String?): DecodeRun {
            val partial = points.size < config.points.size
            return DecodeRun(
                id = idFor(startedAtMs),
                startedAtMs = startedAtMs,
                finishedAtMs = finishedAtMs,
                config = config,
                partial = partial,
                stopReason = stopReason.takeIf { partial },
                points = points,
            )
        }
    }
}

/**
 * The CPU load knobs as a run saves them: [nThreads] `null` for auto (or unknown), [flashAttn] and [kv] as the knobs
 * name them ("auto", "on", "off"; "auto", "f16", "q8_0").
 */
@Serializable
data class DecodeKnobs(
    @SerialName("n_threads") val nThreads: Int? = null,
    @SerialName("flash_attn") val flashAttn: String = Auto,
    val kv: String = Auto,
) {
    companion object {
        const val Auto = "auto"

        /** [knobs] as asked for, before the load resolves them. */
        fun of(knobs: CpuLoadKnobs): DecodeKnobs =
            DecodeKnobs(knobs.decodeThreads, knobs.effectiveFlashAttn.name.lowercase(), knobs.kv?.arg ?: Auto)
    }
}

/**
 * What a run ran on and with.
 *
 * @param quant the model's quantization label as the library shows it, `null` when unknown.
 * @param backend [backendName] of the load: "CPU", "Vulkan".
 * @param nCtx the context the model was loaded with.
 * @param requested the knobs set when it was loaded, `null` on a GPU load, which takes none.
 * @param loaded what the load used: the decode threads, flash attention and the cache type it reports.
 * @param points the context lengths the run was to measure, those that fit [nCtx]; [skipped] the ones picked that did not.
 * @param soc the SoC as the device profile reads it, [device] the maker and model.
 */
@Serializable
data class DecodeRunConfig(
    val model: String,
    val quant: String? = null,
    val backend: String? = null,
    @SerialName("n_ctx") val nCtx: Int,
    val requested: DecodeKnobs? = null,
    val loaded: DecodeKnobs,
    @SerialName("tokens_per_point") val tokensPerPoint: Int,
    val points: List<Int>,
    val skipped: List<Int> = emptyList(),
    @SerialName("app_version_name") val appVersionName: String? = null,
    @SerialName("app_version_code") val appVersionCode: Int? = null,
    @SerialName("llama_kt_commit") val llamaKtCommit: String? = null,
    val device: String? = null,
    val soc: String? = null,
)

/**
 * One point of the curve. The speeds are llama.cpp's own timings ([io.github.tensaiapp.engine.DecodeTimings]), not
 * Kotlin's clock: `null` when the engine gave none.
 *
 * @param promptTokens the KV cache's fill after the decode minus the tokens decoded into it ([evalTokens]: the last
 *   token sampled is never decoded), the whole prompt; `null` when unread.
 * @param prefillTokens the prompt tokens llama.cpp decoded (the template's opening may come from the cache), in [prefillMs].
 * @param evalTokens llama.cpp's single-token decodes, in [evalMs]: one less than [generated].
 * @param generated the tokens sampled, at most the run's tokens per point.
 * @param decodeTps [evalTokens] over [evalMs], [prefillTps] [prefillTokens] over [prefillMs], rounded to 0.01.
 * @param ttftMs and [ms]: Kotlin's time to the first token and for the whole point, for reference only.
 */
@Serializable
data class DecodeRunPoint(
    val ctx: Int,
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("prefill_tokens") val prefillTokens: Int? = null,
    @SerialName("prefill_ms") val prefillMs: Double? = null,
    @SerialName("eval_tokens") val evalTokens: Int = 0,
    @SerialName("eval_ms") val evalMs: Double? = null,
    val generated: Int = 0,
    @SerialName("decode_tps") val decodeTps: Double? = null,
    @SerialName("prefill_tps") val prefillTps: Double? = null,
    @SerialName("ttft_ms") val ttftMs: Long? = null,
    val ms: Long = 0,
    val stop: String? = null,
    val error: String? = null,
) {
    companion object {
        /** The point at [ctx] from a decode's [stats] (or its [error]), the cache's fill after it ([kvTokens]) and its wall time [ms]. */
        fun of(ctx: Int, stats: DecodeStats?, error: String?, kvTokens: Int?, ms: Long): DecodeRunPoint {
            val timings = stats?.timings
            val generated = stats?.tokensGenerated ?: 0
            return DecodeRunPoint(
                ctx = ctx,
                promptTokens = kvTokens?.let { (it - (timings?.evalTokens ?: (generated - 1).coerceAtLeast(0))).coerceAtLeast(0) },
                prefillTokens = timings?.promptTokens,
                prefillMs = timings?.promptMs?.let(::round2),
                evalTokens = timings?.evalTokens ?: 0,
                evalMs = timings?.evalMs?.let(::round2),
                generated = generated,
                decodeTps = timings?.let { rate(it.evalTokens, it.evalMs) },
                prefillTps = timings?.let { rate(it.promptTokens, it.promptMs) },
                ttftMs = stats?.ttftMs,
                ms = ms,
                stop = stats?.stopReason?.name,
                error = error,
            )
        }

        /** Tokens per second rounded to 0.01, `null` for no figure. */
        fun rate(tokens: Int, ms: Double): Double? = if (tokens <= 0 || ms <= 0) null else round2(tokens * 1000.0 / ms)

        internal fun round2(v: Double): Double = Math.round(v * 100) / 100.0
    }
}

/** Reads and writes [DecodeRun]s, and the `tensai.bench` log lines of a run. */
object DecodeRunJson {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(run: DecodeRun): String = json.encodeToString(DecodeRun.serializer(), run)

    /** Throws on a file that is not a decode run: the store skips it. */
    fun decode(text: String): DecodeRun = json.decodeFromString(DecodeRun.serializer(), text)

    /** The run's `decode_start` line: the config whole. */
    fun startLine(config: DecodeRunConfig): String = buildJsonObject {
        put("event", "decode_start")
        put("config", json.encodeToJsonElement(config))
    }.toString()

    /** One `decode` line per point: the point and what the load used. */
    fun pointLine(point: DecodeRunPoint, config: DecodeRunConfig): String = JsonObject(
        mapOf("event" to JsonPrimitive("decode")) +
            json.encodeToJsonElement(point).jsonObject +
            mapOf("loaded" to json.encodeToJsonElement(config.loaded)),
    ).toString()

    /** The run's `decode_summary` line: its id, the setup and every point's decode and prefill speed. */
    fun summaryLine(run: DecodeRun): String = buildJsonObject {
        put("event", "decode_summary")
        put("id", run.id)
        put("model", run.config.model)
        put("backend", run.config.backend)
        put("n_ctx", run.config.nCtx)
        put("loaded", json.encodeToJsonElement(run.config.loaded))
        put("tokens_per_point", run.config.tokensPerPoint)
        put(
            "points",
            buildJsonArray {
                run.points.forEach { p ->
                    add(buildJsonObject {
                        put("ctx", p.ctx)
                        put("prompt_tokens", p.promptTokens)
                        put("decode_tps", p.decodeTps)
                        put("prefill_tps", p.prefillTps)
                    })
                }
            },
        )
        put("skipped", buildJsonArray { run.config.skipped.forEach { add(JsonPrimitive(it)) } })
        if (run.partial) put("stop_reason", run.stopReason)
    }.toString()
}
