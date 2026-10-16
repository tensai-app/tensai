package io.github.hokanosekai.tensai.ui.screens.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.hokanosekai.tensai.R
import io.github.hokanosekai.tensai.ui.components.TensaiBottomSheet
import io.github.hokanosekai.tensai.ui.theme.TensaiMono
import io.github.hokanosekai.tensai.ui.theme.tensaiColors
import io.github.hokanosekai.tensai.ui.theme.tensaiTints
import io.github.hokanosekai.tensai.ui.tools.ReadableOutput
import io.github.hokanosekai.tensai.ui.tools.ToolArgsView
import io.github.hokanosekai.tensai.ui.tools.ToolCallState
import io.github.hokanosekai.tensai.ui.tools.ToolCallUi
import io.github.hokanosekai.tensai.ui.tools.ToolSpinner
import io.github.hokanosekai.tensai.ui.tools.ToolUiCatalog
import io.github.hokanosekai.tensai.ui.tools.UiText
import io.github.hokanosekai.tensai.ui.tools.formatToolDuration
import io.github.hokanosekai.tensai.ui.tools.isJsonOutput
import io.github.hokanosekai.tensai.ui.tools.rawOutput
import io.github.hokanosekai.tensai.ui.tools.readableOutput
import io.github.hokanosekai.tensai.ui.tools.resolve
import io.github.hokanosekai.tensai.ui.tools.toolCallLabel
import io.github.hokanosekai.tensai.ui.tools.toolCallSummary

/**
 * The sheet behind a tool line (TEN-173): the group's calls as a timeline, and a tap on one pushes its detail
 * inside the same sheet (back arrow in the header, and the system back), with its inputs, where it sent
 * something, and its output readable or as JSON.
 *
 * Reads [calls] live, so a call that finishes while the sheet is open updates it.
 *
 * @param title the group's label, "Used 3 tools".
 */
@Composable
fun ToolCallsSheet(title: UiText, calls: List<ToolCallUi>, onDismiss: () -> Unit) {
    TensaiBottomSheet(
        onDismissRequest = onDismiss,
        shape = SheetShape,
        containerColor = MaterialTheme.colorScheme.background,
        scrimColor = MaterialTheme.tensaiTints.scrim,
        dragHandle = { SheetDragHandle() },
        fullHeight = true,
        // A glance at what ran: half height first, pulled up for the detail when wanted.
        openExpanded = false,
    ) {
        var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
        val selected = calls.firstOrNull { it.id == selectedId }
        BackHandler(enabled = selected != null) { selectedId = null }

        Header(
            title = if (selected != null) ToolUiCatalog.name(selected.tool).resolve() else title.resolve(),
            detail = selected,
            onLeading = { if (selected != null) selectedId = null else onDismiss() },
        )
        if (selected == null) {
            Timeline(calls, onSelect = { selectedId = it.id })
        } else {
            Detail(selected)
        }
    }
}

@Composable
private fun Header(title: String, detail: ToolCallUi?, onLeading: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp).heightIn(min = 48.dp),
    ) {
        IconButton(onClick = onLeading, modifier = Modifier.width(52.dp)) {
            Icon(
                imageVector = if (detail != null) Icons.AutoMirrored.Rounded.ArrowBack else Icons.Rounded.Close,
                contentDescription = stringResource(if (detail != null) R.string.tool_sheet_back else R.string.tool_sheet_close),
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    StatusDot(detail.state, describe = false)
                    Text(text = statusText(detail.state), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    detail.durationMs?.let {
                        Text(text = "·", fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = formatToolDuration(it),
                            fontFamily = TensaiMono,
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.width(52.dp))
    }
}

@Composable
private fun statusText(state: ToolCallState): String = stringResource(
    when (state) {
        ToolCallState.Running -> R.string.tool_status_running
        ToolCallState.Ok -> R.string.tool_status_ok
        ToolCallState.Error -> R.string.tool_status_error
        ToolCallState.Timeout -> R.string.tool_status_timeout
        ToolCallState.Refused -> R.string.tool_status_refused
        ToolCallState.Stopped -> R.string.tool_status_stopped
    },
)

@Composable
private fun statusColor(state: ToolCallState): Color = when (state) {
    ToolCallState.Running -> MaterialTheme.colorScheme.primary
    ToolCallState.Ok -> MaterialTheme.tensaiColors.success
    ToolCallState.Error, ToolCallState.Timeout -> MaterialTheme.colorScheme.error
    ToolCallState.Refused -> MaterialTheme.tensaiColors.warning
    ToolCallState.Stopped -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun StatusDot(state: ToolCallState, describe: Boolean = true) {
    // The dot alone carries the status in the timeline; the header prints it next to the dot, so it stays silent there.
    val spoken = statusText(state)
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(statusColor(state), CircleShape)
            .then(if (describe) Modifier.semantics { contentDescription = spoken } else Modifier),
    )
}

@Composable
private fun Timeline(calls: List<ToolCallUi>, onSelect: (ToolCallUi) -> Unit) {
    val connector = MaterialTheme.tensaiTints.outlineStrong
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 0.dp, end = 0.dp, top = 8.dp)
            .navigationBarsPadding()
            .padding(horizontal = 4.dp)
            .padding(bottom = 28.dp),
    ) {
        calls.forEachIndexed { index, call ->
            Box(
                modifier = Modifier.drawBehind {
                    // Between the icons: above the circle of every row but the first, below it of every row but the last.
                    val x = 16.dp.toPx() + 15.5.dp.toPx()
                    val circleTop = 12.dp.toPx()
                    val circleBottom = circleTop + 32.dp.toPx()
                    if (index > 0) drawLine(connector, Offset(x, 0f), Offset(x, circleTop), strokeWidth = 1.dp.toPx())
                    if (index < calls.lastIndex) drawLine(connector, Offset(x, circleBottom), Offset(x, size.height), strokeWidth = 1.dp.toPx())
                },
            ) {
                TimelineRow(call, onClick = { onSelect(call) })
            }
        }
    }
}

@Composable
private fun TimelineRow(call: ToolCallUi, onClick: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(32.dp)
                .background(MaterialTheme.colorScheme.surface, CircleShape)
                .border(1.dp, MaterialTheme.tensaiTints.outline, CircleShape),
        ) {
            Icon(ToolUiCatalog.icon(call.tool), contentDescription = null, tint = muted, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = toolCallLabel(call).resolve(),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val summary = toolCallSummary(call)
            if (summary.isNotEmpty()) {
                Text(
                    text = summary,
                    fontSize = 14.sp,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
            modifier = Modifier.defaultMinSize(minWidth = 40.dp).padding(top = 6.dp),
        ) {
            StatusDot(call.state)
            call.durationMs?.let {
                Text(text = formatToolDuration(it), fontFamily = TensaiMono, fontSize = 12.sp, color = muted, textAlign = TextAlign.End)
            }
        }
    }
}

@Composable
private fun Detail(call: ToolCallUi) {
    var showRaw by rememberSaveable(call.id) { mutableStateOf(false) }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp)
            .navigationBarsPadding()
            .padding(bottom = 28.dp),
    ) {
        Eyebrow(stringResource(R.string.tool_detail_inputs), Modifier.padding(top = 6.dp, bottom = 8.dp))
        val inputs = ToolArgsView.inputs(call.argsJson)
        SheetCard {
            if (inputs.isEmpty()) {
                Text(
                    text = stringResource(R.string.tool_detail_no_inputs),
                    fontSize = 15.sp,
                    color = muted,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
            inputs.forEachIndexed { index, input ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.tensaiTints.outline)
                Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 11.dp, bottom = 12.dp)) {
                    Text(text = input.key, fontSize = 12.sp, color = muted)
                    Text(
                        text = input.value,
                        fontFamily = if (input.mono) TensaiMono else null,
                        fontSize = if (input.mono) 13.5.sp else 15.sp,
                        lineHeight = if (input.mono) 20.sp else 21.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                }
            }
        }
        call.destination?.let { destination ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(start = 4.dp, top = 8.dp),
            ) {
                Icon(Icons.Rounded.Language, contentDescription = null, tint = muted, modifier = Modifier.size(16.dp))
                Text(text = stringResource(R.string.tool_detail_sent_to, destination), fontSize = 13.sp, color = muted)
            }
        }

        val output = call.output
        val running = call.state == ToolCallState.Running
        val hasJson = output != null && isJsonOutput(output)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 22.dp, bottom = if (hasJson && !running) 0.dp else 8.dp),
        ) {
            Eyebrow(stringResource(R.string.tool_detail_output), Modifier.weight(1f))
            if (hasJson && !running) {
                TextButton(onClick = { showRaw = !showRaw }) {
                    Text(
                        text = stringResource(if (showRaw) R.string.tool_detail_show_readable else R.string.tool_detail_show_json),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
        when {
            running -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(horizontal = 4.dp),
            ) {
                ToolSpinner()
                Text(text = stringResource(R.string.tool_detail_waiting), fontSize = 14.sp, color = muted)
            }
            output.isNullOrEmpty() -> Text(
                text = stringResource(R.string.tool_detail_no_output),
                fontSize = 14.sp,
                color = muted,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            showRaw -> RawBlock(rawOutput(output))
            else -> ReadableBlock(readableOutput(output, call.state), call.tool)
        }
    }
}

@Composable
private fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.06.em,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun ReadableBlock(output: ReadableOutput, tool: String) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when (output) {
        is ReadableOutput.Results -> SheetCard {
            output.items.forEachIndexed { index, item ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.tensaiTints.outline)
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        text = item.title,
                        fontSize = 15.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    item.url?.let {
                        Text(
                            text = it,
                            fontFamily = TensaiMono,
                            fontSize = 12.sp,
                            color = muted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 3.dp),
                        )
                    }
                }
            }
        }
        is ReadableOutput.Kv -> SheetCard {
            output.rows.forEachIndexed { index, (key, value) ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.tensaiTints.outline)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
                ) {
                    Text(text = key, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        text = value,
                        fontFamily = TensaiMono,
                        fontSize = 13.sp,
                        color = muted,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        is ReadableOutput.AppOpened -> SheetCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.size(40.dp).background(MaterialTheme.tensaiTints.primary12, CircleShape),
                ) {
                    Icon(ToolUiCatalog.icon(tool), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                }
                Text(
                    text = stringResource(R.string.tool_detail_opened, output.app),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
        is ReadableOutput.Error -> Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.tensaiTints.error12) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Icon(Icons.Rounded.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                Text(
                    text = output.message,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }
        is ReadableOutput.Text -> SheetCard {
            Text(
                text = output.text,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
    }
}

/** The output as it is, pretty-printed when it is JSON: mono 12/18 on surface-variant, scrolling sideways. */
@Composable
private fun RawBlock(text: String) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = text,
            fontFamily = TensaiMono,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = MaterialTheme.colorScheme.onBackground,
            softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}
