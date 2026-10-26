package io.github.tensaiapp.ui.screens.bench

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Functions
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tensaiapp.R
import io.github.tensaiapp.bench.AgentBench
import io.github.tensaiapp.bench.BenchRunJson
import io.github.tensaiapp.bench.BenchRunStore
import io.github.tensaiapp.bench.BenchTrial
import io.github.tensaiapp.bench.BenchVerdict
import io.github.tensaiapp.ui.components.ThinkingBlock
import io.github.tensaiapp.ui.screens.settings.RowDivider
import io.github.tensaiapp.ui.theme.TensaiMono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * One prompt in one saved run (TEN-187), from a matrix cell: the prompt and what it expected, then for the repetition
 * picked its verdict, timings and notes, the tool calls with their arguments, the reasoning and the reply. The top
 * bar's copy icon copies that repetition's result as JSON.
 *
 * @param runKey the run's letter in the comparison it was opened from.
 */
@Composable
internal fun BenchPromptScreen(runId: String, promptId: String, runKey: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { BenchRunStore.of(context) }
    val all by store.runs.collectAsState()
    LaunchedEffect(Unit) { if (store.runs.value == null) withContext(Dispatchers.IO) { store.refresh() } }
    KeepScreenOn(AgentBench.progress.collectAsState().value != null)
    val run = all?.firstOrNull { it.id == runId }
    val trials = remember(run, promptId) { run?.results?.filter { it.id == promptId }.orEmpty() }
    // -1 until picked: opens on the first repetition that went wrong, the one worth reading, read off the trials once
    // they are loaded (after process death the store reads them again first).
    var picked by rememberSaveable(runId, promptId) { mutableIntStateOf(-1) }
    val repIndex = if (picked >= 0) picked else trials.indexOfFirst { it.benchVerdict != BenchVerdict.Correct }.coerceAtLeast(0)
    val trial = trials.getOrNull(repIndex)
    val ids = remember(run) { run?.results?.map { it.id }?.distinct().orEmpty() }

    Column(modifier = Modifier.fillMaxSize()) {
        BenchTopBar(
            title = promptId,
            monoTitle = true,
            subtitle = run?.let { "run ($runKey) · ${startedTime(it.startedAtMs)} · ${ids.indexOf(promptId) + 1} of ${ids.size}" },
            onBack = onBack,
            actions = {
                if (trial != null) {
                    IconButton(onClick = { scope.launch { copyJson(context) { BenchRunJson.encode(trial) } } }) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.bench_copy_json))
                    }
                }
            },
        )
        if (trial == null) return@Column
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            if (trials.size > 1) {
                Box(Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState())) {
                    RepTabs(trials, repIndex) { picked = it }
                }
            }
            Eyebrow(stringResource(R.string.bench_eyebrow_prompt))
            BenchCard {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(trial.prompt, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                    RowDivider()
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.bench_expected), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = if (trial.expected.isEmpty()) "no tool" else "calls " + trial.expected.joinToString(" | "),
                            fontFamily = TensaiMono,
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            Eyebrow(stringResource(R.string.bench_eyebrow_result))
            ResultCard(trial)
            Eyebrow(stringResource(R.string.bench_eyebrow_tool_calls), trailing = trial.calls.size.toString())
            if (trial.calls.isEmpty()) {
                Text(stringResource(R.string.bench_no_calls), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp))
            }
            trial.calls.forEachIndexed { i, call ->
                BenchCard(modifier = Modifier.padding(bottom = 8.dp)) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Rounded.Functions, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            Text(call.name, fontFamily = TensaiMono, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text("#${i + 1}", fontFamily = TensaiMono, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = prettyArgs(call.args),
                                fontFamily = TensaiMono,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(10.dp),
                            )
                        }
                    }
                }
            }
            Eyebrow(stringResource(R.string.bench_eyebrow_reply))
            trial.reasoning?.let { reasoning ->
                ThinkingBlock(thinking = reasoning, isActive = false)
                val facts = listOfNotNull(
                    trial.reasoningTokens?.let { pluralStringResource(R.plurals.bench_reasoning_tokens, it, it) },
                    if (trial.reasoningCut) pluralStringResource(R.plurals.bench_cut, BenchTrial.MaxTextChars, BenchTrial.MaxTextChars) else null,
                )
                if (facts.isNotEmpty()) {
                    Text(facts.joinToString(" · "), fontFamily = TensaiMono, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
                }
                Box(Modifier.height(10.dp))
            }
            Text(
                text = trial.reply ?: stringResource(R.string.bench_no_reply),
                style = MaterialTheme.typography.bodyMedium,
                color = if (trial.reply == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            if (trial.replyCut) {
                Text(pluralStringResource(R.plurals.bench_cut, BenchTrial.MaxTextChars, BenchTrial.MaxTextChars), fontFamily = TensaiMono, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
            }
        }
    }
}

/** One pill per repetition: "Rep 1" and its verdict's icon and code. */
@Composable
private fun RepTabs(trials: List<BenchTrial>, selected: Int, onSelect: (Int) -> Unit) {
    BenchSegmented(
        options = trials.map { SegmentOption(stringResource(R.string.bench_rep, it.rep) + " · " + it.benchVerdict.short, icon = verdictStyle(it.benchVerdict).icon) },
        selected = selected,
        onSelect = onSelect,
    )
}

/** The verdict in full, the timings, and a line per note: wrong tool, truncated or named calls, refusals, the error. */
@Composable
private fun ResultCard(trial: BenchTrial) {
    BenchCard {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                VerdictPill(trial.benchVerdict.longLabel(), verdictStyle(trial.benchVerdict), mono = false)
                Box(Modifier.weight(1f))
                // Measured before the spacer takes the rest: a value never wraps away from its unit.
                Text("ttft ${trial.ttftMs?.let { groupDigits(it) + " ms" } ?: "-"}", fontFamily = TensaiMono, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
                Text("total ${groupDigits(trial.totalMs)} ms", fontFamily = TensaiMono, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
            }
            resultNotes(trial).forEach { note ->
                Text(note, fontFamily = TensaiMono, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

/** The machine notes of a result, mono: what was expected against what came, and the flags the verdict alone hides. */
internal fun resultNotes(trial: BenchTrial): List<String> = buildList {
    if (trial.benchVerdict == BenchVerdict.WrongTool || trial.benchVerdict == BenchVerdict.FalseCall) {
        val got = (trial.calls.map { it.name } + trial.truncatedCalls).distinct().joinToString(", ")
        add("expected ${trial.expected.ifEmpty { listOf("no tool") }.joinToString(" | ")} · got $got")
    }
    if (trial.truncatedCalls.isNotEmpty()) add("truncated call: " + trial.truncatedCalls.joinToString(", "))
    if (trial.namedInText.isNotEmpty()) add("named in text: " + trial.namedInText.joinToString(", "))
    if (trial.refused.isNotEmpty()) add("refused: " + trial.refused.joinToString(", "))
    trial.error?.let { add("error: $it") }
    trial.stop?.let { add("stop: $it · ${trial.tokens} tokens") }
}

private val PrettyJson = Json { prettyPrint = true }

/** [args] pretty-printed when it is JSON, as written otherwise. */
internal fun prettyArgs(args: String): String = runCatching {
    PrettyJson.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(args))
}.getOrDefault(args)
