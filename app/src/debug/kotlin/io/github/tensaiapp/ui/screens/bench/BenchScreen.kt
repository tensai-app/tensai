package io.github.tensaiapp.ui.screens.bench

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
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
import androidx.compose.material.icons.rounded.Speed
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
import androidx.compose.runtime.key
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
import androidx.compose.ui.graphics.Color
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
import io.github.tensaiapp.agent.ToolPolicy
import io.github.tensaiapp.bench.AgentBench
import io.github.tensaiapp.bench.AgentBenchPreparing
import io.github.tensaiapp.bench.AgentBenchProgress
import io.github.tensaiapp.bench.BenchGate
import io.github.tensaiapp.bench.BenchPromptList
import io.github.tensaiapp.bench.BenchRun
import io.github.tensaiapp.bench.BenchRunJson
import io.github.tensaiapp.bench.BenchRunStore
import io.github.tensaiapp.bench.BenchStatus
import io.github.tensaiapp.bench.BenchTrigger
import io.github.tensaiapp.bench.BenchVerdict
import io.github.tensaiapp.bench.DecodeBench
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
import io.github.tensaiapp.ui.screens.chat.ModelPickerSheet
import io.github.tensaiapp.ui.screens.chat.pickerBusy
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

/** The tabs of the bench screen: the agent bench (TEN-187) and the decode curve (TEN-190). */
internal enum class BenchTab { Agent, Decode }

/**
 * Settings > Bench (TEN-187), debug builds only: the shared top bar and tab row, then the Agent tab or the Decode tab
 * ([DecodeTab]). The Agent tab, laid out like Decode (TEN-194): the loaded model, the settings (the [BenchTrigger]
 * toggles and repetitions) and Run or, while [AgentBench] runs, its progress; under it the saved runs of
 * [BenchRunStore] in one card, with selection for a comparison. The screen stays on
 * while a run of either goes.
 *
 * Both tabs' model rows open the chat's model picker ([ModelPickerSheet]), so a model is loaded or switched without
 * leaving the bench; not while a bench or a reload runs.
 *
 * @param onCompare opens the given runs side by side, one run for its own detail.
 * @param onOpenDecodeRun opens a decode run's result, [onCompareDecode] two decode runs side by side.
 * @param onLoadModel loads a model the picker picked: the load screen, which comes back here.
 * @param onManage / [onDiscover] / [onImport] the picker's shortcuts to the Models screen.
 */
@Composable
internal fun BenchScreen(
    onBack: () -> Unit,
    onCompare: (List<String>) -> Unit,
    onOpenDecodeRun: (String) -> Unit,
    onCompareDecode: (List<String>) -> Unit,
    onLoadModel: (String) -> Unit,
    onManage: () -> Unit,
    onDiscover: () -> Unit,
    onImport: () -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(BenchTab.Agent) }
    val agentRunning = AgentBench.progress.collectAsState().value != null || AgentBench.preparing.collectAsState().value != null
    val decodeRunning = DecodeBench.progress.collectAsState().value != null
    // The gate is taken before a run's progress shows: it, not the progress, says the engine is spoken for.
    val gateBusy by BenchGate.busy.collectAsState()
    KeepScreenOn(agentRunning || decodeRunning)
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    val onPickModel = if (gateBusy) null else { { pickerOpen = true } }

    Column(modifier = Modifier.fillMaxSize()) {
        BenchTopBar(title = stringResource(R.string.bench_screen_title), onBack = onBack)
        BenchTabRow(tab) { tab = it }
        Box(modifier = Modifier.weight(1f)) {
            when (tab) {
                BenchTab.Agent -> AgentTab(onCompare, onPickModel)
                BenchTab.Decode -> DecodeTab(onOpenDecodeRun, onCompareDecode, onPickModel)
            }
        }
    }

    if (pickerOpen) {
        val queueState by RuntimeGraph.inferenceQueue.state.collectAsState()
        fun closeThen(action: () -> Unit) {
            pickerOpen = false
            action()
        }
        ModelPickerSheet(
            loadedModelId = queueState.residentModel?.modelId,
            busy = pickerBusy(queueState),
            onPick = { id -> closeThen { onLoadModel(id) } },
            onManage = { closeThen(onManage) },
            onDiscover = { closeThen(onDiscover) },
            onImport = { closeThen(onImport) },
            onDismiss = { pickerOpen = false },
        )
    }
}

/**
 * The model row of both tabs' first card: the memory icon, [content] (the model, or "No model loaded" and "Choose a
 * model"), a chevron. Opens the picker on a tap; [onPick] `null` (a bench running) leaves it a plain row.
 */
@Composable
internal fun ModelRow(onPick: (() -> Unit)?, content: @Composable RowScope.() -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onPick != null, role = Role.Button, onClick = { onPick?.invoke() })
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Icon(Icons.Rounded.Memory, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        content()
        if (onPick != null) {
            Icon(
                Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = stringResource(R.string.bench_choose_model),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Under "No model loaded": what a tap on the row does, in primary. */
@Composable
internal fun ChooseModelHint() {
    Text(stringResource(R.string.bench_choose_model), fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
}

/** The Agent tab: the loaded model, settings and Run, or the run going on; then the saved runs. */
@Composable
private fun AgentTab(onCompare: (List<String>) -> Unit, onPickModel: (() -> Unit)?) {
    val context = LocalContext.current
    val store = remember { BenchRunStore.of(context) }
    val runs by store.runs.collectAsState()
    val progress by AgentBench.progress.collectAsState()
    val preparing by AgentBench.preparing.collectAsState()
    val status by AgentBench.status.collectAsState()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { store.refresh() } }

    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    // Only runs still there count: one deleted is no longer selected.
    val liveSelected = remember(selected, runs) { selected.filter { id -> runs.orEmpty().any { it.id == id } } }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item(key = "new") {
                val current = progress
                val getting = preparing
                if (current != null || getting != null) {
                    Eyebrow(stringResource(R.string.bench_eyebrow_agent))
                    RunningCard(current, getting)
                } else {
                    NewRun(status, onPickModel)
                }
            }
            item(key = "saved") {
                Eyebrow(stringResource(R.string.bench_eyebrow_saved), trailing = runs?.size?.toString())
            }
            val list = runs
            if (list != null && list.isEmpty()) {
                item(key = "empty") { EmptyRuns() }
            }
            if (!list.isNullOrEmpty()) {
                item(key = "runs") {
                    BenchCard {
                        list.forEachIndexed { i, run ->
                            if (i > 0) RowDivider()
                            key(run.id) {
                                SavedRunRow(
                                    run = run,
                                    selected = run.id in liveSelected,
                                    onToggle = { selected = if (run.id in liveSelected) liveSelected - run.id else liveSelected + run.id },
                                    onOpen = { onCompare(listOf(run.id)) },
                                    onDelete = { store.delete(run.id) },
                                )
                            }
                        }
                    }
                }
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

/** The tab row under the shared top bar. */
@Composable
private fun BenchTabRow(selected: BenchTab, onSelect: (BenchTab) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        BenchTab.entries.forEach { tab ->
            val on = tab == selected
            Surface(
                onClick = { onSelect(tab) },
                shape = CircleShape,
                color = if (on) MaterialTheme.tensaiTints.primary16 else MaterialTheme.colorScheme.background,
                contentColor = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.heightIn(min = 34.dp).padding(horizontal = 14.dp, vertical = 7.dp),
                ) {
                    Icon(if (tab == BenchTab.Agent) Icons.Rounded.SmartToy else Icons.Rounded.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(
                        text = stringResource(if (tab == BenchTab.Agent) R.string.bench_tab_agent else R.string.bench_tab_decode),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
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
 * The run settings, kept for the process: the settings card leaves the screen while a run goes, and the next run
 * starts from the last one's settings rather than the defaults.
 */
private object LastRunSettings {
    val thinking = mutableStateOf(DefaultEnableThinking)
    val basePrompt = mutableStateOf(true)
    val examples = mutableStateOf(false)
    val toolGrammar = mutableStateOf(true)
    val greedy = mutableStateOf(false)
    val reasoningBudget = mutableStateOf<Int?>(null)
    val adaptiveReasoning = mutableStateOf(false)
    val repetitions = mutableIntStateOf(1)
    val toolPolicy = mutableStateOf(ToolPolicy.Current)
    val promptList = mutableStateOf(BenchPromptList.Agent)
}

/**
 * LOADED MODEL (the picker's row), SETTINGS (the toggles and the repetitions), then Run under the card with what it
 * runs in mono. Run is off with no model or while the bench gate is held; the last failure under it.
 */
@Composable
private fun NewRun(status: BenchStatus, onPickModel: (() -> Unit)?) {
    val context = LocalContext.current
    val reducedMotion = rememberReducedMotion()
    val queueState by RuntimeGraph.inferenceQueue.state.collectAsState()
    val models by RuntimeGraph.modelRepository.models.collectAsState(initial = emptyList())
    val resident = queueState.residentModel
    val entity = resident?.let { info -> models.firstOrNull { it.id == info.modelId } }
    // One bench at a time: whatever holds the gate (the decode curve, its reload, this bench) holds the engine.
    val gateBusy by BenchGate.busy.collectAsState()
    var promptList by LastRunSettings.promptList
    val promptCount by produceState<Int?>(null, promptList) {
        value = null
        value = withContext(Dispatchers.IO) { runCatching { AgentBench.prompts(context, promptList).size }.getOrNull() }
    }

    var thinking by LastRunSettings.thinking
    var basePrompt by LastRunSettings.basePrompt
    var examples by LastRunSettings.examples
    var toolGrammar by LastRunSettings.toolGrammar
    var greedy by LastRunSettings.greedy
    var reasoningBudget by LastRunSettings.reasoningBudget
    var adaptiveReasoning by LastRunSettings.adaptiveReasoning
    var repetitions by LastRunSettings.repetitions
    var toolPolicy by LastRunSettings.toolPolicy
    val shownPolicy = if (toolPolicy.strictPrompt && !basePrompt) ToolPolicy.Always else toolPolicy

    Eyebrow(stringResource(R.string.decode_eyebrow_loaded))
    BenchCard {
        ModelRow(onPickModel) {
            Column(modifier = Modifier.weight(1f)) {
                if (resident == null) {
                    Text(stringResource(R.string.bench_no_model), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                    if (onPickModel != null) ChooseModelHint()
                } else {
                    Text(
                        text = listOfNotNull(entity?.shownName ?: resident.name, entity?.quantLabel?.takeIf { it.isNotBlank() }, backendName(resident.backend)).joinToString(" · "),
                        fontFamily = TensaiMono,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }

    Eyebrow(stringResource(R.string.decode_eyebrow_settings))
    BenchCard {
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
        // A budget of reasoning: none to cap without thinking.
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().alpha(if (thinking) 1f else 0.38f).padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(stringResource(R.string.bench_reasoning_budget), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(R.string.bench_reasoning_budget_sub), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Adaptive first, then the chat's split (Default), then the fixed budgets.
            val budgets = listOf(null) + BenchTrigger.ReasoningBudgets
            BenchSegmented(
                options = listOf(SegmentOption(stringResource(R.string.bench_reasoning_budget_adaptive))) +
                    budgets.map { SegmentOption(it?.toString() ?: stringResource(R.string.bench_reasoning_budget_default)) },
                selected = if (adaptiveReasoning) 0 else budgets.indexOf(reasoningBudget).coerceAtLeast(0) + 1,
                onSelect = {
                    adaptiveReasoning = it == 0
                    reasoningBudget = if (it == 0) null else budgets[it - 1]
                },
                enabled = thinking,
                mono = true,
                fitLabels = true,
            )
        }
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
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(stringResource(R.string.bench_tool_policy), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(R.string.bench_tool_policy_sub), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // The strict line is part of the base prompt: Always without it.
            BenchSegmented(
                options = ToolPolicy.entries.map { policy ->
                    val label = when (policy) {
                        ToolPolicy.Current -> R.string.bench_tool_policy_current
                        ToolPolicy.Always -> R.string.bench_tool_policy_always
                        ToolPolicy.AlwaysStrict -> R.string.bench_tool_policy_strict
                    }
                    SegmentOption(stringResource(label), enabled = basePrompt || !policy.strictPrompt)
                },
                selected = ToolPolicy.entries.indexOf(shownPolicy),
                onSelect = { toolPolicy = ToolPolicy.entries[it] },
                mono = true,
                fitLabels = true,
            )
        }
        RowDivider()
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(stringResource(R.string.bench_prompt_list), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(R.string.bench_prompt_list_sub), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BenchSegmented(
                options = BenchPromptList.entries.map { list ->
                    val label = when (list) {
                        BenchPromptList.Agent -> R.string.bench_prompt_list_agent
                        BenchPromptList.Web -> R.string.bench_prompt_list_web
                    }
                    SegmentOption(stringResource(label))
                },
                selected = BenchPromptList.entries.indexOf(promptList),
                onSelect = { promptList = BenchPromptList.entries[it] },
                mono = true,
                fitLabels = true,
            )
        }
    }

    Spacer(Modifier.height(12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // No count until the list is read: "0 calls" would be a number nothing measured.
        val prompts = promptCount
        TensaiButton(
            text = prompts?.let { pluralStringResource(R.plurals.bench_run_calls, it * repetitions, it * repetitions) } ?: stringResource(R.string.bench_run_plain),
            onClick = {
                val trigger = BenchTrigger(
                    thinking,
                    basePrompt,
                    examples && basePrompt,
                    toolGrammar,
                    repetitions,
                    greedy,
                    reasoningBudget.takeIf { thinking && it in BenchTrigger.ReasoningBudgets },
                    thinking && adaptiveReasoning,
                    shownPolicy,
                    promptList,
                )
                AgentBench.start(trigger, context)
            },
            icon = Icons.Rounded.PlayArrow,
            enabled = resident != null && prompts != null && !gateBusy,
            modifier = Modifier.fillMaxWidth(),
        )
        if (prompts != null) {
            Text(
                text = pluralStringResource(R.plurals.bench_run_breakdown, prompts, prompts, repetitions),
                fontFamily = TensaiMono,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (resident != null && status is BenchStatus.Failed) {
            Text(stringResource(R.string.bench_failed, status.message), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error)
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

/**
 * The run going on: count, bar, current prompt, elapsed time, verdicts so far, Stop. Ringed in primary, the one glow.
 * Before the first prompt ([progress] `null`, [preparing] set: the cache handed over, the tool prefix warmed) it says
 * so over an open bar, the time already ticking from the tap; Stop then saves nothing.
 */
@Composable
private fun RunningCard(progress: AgentBenchProgress?, preparing: AgentBenchPreparing?) {
    val tints = MaterialTheme.tensaiTints
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(100)
        }
    }
    val elapsed = now - (progress?.shownFromMs ?: preparing?.startedAtMs ?: now)
    val reps = progress?.config?.repetitions ?: 1
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
                        text = progress?.config?.let(::modelLine) ?: preparing?.model.orEmpty(),
                        fontFamily = TensaiMono,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (progress == null) {
                    Text(stringResource(R.string.bench_preparing), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                    TensaiProgressBar(progress = null)
                    RunningFact(stringResource(R.string.bench_elapsed), elapsedText(elapsed))
                    StopButton()
                    Text(stringResource(R.string.bench_stop_note_preparing), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
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
                    StopButton()
                    Text(
                        text = pluralStringResource(if (reps > 1) R.plurals.bench_stop_note_trials else R.plurals.bench_stop_note, progress.done, progress.done),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StopButton() {
    TensaiButton(
        text = stringResource(R.string.bench_stop),
        onClick = AgentBench::stop,
        variant = TensaiButtonVariant.Outlined,
        icon = Icons.Rounded.Stop,
        contentColor = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth(),
    )
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

/**
 * A saved run, one row of the SAVED RUNS card: checkbox, when, partial badge, model, config chips, the stacked bar and
 * its legend, the medians. A primary-08 band when selected, on an opaque row so the swipe's delete underlay only shows
 * while swiping. Swipe to delete.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SavedRunRow(run: BenchRun, selected: Boolean, onToggle: () -> Unit, onOpen: () -> Unit, onDelete: () -> Unit) {
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
        backgroundContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.tensaiTints.error12)
                    .padding(horizontal = 20.dp),
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.bench_delete), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
            }
        },
    ) {
        Surface(onClick = onOpen, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .background(if (selected) MaterialTheme.tensaiTints.primary08 else Color.Transparent)
                    .padding(start = 6.dp, end = 4.dp, top = 6.dp, bottom = 12.dp),
            ) {
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
