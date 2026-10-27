package io.github.tensaiapp.ui.screens.bench

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import io.github.tensaiapp.R
import io.github.tensaiapp.bench.BenchRun
import io.github.tensaiapp.bench.BenchRunConfig
import io.github.tensaiapp.bench.BenchVerdict
import io.github.tensaiapp.bench.RunTotals
import io.github.tensaiapp.bench.backendName
import io.github.tensaiapp.ui.theme.TensaiMono
import io.github.tensaiapp.ui.theme.tensaiColors
import io.github.tensaiapp.ui.theme.tensaiTints
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How a verdict is drawn, from the spec's chip sheet: a 12-14% tint, never a solid semantic fill. */
internal data class VerdictStyle(val fill: Color, val content: Color, val border: Color?, val dashed: Boolean, val icon: ImageVector)

@Composable
internal fun verdictStyle(verdict: BenchVerdict): VerdictStyle {
    val scheme = MaterialTheme.colorScheme
    val tints = MaterialTheme.tensaiTints
    val colors = MaterialTheme.tensaiColors
    return when (verdict) {
        BenchVerdict.Correct -> VerdictStyle(tints.success12, colors.success, null, false, Icons.Rounded.Check)
        BenchVerdict.FalseCall -> VerdictStyle(tints.warning12, colors.warning, null, false, Icons.AutoMirrored.Rounded.CallMade)
        BenchVerdict.Missing -> VerdictStyle(scheme.surfaceVariant, scheme.onSurface, tints.outlineStrong, false, Icons.Rounded.Remove)
        BenchVerdict.WrongTool -> VerdictStyle(tints.error12, scheme.error, null, false, Icons.Rounded.SwapHoriz)
        BenchVerdict.Error -> VerdictStyle(tints.error12, scheme.error, scheme.error, false, Icons.Rounded.Error)
        BenchVerdict.Unavailable -> VerdictStyle(Color.Transparent, scheme.onSurfaceVariant, tints.outlineStrong, true, Icons.Rounded.Block)
    }
}

/** The matrix code of a verdict: machine data, so mono and not translated. */
internal val BenchVerdict.short: String
    get() = when (this) {
        BenchVerdict.Correct -> "OK"
        BenchVerdict.FalseCall -> "FALSE"
        BenchVerdict.Missing -> "MISS"
        BenchVerdict.WrongTool -> "WRONG"
        BenchVerdict.Error -> "ERR"
        BenchVerdict.Unavailable -> "N/A"
    }

/** The legend's lowercase key of a verdict, as in the stacked bar's legend. */
internal val BenchVerdict.legendKey: String
    get() = when (this) {
        BenchVerdict.Correct -> "ok"
        BenchVerdict.FalseCall -> "false"
        BenchVerdict.Missing -> "miss"
        BenchVerdict.WrongTool -> "wrong"
        BenchVerdict.Error -> "err"
        BenchVerdict.Unavailable -> "n/a"
    }

@Composable
internal fun BenchVerdict.longLabel(): String = stringResource(
    when (this) {
        BenchVerdict.Correct -> R.string.bench_verdict_correct
        BenchVerdict.FalseCall -> R.string.bench_verdict_false_call
        BenchVerdict.Missing -> R.string.bench_verdict_missing
        BenchVerdict.WrongTool -> R.string.bench_verdict_wrong_tool
        BenchVerdict.Error -> R.string.bench_verdict_error
        BenchVerdict.Unavailable -> R.string.bench_verdict_unavailable
    },
)

/** The stacked bar's order: ok, false, miss, wrong, err, n/a. */
internal val BarOrder = listOf(
    BenchVerdict.Correct,
    BenchVerdict.FalseCall,
    BenchVerdict.Missing,
    BenchVerdict.WrongTool,
    BenchVerdict.Error,
    BenchVerdict.Unavailable,
)

/**
 * A pill around [text]: [style]'s fill, content color and border (dashed for N/A). [outlineOnly] draws the border in
 * the content color on no fill, for a mixed pass rate.
 */
@Composable
internal fun VerdictPill(text: String, style: VerdictStyle, modifier: Modifier = Modifier, outlineOnly: Boolean = false, mono: Boolean = true) {
    val border = if (outlineOnly) style.content else style.border
    val dashColor = border.takeIf { style.dashed && !outlineOnly }
    Surface(
        shape = CircleShape,
        color = if (outlineOnly) Color.Transparent else style.fill,
        contentColor = style.content,
        border = if (border != null && dashColor == null) BorderStroke(1.dp, border) else null,
        modifier = modifier
            .heightIn(min = 24.dp)
            .then(if (dashColor != null) Modifier.dashedPill(dashColor) else Modifier),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        ) {
            Icon(style.icon, contentDescription = null, modifier = Modifier.size(13.dp))
            Text(
                text = text,
                fontFamily = if (mono) TensaiMono else null,
                fontSize = if (mono) 11.sp else 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

/** A dashed hairline around a pill: the N/A chip. A dashed border is not a fill pattern, the DS allows it. */
private fun Modifier.dashedPill(color: Color): Modifier = drawBehind {
    val stroke = 1.dp.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(size.height / 2),
        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
    )
}

/** One option of [BenchSegmented]: its [label], whether it can be picked, an optional leading icon. */
internal data class SegmentOption(val label: String, val enabled: Boolean = true, val icon: ImageVector? = null)

/**
 * The bench's segmented control, every one of them: a surface-variant pill track, 3dp inside, 2dp between segments; the
 * selected segment in the surface color with primary text, the others transparent. The selected pill fills the track's
 * height: segments are plain selectable rows, not clickable Surfaces, whose minimum touch size would pad the track
 * around a shorter pill. [mono] is the knobs' style: segments of equal width across the row, mono labels; off, segments
 * as wide as their label, Figtree. An option that is not [SegmentOption.enabled] is dimmed and takes no taps, nor does
 * any when [enabled] is false.
 */
@Composable
internal fun BenchSegmented(
    options: List<SegmentOption>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    mono: Boolean = false,
) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = if (mono) modifier.fillMaxWidth() else modifier) {
        Row(modifier = Modifier.height(IntrinsicSize.Min).padding(3.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            options.forEachIndexed { i, option ->
                val on = i == selected
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(if (mono) 3.dp else 5.dp, Alignment.CenterHorizontally),
                    modifier = Modifier
                        .then(if (mono) Modifier.weight(1f) else Modifier)
                        .fillMaxHeight()
                        .alpha(if (option.enabled) 1f else 0.38f)
                        .clip(CircleShape)
                        .background(if (on) MaterialTheme.colorScheme.surface else Color.Transparent)
                        .selectable(selected = on, enabled = enabled && option.enabled, role = Role.Tab, onClick = { onSelect(i) })
                        .heightIn(min = 32.dp)
                        .padding(horizontal = if (mono) 4.dp else 12.dp, vertical = 6.dp),
                ) {
                    val content = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    option.icon?.let { Icon(it, contentDescription = null, tint = content, modifier = Modifier.size(if (mono) 13.dp else 15.dp)) }
                    Text(
                        text = option.label,
                        fontFamily = if (mono) TensaiMono else null,
                        fontSize = if (mono) 12.5.sp else 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = content,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** The DEBUG badge of the bench top bars: warning tint pill, mono. */
@Composable
internal fun DebugBadge() {
    Surface(shape = CircleShape, color = MaterialTheme.tensaiTints.warning12) {
        Text(
            text = "DEBUG",
            fontFamily = TensaiMono,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp,
            color = MaterialTheme.tensaiColors.warning,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/**
 * The bench screens' own top bar (the shell draws none on bench routes): back, [title] (mono for a prompt id), an
 * optional mono [subtitle], the DEBUG badge, then [actions].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BenchTopBar(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    monoTitle: Boolean = false,
    actions: @Composable () -> Unit = {},
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    text = title,
                    style = if (monoTitle) {
                        MaterialTheme.typography.titleMedium.copy(fontFamily = TensaiMono, fontSize = 16.sp)
                    } else {
                        MaterialTheme.typography.titleLarge
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        fontFamily = TensaiMono,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
        },
        actions = {
            DebugBadge()
            actions()
            Box(Modifier.size(8.dp))
        },
        // The shell's Scaffold already pads for the status bar.
        windowInsets = WindowInsets(0),
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}

/** A small uppercase eyebrow label, [trailing] (a count, mono) at the end of the line. */
@Composable
internal fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, trailing: String? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 18.dp, bottom = 8.dp),
    ) {
        Text(
            text = text.uppercase(),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.72.sp,
            color = color,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(text = trailing, fontFamily = TensaiMono, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One config chip of a run: [on] is a primary-12 pill with a check, off an outlined one with a cross (struck through when [strikeOff]). */
@Composable
internal fun ConfigChip(label: String, on: Boolean, strikeOff: Boolean = false) {
    val tints = MaterialTheme.tensaiTints
    val content = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = CircleShape,
        color = if (on) tints.primary12 else Color.Transparent,
        contentColor = content,
        border = if (on) null else BorderStroke(1.dp, tints.outlineStrong),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
        ) {
            Icon(if (on) Icons.Rounded.Check else Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(11.dp))
            Text(
                text = label,
                fontFamily = TensaiMono,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                textDecoration = if (!on && strikeOff) TextDecoration.LineThrough else null,
                maxLines = 1,
            )
        }
    }
}

/**
 * The config chips of [config], label and state: think, base vN, ex, gram, greedy, then "reason N" with the reasoning
 * cap the decodes got, when they had one, off when a reply reasoned past it ([BenchRunConfig.reasoningCapExceeded]).
 */
internal fun configChips(config: BenchRunConfig): List<Pair<String, Boolean>> = listOfNotNull(
    "think" to config.thinking,
    "base v${config.basePromptVersion}" to config.basePrompt,
    "ex" to config.examples,
    "gram" to config.toolGrammar,
    "greedy" to config.sampling.greedy,
    config.sampling.reasoningMaxTokens?.takeIf { config.thinking }?.let { "reason $it" to (config.reasoningCapExceeded != true) },
)

/** "Qwen3-1.7B Q4_K_M · CPU", what the run ran on. A run saved before [backendName] has its full label cut down too. */
internal fun modelLine(config: BenchRunConfig): String = listOfNotNull(
    listOfNotNull(config.model, config.quant).joinToString(" "),
    config.backend?.let(::backendName),
).joinToString(" · ")

/**
 * The verdict counts of [totals] as an 8dp bar, segments in [BarOrder], 2dp apart, n/a a flat outline segment. [unrun]
 * trials (a partial run's) are drawn as n/a.
 */
@Composable
internal fun StackedVerdictBar(totals: RunTotals, modifier: Modifier = Modifier, unrun: Int = 0) {
    val colors = barColors()
    Row(
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier.fillMaxWidth().height(8.dp).clip(CircleShape),
    ) {
        BarOrder.forEach { verdict ->
            val n = totals.count(verdict) + if (verdict == BenchVerdict.Unavailable) unrun else 0
            if (n > 0) Box(Modifier.weight(n.toFloat()).fillMaxHeight().background(colors.getValue(verdict)))
        }
        if (totals.trials + unrun == 0) Box(Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.tensaiTints.outline))
    }
}

@Composable
private fun barColors(): Map<BenchVerdict, Color> {
    val scheme = MaterialTheme.colorScheme
    return mapOf(
        BenchVerdict.Correct to MaterialTheme.tensaiColors.success,
        BenchVerdict.FalseCall to MaterialTheme.tensaiColors.warning,
        BenchVerdict.Missing to scheme.onSurfaceVariant,
        BenchVerdict.WrongTool to scheme.error,
        BenchVerdict.Error to scheme.error,
        BenchVerdict.Unavailable to MaterialTheme.tensaiTints.outlineStrong,
    )
}

/** The bar's legend: icon, key and count of each verdict, mono. */
@Composable
internal fun VerdictLegendItem(verdict: BenchVerdict, count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        Icon(verdictStyle(verdict).icon, contentDescription = null, tint = barColors().getValue(verdict), modifier = Modifier.size(12.dp))
        Text(
            text = "${verdict.legendKey} $count",
            fontFamily = TensaiMono,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Milliseconds with a thin space every three digits: "6 420". */
internal fun groupDigits(value: Long): String {
    val digits = kotlin.math.abs(value).toString()
    val grouped = digits.reversed().chunked(3).joinToString(" ").reversed()
    return if (value < 0) "-$grouped" else grouped
}

/** "00:24.8": minutes, seconds and tenths. */
internal fun elapsedText(ms: Long): String {
    val tenths = (ms / 100).coerceAtLeast(0)
    return String.format(Locale.ROOT, "%02d:%02d.%d", tenths / 600, (tenths / 10) % 60, tenths % 10)
}

/** "Today · 15:30", "Yesterday · 22:17", else the date: when a run started. */
@Composable
internal fun startedLabel(startedAtMs: Long): String {
    val zone = ZoneId.systemDefault()
    val at = Instant.ofEpochMilli(startedAtMs).atZone(zone)
    val time = at.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
    val today = LocalDate.now(zone)
    return when (at.toLocalDate()) {
        today -> stringResource(R.string.bench_today, time)
        today.minusDays(1) -> stringResource(R.string.bench_yesterday, time)
        else -> at.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())) + " · " + time
    }
}

/** "15:30", the time a run started, for the narrow columns. */
internal fun startedTime(startedAtMs: Long): String =
    Instant.ofEpochMilli(startedAtMs).atZone(ZoneId.systemDefault()).toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))

/** "PARTIAL · 12/24": a partial run's badge text, graded of planned. */
internal fun partialText(run: BenchRun): String = "PARTIAL · ${run.results.size}/${run.plannedTrials}"

/** Trials a partial run never got to, 0 for a full one. */
internal val BenchRun.unrun: Int get() = (plannedTrials - results.size).coerceAtLeast(0)

/** Keeps the screen on while [active]: a bench run lasts minutes, and a locked phone slows it down. */
@Composable
internal fun KeepScreenOn(active: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, active) {
        view.keepScreenOn = active
        onDispose { view.keepScreenOn = false }
    }
}

/**
 * Puts what [encode] writes on the clipboard, encoded off the main thread. A clip too large for the system is refused
 * there: the toast says to share it instead.
 */
internal suspend fun copyJson(context: Context, encode: () -> String) {
    val json = withContext(Dispatchers.Default) { encode() }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val copied = runCatching { clipboard.setPrimaryClip(ClipData.newPlainText("tensai bench", json)) }.isSuccess
    Toast.makeText(context, if (copied) R.string.bench_copied else R.string.bench_copy_too_large, Toast.LENGTH_SHORT).show()
}

/** Puts what [write] returns on the clipboard, written off the main thread: a decode run as text (TEN-190). */
internal suspend fun copyText(context: Context, write: () -> String) {
    val text = withContext(Dispatchers.Default) { write() }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("tensai bench", text))
    Toast.makeText(context, R.string.decode_copied, Toast.LENGTH_SHORT).show()
}

/**
 * Opens the share sheet on what [encode] writes, as a file, `cache/bench-share/[name].json` through the debug build's
 * FileProvider: a run can be far over what an intent extra carries. Encoded and written off the main thread. Files of
 * earlier shares go after an hour, not before: the app they went to may still be reading one.
 */
internal suspend fun shareJson(context: Context, name: String, encode: () -> String) {
    val json = withContext(Dispatchers.Default) { encode() }
    val file = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "bench-share").apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - ShareKeepMs
        dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        File(dir, "$name.json").apply { writeText(json) }
    }
    val uri = FileProvider.getUriForFile(context, context.packageName + ".bench", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_SUBJECT, "$name.json")
        clipData = ClipData.newRawUri(name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
}

private const val ShareKeepMs = 60 * 60 * 1000L
