package io.github.tensaiapp.ui.screens.bench

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tensaiapp.R
import io.github.tensaiapp.bench.DecodeBench
import io.github.tensaiapp.bench.DecodeCompare
import io.github.tensaiapp.bench.DecodeCompareRow
import io.github.tensaiapp.bench.DecodeRun
import io.github.tensaiapp.bench.DecodeRunStore
import io.github.tensaiapp.bench.DecodeRunText
import io.github.tensaiapp.bench.DecodeTakeaway
import io.github.tensaiapp.bench.DeltaKind
import io.github.tensaiapp.bench.ctxLabel
import io.github.tensaiapp.bench.deltaKind
import io.github.tensaiapp.bench.durationText
import io.github.tensaiapp.bench.knobChips
import io.github.tensaiapp.bench.pctText
import io.github.tensaiapp.bench.tpsText
import io.github.tensaiapp.ui.components.TensaiButton
import io.github.tensaiapp.ui.components.TensaiButtonSize
import io.github.tensaiapp.ui.components.TensaiButtonVariant
import io.github.tensaiapp.ui.components.TensaiDialog
import io.github.tensaiapp.ui.components.TensaiDialogAction
import io.github.tensaiapp.ui.screens.settings.RowDivider
import io.github.tensaiapp.ui.theme.TensaiMono
import io.github.tensaiapp.ui.theme.tensaiColors
import io.github.tensaiapp.ui.theme.tensaiTints
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The saved decode runs, read once if no screen has yet. */
@Composable
private fun decodeRuns(): List<DecodeRun>? {
    val context = LocalContext.current
    val store = remember { DecodeRunStore.of(context) }
    val runs by store.runs.collectAsState()
    LaunchedEffect(Unit) { if (store.runs.value == null) withContext(Dispatchers.IO) { store.refresh() } }
    KeepScreenOn(DecodeBench.progress.collectAsState().value != null)
    return runs
}

/**
 * One decode run (TEN-190), from its saved row or at the end of a run: its name and setup, the takeaway, the chart of
 * decode or prefill speed against context, the points, Delete and Copy as text.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DecodeRunScreen(runId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { DecodeRunStore.of(context) }
    val run = decodeRuns()?.firstOrNull { it.id == runId }
    val defaultName = stringResource(R.string.decode_run_default_name)
    var renaming by remember { mutableStateOf(false) }
    var metric by rememberSaveable { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        BenchTopBar(
            title = run?.name ?: defaultName,
            subtitle = run?.let { startedLabel(it.startedAtMs) + " · " + durationText(it.finishedAtMs - it.startedAtMs) },
            onBack = onBack,
        )
        if (run == null) return@Column
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.size(4.dp))
            BenchCard {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(run.name ?: defaultName, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f, fill = false))
                        Icon(Icons.Rounded.CloudDone, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
                        Text(stringResource(R.string.decode_auto_saved), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                        TensaiButton(
                            text = stringResource(R.string.decode_rename),
                            onClick = { renaming = true },
                            variant = TensaiButtonVariant.Text,
                            size = TensaiButtonSize.Sm,
                            icon = Icons.Rounded.Edit,
                        )
                    }
                    Text(
                        text = DecodeRunText.modelText(run.config) + " · n_ctx ${run.config.nCtx}",
                        fontFamily = TensaiMono,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        knobChips(run.config.loaded).forEach { (key, value) -> MonoChip("$key $value") }
                        MonoChip("${run.config.tokensPerPoint} tok/point")
                    }
                    if (run.partial) PartialBadge(stringResource(R.string.decode_partial, run.points.size, run.config.points.size))
                }
            }
            val takeaway = remember(run) { DecodeTakeaway.of(run.points) }
            BenchCard {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(takeawayText(takeaway), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    if (takeaway != null) {
                        Text(
                            text = "${tpsText(takeaway.decodeFrom)} → ${tpsText(takeaway.decodeTo)} tok/s · prefill ${tpsText(takeaway.prefillFrom)} → ${tpsText(takeaway.prefillTo)}",
                            fontFamily = TensaiMono,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            val primary = MaterialTheme.colorScheme.primary
            ChartCard(metric, onMetric = { metric = it }, maxCtx = run.points.maxOfOrNull { it.ctx } ?: 0, valueLabels = true) { decode ->
                listOf(ChartSeries(run.points.mapNotNull { p -> (if (decode) p.decodeTps else p.prefillTps)?.let { p.ctx to it } }, primary))
            }
            Eyebrow(stringResource(R.string.decode_eyebrow_points), modifier = Modifier.padding(top = 0.dp))
            PointsTable(run.points.map { it.ctx to it })
            Row(verticalAlignment = Alignment.CenterVertically) {
                TensaiButton(
                    text = stringResource(R.string.bench_delete),
                    onClick = {
                        store.delete(run.id)
                        onBack()
                    },
                    variant = TensaiButtonVariant.Text,
                    contentColor = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.weight(1f))
                TensaiButton(
                    text = stringResource(R.string.decode_copy_text),
                    onClick = { scope.launch { copyText(context) { DecodeRunText.of(run, defaultName) } } },
                    variant = TensaiButtonVariant.Tonal,
                    icon = Icons.Rounded.ContentCopy,
                )
            }
        }
    }
    if (renaming && run != null) {
        RenameDialog(
            initial = run.name ?: defaultName,
            onDismiss = { renaming = false },
            onSave = { name ->
                renaming = false
                val renamed = run.copy(name = name.trim().takeIf { it.isNotEmpty() && it != defaultName })
                scope.launch { withContext(Dispatchers.IO) { runCatching { store.save(renamed) } } }
            },
        )
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    TensaiDialog(
        onDismissRequest = onDismiss,
        icon = Icons.Rounded.Edit,
        iconTint = MaterialTheme.colorScheme.primary,
        title = stringResource(R.string.decode_rename_title),
        body = stringResource(R.string.decode_rename_body),
        actions = listOf(
            TensaiDialogAction(stringResource(R.string.decode_rename_cancel), onDismiss),
            TensaiDialogAction(stringResource(R.string.decode_rename_save), { onSave(text) }),
        ),
    ) {
        OutlinedTextField(value = text, onValueChange = { text = it.take(80) }, singleLine = true, modifier = Modifier.fillMaxWidth())
    }
}

/** "Decode drops 49% from 0 to 2k context.", or that it held within the noise band, or that one point is not a curve. */
@Composable
private fun takeawayText(t: DecodeTakeaway?): String {
    if (t == null) return stringResource(R.string.decode_takeaway_one)
    val pct = String.format(java.util.Locale.ROOT, "%.0f%%", abs(t.decodeChangePct))
    val from = ctxLabel(t.fromCtx)
    val to = ctxLabel(t.toCtx)
    return when (deltaKind(t.decodeChangePct)) {
        DeltaKind.Slower -> stringResource(R.string.decode_takeaway_drop, pct, from, to)
        DeltaKind.Faster -> stringResource(R.string.decode_takeaway_rise, pct, from, to)
        DeltaKind.Noise -> stringResource(R.string.decode_takeaway_flat, from, to)
    }
}

/** The chart card: the y label, Decode | Prefill, the chart, and its x footer. [series] draws for decode (`true`) or prefill. */
@Composable
private fun ChartCard(metric: Int, onMetric: (Int) -> Unit, maxCtx: Int, valueLabels: Boolean, series: (Boolean) -> List<ChartSeries>) {
    val decode = metric == 0
    BenchCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (decode) "decode tok/s" else "prefill tok/s",
                    fontFamily = TensaiMono,
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Segmented(
                    options = listOf(stringResource(R.string.decode_chart_decode), stringResource(R.string.decode_chart_prefill)),
                    selected = metric,
                    onSelect = onMetric,
                )
            }
            DecodeChart(series(decode), valueLabels = valueLabels)
            Row {
                Text("ctx 0 → ${ctxLabel(maxCtx)}", fontFamily = TensaiMono, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.decode_chart_x), fontFamily = TensaiMono, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Two decode runs side by side (TEN-190), A the older: what each ran with (the knobs that differ highlighted), the
 * takeaway at 2k, both lines on one chart, and decode and prefill per context with B against A.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DecodeCompareScreen(runIds: List<String>, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val all = decodeRuns()
    val runs = remember(all, runIds) { runIds.mapNotNull { id -> all?.firstOrNull { it.id == id } } }
    val compare = remember(runs) { if (runs.size == 2) DecodeCompare(runs[0], runs[1]) else null }
    var metric by rememberSaveable { mutableIntStateOf(0) }

    Column(modifier = Modifier.fillMaxSize()) {
        BenchTopBar(
            title = stringResource(R.string.decode_compare_title),
            subtitle = compare?.let { c -> (listOf("A vs B") + c.changes.map { it.label }).joinToString(" · ") },
            onBack = onBack,
        )
        if (compare == null) return@Column
        val primary = MaterialTheme.colorScheme.primary
        val secondary = MaterialTheme.colorScheme.onSurfaceVariant
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.size(4.dp))
            BenchCard {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val changed = compare.changes.map { it.key }.toSet()
                        listOf(compare.a to false, compare.b to true).forEach { (run, isB) ->
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    LegendSwatch(if (isB) secondary else primary, dashed = isB, square = isB)
                                    Text(if (isB) "B" else "A", fontFamily = TensaiMono, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = if (isB) secondary else primary)
                                    Text(startedLabel(run.startedAtMs), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    knobChips(run.config.loaded).forEach { (key, value) -> MonoChip("$key $value", highlighted = key in changed) }
                                    MonoChip("${run.config.tokensPerPoint} tok/point")
                                }
                                if (run.partial) PartialBadge(stringResource(R.string.decode_partial, run.points.size, run.config.points.size))
                            }
                        }
                    }
                    RowDivider()
                    Text(
                        text = if (compare.sameModel) {
                            DecodeRunText.modelText(compare.a.config) + " · " + stringResource(R.string.decode_same_model)
                        } else {
                            "A " + DecodeRunText.modelText(compare.a.config) + "\nB " + DecodeRunText.modelText(compare.b.config)
                        },
                        fontFamily = TensaiMono,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            BenchCard {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(compareTakeaway(compare), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Text(sameLine(compare), fontFamily = TensaiMono, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            ChartCard(metric, onMetric = { metric = it }, maxCtx = compare.rows.maxOfOrNull { it.ctx } ?: 0, valueLabels = false) { decode ->
                fun line(pick: (DecodeCompareRow) -> Double?) = compare.rows.mapNotNull { r -> pick(r)?.let { r.ctx to it } }
                listOf(
                    ChartSeries(line { if (decode) it.aDecode else it.aPrefill }, primary),
                    ChartSeries(line { if (decode) it.bDecode else it.bPrefill }, secondary, dashed = true, squareMarkers = true),
                )
            }
            Eyebrow(stringResource(R.string.decode_eyebrow_decode_tps))
            DeltaTable(compare.rows) { Triple(it.aDecode, it.bDecode, it.decodeDelta) }
            Eyebrow(stringResource(R.string.decode_eyebrow_prefill_tps))
            DeltaTable(compare.rows) { Triple(it.aPrefill, it.bPrefill, it.prefillDelta) }
            Text(stringResource(R.string.decode_noise_note), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
            Row {
                Spacer(Modifier.weight(1f))
                TensaiButton(
                    text = stringResource(R.string.decode_copy_text),
                    onClick = { scope.launch { copyText(context) { DecodeRunText.compare(compare) } } },
                    variant = TensaiButtonVariant.Tonal,
                    icon = Icons.Rounded.ContentCopy,
                )
            }
        }
    }
}

/** "fa off: decode +10%, prefill −32% at 2k.": B's differing knobs, then its deltas at the headline row. */
@Composable
private fun compareTakeaway(compare: DecodeCompare): String {
    val subject = compare.changes.joinToString(", ") { "${it.key} ${it.to}" }.ifEmpty { "B vs A" }
    val row = compare.headline ?: return subject
    val decode = pctText(row.decodeDelta!!)
    val ctx = ctxLabel(row.ctx)
    return row.prefillDelta?.let { stringResource(R.string.decode_compare_takeaway, subject, decode, pctText(it), ctx) }
        ?: stringResource(R.string.decode_compare_takeaway_decode, subject, decode, ctx)
}

/** "B vs A · same model, threads and KV cache": what the two runs share, of the model and the three knobs. */
@Composable
private fun sameLine(compare: DecodeCompare): String {
    val changed = compare.changes.map { it.key }.toSet()
    val same = listOfNotNull(
        stringResource(R.string.decode_same_part_model).takeIf { compare.sameModel },
        stringResource(R.string.decode_same_part_threads).takeIf { "n_threads" !in changed },
        stringResource(R.string.decode_same_part_fa).takeIf { "fa" !in changed },
        stringResource(R.string.decode_same_part_kv).takeIf { "kv" !in changed },
    )
    if (same.isEmpty()) return "B vs A"
    val list = if (same.size == 1) same.single() else same.dropLast(1).joinToString(", ") + stringResource(R.string.decode_and) + same.last()
    return "B vs A · " + stringResource(R.string.decode_same, list)
}

/** A short sample of a run's line in the legend: solid with a dot for A, dashed with a square for B. */
@Composable
private fun LegendSwatch(color: Color, dashed: Boolean, square: Boolean) {
    Canvas(modifier = Modifier.size(width = 22.dp, height = 10.dp)) {
        val y = size.height / 2
        drawLine(
            color,
            Offset(0f, y),
            Offset(size.width, y),
            strokeWidth = 2.dp.toPx(),
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null,
        )
        val r = 3.dp.toPx()
        if (square) {
            drawRect(color, Offset(size.width / 2 - r, y - r), Size(2 * r, 2 * r))
        } else {
            drawCircle(color, r, Offset(size.width / 2, y))
        }
    }
}

/** ctx · A · B · "B vs A": the values mono and right-aligned, the delta as a pill. */
@Composable
private fun DeltaTable(rows: List<DecodeCompareRow>, pick: (DecodeCompareRow) -> Triple<Double?, Double?, Double?>) {
    BenchCard {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(modifier = Modifier.fillMaxWidth().heightIn(min = 30.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("ctx", "A", "B", "B vs A").forEachIndexed { i, h ->
                    Text(
                        text = h,
                        fontFamily = TensaiMono,
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = if (i == 0) TextAlign.Start else TextAlign.End,
                        modifier = Modifier.weight(if (i == 3) 1.4f else 1f),
                    )
                }
            }
            rows.forEach { row ->
                val (a, b, delta) = pick(row)
                RowDivider()
                Row(modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(ctxLabel(row.ctx), fontFamily = TensaiMono, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(tpsText(a), fontFamily = TensaiMono, fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                    Text(tpsText(b), fontFamily = TensaiMono, fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.weight(1.4f)) {
                        if (delta == null) {
                            Text("·", fontFamily = TensaiMono, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            DeltaPill(delta)
                        }
                    }
                }
            }
        }
    }
}

/** B against A: success tint and an up arrow when faster, error tint and a down arrow when slower, outlined within the noise band. */
@Composable
private fun DeltaPill(pct: Double) {
    val tints = MaterialTheme.tensaiTints
    val kind = deltaKind(pct)
    val (fill, content, icon) = when (kind) {
        DeltaKind.Faster -> Triple(tints.success12, MaterialTheme.tensaiColors.success, Icons.Rounded.ArrowUpward)
        DeltaKind.Slower -> Triple(tints.error12, MaterialTheme.colorScheme.error, Icons.Rounded.ArrowDownward)
        DeltaKind.Noise -> Triple(Color.Transparent, MaterialTheme.colorScheme.onSurfaceVariant, Icons.Rounded.DragHandle)
    }
    Surface(
        shape = CircleShape,
        color = fill,
        contentColor = content,
        border = if (kind == DeltaKind.Noise) BorderStroke(1.dp, tints.outlineStrong) else null,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp))
            Text(pctText(pct, decimals = 1), fontFamily = TensaiMono, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}
