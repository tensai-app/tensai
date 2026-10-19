package io.github.tensaiapp.ui.screens.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tensaiapp.R
import io.github.tensaiapp.ui.components.rememberReducedMotion
import io.github.tensaiapp.ui.theme.TensaiMotion
import io.github.tensaiapp.data.AutoMemoryShare
import io.github.tensaiapp.data.ContextSetting
import io.github.tensaiapp.data.chat.ToolActivityRow
import io.github.tensaiapp.runtime.EffectiveBackend
import io.github.tensaiapp.runtime.RuntimeGraph
import io.github.tensaiapp.ui.components.TensaiBottomSheet
import io.github.tensaiapp.ui.screens.models.backendHardware
import io.github.tensaiapp.ui.screens.models.formatFileSize
import io.github.tensaiapp.ui.theme.TensaiMono
import io.github.tensaiapp.ui.tools.ToolUiCatalog
import io.github.tensaiapp.ui.tools.formatToolDuration
import io.github.tensaiapp.ui.tools.resolve
import io.github.tensaiapp.ui.theme.tensaiColors
import io.github.tensaiapp.ui.theme.tensaiTints
import java.util.Locale
import kotlin.math.max

private val TokensColumn = 64.dp
private val PercentColumn = 36.dp

/**
 * The "Context" bottom sheet behind the chat header's ring (TEN-161): the window card with its
 * breakdown, and the runtime card.
 *
 * @param onLoad "Load" on the no-model row, with the model the conversation was started on.
 * @param onOpenSettings the full state's way out.
 */
@Composable
fun ContextSheet(
    state: ContextSheetState,
    onLoad: (modelId: String?) -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
) {
    TensaiBottomSheet(
        onDismissRequest = onDismiss,
        shape = SheetShape,
        containerColor = MaterialTheme.colorScheme.background,
        scrimColor = MaterialTheme.tensaiTints.scrim,
        dragHandle = { SheetDragHandle() },
        // Full height, like the conversation settings: unfolding the breakdown only unfolds, the
        // sheet keeps its size (and its half stop on the way down).
        fullHeight = true,
    ) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterStart)) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.context_sheet_close),
                    tint = MaterialTheme.colorScheme.onBackground,
                )
            }
            Text(
                text = stringResource(R.string.context_sheet_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(22.dp),
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp)
                .navigationBarsPadding()
                .padding(bottom = 28.dp),
        ) {
            Section(stringResource(R.string.context_sheet_section_window)) {
                SheetCard { WindowCard(state.breakdown, modelLoaded = state.runtime is ContextRuntime.Loaded, onOpenSettings) }
            }
            Section(stringResource(R.string.context_sheet_section_runtime)) {
                SheetCard { RuntimeCard(state.runtime, onLoad) }
            }
            if (state.activity.isNotEmpty()) {
                Section(stringResource(R.string.context_sheet_section_activity)) {
                    AgentActivity(state.activity)
                }
            }
        }
    }
}

@Composable
private fun WindowCard(breakdown: ContextBreakdown, modelLoaded: Boolean, onOpenSettings: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val reducedMotion = rememberReducedMotion()
    val rotation by animateFloatAsState(
        if (expanded) 180f else 0f,
        tween(if (reducedMotion) 0 else TensaiMotion.DurBase),
        label = "chevron",
    )
    val error = MaterialTheme.colorScheme.error
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val title = stringResource(R.string.context_sheet_section_window)
    val toggleLabel = title + ", " + stringResource(
        if (expanded) R.string.context_sheet_collapse else R.string.context_sheet_expand,
    )

    // One animation for the whole unfold, as in the conversation settings' sampling block: the card
    // grows with its rows (expand + fade together) instead of the rows outrunning an animateContentSize.
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button) { expanded = !expanded }
                .semantics { contentDescription = toggleLabel }
                .heightIn(min = 52.dp)
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            if (breakdown.length > 0) {
                Text(
                    text = stringResource(R.string.context_sheet_percent_used, breakdown.usedPercent),
                    fontSize = 14.sp,
                    fontWeight = if (breakdown.full) FontWeight.SemiBold else null,
                    color = if (breakdown.full) error else muted,
                )
            }
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = muted,
                modifier = Modifier.size(22.dp).rotate(rotation),
            )
        }

        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
            ContextBar(breakdown)
            Text(
                text = usedLabel(breakdown),
                fontFamily = TensaiMono,
                fontSize = 12.5.sp,
                color = muted,
                modifier = Modifier.padding(top = 10.dp),
            )
            if (breakdown.estimate) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Icon(Icons.Outlined.Info, contentDescription = null, tint = muted, modifier = Modifier.size(16.dp))
                    Text(
                        // With a model loaded the estimate means this conversation is not in the cache yet.
                        text = stringResource(if (modelLoaded) R.string.context_sheet_estimate_not_cached else R.string.context_sheet_estimate),
                        fontSize = 13.sp,
                        color = muted,
                    )
                }
            }
            if (breakdown.full) {
                Text(
                    text = stringResource(R.string.context_sheet_full_note),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = 10.dp),
                )
                TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.context_sheet_open_settings)) }
            }
        }

        AnimatedVisibility(
            visible = expanded,
            enter = if (reducedMotion) EnterTransition.None else expandVertically(tween(TensaiMotion.DurBase)) + fadeIn(tween(TensaiMotion.DurBase)),
            exit = if (reducedMotion) ExitTransition.None else shrinkVertically(tween(TensaiMotion.DurBase)) + fadeOut(tween(TensaiMotion.DurBase)),
        ) {
            Column {
                HorizontalDivider(color = MaterialTheme.tensaiTints.outline)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 10.dp, bottom = 4.dp),
                ) {
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = stringResource(R.string.context_sheet_col_tokens),
                        fontFamily = TensaiMono,
                        fontSize = 11.5.sp,
                        color = muted,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(TokensColumn),
                    )
                    Text(
                        text = "%",
                        fontFamily = TensaiMono,
                        fontSize = 11.5.sp,
                        color = muted,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(PercentColumn),
                    )
                }
                breakdown.rows.forEach { row -> PartRow(row, breakdown.estimate) }
                if (breakdown.rows.any { it.part == ContextPart.Overhead }) {
                    Text(
                        text = stringResource(R.string.context_overhead_note),
                        fontSize = 12.5.sp,
                        color = muted,
                        modifier = Modifier.padding(horizontal = 16.dp).padding(top = 4.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/** "11.2k of 32k tokens", "32k of 32k tokens" once full, `~` on the count of an estimate. */
@Composable
private fun usedLabel(breakdown: ContextBreakdown): String = when {
    breakdown.length <= 0 -> "~" + formatTokens(breakdown.used) + " " + stringResource(R.string.context_sheet_col_tokens)
    breakdown.full -> {
        val length = formatContextLength(breakdown.length)
        stringResource(R.string.context_sheet_tokens_of, length, length)
    }
    breakdown.estimate ->
        stringResource(R.string.context_sheet_tokens_of_estimate, formatTokens(breakdown.used), formatContextLength(breakdown.length))
    else -> stringResource(R.string.context_sheet_tokens_of, formatTokens(breakdown.used), formatContextLength(breakdown.length))
}

@Composable
private fun partLabel(part: ContextPart): String = stringResource(
    when (part) {
        ContextPart.System -> R.string.context_part_system
        ContextPart.User -> R.string.context_part_user
        ContextPart.Assistant -> R.string.context_part_assistant
        ContextPart.ToolDefinitions -> R.string.context_part_tool_definitions
        ContextPart.Memory -> R.string.context_part_memory
        ContextPart.ToolResults -> R.string.context_part_tool_results
        ContextPart.Thinking -> R.string.context_part_thinking
        ContextPart.Overhead -> R.string.context_part_overhead
        ContextPart.Reserved -> R.string.context_part_reserved
        ContextPart.Free -> R.string.context_part_free
    },
)

/** The color a part is drawn in, both its bar segment and its dot. Free has none: it is the track. */
@Composable
private fun partColor(part: ContextPart): Color {
    val primary = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    return when (part) {
        ContextPart.System, ContextPart.Reserved -> muted
        ContextPart.User -> primary
        ContextPart.Assistant -> lerp(primary, MaterialTheme.tensaiColors.success, 0.45f)
        ContextPart.ToolDefinitions -> lerp(MaterialTheme.tensaiColors.success, primary, 0.25f)
        ContextPart.Memory -> lerp(MaterialTheme.tensaiColors.warning, MaterialTheme.tensaiColors.success, 0.8f)
        ContextPart.ToolResults -> lerp(MaterialTheme.tensaiColors.warning, MaterialTheme.tensaiColors.success, 0.55f)
        ContextPart.Thinking -> lerp(primary, MaterialTheme.colorScheme.error, 0.5f)
        ContextPart.Overhead -> muted.copy(alpha = 0.5f)
        ContextPart.Free -> Color.Transparent
    }
}

@Composable
private fun ContextBar(breakdown: ContextBreakdown) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val surface = MaterialTheme.colorScheme.surface
    val segments = breakdown.rows.filter { it.part != ContextPart.Free }
    val colors = segments.map { partColor(it.part) }
    val alpha = if (breakdown.estimate) 0.5f else 1f
    val base = if (breakdown.length > 0) breakdown.length else breakdown.used
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(track),
    ) {
        val gap = 2.dp.toPx()
        val minWidth = 3.dp.toPx()
        var x = 0f
        segments.forEachIndexed { i, row ->
            val width = max(minWidth, size.width * row.tokens / base.coerceAtLeast(1))
            val last = i == segments.lastIndex
            val drawn = (if (last) width else (width - gap).coerceAtLeast(1f)).coerceAtMost(size.width - x)
            if (drawn > 0f) {
                if (row.part == ContextPart.Reserved) {
                    drawHatch(Offset(x, 0f), Size(drawn, size.height), colors[i].copy(alpha = alpha), surface)
                } else {
                    drawRect(colors[i].copy(alpha = alpha), Offset(x, 0f), Size(drawn, size.height))
                }
            }
            x += width
        }
    }
}

/** Diagonal stripes at 135 degrees over a surface-colored ground, inside [topLeft] and [size]. */
private fun DrawScope.drawHatch(topLeft: Offset, size: Size, stripe: Color, ground: Color) {
    drawRect(ground, topLeft, size)
    val step = 3.dp.toPx()
    clipRect(topLeft.x, topLeft.y, topLeft.x + size.width, topLeft.y + size.height) {
        var offset = -size.height
        while (offset < size.width) {
            drawLine(
                color = stripe,
                start = Offset(topLeft.x + offset, topLeft.y),
                end = Offset(topLeft.x + offset + size.height, topLeft.y + size.height),
                strokeWidth = 1.dp.toPx(),
            )
            offset += step
        }
    }
}

@Composable
private fun PartRow(row: ContextPartRow, estimate: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(horizontal = 16.dp),
    ) {
        PartDot(row.part)
        Text(
            text = partLabel(row.part),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 10.dp),
        )
        Text(
            text = (if (estimate) "~" else "") + String.format(Locale.US, "%,d", row.tokens),
            fontFamily = TensaiMono,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(TokensColumn),
        )
        Text(
            text = if (row.percent < 1) "<1" else row.percent.toString(),
            fontFamily = TensaiMono,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(PercentColumn),
        )
    }
}

/** 8dp: filled, hatched inside a 1dp border for the reserve, an open ring for the free space. */
@Composable
private fun PartDot(part: ContextPart) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val surface = MaterialTheme.colorScheme.surface
    val modifier = Modifier.size(8.dp).clip(CircleShape)
    when (part) {
        ContextPart.Free -> Box(modifier.border(1.5.dp, MaterialTheme.tensaiTints.outlineStrong, CircleShape))
        ContextPart.Reserved -> Box(
            modifier
                .drawBehind { drawHatch(Offset.Zero, size, muted, surface) }
                .border(1.dp, muted, CircleShape),
        )
        else -> Box(modifier.background(partColor(part)))
    }
}

@Composable
private fun RuntimeCard(runtime: ContextRuntime, onLoad: (String?) -> Unit) {
    when (runtime) {
        is ContextRuntime.NoModel -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 16.dp, end = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.context_runtime_no_model),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onLoad(runtime.modelId) }) { Text(stringResource(R.string.context_runtime_load)) }
        }
        is ContextRuntime.Loaded -> {
            val profile = remember { RuntimeGraph.deviceProfiler.profile() }
            val prefs by remember { RuntimeGraph.contextRepository.prefs }.collectAsState(initial = null)
            val cpuCores = profile.cpuCoreCount.takeIf { it > 0 }?.let { stringResource(R.string.model_detail_cpu_cores, it) }
            val backend = effectiveBackendOf(runtime.backend)?.let {
                backendHardware(it, profile.vulkanDeviceName, profile.openClDeviceName, cpuCores)
            } ?: runtime.backend
            val length = formatContextLength(runtime.nCtx)
            val setting = runtime.contextSetting ?: prefs?.setting
            val contextValue = when {
                setting == null -> length
                setting == ContextSetting.Auto ->
                    stringResource(R.string.context_runtime_ctx_auto, length, (prefs?.autoShare ?: AutoMemoryShare.P50).percent)
                setting == ContextSetting.Max -> stringResource(R.string.context_runtime_ctx_max, length)
                else -> stringResource(R.string.context_runtime_ctx_fixed, length, formatContextLength(setting.tokens ?: runtime.nCtx))
            }
            val reply = runtime.lastReply?.let { stats ->
                val ttft = String.format(Locale.US, "%.1f", stats.ttftMs / 1000f)
                if (stats.tokPerSec > 0f) {
                    stringResource(R.string.context_runtime_reply, String.format(Locale.US, "%.1f", stats.tokPerSec), ttft)
                } else {
                    stringResource(R.string.context_runtime_reply_ttft, ttft)
                }
            }
            val rows = listOfNotNull(
                R.string.context_runtime_model to runtime.model,
                R.string.context_runtime_backend to backend,
                R.string.context_runtime_context to contextValue,
                R.string.context_runtime_kv to
                    listOfNotNull(runtime.kvCacheType.name, runtime.kvCacheBytes?.let(::formatFileSize)).joinToString(" · "),
                reply?.let { R.string.context_runtime_last_reply to it },
            )
            Column {
                rows.forEachIndexed { i, (label, value) ->
                    if (i > 0) HorizontalDivider(color = MaterialTheme.tensaiTints.outline)
                    RuntimeRow(stringResource(label), value)
                }
            }
        }
    }
}

@Composable
private fun RuntimeRow(label: String, value: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 16.dp),
    ) {
        Text(
            text = label,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = value,
            fontFamily = TensaiMono,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The engine's backend label as an [EffectiveBackend], `null` for one this build has no name for. */
internal fun effectiveBackendOf(label: String): EffectiveBackend? = when {
    label.contains("vulkan", ignoreCase = true) -> EffectiveBackend.Vulkan
    label.contains("opencl", ignoreCase = true) -> EffectiveBackend.OpenCl
    label.contains("cpu", ignoreCase = true) -> EffectiveBackend.Cpu
    else -> null
}

/**
 * "Agent activity" (TEN-162): what the tools did in this conversation, not what the model sees. One
 * card, a row of totals that unfolds into a per-tool table, and a note under it.
 */
@Composable
private fun AgentActivity(rows: List<ToolActivityRow>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    val reducedMotion = rememberReducedMotion()
    val rotation by animateFloatAsState(
        if (expanded) 180f else 0f,
        tween(if (reducedMotion) 0 else TensaiMotion.DurBase),
        label = "chevron",
    )
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val title = stringResource(R.string.context_activity_tools_called)
    val totalCalls = rows.sumOf { it.calls }
    val totalTokens = formatTokens(rows.sumOf { it.resultTokens })
    val toggleLabel = stringResource(R.string.context_activity_a11y_header, title, totalCalls, totalTokens)
    val toggleState = stringResource(if (expanded) R.string.chat_settings_sampling_expanded else R.string.chat_settings_sampling_collapsed)
    val visible = rows.visibleActivity(showAll)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SheetCard {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { expanded = !expanded }
                        .semantics {
                            contentDescription = toggleLabel
                            stateDescription = toggleState
                        }
                        .heightIn(min = 52.dp)
                        .padding(horizontal = 16.dp),
                ) {
                    Text(
                        text = title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = totalCalls.toString(),
                        fontFamily = TensaiMono,
                        fontSize = 12.sp,
                        color = muted,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                    Text(
                        text = totalTokens + " " + stringResource(R.string.context_sheet_col_tokens),
                        fontFamily = TensaiMono,
                        fontSize = 13.sp,
                        color = muted,
                        maxLines = 1,
                    )
                    Icon(
                        imageVector = Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = muted,
                        modifier = Modifier.size(22.dp).rotate(rotation),
                    )
                }
                AnimatedVisibility(
                    visible = expanded,
                    enter = if (reducedMotion) EnterTransition.None else expandVertically(tween(TensaiMotion.DurBase)) + fadeIn(tween(TensaiMotion.DurBase)),
                    exit = if (reducedMotion) ExitTransition.None else shrinkVertically(tween(TensaiMotion.DurBase)) + fadeOut(tween(TensaiMotion.DurBase)),
                ) {
                    Column {
                        HorizontalDivider(color = MaterialTheme.tensaiTints.outline)
                        ActivityRow(
                            tool = stringResource(R.string.context_activity_col_tool),
                            calls = stringResource(R.string.context_activity_col_calls),
                            tokens = stringResource(R.string.context_sheet_col_tokens),
                            time = stringResource(R.string.context_activity_col_time),
                            header = true,
                            description = null,
                        )
                        visible.forEach { row ->
                            ActivityRow(
                                tool = ToolUiCatalog.name(row.tool).resolve(),
                                calls = row.calls.toString(),
                                tokens = formatTokens(row.resultTokens),
                                time = formatToolDuration(row.durationMs),
                                header = false,
                                description = stringResource(
                                    R.string.context_activity_a11y_row,
                                    ToolUiCatalog.name(row.tool).resolve(), row.calls, formatTokens(row.resultTokens), formatToolDuration(row.durationMs),
                                ),
                            )
                        }
                        if (rows.size > ActivityRowCap) {
                            TextButton(onClick = { showAll = !showAll }, modifier = Modifier.padding(horizontal = 8.dp)) {
                                Text(
                                    stringResource(
                                        if (showAll) R.string.context_activity_show_less else R.string.context_activity_show_more,
                                        rows.size - ActivityRowCap,
                                    ),
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
        Text(
            text = stringResource(R.string.context_activity_note),
            fontSize = 12.5.sp,
            color = muted,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

/** A line of the table: tool | calls | tokens | time, in mono. */
@Composable
private fun ActivityRow(tool: String, calls: String, tokens: String, time: String, header: Boolean, description: String?) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val size = if (header) 11.5.sp else 13.sp
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                if (header) invisibleToUser() else contentDescription = description.orEmpty()
            }
            .then(if (header) Modifier.padding(top = 10.dp, bottom = 4.dp) else Modifier.heightIn(min = 36.dp))
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = tool,
            fontFamily = TensaiMono,
            fontSize = size,
            color = if (header) muted else MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        listOf(calls to 48.dp, tokens to 56.dp, time to 64.dp).forEach { (text, width) ->
            Text(
                text = text,
                fontFamily = TensaiMono,
                fontSize = size,
                color = muted,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.width(width),
            )
        }
    }
}
