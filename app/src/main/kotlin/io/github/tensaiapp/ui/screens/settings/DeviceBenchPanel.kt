package io.github.tensaiapp.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tensaiapp.R
import io.github.tensaiapp.bench.BenchGate
import io.github.tensaiapp.bench.DeviceBench
import io.github.tensaiapp.bench.DeviceBenchOutcome
import io.github.tensaiapp.bench.DeviceBenchPlan
import io.github.tensaiapp.ui.components.TensaiButton
import io.github.tensaiapp.ui.components.TensaiButtonVariant
import io.github.tensaiapp.ui.theme.TensaiMono

/**
 * Settings > This device > Benchmark (TEN-208), in every build: says what [DeviceBench] measures and how long it takes,
 * runs it on a tap, and shows its [io.github.tensaiapp.bench.DeviceReport] as plain text with a Copy button, for a
 * GitHub device report. The screen stays on while it runs.
 */
@Composable
internal fun DeviceBenchPanel() {
    val context = LocalContext.current
    val state by DeviceBench.state.collectAsState()
    val gateBusy by BenchGate.busy.collectAsState()
    val running = state is DeviceBench.State.Running
    val view = LocalView.current
    DisposableEffect(running) {
        view.keepScreenOn = running
        onDispose { view.keepScreenOn = false }
    }

    SectionLabel(stringResource(R.string.device_bench_title))
    SectionCard {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (val s = state) {
                DeviceBench.State.Idle -> {
                    Body(stringResource(R.string.device_bench_intro, DeviceBenchPlan.Lengths.joinToString(", ")))
                    if (gateBusy) Body(stringResource(R.string.device_bench_gate_busy))
                    TensaiButton(
                        text = stringResource(R.string.device_bench_start),
                        onClick = { DeviceBench.start(context) },
                        enabled = !gateBusy,
                    )
                }
                is DeviceBench.State.Running -> {
                    val p = s.progress
                    Body(
                        if (p?.current == null) {
                            stringResource(R.string.device_bench_preparing)
                        } else {
                            stringResource(R.string.device_bench_running, p.current.toString(), p.done + 1, p.total)
                        },
                    )
                    TensaiButton(
                        text = stringResource(R.string.device_bench_stop),
                        onClick = DeviceBench::stop,
                        variant = TensaiButtonVariant.Outlined,
                    )
                }
                is DeviceBench.State.Finished -> {
                    val text = s.report.text()
                    Body(stringResource(R.string.device_bench_result))
                    SelectionContainer {
                        Text(
                            text = text,
                            fontFamily = TensaiMono,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TensaiButton(text = stringResource(R.string.device_bench_copy), onClick = { copy(context, text) })
                        TensaiButton(
                            text = stringResource(R.string.device_bench_again),
                            onClick = DeviceBench::dismiss,
                            variant = TensaiButtonVariant.Text,
                        )
                    }
                }
                is DeviceBench.State.NotRun -> {
                    Body(
                        stringResource(
                            when (s.outcome) {
                                DeviceBenchOutcome.NoModel -> R.string.device_bench_no_model
                                DeviceBenchOutcome.Busy -> R.string.device_bench_busy
                                DeviceBenchOutcome.NothingFits -> R.string.device_bench_too_small
                                is DeviceBenchOutcome.Failed, is DeviceBenchOutcome.Done -> R.string.device_bench_failed
                            },
                        ),
                    )
                    TensaiButton(
                        text = stringResource(R.string.device_bench_again),
                        onClick = DeviceBench::dismiss,
                        variant = TensaiButtonVariant.Text,
                    )
                }
            }
        }
    }
}

@Composable
private fun Body(text: String) {
    Text(text = text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.device_bench_title), text))
    Toast.makeText(context, R.string.device_bench_copied, Toast.LENGTH_SHORT).show()
}
