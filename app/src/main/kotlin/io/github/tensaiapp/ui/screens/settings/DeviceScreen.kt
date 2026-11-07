package io.github.tensaiapp.ui.screens.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tensaiapp.R
import io.github.tensaiapp.ui.theme.TensaiMono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext

/**
 * Settings > This device (TEN-158): what this phone is and what llama.cpp found on it, in the same
 * grouped cards as Settings, values in mono. Read-only. [copyRequests] are taps on the top bar's
 * copy icon (owned by `TensaiShell`): the whole report goes to the clipboard as plain text, for a
 * bug report. Under the cards, the benchmark ([DeviceBenchPanel], TEN-208); a debug build also has the agent bench
 * at the bottom, see [BenchPanel].
 */
@Composable
fun DeviceScreen(
    modifier: Modifier = Modifier,
    copyRequests: Flow<Unit> = emptyFlow(),
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val viewModel = remember {
        val app = context.applicationContext
        DeviceInfoViewModel(read = { readDeviceInfo(app) })
    }
    val info by viewModel.info.collectAsState()
    val current = info
    // The locale too: with `locale` in configChanges the Resources instance can stay the same across a language change.
    val locale = LocalConfiguration.current.locales[0]
    val sections = remember(current, resources, locale) {
        current?.let { deviceSections(it) { id -> resources.getString(id) } }
    }

    val latestSections by rememberUpdatedState(sections)
    LaunchedEffect(copyRequests) {
        copyRequests.collect {
            val ready = latestSections ?: return@collect
            val text = withContext(Dispatchers.Default) { deviceReportText(ready) { id -> resources.getString(id) } }
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(resources.getString(R.string.device_title), text))
            Toast.makeText(context, R.string.device_copied, Toast.LENGTH_SHORT).show()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
    ) {
        if (sections == null) {
            Text(
                text = stringResource(R.string.device_loading),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 18.dp, start = 4.dp),
            )
        } else {
            sections.forEach { section ->
                SectionLabel(stringResource(section.title))
                SectionCard {
                    section.rows.forEachIndexed { index, row ->
                        if (index > 0) RowDivider()
                        DeviceValueRow(row.labelText ?: stringResource(row.label), row.value)
                    }
                }
            }
        }
        DeviceBenchPanel()
        BenchPanel()
    }
}

/** Title left, mono value right. A long value (feature list, backend line) drops under the title instead. */
@Composable
private fun DeviceValueRow(title: String, value: String) {
    val long = value.length > 24
    val valueText: @Composable (Modifier, TextAlign) -> Unit = { mod, align ->
        Text(
            text = value,
            fontFamily = TensaiMono,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = align,
            modifier = mod,
        )
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(if (long) 1f else 0.45f),
            )
            if (!long) valueText(Modifier.weight(0.55f), TextAlign.End)
        }
        if (long) valueText(Modifier.padding(top = 4.dp), TextAlign.Start)
    }
}
