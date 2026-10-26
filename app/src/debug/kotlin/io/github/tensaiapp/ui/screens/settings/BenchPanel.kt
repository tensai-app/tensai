package io.github.tensaiapp.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tensaiapp.R
import io.github.tensaiapp.bench.AgentBench
import io.github.tensaiapp.bench.BenchStatus
import io.github.tensaiapp.bench.BenchTrigger
import io.github.tensaiapp.bench.BenchGate
import io.github.tensaiapp.ui.components.rememberReducedMotion
import io.github.tensaiapp.ui.screens.bench.KeepScreenOn
import io.github.tensaiapp.ui.screens.chat.DefaultEnableThinking
import io.github.tensaiapp.ui.screens.chat.SwitchVisual
import io.github.tensaiapp.ui.theme.TensaiMono

/**
 * Debug builds only (this source set, the release one has an empty stub), under Settings > This device:
 * runs [AgentBench] with the toggles of a [BenchTrigger], reachable by taps where the launch intent is not.
 * The run lives on the application scope, so this screen can be left and come back to; its progress and
 * summary are [AgentBench.status], drawn here in mono to be read off a screenshot. The log lines on `tensai.bench` are the same as from the intent.
 * A run started here is saved like one from Settings > Bench (TEN-187), which is the main entry now. The decode
 * curve lives on its Decode tab (TEN-190).
 */
@Composable
internal fun BenchPanel() {
    val context = LocalContext.current
    val status by AgentBench.status.collectAsState()
    val gateBusy by BenchGate.busy.collectAsState()
    val reducedMotion = rememberReducedMotion()
    var thinking by rememberSaveable { mutableStateOf(DefaultEnableThinking) }
    var basePrompt by rememberSaveable { mutableStateOf(true) }
    var examples by rememberSaveable { mutableStateOf(false) }
    var toolGrammar by rememberSaveable { mutableStateOf(true) }
    // One bench at a time: they share the engine and the log tag.
    val running = status is BenchStatus.Running || gateBusy
    KeepScreenOn(running)

    SectionLabel(stringResource(R.string.bench_title))
    SectionCard {
        BenchToggle(stringResource(R.string.bench_thinking), thinking, reducedMotion) { thinking = it }
        RowDivider()
        BenchToggle(stringResource(R.string.settings_base_prompt), basePrompt, reducedMotion) { basePrompt = it }
        RowDivider()
        // The examples are part of the base prompt: no examples without it.
        BenchToggle(stringResource(R.string.bench_examples), examples && basePrompt, reducedMotion, enabled = basePrompt) { examples = it }
        RowDivider()
        BenchToggle(stringResource(R.string.bench_tool_grammar), toolGrammar, reducedMotion) { toolGrammar = it }
        RowDivider()
        SettingsRow(
            icon = Icons.Rounded.PlayArrow,
            title = stringResource(R.string.bench_run),
            supporting = stringResource(R.string.bench_hint),
            onClick = if (running) null else { { AgentBench.start(BenchTrigger(thinking, basePrompt, examples && basePrompt, toolGrammar), context) } },
        )
    }
    StatusLines(status, runningText = { done, total -> stringResource(R.string.bench_running, done, total) }, stoppedEarly = R.string.bench_stopped_early)
}

/** A bench's progress or summary under its card, in mono. */
@Composable
private fun StatusLines(status: BenchStatus, runningText: @Composable (Int, Int) -> String, stoppedEarly: Int) {
    val lines = when (status) {
        BenchStatus.Idle -> emptyList()
        is BenchStatus.Running -> listOf(status.total?.let { runningText(status.done, it) } ?: stringResource(R.string.bench_starting))
        is BenchStatus.Failed -> listOf(stringResource(R.string.bench_failed, status.message))
        is BenchStatus.Finished -> status.lines + listOfNotNull(if (status.stoppedEarly) stringResource(stoppedEarly) else null)
    }
    if (lines.isNotEmpty()) {
        Column(modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp)) {
            lines.forEach {
                Text(text = it, fontFamily = TensaiMono, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun BenchToggle(title: String, checked: Boolean, reducedMotion: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.38f)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        SwitchVisual(checked, reducedMotion)
    }
}
