package io.github.tensaiapp.ui.screens.bench

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tensaiapp.R
import io.github.tensaiapp.bench.AgentBench
import io.github.tensaiapp.bench.BenchRun
import io.github.tensaiapp.bench.BenchRunJson
import io.github.tensaiapp.bench.BenchRunStore
import io.github.tensaiapp.bench.BenchVerdict
import io.github.tensaiapp.bench.CompareRow
import io.github.tensaiapp.bench.PassCell
import io.github.tensaiapp.bench.RunTotals
import io.github.tensaiapp.bench.SummaryMetric
import io.github.tensaiapp.bench.bestRuns
import io.github.tensaiapp.bench.compareRows
import io.github.tensaiapp.bench.runKey
import io.github.tensaiapp.ui.components.TensaiButton
import io.github.tensaiapp.ui.components.TensaiButtonVariant
import io.github.tensaiapp.ui.screens.settings.RowDivider
import io.github.tensaiapp.ui.theme.TensaiMono
import io.github.tensaiapp.ui.theme.tensaiTints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Two to four saved runs side by side (TEN-187), or one run on its own (a tap on its card): what each ran with, the
 * summary with the best of each metric in bold, and the matrix of prompts, all of them or only those whose outcome
 * changed. A cell opens that prompt in that run ([onOpenPrompt]: run id, prompt id, the run's key).
 */
@Composable
internal fun BenchCompareScreen(runIds: List<String>, onBack: () -> Unit, onOpenPrompt: (String, String, String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { BenchRunStore.of(context) }
    val all by store.runs.collectAsState()
    LaunchedEffect(Unit) { if (store.runs.value == null) withContext(Dispatchers.IO) { store.refresh() } }
    KeepScreenOn(AgentBench.progress.collectAsState().value != null)
    val runs = remember(all, runIds) { runIds.mapNotNull { id -> all?.firstOrNull { it.id == id } } }
    val rows = remember(runs) { compareRows(runs) }
    val totals = remember(runs) { runs.map { RunTotals.of(it.results) } }
    var onlyChanged by rememberSaveable { mutableStateOf(false) }
    val changedCount = rows.count { it.changed }

    Column(modifier = Modifier.fillMaxSize()) {
        BenchTopBar(
            title = stringResource(if (runs.size > 1) R.string.bench_compare_title else R.string.bench_run_title),
            subtitle = if (runs.isEmpty()) {
                null
            } else {
                pluralStringResource(R.plurals.bench_runs_count, runs.size, runs.size) + " · " +
                    pluralStringResource(R.plurals.bench_prompts_count, rows.size, rows.size)
            },
            onBack = onBack,
        )
        if (runs.isEmpty()) return@Column
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            Eyebrow(stringResource(R.string.bench_eyebrow_runs))
            RunsCard(runs)
            Eyebrow(stringResource(R.string.bench_eyebrow_summary), trailing = if (runs.size > 1) stringResource(R.string.bench_best_hint) else null)
            SummaryCard(totals)
            Eyebrow(stringResource(R.string.bench_eyebrow_prompts))
            if (runs.size > 1) {
                BenchSegmented(
                    options = listOf(
                        SegmentOption(stringResource(R.string.bench_filter_all, rows.size)),
                        SegmentOption(stringResource(R.string.bench_filter_changed, changedCount), icon = Icons.Rounded.FilterList),
                    ),
                    selected = if (onlyChanged) 1 else 0,
                    onSelect = { onlyChanged = it == 1 },
                )
                Box(Modifier.height(10.dp))
            }
            Matrix(runs, if (onlyChanged) rows.filter { it.changed } else rows, onOpenPrompt)
            Text(
                text = stringResource(R.string.bench_matrix_legend),
                fontFamily = TensaiMono,
                fontSize = 10.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp),
            )
            Text(
                text = stringResource(R.string.bench_matrix_hint),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 6.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 16.dp)) {
                val json = { if (runs.size == 1) BenchRunJson.encode(runs.single()) else BenchRunJson.encodeCompare(runs) }
                val name = if (runs.size == 1) runs.single().id else "compare-" + runs.joinToString("-") { it.startedAtMs.toString() }
                TensaiButton(
                    text = stringResource(R.string.bench_copy_json),
                    onClick = { scope.launch { copyJson(context, json) } },
                    variant = TensaiButtonVariant.Outlined,
                    icon = Icons.Rounded.ContentCopy,
                    modifier = Modifier.weight(1f),
                )
                TensaiButton(
                    text = stringResource(R.string.bench_share_json),
                    onClick = { scope.launch { shareJson(context, name, json) } },
                    icon = Icons.Rounded.Share,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private val LabelColumn = 84.dp

/** "(a)" in primary mono bold: a run's key. */
@Composable
private fun RunKey(index: Int, suffix: String? = null) {
    Text(
        text = "(${runKey(index)})" + (suffix?.let { " $it" } ?: ""),
        fontFamily = TensaiMono,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
    )
}

@Composable
private fun GridLabel(text: String, modifier: Modifier = Modifier, sub: String? = null) {
    Column(modifier = modifier) {
        Text(text, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface)
        if (sub != null) Text(sub, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MonoValue(text: String, modifier: Modifier = Modifier, maxLines: Int = 1) {
    Text(
        text = text,
        fontFamily = TensaiMono,
        fontSize = 11.5.sp,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** What each run ran with: started, model (unless the same for all), repetitions, config. */
@Composable
private fun RunsCard(runs: List<BenchRun>) {
    val models = runs.map { modelLine(it.config) }
    val sameModel = models.distinct().size == 1
    BenchCard {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row {
                Box(Modifier.width(LabelColumn))
                runs.indices.forEach { i -> Box(Modifier.weight(1f)) { RunKey(i) } }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                GridLabel(stringResource(R.string.bench_row_started), Modifier.width(LabelColumn))
                runs.forEach { run ->
                    Column(Modifier.weight(1f)) {
                        MonoValue(startedTime(run.startedAtMs))
                        if (run.partial) MonoValue("${run.results.size}/${run.plannedTrials}")
                    }
                }
            }
            if (!sameModel) {
                Row {
                    GridLabel(stringResource(R.string.bench_row_model), Modifier.width(LabelColumn))
                    models.forEach { MonoValue(it, Modifier.weight(1f).padding(end = 4.dp), maxLines = 3) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                GridLabel(stringResource(R.string.bench_row_reps), Modifier.width(LabelColumn))
                runs.forEach { MonoValue("×${it.config.repetitions}", Modifier.weight(1f)) }
            }
            Row {
                GridLabel(stringResource(R.string.bench_row_config), Modifier.width(LabelColumn))
                runs.forEach { run ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        configChips(run.config).forEach { (label, on) -> ConfigChip(label, on, strikeOff = true) }
                    }
                }
            }
            RowDivider()
            Text(
                text = if (sameModel) stringResource(R.string.bench_same_model, models.first()) else stringResource(R.string.bench_models_differ),
                fontFamily = TensaiMono,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The summary rows: value and share of trials (or ms) per run, the best of each row in primary bold. */
@Composable
private fun SummaryCard(totals: List<RunTotals>) {
    BenchCard {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            SummaryMetric.entries.forEachIndexed { index, metric ->
                if (index > 0) RowDivider()
                val best = bestRuns(totals, metric)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    val (label, sub) = metric.labels()
                    GridLabel(label, Modifier.width(LabelColumn + 16.dp), sub)
                    totals.forEachIndexed { i, run -> SummaryCell(run, metric, i in best, Modifier.weight(1f).padding(horizontal = 2.dp)) }
                }
            }
        }
    }
}

@Composable
private fun SummaryMetric.labels(): Pair<String, String> = when (this) {
    SummaryMetric.Correct -> "correct" to stringResource(R.string.bench_metric_correct)
    SummaryMetric.FalseCall -> "false_call" to stringResource(R.string.bench_metric_false_call)
    SummaryMetric.Missing -> "missing" to stringResource(R.string.bench_metric_missing)
    SummaryMetric.WrongTool -> "wrong_tool" to stringResource(R.string.bench_metric_wrong_tool)
    SummaryMetric.Error -> "error" to stringResource(R.string.bench_metric_error)
    SummaryMetric.NamedInText -> "named_in_text" to stringResource(R.string.bench_metric_named)
    SummaryMetric.ToolErrors -> "tool_errors" to stringResource(R.string.bench_metric_tool_errors)
    SummaryMetric.AnswerOk -> "answer_ok" to stringResource(R.string.bench_metric_answer_ok)
    SummaryMetric.AnswerWrong -> "answer_wrong" to stringResource(R.string.bench_metric_answer_wrong)
    SummaryMetric.AnswerCut -> "answer_cut" to stringResource(R.string.bench_metric_answer_cut)
    SummaryMetric.AnswerEmpty -> "answer_empty" to stringResource(R.string.bench_metric_answer_empty)
    SummaryMetric.TtftP50 -> "ttft p50" to "ms"
    SummaryMetric.TotalP50 -> "total p50" to "ms"
    SummaryMetric.ReasoningP50 -> "reasoning p50" to "tokens"
}

@Composable
private fun SummaryCell(totals: RunTotals, metric: SummaryMetric, best: Boolean, modifier: Modifier) {
    val color = if (best) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    val weight = if (best) FontWeight.Bold else FontWeight.Normal
    val (main, sub) = when (metric) {
        SummaryMetric.TtftP50 -> (totals.ttftP50Ms?.let(::groupDigits) ?: "-") to null
        SummaryMetric.TotalP50 -> (totals.totalP50Ms?.let(::groupDigits) ?: "-") to null
        SummaryMetric.ReasoningP50 -> (totals.reasoningP50?.let(::groupDigits) ?: "-") to null
        else -> totals.countOf(metric).toString() to totals.value(metric)?.let { "${Math.round(it * 100)}%" }
    }
    Column(
        modifier = modifier
            .background(if (best) MaterialTheme.tensaiTints.primary12 else Color.Transparent, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Text(main, fontFamily = TensaiMono, fontSize = 13.sp, fontWeight = weight, color = color, maxLines = 1)
        if (sub != null) Text(sub, fontFamily = TensaiMono, fontSize = 10.5.sp, color = if (best) color else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}


private val RowHeight = 40.dp
private val IdColumn = 120.dp
private val CellWidth = 72.dp

/**
 * The prompt matrix: the ids pinned on the left, one column of cells per run, scrolling sideways when they do not
 * fit. A changed row gets the ≠ mark and a primary-08 band.
 */
@Composable
private fun Matrix(runs: List<BenchRun>, rows: List<CompareRow>, onOpenPrompt: (String, String, String) -> Unit) {
    val band = MaterialTheme.tensaiTints.primary08
    BenchCard {
        Row(modifier = Modifier.padding(vertical = 6.dp)) {
            Column(modifier = Modifier.width(IdColumn)) {
                MatrixHeaderCell(Modifier.width(IdColumn)) {
                    Text("prompt id", fontFamily = TensaiMono, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 26.dp))
                }
                rows.forEach { row ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.height(RowHeight).fillMaxWidth().background(if (row.changed && runs.size > 1) band else Color.Transparent),
                    ) {
                        Text(
                            text = if (row.changed && runs.size > 1) "≠" else "",
                            fontFamily = TensaiMono,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(26.dp).padding(start = 10.dp),
                        )
                        Text(
                            text = row.promptId,
                            fontFamily = TensaiMono,
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Column(modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                Row {
                    runs.forEachIndexed { i, run ->
                        MatrixHeaderCell(Modifier.width(CellWidth)) {
                            RunKey(i, if (run.config.repetitions > 1) "n/${run.config.repetitions}" else null)
                        }
                    }
                }
                rows.forEach { row ->
                    Row(modifier = Modifier.height(RowHeight).background(if (row.changed && runs.size > 1) band else Color.Transparent)) {
                        row.cells.forEachIndexed { i, cell ->
                            Box(
                                contentAlignment = Alignment.CenterStart,
                                modifier = Modifier
                                    .width(CellWidth)
                                    .height(RowHeight)
                                    .then(if (cell != null) Modifier.clickable { onOpenPrompt(runs[i].id, row.promptId, runKey(i)) } else Modifier)
                                    .padding(horizontal = 4.dp),
                            ) {
                                // A partial run that never got to this prompt: nothing to say, as for a tool it lacked.
                                if (cell != null) PassCellPill(cell) else VerdictPill(BenchVerdict.Unavailable.short, verdictStyle(BenchVerdict.Unavailable))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MatrixHeaderCell(modifier: Modifier, content: @Composable () -> Unit) {
    Box(contentAlignment = Alignment.CenterStart, modifier = modifier.height(32.dp).padding(horizontal = 4.dp)) { content() }
}

/**
 * A matrix cell: one trial is its verdict pill; several a pass rate, "3/3" in the success tint, "0/3" in the failure's
 * tint, a mixed one outlined in the failure's color with its icon.
 */
@Composable
private fun PassCellPill(cell: PassCell) {
    val failure = cell.failure
    when {
        cell.total == 1 -> {
            val verdict = cell.trials.single().benchVerdict
            VerdictPill(verdict.short, verdictStyle(verdict))
        }
        cell.unavailable -> VerdictPill(BenchVerdict.Unavailable.short, verdictStyle(BenchVerdict.Unavailable))
        failure == null -> VerdictPill("${cell.passes}/${cell.total}", verdictStyle(BenchVerdict.Correct))
        cell.passes == 0 -> VerdictPill("0/${cell.total}", verdictStyle(failure))
        else -> VerdictPill("${cell.passes}/${cell.total}", verdictStyle(failure), outlineOnly = true)
    }
}
