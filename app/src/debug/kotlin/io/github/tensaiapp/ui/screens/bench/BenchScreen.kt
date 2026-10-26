package io.github.tensaiapp.ui.screens.bench

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tensaiapp.R
import io.github.tensaiapp.bench.AgentBench
import io.github.tensaiapp.bench.AgentBenchProgress
import io.github.tensaiapp.bench.BenchRun
import io.github.tensaiapp.bench.BenchRunJson
import io.github.tensaiapp.bench.BenchRunStore
import io.github.tensaiapp.bench.BenchStatus
import io.github.tensaiapp.bench.BenchTrigger
import io.github.tensaiapp.bench.BenchVerdict
import io.github.tensaiapp.bench.DecodeCurveBench
import io.github.tensaiapp.bench.backendName
import io.github.tensaiapp.bench.RunTotals
import io.github.tensaiapp.runtime.RuntimeGraph
import io.github.tensaiapp.runtime.residentModel
import io.github.tensaiapp.ui.components.TensaiButton
import io.github.tensaiapp.ui.components.TensaiButtonSize
import io.github.tensaiapp.ui.components.TensaiButtonVariant
import io.github.tensaiapp.ui.components.TensaiProgressBar
import io.github.tensaiapp.ui.components.rememberReducedMotion
import io.github.tensaiapp.ui.screens.chat.DefaultEnableThinking
import io.github.tensaiapp.ui.screens.chat.SwitchVisual
import io.github.tensaiapp.ui.screens.settings.RowDivider
import io.github.tensaiapp.ui.theme.TensaiMono
import io.github.tensaiapp.ui.theme.TensaiShapes
import io.github.tensaiapp.ui.theme.tensaiColors
import io.github.tensaiapp.ui.theme.tensaiTints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The tabs of the bench screen. Decode comes with TEN-190: until then the row holds Agent alone. */
internal enum class BenchTab { Agent }

/**
 * Settings > Bench (TEN-187), debug builds only: the shared top bar and tab row, then the Agent tab. A new run card
 * (the [BenchTrigger] toggles and repetitions) or, while [AgentBench] runs, its progress; under it the saved runs of
 * [BenchRunStore], with selection for a comparison. The screen stays on while a run goes.
 *
 * @param onCompare opens the given runs side by side, one run for its own detail.
 * @param onOpenModels where "Open models" goes when no model is loaded.
 */
@Composable
internal fun BenchScreen(onBack: () -> Unit, onCompare: (List<String>) -> Unit, onOpenModels: () -> Unit) {
    val context = LocalContext.current
    val store = remember { BenchRunStore.of(context) }
    val runs by store.runs.collectAsState()
    val progress by AgentBench.progress.collectAsState()
    val status by AgentBench.status.collectAsState()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { store.refresh() } }
    KeepScreenOn(progress != null)

    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    // Only runs still there count: one deleted is no longer selected.
    val liveSelected = remember(selected, runs) { selected.filter { id -> runs.orEmpty().any { it.id == id } } }

    Column(modifier = Modifier.fillMaxSize()) {
        BenchTopBar(title = stringResource(R.string.bench_screen_title), onBack = onBack)
        BenchTabRow(BenchTab.Agent)
        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                item(key = "new") {
                    Eyebrow(stringResource(R.string.bench_eyebrow_agent))
                    val current = progress
                    if (current != null) RunningCard(current) else NewRunCard(status, onOpenModels)
                }
                item(key = "saved") {
                    Eyebrow(stringResource(R.string.bench_eyebrow_saved), trailing = runs?.size?.toString())
                }
                val list = runs
                if (list != null && list.isEmpty()) {
                    item(key = "empty") { EmptyRuns() }
                }
                items(list.orEmpty(), key = { it.id }) { run ->
                    SavedRunCard(
                        run = run,
                        selected = run.id in liveSelected,
                        onToggle = { selected = if (run.id in liveSelected) liveSelected - run.id else liveSelected + run.id },
                        onOpen = { onCompare(listOf(run.id)) },
                        onDelete = { store.delete(run.id) },
                    )
                }
                item(key = "foot") { Spacer(Modifier.heightIn(min = if (liveSelected.isEmpty()) 24.dp else 96.dp)) }
            }
            if (liveSelected.isNotEmpty()) {
                SelectionBar(
                    count = liveSelected.size,
                    onClear = { selected = emptyList() },
                    // Oldest first: (a) is the earlier run, what the later ones are compared against.
                    onCompare = { onCompare(runs.orEmpty().filter { it.id in liveSelected }.sortedBy { it.startedAtMs }.map { it.id }) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

/** The tab row under the shared top bar. One tab today: drawn as the row TEN-190 adds Decode to. */
@Composable
private fun BenchTabRow(selected: BenchTab) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        BenchTab.entries.forEach { tab ->
            val on = tab == selected
            Surface(
                shape = CircleShape,
                color = if (on) MaterialTheme.tensaiTints.primary16 else MaterialTheme.colorScheme.background,
                contentColor = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.heightIn(min = 34.dp).padding(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    Icon(Icons.Rounded.SmartToy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(text = stringResource(R.string.bench_tab_agent), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** A card: surface fill, 1dp hairline, 20dp radius. [onClick] makes the whole card a button, its ripple clipped to the shape. */
@Composable
internal fun BenchCard(modifier: Modifier = Modifier, border: BorderStroke? = null, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val shape = TensaiShapes.large
    val color = MaterialTheme.colorScheme.surface
    val stroke = border ?: BorderStroke(1.dp, MaterialTheme.tensaiTints.outline)
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = color, border = stroke, modifier = modifier.fillMaxWidth()) { Column { content() } }
    } else {
        Surface(shape = shape, color = color, border = stroke, modifier = modifier.fillMaxWidth()) { Column { content() } }
    }
}

/**
 * The new run card's settings, kept for the process: the card leaves the screen while a run goes, and the next run
 * starts from the last one's settings rather than the defaults.
 */
private object LastRunSettings {
    val thinking = mutableStateOf(DefaultEnableThinking)
    val basePrompt = mutableStateOf(true)
    val examples = mutableStateOf(false)
    val toolGrammar = mutableStateOf(true)
    val greedy = mutableStateOf(false)
    val repetitions = mutableIntStateOf(1)
}

/** The run settings, the repetitions and Run. Off with a reason when no model is loaded; the last failure under it. */
@Composable
private fun NewRunCard(status: BenchStatus, onOpenModels: () -> Unit) {
    val context = LocalContext.current
    val reducedMotion = rememberReducedMotion()
    val queueState by RuntimeGraph.inferenceQueue.state.collectAsState()
    val models by RuntimeGraph.modelRepository.models.collectAsState(initial = emptyList())
    val resident = queueState.residentModel
    val entity = resident?.let { info -> models.firstOrNull { it.id == info.modelId } }
    // One bench at a time (BenchGate): the decode curve of This device holds the engine too.
    val curve by DecodeCurveBench.status.collectAsState()
    val promptCount by produceState<Int?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { AgentBench.prompts(context).size }.getOrNull() }
    }

    var thinking by LastRunSettings.thinking
    var basePrompt by LastRunSettings.basePrompt
    var examples by LastRunSettings.examples
    var toolGrammar by LastRunSettings.toolGrammar
    var greedy by LastRunSettings.greedy
    var repetitions by LastRunSettings.repetitions

    BenchCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Icon(Icons.Rounded.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.bench_new_run), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                if (resident == null) {
                    Text(stringResource(R.string.bench_no_model), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error)
                } else {
                    Text(
                        text = listOfNotNull(listOfNotNull(entity?.shownName ?: resident.name, entity?.quantLabel?.takeIf { it.isNotBlank() }).joinToString(" "), backendName(resident.backend)).joinToString(" · "),
                        fontFamily = TensaiMono,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            promptCount?.let {
                Text(pluralStringResource(R.plurals.bench_prompts_count, it, it), fontFamily = TensaiMono, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        RowDivider()
        ToggleRow(stringResource(R.string.bench_thinking), stringResource(R.string.bench_thinking_sub), thinking, reducedMotion) { thinking = it }
        RowDivider()
        ToggleRow(stringResource(R.string.bench_base_prompt), stringResource(R.string.bench_base_prompt_sub), basePrompt, reducedMotion) { basePrompt = it }
        RowDivider()
        // The examples are part of the base prompt: no examples without it.
        ToggleRow(stringResource(R.string.bench_examples), stringResource(R.string.bench_examples_sub), examples && basePrompt, reducedMotion, enabled = basePrompt) { examples = it }
        RowDivider()
        ToggleRow(stringResource(R.string.bench_tool_grammar), stringResource(R.string.bench_tool_grammar_sub), toolGrammar, reducedMotion) { toolGrammar = it }
        RowDivider()
        ToggleRow(stringResource(R.string.bench_greedy), stringResource(R.string.bench_greedy_sub), greedy, reducedMotion) { greedy = it }
        RowDivider()
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.bench_repetitions), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(R.string.bench_repetitions_sub), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Stepper(repetitions, 1..BenchTrigger.MaxRepetitions) { repetitions = it }
        }
        RowDivider()
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // No count until the list is read: "0 calls" would be a number nothing measured.
            val calls = promptCount?.let { it * repetitions }
            TensaiButton(
                text = calls?.let { pluralStringResource(R.plurals.bench_run_calls, it, it) } ?: stringResource(R.string.bench_run_plain),
                onClick = { AgentBench.start(BenchTrigger(thinking, basePrompt, examples && basePrompt, toolGrammar, repetitions, greedy), context) },
                icon = Icons.Rounded.PlayArrow,
                enabled = resident != null && promptCount != null && status !is BenchStatus.Running && curve !is BenchStatus.Running,
                modifier = Modifier.fillMaxWidth(),
            )
            if (resident == null) {
                Text(stringResource(R.string.bench_run_off), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TensaiButton(
                    text = stringResource(R.string.bench_open_models),
                    onClick = onOpenModels,
                    variant = TensaiButtonVariant.Text,
                    size = TensaiButtonSize.Sm,
                )
            } else if (status is BenchStatus.Failed) {
                Text(stringResource(R.string.bench_failed, status.message), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, supporting: String, checked: Boolean, reducedMotion: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.38f)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 60.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Text(supporting, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SwitchVisual(checked, reducedMotion)
    }
}

/** A pill stepper: minus, the value in mono, plus, within [range]. */
@Composable
private fun Stepper(value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onChange(value - 1) }, enabled = value > range.first) {
                Icon(Icons.Rounded.Remove, contentDescription = stringResource(R.string.bench_repetitions_less), modifier = Modifier.size(18.dp))
            }
            Text(
                text = value.toString(),
                fontFamily = TensaiMono,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(20.dp),
            )
            IconButton(onClick = { onChange(value + 1) }, enabled = value < range.last) {
                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.bench_repetitions_more), modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** The run going on: count, bar, current prompt, elapsed time, verdicts so far, Stop. Ringed in primary, the one glow. */
@Composable
private fun RunningCard(progress: AgentBenchProgress) {
    val tints = MaterialTheme.tensaiTints
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(progress.startedAtMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(100)
        }
    }
    val elapsed = now - progress.startedAtMs
    val reps = progress.config.repetitions
    Box(modifier = Modifier.border(4.dp, tints.primary08, TensaiShapes.large).padding(4.dp)) {
        BenchCard(border = BorderStroke(1.dp, tints.primary24)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
                    Text(
                        text = stringResource(R.string.bench_running_eyebrow).uppercase(),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.72.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = modelLine(progress.config),
                        fontFamily = TensaiMono,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(progress.done.toString(), fontFamily = TensaiMono, fontSize = 36.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Text(
                        text = pluralStringResource(if (reps > 1) R.plurals.bench_of_trials else R.plurals.bench_of_prompts, progress.planned, progress.planned),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 7.dp),
                    )
                }
                TensaiProgressBar(progress = if (progress.planned == 0) null else progress.done.toFloat() / progress.planned)
                val current = progress.currentId?.let { id -> if (reps > 1) "$id · rep ${progress.currentRep}/$reps" else id } ?: "..."
                RunningFact(stringResource(R.string.bench_current), current)
                RunningFact(stringResource(R.string.bench_elapsed), elapsedText(elapsed))
                RunningFact(
                    stringResource(R.string.bench_so_far),
                    listOf(BenchVerdict.Correct, BenchVerdict.FalseCall, BenchVerdict.Missing, BenchVerdict.WrongTool)
                        .joinToString(" · ") { "${progress.counts[it] ?: 0} ${it.legendKey}" },
                    maxLines = 2,
                )
                TensaiButton(
                    text = stringResource(R.string.bench_stop),
                    onClick = AgentBench::stop,
                    variant = TensaiButtonVariant.Outlined,
                    icon = Icons.Rounded.Stop,
                    contentColor = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = pluralStringResource(if (reps > 1) R.plurals.bench_stop_note_trials else R.plurals.bench_stop_note, progress.done, progress.done),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RunningFact(label: String, value: String, maxLines: Int = 1) {
    Row(verticalAlignment = Alignment.Top) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(76.dp))
        Text(value, fontFamily = TensaiMono, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

/** No run saved yet: a dashed card. */
@Composable
private fun EmptyRuns() {
    val dash = MaterialTheme.tensaiTints.outlineStrong
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawRoundRect(
                    color = dash,
                    cornerRadius = CornerRadius(20.dp.toPx()),
                    style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                )
            }
            .padding(horizontal = 24.dp, vertical = 28.dp),
    ) {
        Icon(Icons.Rounded.Science, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
        Text(stringResource(R.string.bench_empty_title), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Text(
            stringResource(R.string.bench_empty_body),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** A saved run: checkbox, when, partial badge, model, config chips, the stacked bar and its legend, the medians. Swipe to delete. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SavedRunCard(run: BenchRun, selected: Boolean, onToggle: () -> Unit, onOpen: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val totals = remember(run) { RunTotals.of(run.results) }
    val dismiss = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onDelete()
            value == SwipeToDismissBoxValue.EndToStart
        },
    )
    var menu by remember { mutableStateOf(false) }
    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromStartToEnd = false,
        modifier = Modifier.padding(bottom = 10.dp),
        backgroundContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.tensaiTints.error12, TensaiShapes.large)
                    .padding(horizontal = 20.dp),
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.bench_delete), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
            }
        },
    ) {
        BenchCard(
            border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.tensaiTints.outline),
            onClick = onOpen,
        ) {
            Column(modifier = Modifier.padding(start = 6.dp, end = 4.dp, top = 6.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onToggle) {
                        Icon(
                            imageVector = if (selected) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                            contentDescription = stringResource(R.string.bench_select),
                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(startedLabel(run.startedAtMs), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            if (run.partial) PartialBadge(partialText(run))
                        }
                        Text(
                            text = modelLine(run.config) + " · ${run.config.prompts}×${run.config.repetitions}",
                            fontFamily = TensaiMono,
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.bench_more))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.bench_copy_json)) },
                                onClick = {
                                    menu = false
                                    scope.launch { copyJson(context) { BenchRunJson.encode(run) } }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.bench_share_json)) },
                                onClick = {
                                    menu = false
                                    scope.launch { shareJson(context, run.id) { BenchRunJson.encode(run) } }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.bench_delete), color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    menu = false
                                    onDelete()
                                },
                            )
                        }
                    }
                }
                Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        configChips(run.config).forEach { (label, on) -> ConfigChip(label, on) }
                    }
                    StackedVerdictBar(totals, unrun = run.unrun)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        BarOrder.forEach { VerdictLegendItem(it, totals.count(it) + if (it == BenchVerdict.Unavailable) run.unrun else 0) }
                    }
                    RowDivider()
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        MedianText("ttft p50", totals.ttftP50Ms)
                        MedianText("total p50", totals.totalP50Ms)
                    }
                }
            }
        }
    }
}

@Composable
private fun MedianText(label: String, ms: Long?) {
    Text(
        text = "$label ${ms?.let { groupDigits(it) + " ms" } ?: "-"}",
        fontFamily = TensaiMono,
        fontSize = 11.5.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The warning tint pill of a partial run. */
@Composable
internal fun PartialBadge(text: String) {
    Surface(shape = CircleShape, color = MaterialTheme.tensaiTints.warning12) {
        Text(
            text = text,
            fontFamily = TensaiMono,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.tensaiColors.warning,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** At the foot while runs are selected: how many, Clear, Compare (2 to 4). */
@Composable
private fun SelectionBar(count: Int, onClear: () -> Unit, onCompare: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.tensaiTints.outline),
        shape = TensaiShapes.large,
        modifier = modifier.fillMaxWidth().padding(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(pluralStringResource(R.plurals.bench_selected, count, count), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TensaiButton(text = stringResource(R.string.bench_clear), onClick = onClear, variant = TensaiButtonVariant.Text, size = TensaiButtonSize.Sm)
            TensaiButton(
                text = stringResource(
                    when {
                        count < 2 -> R.string.bench_pick_more
                        count > 4 -> R.string.bench_max_four
                        else -> R.string.bench_compare
                    },
                ),
                onClick = onCompare,
                enabled = count in 2..4,
                size = TensaiButtonSize.Sm,
            )
        }
    }
}
