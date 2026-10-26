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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.HourglassEmpty
import androidx.compose.material.icons.rounded.Input
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tensaiapp.R
import io.github.tensaiapp.bench.BenchGate
import io.github.tensaiapp.bench.DecodeBench
import io.github.tensaiapp.bench.DecodeCurve
import io.github.tensaiapp.bench.DecodeEstimate
import io.github.tensaiapp.bench.DecodeKnobs
import io.github.tensaiapp.bench.DecodePhase
import io.github.tensaiapp.bench.DecodeProgress
import io.github.tensaiapp.bench.DecodeRates
import io.github.tensaiapp.bench.DecodeRun
import io.github.tensaiapp.bench.DecodeRunConfig
import io.github.tensaiapp.bench.DecodeRunPoint
import io.github.tensaiapp.bench.DecodeRunStore
import io.github.tensaiapp.bench.DecodeRunText
import io.github.tensaiapp.bench.DecodeTakeaway
import io.github.tensaiapp.bench.ReloadFailure
import io.github.tensaiapp.bench.ReloadState
import io.github.tensaiapp.bench.backendName
import io.github.tensaiapp.bench.ctxLabel
import io.github.tensaiapp.bench.knobChanges
import io.github.tensaiapp.bench.knobChips
import io.github.tensaiapp.bench.tpsText
import io.github.tensaiapp.engine.CpuLoadKnobs
import io.github.tensaiapp.engine.CpuLoadOverride
import io.github.tensaiapp.engine.FlashAttnMode
import io.github.tensaiapp.engine.KvKnob
import io.github.tensaiapp.runtime.QueueState
import io.github.tensaiapp.runtime.RuntimeGraph
import io.github.tensaiapp.runtime.residentModel
import io.github.tensaiapp.ui.components.TensaiButton
import io.github.tensaiapp.ui.components.TensaiButtonSize
import io.github.tensaiapp.ui.components.TensaiButtonVariant
import io.github.tensaiapp.ui.components.TensaiProgressBar
import io.github.tensaiapp.ui.screens.settings.RowDivider
import io.github.tensaiapp.ui.theme.TensaiMono
import io.github.tensaiapp.ui.theme.TensaiShapes
import io.github.tensaiapp.ui.theme.tensaiColors
import io.github.tensaiapp.ui.theme.tensaiTints
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The points and tokens per point of the next run, kept for the process like the agent tab's settings. The knobs
 * themselves are [CpuLoadOverride]'s.
 */
private object LastCurveSettings {
    val points = mutableStateOf(DecodeCurve.Lengths)
    val tokens = mutableIntStateOf(DecodeCurve.EvalTokens)
}

/**
 * Settings > Bench > Decode (TEN-190), debug builds only: the loaded model and what it was loaded with, the knobs of
 * the next CPU load ([CpuLoadOverride]) and of the curve, Reload when they differ, Run; while [DecodeBench] runs, its
 * progress instead. Under it the saved [DecodeRun]s, two of which can be compared. A run that ends opens its result.
 *
 * @param onOpenRun opens one run's result, [onCompare] two runs side by side, older first.
 * @param onPickModel opens the model picker from the loaded model card, `null` while a bench runs.
 */
@Composable
internal fun DecodeTab(onOpenRun: (String) -> Unit, onCompare: (List<String>) -> Unit, onPickModel: (() -> Unit)?) {
    val context = LocalContext.current
    val store = remember { DecodeRunStore.of(context) }
    val runs by store.runs.collectAsState()
    val progress by DecodeBench.progress.collectAsState()
    val lastSaved by DecodeBench.lastSaved.collectAsState()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { store.refresh() } }
    // A run that ends while the tab is up opens its result; one that ended before is not reopened.
    var seenSaved by rememberSaveable { mutableStateOf(DecodeBench.lastSaved.value) }
    LaunchedEffect(lastSaved) {
        val id = lastSaved
        if (id != null && id != seenSaved) {
            seenSaved = id
            onOpenRun(id)
        }
    }

    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    val liveSelected = remember(selected, runs) { selected.filter { id -> runs.orEmpty().any { it.id == id } } }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            item(key = "top") {
                val current = progress
                if (current != null) {
                    RunningSection(current, reference = runs.orEmpty())
                } else {
                    Eyebrow(stringResource(R.string.decode_eyebrow_loaded))
                    DecodeHome(runs.orEmpty(), onPickModel)
                }
            }
            item(key = "saved") {
                Eyebrow(stringResource(R.string.bench_eyebrow_saved), trailing = runs?.size?.toString())
            }
            val list = runs.orEmpty()
            if (runs != null && list.isEmpty()) {
                item(key = "empty") { EmptyDecodeRuns() }
            }
            if (list.isNotEmpty()) {
                item(key = "runs") {
                    BenchCard {
                        list.forEachIndexed { i, run ->
                            if (i > 0) RowDivider()
                            key(run.id) {
                                SavedDecodeRow(
                                    run = run,
                                    selected = run.id in liveSelected,
                                    dimmed = liveSelected.size >= 2 && run.id !in liveSelected,
                                    onToggle = {
                                        selected = when {
                                            run.id in liveSelected -> liveSelected - run.id
                                            liveSelected.size >= 2 -> liveSelected
                                            else -> liveSelected + run.id
                                        }
                                    },
                                    onOpen = { onOpenRun(run.id) },
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
            DecodeSelectionBar(
                count = liveSelected.size,
                onClear = { selected = emptyList() },
                // Oldest first: A is the earlier run, what B is compared against.
                onCompare = { onCompare(runs.orEmpty().filter { it.id in liveSelected }.sortedBy { it.startedAtMs }.map { it.id }) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

/** The latest run on the same model and backend, for the time estimates. */
private fun referenceRates(runs: List<DecodeRun>, config: DecodeRunConfig): DecodeRates =
    runs.firstOrNull { it.config.model == config.model && it.config.quant == config.quant && it.config.backend == config.backend }
        ?.let { DecodeRates.of(it.points) }
        ?: DecodeRates.defaultFor(config.backend)

/** No run going: the loaded model card, the settings, the reload notice and Run. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DecodeHome(runs: List<DecodeRun>, onPickModel: (() -> Unit)?) {
    val context = LocalContext.current
    val queueState by RuntimeGraph.inferenceQueue.state.collectAsState()
    val models by RuntimeGraph.modelRepository.models.collectAsState(initial = emptyList())
    val knobs by CpuLoadOverride.knobs.collectAsState()
    val reload by DecodeBench.reload.collectAsState()
    val failure by DecodeBench.failure.collectAsState()
    // Held by any run or reload, from before its progress shows: nothing that competes for the engine starts meanwhile.
    val gateBusy by BenchGate.busy.collectAsState()
    val resident = queueState.residentModel
    val entity = resident?.let { info -> models.firstOrNull { it.id == info.modelId } }
    val backend = resident?.let { backendName(it.backend) }
    val cpu = backend == null || backend == "CPU"
    val reloading = reload is ReloadState.Loading
    var points by LastCurveSettings.points
    var tokens by LastCurveSettings.tokens

    // What the load used is no state of its own: read again when the model or a reload moves. A reload of the same
    // model with other knobs leaves an equal LoadedModelInfo, hence the reload key.
    val loaded = remember(resident, reload) { resident?.let(DecodeBench::loadedKnobs) }
    val changes = if (resident == null || !cpu) emptyList() else knobChanges(DecodeKnobs.of(DecodeBench.appliedKnobs()), DecodeKnobs.of(knobs))
    val nCtx = resident?.nCtxEffective
    val eligible = nCtx?.let { DecodeCurve.lengthsFor(it, points, tokens) } ?: points

    BenchCard {
        ModelRow(onPickModel) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (resident == null) {
                    Text(stringResource(R.string.bench_no_model), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                    if (onPickModel != null) ChooseModelHint()
                } else {
                    Text(
                        text = listOfNotNull(entity?.shownName ?: resident.name, entity?.quantLabel?.takeIf { it.isNotBlank() }, backend).joinToString(" · "),
                        fontFamily = TensaiMono,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        loaded?.let { k -> knobChips(k).forEach { (key, value) -> MonoChip("$key $value") } }
                        MonoChip("n_ctx ${resident.nCtxEffective}")
                    }
                }
            }
        }
    }

    Eyebrow(stringResource(R.string.decode_eyebrow_settings))
    BenchCard {
        if (!cpu) {
            Text(
                text = stringResource(R.string.decode_gpu_note, backend.orEmpty()),
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
            )
        }
        val knobsOn = cpu && !reloading
        KnobRow(stringResource(R.string.bench_knob_threads), "n_threads", enabled = knobsOn) {
            PillSegments(
                options = CpuLoadKnobs.DecodeThreadChoices.map { SegmentOption(it?.toString() ?: stringResource(R.string.bench_knob_auto)) },
                selected = CpuLoadKnobs.DecodeThreadChoices.indexOf(knobs.decodeThreads),
                onSelect = { CpuLoadOverride.set(knobs.copy(decodeThreads = CpuLoadKnobs.DecodeThreadChoices[it])) },
                enabled = knobsOn,
            )
        }
        RowDivider()
        // A quantized V needs flash attention: with q8_0 the row is locked on.
        val faLocked = knobs.kv == KvKnob.Q8_0
        val modes = listOf(FlashAttnMode.Auto, FlashAttnMode.On, FlashAttnMode.Off)
        KnobRow(
            stringResource(R.string.bench_knob_flash_attn),
            "fa",
            enabled = knobsOn,
            note = if (faLocked) stringResource(R.string.decode_fa_locked) else null,
        ) {
            PillSegments(
                options = modes.map { mode ->
                    SegmentOption(
                        label = stringResource(
                            when (mode) {
                                FlashAttnMode.Auto -> R.string.bench_knob_auto
                                FlashAttnMode.On -> R.string.bench_knob_on
                                FlashAttnMode.Off -> R.string.bench_knob_off
                            },
                        ),
                        enabled = !faLocked || mode == FlashAttnMode.On,
                        icon = if (faLocked && mode == FlashAttnMode.On) Icons.Rounded.Lock else null,
                    )
                },
                selected = modes.indexOf(knobs.effectiveFlashAttn),
                onSelect = { CpuLoadOverride.set(knobs.copy(flashAttn = modes[it])) },
                enabled = knobsOn,
            )
        }
        RowDivider()
        val kvChoices = listOf(null) + KvKnob.entries
        KnobRow(stringResource(R.string.bench_knob_kv), "type_k · type_v", enabled = knobsOn) {
            PillSegments(
                options = kvChoices.map { SegmentOption(it?.arg ?: stringResource(R.string.bench_knob_auto)) },
                selected = kvChoices.indexOf(knobs.kv),
                onSelect = { CpuLoadOverride.set(knobs.withKv(kvChoices[it])) },
                enabled = knobsOn,
            )
        }
        RowDivider()
        val blocked = DecodeCurve.Lengths.filter { nCtx != null && it !in DecodeCurve.lengthsFor(nCtx, DecodeCurve.Lengths, tokens) }
        KnobRow(
            stringResource(R.string.decode_knob_points),
            stringResource(R.string.decode_knob_points_hint),
            monoHint = false,
            note = blocked.firstOrNull()?.let { stringResource(R.string.decode_point_needs, ctxLabel(it), DecodeCurve.contextNeeded(it, tokens), nCtx ?: 0) },
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DecodeCurve.Lengths.forEach { ctx ->
                    PointChip(
                        label = ctxLabel(ctx),
                        on = ctx in points,
                        blocked = ctx in blocked,
                        onClick = { points = if (ctx in points) points - ctx else (points + ctx).sorted() },
                    )
                }
            }
        }
        RowDivider()
        KnobRow(stringResource(R.string.decode_knob_tokens), null) {
            PillSegments(
                options = DecodeCurve.EvalTokenChoices.map { SegmentOption(it.toString()) },
                selected = DecodeCurve.EvalTokenChoices.indexOf(tokens),
                onSelect = { tokens = DecodeCurve.EvalTokenChoices[it] },
            )
        }
    }

    if (changes.isNotEmpty() || reload != null) {
        Spacer(Modifier.height(12.dp))
        ReloadNotice(changes.map { it.label }, reload, canReload = queueState is QueueState.ModelReady && !gateBusy, modelLoaded = resident != null)
    }

    Spacer(Modifier.height(12.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val rates = remember(runs, resident, entity) {
            val probe = DecodeRunConfig(
                model = entity?.shownName ?: resident?.name.orEmpty(),
                quant = entity?.quantLabel?.takeIf { it.isNotBlank() },
                backend = backend,
                nCtx = nCtx ?: 0,
                loaded = DecodeKnobs(),
                tokensPerPoint = tokens,
                points = eligible,
            )
            referenceRates(runs, probe)
        }
        val minutes = DecodeEstimate.minutes(DecodeEstimate.totalMs(eligible, tokens, rates))
        val withLoaded = changes.isNotEmpty()
        TensaiButton(
            text = stringResource(if (withLoaded) R.string.decode_run_loaded else R.string.decode_run, minutes),
            // The points picked, not only those that fit: the run records the others as skipped.
            onClick = { DecodeBench.start(context, points, tokens) },
            variant = if (withLoaded) TensaiButtonVariant.Outlined else TensaiButtonVariant.Filled,
            icon = Icons.Rounded.Speed,
            enabled = resident != null && eligible.isNotEmpty() && !gateBusy,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.decode_run_points, eligible.size, tokens),
            fontFamily = TensaiMono,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        failure?.let { Text(stringResource(R.string.bench_failed, it), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error) }
    }
}

/** "Settings apply on reload": the knobs that changed, and the reload, its progress or why it failed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReloadNotice(changes: List<String>, reload: ReloadState?, canReload: Boolean, modelLoaded: Boolean) {
    val tints = MaterialTheme.tensaiTints
    Surface(shape = TensaiShapes.large, color = tints.primary08, border = BorderStroke(1.dp, tints.primary24), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.decode_reload_title), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            if (changes.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    changes.forEach { MonoChip(it, highlighted = true) }
                }
            }
            when (reload) {
                is ReloadState.Loading -> {
                    Text(stringResource(R.string.decode_reloading), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TensaiProgressBar(progress = reload.progress)
                }
                else -> {
                    if (reload is ReloadState.Failed) {
                        // The unload came first: a failed load leaves no model, and the line says so.
                        val line = stringResource(R.string.decode_reload_failed, reloadFailureText(reload.reason)) +
                            if (modelLoaded) "" else " " + stringResource(R.string.decode_reload_no_model)
                        Text(line, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.error)
                    }
                    if (changes.isNotEmpty()) {
                        TensaiButton(
                            text = stringResource(R.string.decode_reload),
                            onClick = { DecodeBench.reload() },
                            icon = Icons.Rounded.Refresh,
                            // Not over a reply streaming in a chat (the unload would cancel it), nor during a bench.
                            enabled = canReload,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

/** Why a reload failed, as a sentence end: "Reload failed: <this>." */
@Composable
private fun reloadFailureText(reason: ReloadFailure): String = stringResource(
    when (reason) {
        ReloadFailure.OutOfMemory -> R.string.decode_reload_oom
        ReloadFailure.WontFit -> R.string.decode_reload_wont_fit
        ReloadFailure.FileMissing -> R.string.decode_reload_file_missing
        ReloadFailure.ModelNotFound -> R.string.decode_reload_not_found
        ReloadFailure.Unreadable -> R.string.decode_reload_unreadable
        ReloadFailure.Unsupported -> R.string.decode_reload_unsupported
        ReloadFailure.EngineBusy -> R.string.decode_reload_busy
        ReloadFailure.Unknown -> R.string.decode_reload_unknown
    },
)

/** A knob: its title and the machine id it sets (mono), an optional note, then its control. Dimmed when not [enabled]. */
@Composable
private fun KnobRow(title: String, hint: String?, enabled: Boolean = true, monoHint: Boolean = true, note: String? = null, control: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.5f).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            if (hint != null) {
                Text(hint, fontFamily = if (monoHint) TensaiMono else null, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        control()
        if (note != null) Text(note, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One option of [PillSegments]: its mono [label], whether it can be picked, an optional leading icon. */
private data class SegmentOption(val label: String, val enabled: Boolean = true, val icon: ImageVector? = null)

/** A row of mono pill segments, the [selected] one in the surface color with primary text, as Compare's control. */
@Composable
private fun PillSegments(options: List<SegmentOption>, selected: Int, onSelect: (Int) -> Unit, enabled: Boolean = true) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            options.forEachIndexed { i, option ->
                val on = i == selected
                Surface(
                    onClick = { onSelect(i) },
                    enabled = enabled && option.enabled,
                    shape = CircleShape,
                    color = if (on) MaterialTheme.colorScheme.surface else Color.Transparent,
                    contentColor = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).alpha(if (option.enabled) 1f else 0.38f),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterHorizontally),
                        modifier = Modifier.heightIn(min = 32.dp).padding(horizontal = 4.dp, vertical = 6.dp),
                    ) {
                        option.icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(13.dp)) }
                        Text(option.label, fontFamily = TensaiMono, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** A context point to toggle: primary-12 with a check when on, outlined with a plus when off, dashed with a block icon when it does not fit. */
@Composable
private fun PointChip(label: String, on: Boolean, blocked: Boolean, onClick: () -> Unit) {
    val tints = MaterialTheme.tensaiTints
    val dash = tints.outlineStrong
    val active = on && !blocked
    Surface(
        onClick = onClick,
        enabled = !blocked,
        shape = CircleShape,
        color = if (active) tints.primary12 else Color.Transparent,
        contentColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        border = if (active || blocked) null else BorderStroke(1.dp, dash),
        modifier = Modifier.then(
            if (blocked) {
                Modifier.alpha(0.6f).drawBehind {
                    val stroke = 1.dp.toPx()
                    drawRoundRect(
                        color = dash,
                        cornerRadius = CornerRadius(size.height / 2),
                        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                    )
                }
            } else {
                Modifier
            },
        ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.heightIn(min = 32.dp).padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Icon(
                imageVector = when {
                    blocked -> Icons.Rounded.Block
                    on -> Icons.Rounded.Check
                    else -> Icons.Rounded.Add
                },
                contentDescription = null,
                modifier = Modifier.size(14.dp),
            )
            Text(label, fontFamily = TensaiMono, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** A mono pill of machine data: surface-variant, or primary-12 bold when [highlighted] (a knob that changed). */
@Composable
internal fun MonoChip(text: String, highlighted: Boolean = false) {
    Surface(
        shape = CircleShape,
        color = if (highlighted) MaterialTheme.tensaiTints.primary12 else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = text,
            fontFamily = TensaiMono,
            fontSize = 11.sp,
            fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** The run going on: the wait for a prefix warm-up, the running card, its points so far, Cancel. */
@Composable
private fun RunningSection(progress: DecodeProgress, reference: List<DecodeRun>) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(progress.startedAtMs) {
        while (true) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    val config = progress.config
    val tokens = config.tokensPerPoint
    if (progress.phase == DecodePhase.Waiting) {
        Spacer(Modifier.height(18.dp))
        Surface(shape = TensaiShapes.large, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(16.dp)) {
                Icon(Icons.Rounded.HourglassEmpty, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.decode_waiting_title), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.decode_waiting_body), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    val rates = DecodeRates.of(progress.done) ?: referenceRates(reference, config)
    val remaining = config.points.filter { ctx -> progress.done.none { it.ctx == ctx } }
    val pointStart = progress.phaseStartedAtMs - (if (progress.phase == DecodePhase.Decode) progress.ttftMs ?: 0 else 0)
    val leftMs = remaining.sumOf { ctx ->
        val whole = DecodeEstimate.pointMs(ctx, tokens, rates)
        if (ctx == progress.current) (whole - (now - pointStart)).coerceAtLeast(0.0) else whole
    }
    val waiting = progress.phase == DecodePhase.Waiting
    Eyebrow(
        stringResource(R.string.bench_running_eyebrow),
        color = MaterialTheme.colorScheme.primary,
        trailing = if (waiting) null else elapsedClock(now - progress.startedAtMs) + " · " + stringResource(R.string.decode_left, DecodeEstimate.minutes(leftMs)),
    )
    val tints = MaterialTheme.tensaiTints
    Box(modifier = Modifier.border(4.dp, tints.primary08, TensaiShapes.large).padding(4.dp)) {
        BenchCard(border = BorderStroke(1.dp, tints.primary24)) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PointStepper(config.points, progress)
                CurrentPoint(progress, now, tokens)
            }
        }
    }
    Eyebrow(stringResource(R.string.decode_eyebrow_points))
    PointsTable(config.points.map { ctx -> ctx to progress.done.firstOrNull { it.ctx == ctx } }, current = progress.current)
    Spacer(Modifier.height(12.dp))
    TensaiButton(
        text = stringResource(R.string.decode_cancel),
        onClick = DecodeBench::cancel,
        variant = TensaiButtonVariant.Outlined,
        icon = Icons.Rounded.Close,
        contentColor = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        text = stringResource(R.string.decode_cancel_note),
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp, start = 4.dp),
    )
}

/** "03:12": minutes and seconds. */
private fun elapsedClock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60)
}

/** One step per point: done in the success tint with a check, the current one primary with its phase's icon, lines between. */
@Composable
private fun PointStepper(points: List<Int>, progress: DecodeProgress) {
    val tints = MaterialTheme.tensaiTints
    val success = MaterialTheme.tensaiColors.success
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        points.forEachIndexed { i, ctx ->
            val done = progress.done.any { it.ctx == ctx }
            val current = ctx == progress.current && !done
            if (i > 0) {
                Box(Modifier.weight(1f).height(2.dp).background(if (done || current) success else tints.outline))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Surface(
                    shape = CircleShape,
                    color = when {
                        done -> tints.success12
                        current -> tints.primary12
                        else -> Color.Transparent
                    },
                    contentColor = when {
                        done -> success
                        current -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    border = when {
                        current -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                        done -> null
                        else -> BorderStroke(1.dp, tints.outlineStrong)
                    },
                    modifier = Modifier.size(30.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        when {
                            done -> Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            current -> Icon(
                                if (progress.phase == DecodePhase.Decode) Icons.Rounded.EditNote else Icons.Rounded.Input,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                            else -> Unit
                        }
                    }
                }
                Text(ctxLabel(ctx), fontFamily = TensaiMono, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The point being measured: its phase, the live rate, a bar, and what comes next or what the prefill took. */
@Composable
private fun CurrentPoint(progress: DecodeProgress, now: Long, tokens: Int) {
    val label = progress.current?.let(::ctxLabel)
    val (line, rate, bar, note) = when (progress.phase) {
        DecodePhase.Waiting -> Quad(stringResource(R.string.decode_waiting_title), null, null, null)
        DecodePhase.WarmUp -> Quad(stringResource(R.string.decode_warmup, DecodeEstimate.WarmUpTokens), null, null, null)
        // llama.cpp reports no prefill progress before the first token: the phase alone, an open bar.
        DecodePhase.Prefill -> Quad(stringResource(R.string.decode_prefill, label.orEmpty()), null, null, stringResource(R.string.decode_then, tokens))
        DecodePhase.Decode -> {
            val seconds = (now - progress.phaseStartedAtMs) / 1000.0
            Quad(
                stringResource(R.string.decode_decoding, label.orEmpty(), progress.decoded, tokens),
                if (progress.decoded > 1 && seconds > 0) stringResource(R.string.decode_tps, String.format(Locale.ROOT, "%.1f", progress.decoded / seconds)) else null,
                progress.decoded.toFloat() / tokens,
                progress.ttftMs?.let { stringResource(R.string.decode_prefill_done, String.format(Locale.ROOT, "%.1f", it / 1000.0)) },
            )
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(line, fontFamily = TensaiMono, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (rate != null) Text(rate, fontFamily = TensaiMono, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
        }
        TensaiProgressBar(progress = bar)
        if (note != null) Text(note, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private data class Quad(val line: String, val rate: String?, val bar: Float?, val note: String?)

/**
 * The POINTS table: ctx · prompt · eval · decode t/s · prefill t/s, mono, numbers right-aligned. A row not measured
 * yet shows "·", the one being measured "...".
 */
@Composable
internal fun PointsTable(rows: List<Pair<Int, DecodeRunPoint?>>, current: Int? = null) {
    val headers = listOf("ctx", "prompt", "eval", "decode t/s", "prefill t/s")
    val weights = listOf(0.8f, 1f, 0.8f, 1.2f, 1.2f)
    BenchCard {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            TableRow(headers, weights, header = true)
            rows.forEach { (ctx, p) ->
                RowDivider()
                val pending = if (ctx == current) "..." else "·"
                TableRow(
                    listOf(
                        ctxLabel(ctx),
                        p?.let { it.promptTokens?.toString() ?: "-" } ?: pending,
                        p?.evalTokens?.toString() ?: pending,
                        p?.let { tpsText(it.decodeTps) } ?: pending,
                        p?.let { tpsText(it.prefillTps) } ?: pending,
                    ),
                    weights,
                )
            }
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, weights: List<Float>, header: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth().heightIn(min = if (header) 30.dp else 36.dp), verticalAlignment = Alignment.CenterVertically) {
        cells.forEachIndexed { i, cell ->
            Text(
                text = cell,
                fontFamily = TensaiMono,
                fontSize = if (header) 10.5.sp else 12.sp,
                fontWeight = if (header || i == 0) FontWeight.SemiBold else FontWeight.Normal,
                color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                textAlign = if (i == 0) TextAlign.Start else TextAlign.End,
                maxLines = 1,
                modifier = Modifier.weight(weights[i]),
            )
        }
    }
}

/** No decode run saved yet: a dashed card. */
@Composable
private fun EmptyDecodeRuns() {
    val dash = MaterialTheme.tensaiTints.outlineStrong
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    color = dash,
                    cornerRadius = CornerRadius(20.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                )
            }
            .padding(horizontal = 24.dp, vertical = 28.dp),
    ) {
        Icon(Icons.Rounded.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(28.dp))
        Text(stringResource(R.string.bench_empty_title), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Text(stringResource(R.string.decode_empty_body), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/**
 * A saved run, one row of the SAVED RUNS card: checkbox, when, partial pill, model, knob chips, and decode at the first
 * point and at 2k (or the last). A primary-08 band when selected. Swipe or overflow to delete.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SavedDecodeRow(
    run: DecodeRun,
    selected: Boolean,
    dimmed: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tints = MaterialTheme.tensaiTints
    val defaultName = stringResource(R.string.decode_run_default_name)
    val dismiss = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart) onDelete()
            value == SwipeToDismissBoxValue.EndToStart
        },
    )
    var menu by remember { mutableStateOf(false) }
    val takeaway = remember(run) { DecodeTakeaway.of(run.points) }
    SwipeToDismissBox(
        state = dismiss,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                modifier = Modifier.fillMaxSize().background(tints.error12).padding(horizontal = 20.dp),
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.bench_delete), color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
            }
        },
    ) {
        // Opaque, the selection tint and the dimming inside it: a see-through row would show the swipe's delete
        // underlay at rest.
        Surface(onClick = onOpen, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Row(
                verticalAlignment = Alignment.Top,
                modifier = Modifier
                    .background(if (selected) tints.primary08 else Color.Transparent)
                    .alpha(if (dimmed) 0.5f else 1f)
                    .padding(start = 4.dp, end = 4.dp, top = 6.dp, bottom = 12.dp),
            ) {
                IconButton(onClick = onToggle, enabled = !dimmed) {
                    Icon(
                        imageVector = if (selected) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank,
                        contentDescription = stringResource(R.string.bench_select),
                        tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(modifier = Modifier.weight(1f).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(run.name ?: startedLabel(run.startedAtMs), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (run.partial) PartialBadge(stringResource(R.string.decode_partial, run.points.size, run.config.points.size))
                    }
                    if (run.name != null) Text(startedLabel(run.startedAtMs), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = DecodeRunText.modelText(run.config),
                        fontFamily = TensaiMono,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        knobChips(run.config.loaded).forEach { (key, value) -> MonoChip("$key $value") }
                    }
                }
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(top = 10.dp, start = 8.dp)) {
                    val (headline, sub) = when {
                        takeaway != null -> String.format(Locale.ROOT, "%.1f → %.1f", takeaway.decodeFrom, takeaway.decodeTo) to
                            stringResource(R.string.decode_headline_sub, ctxLabel(takeaway.fromCtx) + " → " + ctxLabel(takeaway.toCtx))
                        else -> run.points.firstOrNull { it.decodeTps != null }?.let { p ->
                            String.format(Locale.ROOT, "%.1f", p.decodeTps) to stringResource(R.string.decode_headline_sub, ctxLabel(p.ctx))
                        } ?: ("-" to "")
                    }
                    Text(headline, fontFamily = TensaiMono, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    if (sub.isNotEmpty()) Text(sub, fontFamily = TensaiMono, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.bench_more))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.decode_copy_text)) },
                            onClick = {
                                menu = false
                                scope.launch { copyText(context) { DecodeRunText.of(run, defaultName) } }
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
        }
    }
}

/** At the foot while runs are selected: "{n} of 2 selected", Clear, Compare at exactly 2. */
@Composable
private fun DecodeSelectionBar(count: Int, onClear: () -> Unit, onCompare: () -> Unit, modifier: Modifier = Modifier) {
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
            Text(stringResource(R.string.decode_selected, count), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TensaiButton(text = stringResource(R.string.bench_clear), onClick = onClear, variant = TensaiButtonVariant.Text, size = TensaiButtonSize.Sm)
            TensaiButton(text = stringResource(R.string.bench_compare), onClick = onCompare, enabled = count == 2, size = TensaiButtonSize.Sm)
        }
    }
}
