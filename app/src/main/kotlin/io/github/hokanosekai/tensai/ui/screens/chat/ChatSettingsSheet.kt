package io.github.hokanosekai.tensai.ui.screens.chat

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import io.github.hokanosekai.tensai.ui.components.TensaiBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.hokanosekai.tensai.R
import io.github.hokanosekai.tensai.data.chat.SamplingRanges
import io.github.hokanosekai.tensai.engine.SamplingConfig
import io.github.hokanosekai.tensai.ui.components.TensaiChip
import io.github.hokanosekai.tensai.ui.theme.TensaiMono
import io.github.hokanosekai.tensai.ui.theme.TensaiMotion
import io.github.hokanosekai.tensai.ui.theme.tensaiTints
import java.text.NumberFormat
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal val SheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

/** Drag handle slot height: 10dp above, 4dp bar, 2dp below. */
internal val SheetHandleHeight = 16.dp

/**
 * 1dp stroke along the sheet's top edge only, following its 28dp top corners: the shape's outline is
 * stroked and clipped to the corner band, so the straight sides below it never show.
 */
internal fun DrawScope.drawTopHairline(color: Color) {
    val band = 28.dp.toPx()
    val corner = CornerRadius(band)
    val shape = Path().apply {
        addRoundRect(RoundRect(0f, 0f, size.width, band * 2, corner, corner, CornerRadius.Zero, CornerRadius.Zero))
    }
    clipRect(top = 0f, bottom = band) {
        drawPath(shape, color, style = Stroke(width = 2.dp.toPx()))
    }
}

/** The sheets' drag handle: a 32dp bar under a hairline along the top edge. */
@Composable
internal fun SheetDragHandle() {
    val hairline = MaterialTheme.tensaiTints.outline
    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = Modifier.fillMaxWidth().drawBehind { drawTopHairline(hairline) },
    ) {
        Box(
            modifier = Modifier
                .padding(top = 10.dp, bottom = 2.dp)
                .size(width = 32.dp, height = 4.dp)
                .background(MaterialTheme.tensaiTints.outlineStrong, RoundedCornerShape(2.dp)),
        )
    }
}

/**
 * Per-conversation settings (TEN-35, redesigned in TEN-119), as a bottom sheet over the chat.
 *
 * ### Why the sheet keeps its own draft
 *
 * [config]/[systemPrompt] seed a local draft once, when the sheet opens, and every edit is committed
 * outward as it happens. The draft is not re-seeded from later emissions of the flows behind those
 * parameters: those emissions are this sheet's own writes coming back around, and following them
 * would fight the user's typing and snap a slider being dragged back to the last committed notch.
 * Reset is the one change that comes from outside a field, so it rewrites the draft itself.
 *
 * ### What is committed when
 *
 * Sliders commit on release, not on every pixel: a drag is one setting change, not two hundred Room
 * writes. Value pills commit when they lose focus or on Done, clamped into range (see
 * [commitPillText]). Presets and chips commit on tap. The stop-sequence field commits on every
 * keystroke, see [stopSequencesFromText].
 *
 * Everything here applies to the next generation. Nothing reloads the model, and nothing touches a
 * reply already streaming.
 *
 * ### Reset
 *
 * Resets the tuning only: sampling and max length to [SamplingConfig]'s defaults, stop
 * sequences and thinking (on, when the model supports it). The enabled
 * state of the button is [isSheetDefault]. The system prompt is never touched (no undo), as in
 * ChatRepository.resetSampling.
 *
 * @param contextSize the loaded model's effective context, or `null` with no model loaded: max-length
 *   chips at or above it are disabled, and the caption names it. A stored value that is not allowed
 *   any more is shown as is and never rewritten.
 * @param supportsThinking gates the thinking row, see [ChatViewModel.currentModelSupportsThinking]:
 *   the row stays visible but disabled, with a caption, when the model cannot think.
 * @param supportsTools whether the loaded model's template takes tools, see [ChatViewModel.currentModelSupportsTools]:
 *   the Tools row (TEN-173) is only there then. The sheet does not show the model's name, so the Tools badge is not either.
 * @param toolsEnabled this conversation's Tools switch, committed on tap by [onToolsEnabledChange].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSettingsSheet(
    config: SamplingConfig,
    systemPrompt: String,
    enableThinking: Boolean,
    supportsThinking: Boolean,
    contextSize: Int?,
    onConfigChange: (SamplingConfig) -> Unit,
    onSystemPromptChange: (String) -> Unit,
    onEnableThinkingChange: (Boolean) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    supportsTools: Boolean = false,
    toolsEnabled: Boolean = true,
    onToolsEnabledChange: (Boolean) -> Unit = {},
) {
    TensaiBottomSheet(
        onDismissRequest = onDismiss,
        shape = SheetShape,
        containerColor = MaterialTheme.colorScheme.background,
        scrimColor = MaterialTheme.tensaiTints.scrim,
        dragHandle = { SheetDragHandle() },
    ) {
        // Deliberately unkeyed, see the KDoc's "why the sheet keeps its own draft".
        var draft by remember { mutableStateOf(config) }
        var promptDraft by remember { mutableStateOf(systemPrompt) }
        // Bumped by Reset to rebuild the one field that holds its text uncontrolled (stop sequences).
        var resetSeed by remember { mutableIntStateOf(0) }
        var samplingExpanded by remember { mutableStateOf(false) }

        fun commit(next: SamplingConfig) {
            draft = next
            onConfigChange(next)
        }

        val isDefault = isSheetDefault(draft, enableThinking, supportsThinking)
        val reducedMotion = rememberReducedMotion()
        val focusManager = LocalFocusManager.current

        // Typing is written out after a pause, and flushed if the sheet goes away first: one Room
        // write per burst of keystrokes, not one per key.
        DebouncedWrite(value = promptDraft, resetKey = Unit, write = onSystemPromptChange)
        DebouncedWrite(value = draft.stopSequences, resetKey = resetSeed) { onConfigChange(draft.copy(stopSequences = it)) }

        // The sheet is edge to edge, so it would rise under the status bar when the content is tall.
        // Capping the content keeps the handle (about 16dp of it sits above) below the bar.
        val density = LocalDensity.current
        val windowHeight = LocalView.current.rootView.height
        val maxContentHeight = with(density) {
            (windowHeight - WindowInsets.statusBars.getTop(density)).toDp() - SheetHandleHeight
        }
        Column(modifier = Modifier.heightIn(max = maxContentHeight)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.chat_settings_title),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.01).em,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = !isDefault,
                    onClick = {
                        focusManager.clearFocus()
                        draft = SamplingConfig()
                        resetSeed++
                        onReset()
                        if (supportsThinking) onEnableThinkingChange(true)
                    },
                ) {
                    Text(stringResource(R.string.chat_settings_reset))
                }
            }

            Column(
                verticalArrangement = Arrangement.spacedBy(22.dp),
                modifier = Modifier
                    .weight(1f, fill = false)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 28.dp),
            ) {
                Section(stringResource(R.string.chat_settings_section_instructions)) {
                    SheetCard {
                        PromptField(
                            value = promptDraft,
                            onValueChange = { promptDraft = it },
                        )
                    }
                }

                Section(stringResource(R.string.chat_settings_section_style)) {
                    SheetCard {
                        ThinkingRow(
                            checked = enableThinking,
                            enabled = supportsThinking,
                            reducedMotion = reducedMotion,
                            onCheckedChange = onEnableThinkingChange,
                        )
                    }
                    val style = detectStyle(draft)
                    StyleChips(
                        style = style,
                        onPick = {
                            focusManager.clearFocus()
                            commit(draft.withStyle(it))
                        },
                    )
                    SheetCard {
                        SamplingHeader(
                            config = draft,
                            expanded = samplingExpanded,
                            reducedMotion = reducedMotion,
                            onToggle = { samplingExpanded = !samplingExpanded },
                        )
                        AnimatedVisibility(
                            visible = samplingExpanded,
                            enter = if (reducedMotion) {
                                EnterTransition.None
                            } else {
                                expandVertically(tween(TensaiMotion.DurBase)) + fadeIn(tween(TensaiMotion.DurBase))
                            },
                            exit = if (reducedMotion) {
                                ExitTransition.None
                            } else {
                                shrinkVertically(tween(TensaiMotion.DurBase)) + fadeOut(tween(TensaiMotion.DurBase))
                            },
                        ) {
                            Column {
                                HorizontalDivider(color = MaterialTheme.tensaiTints.outline)
                                ParamRow(
                                    label = stringResource(R.string.chat_settings_temperature),
                                    hint = stringResource(R.string.chat_settings_temperature_hint),
                                    value = draft.temperature,
                                    min = SamplingRanges.Temperature.start,
                                    max = SamplingRanges.Temperature.endInclusive,
                                    step = 0.05f,
                                    decimals = 2,
                                    onCommit = { commit(draft.copy(temperature = it)) },
                                )
                                HorizontalDivider(color = MaterialTheme.tensaiTints.outline)
                                ParamRow(
                                    label = "Top P",
                                    hint = stringResource(R.string.chat_settings_top_p_hint),
                                    value = draft.topP,
                                    min = SamplingRanges.TopP.start,
                                    max = SamplingRanges.TopP.endInclusive,
                                    step = 0.01f,
                                    decimals = 2,
                                    onCommit = { commit(draft.copy(topP = it)) },
                                )
                                HorizontalDivider(color = MaterialTheme.tensaiTints.outline)
                                ParamRow(
                                    label = "Min P",
                                    hint = stringResource(R.string.chat_settings_min_p_hint),
                                    value = draft.minP,
                                    min = SamplingRanges.MinP.start,
                                    max = SamplingRanges.MinP.endInclusive,
                                    step = 0.01f,
                                    decimals = 2,
                                    onCommit = { commit(draft.copy(minP = it)) },
                                )
                                HorizontalDivider(color = MaterialTheme.tensaiTints.outline)
                                ParamRow(
                                    label = "Top K",
                                    hint = stringResource(R.string.chat_settings_top_k_hint),
                                    value = draft.topK.toFloat(),
                                    min = SamplingRanges.TopK.first.toFloat(),
                                    max = SamplingRanges.TopK.last.toFloat(),
                                    step = 1f,
                                    decimals = 0,
                                    onCommit = { commit(draft.copy(topK = it.toInt())) },
                                )
                                HorizontalDivider(color = MaterialTheme.tensaiTints.outline)
                                ParamRow(
                                    label = stringResource(R.string.chat_settings_repeat_penalty),
                                    hint = stringResource(R.string.chat_settings_repeat_penalty_hint),
                                    value = draft.repeatPenalty,
                                    min = SamplingRanges.RepeatPenalty.start,
                                    max = SamplingRanges.RepeatPenalty.endInclusive,
                                    step = 0.01f,
                                    decimals = 2,
                                    onCommit = { commit(draft.copy(repeatPenalty = it)) },
                                )
                                HorizontalDivider(color = MaterialTheme.tensaiTints.outline)
                                key(resetSeed) {
                                    StopSequencesRow(
                                        initial = draft.stopSequences,
                                        onCommit = { draft = draft.copy(stopSequences = it) },
                                    )
                                }
                            }
                        }
                    }
                }

                // Only for a model whose template takes tools: the switch means nothing to another.
                if (supportsTools) {
                    Section(stringResource(R.string.chat_settings_section_tools)) {
                        SheetCard {
                            ToolsRow(checked = toolsEnabled, reducedMotion = reducedMotion, onCheckedChange = onToolsEnabledChange)
                        }
                    }
                }

                Section(stringResource(R.string.chat_settings_section_length)) {
                    MaxTokensChips(
                        selected = draft.maxTokens,
                        contextSize = contextSize,
                        onPick = { commit(draft.copy(maxTokens = it)) },
                    )
                    MaxTokensCaption(draft.maxTokens, contextSize)
                }
            }
        }
    }
}

/** True under the system's "remove animations" setting, same convention as InputPill's generation ring. */
@Composable
private fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

private fun <T> motionSpec(reducedMotion: Boolean): FiniteAnimationSpec<T> =
    if (reducedMotion) snap() else tween(TensaiMotion.DurBase)

/**
 * Writes [value] through [write] 400ms after it last changed, and once more on the way out if it was
 * still pending. [resetKey] changing re-baselines "already written" to the current value, for a
 * change that was persisted by other means (Reset).
 */
@Composable
private fun <T> DebouncedWrite(value: T, resetKey: Any?, write: (T) -> Unit) {
    var sent by remember(resetKey) { mutableStateOf(value) }
    val latest by rememberUpdatedState(value)
    val writer by rememberUpdatedState(write)
    LaunchedEffect(value, resetKey) {
        if (value != sent) {
            delay(WriteDebounceMillis)
            writer(value)
            sent = value
        }
    }
    DisposableEffect(Unit) { onDispose { if (latest != sent) writer(latest) } }
}

private const val WriteDebounceMillis = 400L

/** Scrolls the field into view once it is focused, after the keyboard has had time to resize the sheet. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Modifier.bringIntoViewOnFocus(): Modifier {
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    return this
        .bringIntoViewRequester(requester)
        .onFocusChanged {
            if (it.isFocused) {
                scope.launch {
                    delay(250)
                    requester.bringIntoView()
                }
            }
        }
}

/** Uppercase eyebrow over a block, 12sp 600 with 0.06em tracking. */
@Composable
internal fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = title.uppercase(),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.06.em,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        content()
    }
}

/** Kit `Card` with no padding: surface fill, hairline border, 20dp radius. */
@Composable
internal fun SheetCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.tensaiTints.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column { content() }
    }
}

@Composable
private fun PromptField(value: String, onValueChange: (String) -> Unit) {
    val textStyle = TextStyle(
        fontSize = 15.sp,
        lineHeight = 22.sp,
        color = MaterialTheme.colorScheme.onBackground,
    )
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        minLines = 3,
        maxLines = 8,
        textStyle = textStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text(
                        text = stringResource(R.string.chat_settings_prompt_placeholder),
                        style = textStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                    )
                }
                inner()
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)
            .bringIntoViewOnFocus(),
    )
    Text(
        text = stringResource(R.string.chat_settings_prompt_chars, value.length),
        fontFamily = TensaiMono,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.End,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
    )
}

/**
 * Kit `ListItem` holding the thinking switch. The whole row is the toggle target ([Role.Switch]); the
 * switch itself is only drawn. Disabled, it keeps its place and says why instead of vanishing.
 */
@Composable
private fun ThinkingRow(checked: Boolean, enabled: Boolean, reducedMotion: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.38f)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Psychology,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.chat_thinking_toggle),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(
                    if (enabled) R.string.chat_settings_thinking_supporting else R.string.chat_settings_thinking_unsupported,
                ),
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SwitchVisual(checked && enabled, reducedMotion)
    }
}

/** 52x32 switch from the kit. Purely visual: semantics live on the row that contains it. */
@Composable
internal fun SwitchVisual(checked: Boolean, reducedMotion: Boolean) {
    val spec = motionSpec<Dp>(reducedMotion)
    val thumbSize by animateDpAsState(if (checked) 24.dp else 16.dp, spec, label = "thumbSize")
    val thumbX by animateDpAsState(if (checked) 24.dp else 8.dp, spec, label = "thumbX")
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(width = 52.dp, height = 32.dp)
            .clip(CircleShape)
            .background(if (checked) primary else MaterialTheme.colorScheme.surfaceVariant)
            .border(2.dp, if (checked) primary else MaterialTheme.tensaiTints.outlineStrong, CircleShape),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = thumbX)
                .size(thumbSize)
                .background(
                    if (checked) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurfaceVariant,
                    CircleShape,
                ),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StyleChips(style: ResponseStyle?, onPick: (ResponseStyle) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ResponseStyle.entries.forEach { s ->
            TensaiChip(
                label = stringResource(
                    when (s) {
                        ResponseStyle.Precise -> R.string.chat_settings_style_precise
                        ResponseStyle.Balanced -> R.string.chat_settings_style_balanced
                        ResponseStyle.Creative -> R.string.chat_settings_style_creative
                    },
                ),
                selected = style == s,
                onClick = { onPick(s) },
            )
        }
        if (style == null) {
            TensaiChip(
                label = stringResource(R.string.chat_settings_style_custom),
                selected = true,
                onClick = null,
                icon = Icons.Outlined.Tune,
            )
        }
    }
}

/** Expandable header of the Sampling card: title, mono summary, chevron. */
@Composable
private fun SamplingHeader(config: SamplingConfig, expanded: Boolean, reducedMotion: Boolean, onToggle: () -> Unit) {
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, motionSpec(reducedMotion), label = "chevron")
    val spoken = "${stringResource(R.string.chat_settings_temperature)} ${formatFloatField(config.temperature)}, " +
        "Top P ${formatFloatField(config.topP)}, Min P ${formatFloatField(config.minP)}, Top K ${config.topK}, " +
        "${stringResource(R.string.chat_settings_repeat_penalty)} ${formatFloatField(config.repeatPenalty)}"
    val state = stringResource(
        if (expanded) R.string.chat_settings_sampling_expanded else R.string.chat_settings_sampling_collapsed,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = state }
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.chat_settings_sampling),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = samplingSummary(config),
            fontFamily = TensaiMono,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { contentDescription = spoken },
        )
        Icon(
            imageVector = Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp).rotate(rotation),
        )
    }
}

/**
 * One sampling parameter: label and hint, an editable value pill and a slider. The displayed value is
 * local so a drag is smooth, and follows [value] again whenever the committed value changes (a preset
 * tap, Reset).
 */
@Composable
private fun ParamRow(
    label: String,
    hint: String,
    value: Float,
    min: Float,
    max: Float,
    step: Float,
    decimals: Int,
    onCommit: (Float) -> Unit,
) {
    var position by remember(value) { mutableFloatStateOf(value) }
    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = hint,
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            ValuePill(
                description = hint,
                value = position,
                min = min,
                max = max,
                decimals = decimals,
                onCommit = {
                    position = it
                    onCommit(it)
                },
            )
        }
        SheetSlider(
            label = label,
            value = position.coerceIn(min, max),
            min = min,
            max = max,
            step = step,
            decimals = decimals,
            onChange = { position = it },
            onCommit = {
                position = it
                onCommit(it)
            },
            onCancel = { position = value },
        )
    }
}

/**
 * Mono pill holding a number you can type. Edits stay a draft until focus leaves, Done is pressed or
 * the pill leaves composition, then [commitPillText] clamps the figure into range; text that is not
 * a number reverts. The whole 48dp band focuses the field, not just the 64x28 pill drawn in it.
 *
 * [description] names the field for TalkBack; it is the row's hint rather than its label, since the
 * slider next to it already carries the label.
 */
@Composable
private fun ValuePill(
    description: String,
    value: Float,
    min: Float,
    max: Float,
    decimals: Int,
    onCommit: (Float) -> Unit,
) {
    var draft by remember { mutableStateOf<String?>(null) }
    var focused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    val currentOnCommit by rememberUpdatedState(onCommit)
    val latestDraft by rememberUpdatedState(draft)

    fun finish() {
        latestDraft?.let { text -> commitPillText(text, min, max, decimals)?.let(currentOnCommit) }
        draft = null
    }
    val currentFinish by rememberUpdatedState(::finish)
    // A pending edit must survive its row being collapsed or the sheet being dismissed.
    DisposableEffect(Unit) { onDispose { currentFinish() } }

    val textStyle = TextStyle(
        fontFamily = TensaiMono,
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Center,
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clickable(interactionSource = null, indication = null) { focusRequester.requestFocus() },
    ) {
        BasicTextField(
            value = draft ?: formatFloatField(value, decimals),
            onValueChange = { draft = it },
            singleLine = true,
            textStyle = textStyle,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(
                keyboardType = if (decimals == 0) KeyboardType.Number else KeyboardType.Decimal,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
            decorationBox = { inner -> Box(contentAlignment = Alignment.Center) { inner() } },
            modifier = Modifier
                .size(width = 64.dp, height = 28.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                .border(
                    1.dp,
                    if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                    CircleShape,
                )
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .focusRequester(focusRequester)
                .onFocusChanged {
                    if (focused && !it.isFocused) finish()
                    focused = it.isFocused
                }
                .semantics { contentDescription = description },
        )
    }
}

/**
 * Thin kit slider: 4dp track, 4x20 handle bar, 48dp touch band. Exposes range info and `setProgress`
 * so TalkBack's adjust gestures work: each adjustment is a full change plus commit.
 *
 * Touch rules: a tap sets and commits only on a real release; a horizontal drag changes live and
 * commits when it completes. A gesture that is cancelled (a vertical scroll that started on the
 * band) commits nothing and [onCancel] puts the shown value back.
 */
@Composable
private fun SheetSlider(
    label: String,
    value: Float,
    min: Float,
    max: Float,
    step: Float,
    decimals: Int,
    onChange: (Float) -> Unit,
    onCommit: (Float) -> Unit,
    onCancel: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.surfaceVariant
    val currentOnChange by rememberUpdatedState(onChange)
    val currentOnCommit by rememberUpdatedState(onCommit)
    val currentOnCancel by rememberUpdatedState(onCancel)
    val valueText = formatFloatField(value, decimals)
    val steps = (((max - min) / step).roundToInt() - 1).coerceAtLeast(0)

    fun valueAt(x: Float, width: Int): Float {
        val t = if (width > 0) (x / width).coerceIn(0f, 1f) else 0f
        return snapToStep(min + t * (max - min), min, max, step)
    }

    var dragValue by remember { mutableFloatStateOf(value) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .pointerInput(min, max, step) {
                detectTapGestures(onTap = { offset -> currentOnCommit(valueAt(offset.x, size.width)) })
            }
            .pointerInput(min, max, step) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragValue = valueAt(offset.x, size.width)
                        currentOnChange(dragValue)
                    },
                    onHorizontalDrag = { change, _ ->
                        dragValue = valueAt(change.position.x, size.width)
                        currentOnChange(dragValue)
                        change.consume()
                    },
                    onDragEnd = { currentOnCommit(dragValue) },
                    onDragCancel = { currentOnCancel() },
                )
            }
            .semantics {
                contentDescription = label
                stateDescription = valueText
                progressBarRangeInfo = ProgressBarRangeInfo(value, min..max, steps)
                setProgress { target ->
                    val next = snapToStep(target, min, max, step)
                    currentOnCommit(next)
                    true
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val fraction = if (max > min) ((value - min) / (max - min)).coerceIn(0f, 1f) else 0f
            val x = fraction * (size.width - 4.dp.toPx())
            val cy = size.height / 2
            val track = 4.dp.toPx()
            val radius = CornerRadius(2.dp.toPx())
            val activeWidth = (x - 4.dp.toPx()).coerceAtLeast(0f)
            if (activeWidth > 0f) {
                drawRoundRect(primary, Offset(0f, cy - track / 2), Size(activeWidth, track), radius)
            }
            val inactiveStart = x + 8.dp.toPx()
            if (size.width - inactiveStart > 0f) {
                drawRoundRect(inactive, Offset(inactiveStart, cy - track / 2), Size(size.width - inactiveStart, track), radius)
            }
            drawRoundRect(primary, Offset(x, cy - 10.dp.toPx()), Size(4.dp.toPx(), 20.dp.toPx()), radius)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MaxTokensChips(selected: Int, contextSize: Int?, onPick: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MaxTokenChoices.forEach { n ->
            val fits = maxTokenFits(n, contextSize)
            TensaiChip(
                label = maxTokensLabel(n),
                selected = fits && selected == n,
                onClick = { onPick(n) },
                enabled = fits,
                mono = true,
            )
        }
    }
}

/**
 * "Up to N tokens per reply · context C", N and C in mono; C only when a model is loaded. Shows the
 * real stored value even when no chip matches it or the chip is no longer allowed.
 */
@Composable
private fun MaxTokensCaption(maxTokens: Int, contextSize: Int?) {
    val number = NumberFormat.getIntegerInstance().format(maxTokens)
    val context = contextSize?.let(::maxTokensLabel)
    val text = if (context == null) {
        stringResource(R.string.chat_settings_length_caption, number)
    } else {
        stringResource(R.string.chat_settings_length_caption_context, number, context)
    }
    Text(
        text = buildAnnotatedString {
            var cursor = 0
            for (piece in listOfNotNull(number, context)) {
                val at = text.indexOf(piece, cursor)
                if (at < 0) continue
                append(text.substring(cursor, at))
                withStyle(SpanStyle(fontFamily = TensaiMono)) { append(piece) }
                cursor = at + piece.length
            }
            append(text.substring(cursor))
        },
        fontSize = 12.5.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

/**
 * Last row of the Sampling card: one stop sequence per line, in mono because whitespace is
 * significant here and a proportional font hides it, see [stopSequencesFromText] for what the format
 * can and cannot express. The field sits on surface-variant, 12dp radius.
 */
@Composable
private fun StopSequencesRow(initial: List<String>, onCommit: (List<String>) -> Unit) {
    val stopLabel = stringResource(R.string.chat_settings_stop_sequences)
    var text by remember { mutableStateOf(stopSequencesToText(initial)) }
    val textStyle = TextStyle(
        fontFamily = TensaiMono,
        fontSize = 13.sp,
        lineHeight = 20.sp,
        color = MaterialTheme.colorScheme.onBackground,
    )
    // Lines past the cap are dropped on save: the hint turns to the error colour to say so.
    val overCap = text.split('\n').count { it.isNotEmpty() } > SamplingRanges.MaxStopSequences
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
    ) {
        Column {
            Text(
                text = stringResource(R.string.chat_settings_stop_sequences),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.chat_settings_stop_sequences_help, SamplingRanges.MaxStopSequences),
                fontSize = 12.5.sp,
                color = if (overCap) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        BasicTextField(
            value = text,
            onValueChange = {
                text = it
                onCommit(stopSequencesFromText(it))
            },
            minLines = 3,
            textStyle = textStyle,
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) {
                        Text(
                            text = "</s>\nUser:",
                            style = textStyle.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                        )
                    }
                    inner()
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .bringIntoViewOnFocus()
                .semantics { contentDescription = stopLabel },
        )
    }
}
