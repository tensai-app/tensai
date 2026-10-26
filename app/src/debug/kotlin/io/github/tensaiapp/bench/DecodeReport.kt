package io.github.tensaiapp.bench

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

/*
 * What the Decode tab (TEN-190) reads off [DecodeRun]s, pure so it is tested on the JVM: the takeaway, the comparison
 * of two runs, the time a run takes, the knobs that changed, and the text a ticket comment gets. Debug builds only.
 */

/** A context length as the screen writes it: "0", "512", "1k", "2k", "4k". */
fun ctxLabel(ctx: Int): String = if (ctx >= 1024 && ctx % 1024 == 0) "${ctx / 1024}k" else ctx.toString()

/** A speed with two decimals, "-" for none. */
fun tpsText(tps: Double?): String = tps?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "-"

/**
 * How decode speed moves from the run's first measured point to [TargetCtx], or to its last point when 2k was not
 * measured; prefill alongside, `null` where unmeasured. `null` with fewer than two measured points.
 */
data class DecodeTakeaway(
    val fromCtx: Int,
    val toCtx: Int,
    val decodeFrom: Double,
    val decodeTo: Double,
    val prefillFrom: Double?,
    val prefillTo: Double?,
) {
    /** Percent decode changes from the first point to the other, negative for a drop. */
    val decodeChangePct: Double get() = (decodeTo - decodeFrom) / decodeFrom * 100

    companion object {
        const val TargetCtx = 2048

        fun of(points: List<DecodeRunPoint>): DecodeTakeaway? {
            val measured = points.filter { (it.decodeTps ?: 0.0) > 0 }
            val first = measured.firstOrNull() ?: return null
            val rest = measured.drop(1)
            val to = rest.firstOrNull { it.ctx == TargetCtx } ?: rest.lastOrNull() ?: return null
            return DecodeTakeaway(first.ctx, to.ctx, first.decodeTps!!, to.decodeTps!!, first.prefillTps, to.prefillTps)
        }
    }
}

/** Within this many percent either way, two speeds are the same: run to run noise. */
const val NoiseBandPct = 2.0

/** Which way B moved against A. */
enum class DeltaKind { Faster, Slower, Noise }

/** B against A in percent, `null` when either is missing. */
fun deltaPct(a: Double?, b: Double?): Double? = if (a == null || b == null || a <= 0) null else (b - a) / a * 100

/** [pct] read against the ±[NoiseBandPct] band: higher is faster for decode and prefill alike. */
fun deltaKind(pct: Double): DeltaKind = when {
    pct > NoiseBandPct -> DeltaKind.Faster
    pct < -NoiseBandPct -> DeltaKind.Slower
    else -> DeltaKind.Noise
}

/** "+10%", "−32%" with [minus] for the sign (the screen's U+2212, the text export's ASCII "-"), rounded to [decimals]. */
fun pctText(pct: Double, minus: String = "−", decimals: Int = 0): String {
    val shown = String.format(Locale.ROOT, "%.${decimals}f", abs(pct))
    val zero = shown.all { it == '0' || it == '.' }
    return when {
        zero -> "$shown%"
        pct < 0 -> "$minus$shown%"
        else -> "+$shown%"
    }
}

/** One context length of a comparison: each run's speeds there, `null` where that run has no figure. */
data class DecodeCompareRow(
    val ctx: Int,
    val aDecode: Double?,
    val bDecode: Double?,
    val aPrefill: Double?,
    val bPrefill: Double?,
) {
    val decodeDelta: Double? get() = deltaPct(aDecode, bDecode)
    val prefillDelta: Double? get() = deltaPct(aPrefill, bPrefill)
}

/** A knob that differs: [key] as the chips write it ("n_threads", "fa", "kv"), its value [from] and [to]. */
data class KnobChange(val key: String, val from: String, val to: String) {
    /** "fa auto → off". */
    val label: String get() = "$key $from → $to"
}

/** "n_threads 2", "fa auto", "kv q8_0": the chips of [knobs], in that order. */
fun knobChips(knobs: DecodeKnobs): List<Pair<String, String>> = listOf(
    "n_threads" to (knobs.nThreads?.toString() ?: DecodeKnobs.Auto),
    "fa" to knobs.flashAttn,
    "kv" to knobs.kv,
)

/** The knobs that differ from [from] to [to], in chip order. */
fun knobChanges(from: DecodeKnobs, to: DecodeKnobs): List<KnobChange> =
    knobChips(from).zip(knobChips(to)).filter { (a, b) -> a.second != b.second }.map { (a, b) -> KnobChange(a.first, a.second, b.second) }

/** Two runs side by side, A the older: the rows, what differs between them, and the headline. */
data class DecodeCompare(val a: DecodeRun, val b: DecodeRun) {
    /** Every context length either run measured, smallest first. */
    val rows: List<DecodeCompareRow> = (a.points.map { it.ctx } + b.points.map { it.ctx }).distinct().sorted().map { ctx ->
        val pa = a.points.firstOrNull { it.ctx == ctx }
        val pb = b.points.firstOrNull { it.ctx == ctx }
        DecodeCompareRow(ctx, pa?.decodeTps, pb?.decodeTps, pa?.prefillTps, pb?.prefillTps)
    }

    /** What the loads used that differs, A's value to B's. */
    val changes: List<KnobChange> = knobChanges(a.config.loaded, b.config.loaded)

    val sameModel: Boolean get() = a.config.model == b.config.model && a.config.quant == b.config.quant && a.config.backend == b.config.backend

    /** The row the headline reads: 2k when both runs measured decode there, else the last row both did, `null` for none. */
    val headline: DecodeCompareRow?
        get() {
            val both = rows.filter { it.decodeDelta != null }
            return both.firstOrNull { it.ctx == DecodeTakeaway.TargetCtx } ?: both.lastOrNull()
        }
}

/** Prefill and decode speeds to estimate a run's time with. */
data class DecodeRates(val prefillTps: Double, val decodeTps: Double) {
    companion object {
        /** Rough figures for a 1-2B Q4 model on a mid-range phone, until a run on the model measures better ones. */
        val Cpu = DecodeRates(prefillTps = 30.0, decodeTps = 8.0)
        val Gpu = DecodeRates(prefillTps = 4.0, decodeTps = 6.0)

        fun defaultFor(backend: String?): DecodeRates = if (backend == null || backend == "CPU") Cpu else Gpu

        /** The mean speeds of [points], `null` unless both were measured somewhere. */
        fun of(points: List<DecodeRunPoint>): DecodeRates? {
            val prefill = points.mapNotNull { it.prefillTps }.filter { it > 0 }
            val decode = points.mapNotNull { it.decodeTps }.filter { it > 0 }
            if (prefill.isEmpty() || decode.isEmpty()) return null
            return DecodeRates(prefill.average(), decode.average())
        }
    }
}

/** How long a run takes, from its points, its tokens per point and [DecodeRates]. */
object DecodeEstimate {
    /** The template, the nonce and the instruction around a point's filler. */
    const val PromptOverheadTokens = 40

    /** The unlogged decode before the first point. */
    const val WarmUpTokens = 16

    fun pointMs(ctx: Int, tokens: Int, rates: DecodeRates): Double =
        ((ctx + PromptOverheadTokens) / rates.prefillTps + tokens / rates.decodeTps) * 1000

    /** The whole run: the warm-up, then every point. */
    fun totalMs(points: List<Int>, tokens: Int, rates: DecodeRates): Double =
        pointMs(0, WarmUpTokens, rates) + points.sumOf { pointMs(it, tokens, rates) }

    /** [ms] in whole minutes, rounded up, at least 1. */
    fun minutes(ms: Double): Int = ceil(ms / 60_000).toInt().coerceAtLeast(1)
}

/** "6 min 12 s", "48 s": how long a run took. */
fun durationText(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s < 60) "$s s" else "${s / 60} min ${s % 60} s"
}

/** The plain-text tables a ticket comment takes: Markdown, the config on top. ASCII signs only. */
object DecodeRunText {
    private const val TableHead = "| ctx | prompt | eval | decode t/s | prefill t/s |\n|---:|---:|---:|---:|---:|"

    fun of(run: DecodeRun, defaultName: String = "Decode run", zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        val c = run.config
        appendLine("**${run.name ?: defaultName}** · ${dateText(run.startedAtMs, zone)} · ${durationText(run.finishedAtMs - run.startedAtMs)}")
        appendLine("${modelText(c)} · n_ctx ${c.nCtx}")
        appendLine("loaded: ${knobsText(c.loaded)} · ${c.tokensPerPoint} tok/point")
        c.requested?.let { appendLine("requested: ${knobsText(it)}") }
        buildText(c)?.let { appendLine(it) }
        if (run.partial) appendLine("partial ${run.points.size}/${c.points.size}: ${run.stopReason ?: "stopped"}")
        if (c.skipped.isNotEmpty()) appendLine("skipped (over n_ctx): ${c.skipped.joinToString(", ") { ctxLabel(it) }}")
        DecodeTakeaway.of(run.points)?.let { t ->
            append("decode ${tpsText(t.decodeFrom)} → ${tpsText(t.decodeTo)} tok/s (${pctText(t.decodeChangePct, "-")}) from ${ctxLabel(t.fromCtx)} to ${ctxLabel(t.toCtx)}")
            if (t.prefillFrom != null || t.prefillTo != null) append(" · prefill ${tpsText(t.prefillFrom)} → ${tpsText(t.prefillTo)}")
            appendLine()
        }
        appendLine()
        appendLine(TableHead)
        run.points.forEach { p ->
            append("| ${p.ctx} | ${p.promptTokens ?: "-"} | ${p.evalTokens} | ${tpsText(p.decodeTps)} | ${tpsText(p.prefillTps)} |")
            p.error?.let { append(" $it") }
            appendLine()
        }
    }.trimEnd()

    fun compare(compare: DecodeCompare, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        val (a, b) = compare.a to compare.b
        append("**Decode compare** · A vs B")
        if (compare.changes.isNotEmpty()) append(" · " + compare.changes.joinToString(", ") { it.label })
        appendLine()
        listOf("A" to a, "B" to b).forEach { (key, run) ->
            val c = run.config
            appendLine("$key: ${dateText(run.startedAtMs, zone)} · ${modelText(c)} · n_ctx ${c.nCtx} · ${knobsText(c.loaded)} · ${c.tokensPerPoint} tok/point" + if (run.partial) " · partial" else "")
        }
        compare.headline?.let { h ->
            append("B vs A at ${ctxLabel(h.ctx)}: decode ${pctText(h.decodeDelta!!, "-")}")
            h.prefillDelta?.let { append(", prefill ${pctText(it, "-")}") }
            appendLine()
        }
        listOf(
            "decode tok/s" to { r: DecodeCompareRow -> Triple(r.aDecode, r.bDecode, r.decodeDelta) },
            "prefill tok/s" to { r: DecodeCompareRow -> Triple(r.aPrefill, r.bPrefill, r.prefillDelta) },
        ).forEach { (title, pick) ->
            appendLine()
            appendLine(title)
            appendLine("| ctx | A | B | B vs A |\n|---:|---:|---:|---:|")
            compare.rows.forEach { row ->
                val (va, vb, delta) = pick(row)
                val d = delta?.let { pctText(it, "-", decimals = 1) + if (deltaKind(it) == DeltaKind.Noise) " (noise)" else "" } ?: "-"
                appendLine("| ${row.ctx} | ${tpsText(va)} | ${tpsText(vb)} | $d |")
            }
        }
        appendLine()
        append("Within ±${NoiseBandPct.toInt()}% counts as noise. Higher is faster for both.")
    }

    /** "Qwen3-1.7B Q4_K_M · CPU". */
    fun modelText(c: DecodeRunConfig): String =
        listOfNotNull(listOfNotNull(c.model, c.quant).joinToString(" "), c.backend).joinToString(" · ")

    fun knobsText(k: DecodeKnobs): String = knobChips(k).joinToString(" · ") { (key, value) -> "$key $value" }

    private fun buildText(c: DecodeRunConfig): String? = listOfNotNull(
        c.appVersionName?.let { v -> "app $v" + (c.appVersionCode?.let { " ($it)" } ?: "") },
        c.llamaKtCommit?.let { "llama.kt $it" },
        c.soc,
        c.device,
    ).takeIf { it.isNotEmpty() }?.joinToString(" · ")

    private fun dateText(ms: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(ms).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT))
}
