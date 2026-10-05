package io.github.hokanosekai.tensai.ui.screens.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.hokanosekai.tensai.R
import io.github.hokanosekai.tensai.data.chat.SamplingRanges
import io.github.hokanosekai.tensai.engine.SamplingConfig
import io.github.hokanosekai.tensai.ui.theme.TensaiMono

/**
 * Per-conversation sampling settings (TEN-35), as a bottom sheet over the chat.
 *
 * ### Why the sheet keeps its own draft
 *
 * [config]/[systemPrompt] seed a local draft *once*, when the sheet opens, and every edit is
 * committed outward as it happens. The draft is not re-seeded from later emissions of the flows
 * behind those parameters, because those emissions are this sheet's own writes coming back around:
 * re-seeding from them would fight the user's typing (a keystroke, a write, an echo, a cursor jump)
 * and would snap a slider being dragged back to the last committed notch. [onReset] is the one
 * change that comes from outside the draft, so it updates the draft itself as well as calling out.
 *
 * ### What is committed when
 *
 * Sliders commit on release (`onValueChangeFinished`), not on every pixel — a drag is one setting
 * change, not two hundred Room writes. Text fields commit on every keystroke that parses, and a
 * keystroke that doesn't parse (an empty field mid-edit, a value outside its range) commits
 * nothing and leaves the last valid value stored — see [parseIntField].
 *
 * Everything here applies to the **next** generation. Nothing reloads the model, and nothing
 * touches a reply already streaming: the request is built from a fresh read of the conversation row
 * each turn, see [ChatViewModel.generateReply].
 *
 * @param supportsThinking gates the thinking switch, exactly as it gates the chat's own toggle row
 *   — see [ChatViewModel.currentModelSupportsThinking].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatSettingsSheet(
    config: SamplingConfig,
    systemPrompt: String,
    enableThinking: Boolean,
    supportsThinking: Boolean,
    onConfigChange: (SamplingConfig) -> Unit,
    onSystemPromptChange: (String) -> Unit,
    onEnableThinkingChange: (Boolean) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // Deliberately unkeyed — see the KDoc's "why the sheet keeps its own draft".
        var draft by remember { mutableStateOf(config) }
        var promptDraft by remember { mutableStateOf(systemPrompt) }
        // Bumped by "reset", and used below as a `key` to rebuild every field. Reset is the one
        // change that does not come from a field's own editing, so it is the one case where the
        // fields' internal text and slider positions have to be re-seeded — and following the
        // committed value would not do: each field holds its text uncontrolled precisely so that
        // committing a keystroke cannot rewrite what is being typed.
        var resetSeed by remember { mutableStateOf(0) }

        fun commit(next: SamplingConfig) {
            draft = next
            onConfigChange(next)
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_settings_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            OutlinedTextField(
                value = promptDraft,
                onValueChange = {
                    promptDraft = it
                    onSystemPromptChange(it)
                },
                label = { Text(stringResource(R.string.chat_settings_system_prompt)) },
                placeholder = { Text(stringResource(R.string.chat_settings_system_prompt_none)) },
                minLines = 2,
                maxLines = 6,
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )

            if (supportsThinking) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.chat_thinking_toggle),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(checked = enableThinking, onCheckedChange = onEnableThinkingChange)
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            key(resetSeed) {
                FloatSetting(
                    label = stringResource(R.string.chat_settings_temperature),
                    initial = draft.temperature,
                    range = SamplingRanges.Temperature,
                    onCommit = { commit(draft.copy(temperature = it)) },
                )
                FloatSetting(
                    label = "Top P",
                    initial = draft.topP,
                    range = SamplingRanges.TopP,
                    onCommit = { commit(draft.copy(topP = it)) },
                )
                FloatSetting(
                    label = "Min P",
                    initial = draft.minP,
                    range = SamplingRanges.MinP,
                    onCommit = { commit(draft.copy(minP = it)) },
                )
                IntSetting(
                    label = "Top K",
                    initial = draft.topK,
                    range = SamplingRanges.TopK,
                    onCommit = { commit(draft.copy(topK = it)) },
                )
                IntSetting(
                    label = stringResource(R.string.chat_settings_max_tokens),
                    initial = draft.maxTokens,
                    range = SamplingRanges.MaxTokens,
                    onCommit = { commit(draft.copy(maxTokens = it)) },
                )

                StopSequencesField(
                    initial = draft.stopSequences,
                    onCommit = { commit(draft.copy(stopSequences = it)) },
                )
            }

            TextButton(
                onClick = {
                    draft = SamplingConfig()
                    resetSeed++
                    onReset()
                },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                // Named for what it touches: the system prompt above is deliberately not reset,
                // see ChatRepository.resetSampling.
                Text(stringResource(R.string.chat_settings_reset_sampling))
            }
        }
    }
}

/**
 * One slider plus the mono field showing its exact value — the slider is for dialling something in,
 * the field for typing a figure you already know. The field is the reason the slider needs no
 * `steps`: continuous dragging for the feel of it, exact entry when it matters.
 */
@Composable
private fun FloatSetting(
    label: String,
    initial: Float,
    range: ClosedFloatingPointRange<Float>,
    onCommit: (Float) -> Unit,
) {
    // Seeded from [initial] once and owned here afterwards — hence the parameter's name. Following
    // the committed value instead would rewrite the field under the cursor on the very keystroke
    // that committed it, turning "0.7" into "0.70" as it is typed. The slider position is local for
    // the same reason plus one more: a drag has to be smooth, and it only commits on release.
    var position by remember { mutableStateOf(initial) }
    var text by remember { mutableStateOf(formatFloatField(initial)) }

    SettingRow(
        label = label,
        fieldValue = text,
        keyboardType = KeyboardType.Decimal,
        onFieldChange = { typed ->
            text = typed
            parseFloatField(typed, range)?.let {
                position = it
                onCommit(it)
            }
        },
    ) {
        Slider(
            value = position,
            onValueChange = {
                position = it
                text = formatFloatField(it)
            },
            onValueChangeFinished = { onCommit(position) },
            valueRange = range,
        )
    }
}

/** [FloatSetting] for a whole-number setting. */
@Composable
private fun IntSetting(
    label: String,
    initial: Int,
    range: IntRange,
    onCommit: (Int) -> Unit,
) {
    var position by remember { mutableStateOf(initial.toFloat()) }
    var text by remember { mutableStateOf(initial.toString()) }

    SettingRow(
        label = label,
        fieldValue = text,
        keyboardType = KeyboardType.Number,
        onFieldChange = { typed ->
            text = typed
            parseIntField(typed, range)?.let {
                position = it.toFloat()
                onCommit(it)
            }
        },
    ) {
        Slider(
            value = position,
            onValueChange = {
                position = it
                text = it.toInt().toString()
            },
            onValueChangeFinished = { onCommit(position.toInt().coerceIn(range.first, range.last)) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}

/** Shared layout for [FloatSetting]/[IntSetting]: label, mono entry field, and the slider under them. */
@Composable
private fun SettingRow(
    label: String,
    fieldValue: String,
    keyboardType: KeyboardType,
    onFieldChange: (String) -> Unit,
    slider: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = fieldValue,
                onValueChange = onFieldChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = TensaiMono),
                modifier = Modifier.width(112.dp),
            )
        }
        slider()
    }
}

/**
 * One stop sequence per line, in mono because whitespace is significant here and a proportional
 * font hides it — see [stopSequencesFromText] for what the format can and cannot express.
 */
@Composable
private fun StopSequencesField(initial: List<String>, onCommit: (List<String>) -> Unit) {
    var text by remember { mutableStateOf(stopSequencesToText(initial)) }
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onCommit(stopSequencesFromText(it))
        },
        label = { Text(stringResource(R.string.chat_settings_stop_sequences)) },
        supportingText = {
            Text(stringResource(R.string.chat_settings_stop_sequences_help, SamplingRanges.MaxStopSequences))
        },
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = TensaiMono),
        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(vertical = 8.dp),
    )
}
