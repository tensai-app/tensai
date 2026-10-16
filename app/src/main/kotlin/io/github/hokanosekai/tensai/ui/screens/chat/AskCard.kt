package io.github.hokanosekai.tensai.ui.screens.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.hokanosekai.tensai.R
import io.github.hokanosekai.tensai.agent.AskUserTool
import io.github.hokanosekai.tensai.agent.PendingQuestion
import io.github.hokanosekai.tensai.ui.components.TensaiButton
import io.github.hokanosekai.tensai.ui.components.TensaiButtonVariant
import io.github.hokanosekai.tensai.ui.theme.TensaiMono
import io.github.hokanosekai.tensai.ui.theme.TensaiShapes
import io.github.hokanosekai.tensai.ui.theme.tensaiTints

/** The longest typed answer: a result is cut at a token budget, and a cut one cannot be read back as JSON, see [AskUserTool.MAX_ANSWER_CHARS]. */
private const val MaxAnswerChars = AskUserTool.MAX_ANSWER_CHARS

/**
 * The answer a submit gives (TEN-187): a single choice answers with the typed text alone (a tap on an option
 * answers at once, see [AskCard]); a multiple one with the checked options in their order, then the typed text as
 * one more answer (once, when it equals a checked option). Empty when there is nothing to submit.
 */
internal fun composeAskAnswers(options: List<String>, checked: Set<Int>, typed: String, multiple: Boolean): List<String> {
    val text = typed.trim()
    val picked = if (multiple) options.filterIndexed { i, _ -> i in checked } else emptyList()
    return (picked + listOfNotNull(text.takeIf { it.isNotEmpty() })).distinct()
}

/**
 * The question of `ask_user` (TEN-187), in the place of the input pill while it is open: same inset, same
 * outline and radius, and it grows upward to [MaxHeight], past which the options scroll. A single choice answers
 * on tap, a multiple one with its checkboxes and "Submit"; the last row is always a free answer. The X dismisses.
 *
 * The card is announced as it appears (pane title and polite live region), options are buttons or checkboxes.
 *
 * @param onAnswer the chosen options, then the typed text if any, never empty.
 */
@Composable
fun AskCard(
    question: PendingQuestion,
    onAnswer: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Per question: the card of the next one starts blank.
    var typed by remember(question.callId) { mutableStateOf("") }
    var checked by remember(question.callId) { mutableStateOf(emptySet<Int>()) }
    val answers = composeAskAnswers(question.options, checked, typed, question.multiple)
    val submit = { if (answers.isNotEmpty()) onAnswer(answers) }
    val tints = MaterialTheme.tensaiTints
    Surface(
        shape = TensaiShapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, tints.outlineStrong),
        modifier = modifier
            .fillMaxWidth()
            .padding(3.dp)
            .semantics { paneTitle = question.question },
    ) {
        Column(modifier = Modifier.heightIn(max = MaxHeight)) {
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)) {
                Text(
                    text = question.question,
                    fontSize = 17.sp,
                    lineHeight = 23.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.01).em,
                    color = MaterialTheme.colorScheme.onSurface,
                    // Four lines at most: a long question must not push the options out of the card.
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(top = 11.dp, bottom = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.ask_dismiss),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                question.options.forEachIndexed { index, option ->
                    HorizontalDivider(color = tints.outline)
                    if (question.multiple) {
                        val on = index in checked
                        OptionRow(
                            label = option,
                            modifier = Modifier.toggleable(
                                value = on,
                                role = Role.Checkbox,
                                onValueChange = { checked = if (on) checked - index else checked + index },
                            ),
                        ) { Checkbox(on) }
                    } else {
                        OptionRow(
                            label = option,
                            modifier = Modifier.clickable(role = Role.Button) { onAnswer(listOf(option)) },
                        ) { NumberBadge(index + 1) }
                    }
                }
            }
            HorizontalDivider(color = tints.outline)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    // The send circle sits 8dp inside its 48dp target: 4dp here leaves its visible edge 12dp from the card's.
                    .padding(start = 20.dp, end = 4.dp, top = 6.dp, bottom = if (question.multiple) 6.dp else 12.dp)
                    .heightIn(min = 52.dp),
            ) {
                val hint = stringResource(R.string.ask_own_answer)
                BasicTextField(
                    value = typed,
                    onValueChange = { typed = it.take(MaxAnswerChars) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { if (typed.isNotBlank()) submit() }),
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                    decorationBox = { innerField ->
                        if (typed.isEmpty()) {
                            Text(text = hint, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        innerField()
                    },
                )
                SendAnswerButton(enabled = typed.isNotBlank(), onClick = submit)
            }
            if (question.multiple) {
                Row(
                    horizontalArrangement = Arrangement.End,
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
                ) {
                    TensaiButton(
                        text = stringResource(R.string.ask_submit),
                        onClick = submit,
                        variant = TensaiButtonVariant.Tonal,
                        enabled = answers.isNotEmpty(),
                    )
                }
            }
        }
    }
}

/** One choice: the [leading] mark (number or checkbox), then the label, at least 52dp tall. [modifier] carries the click semantics. */
@Composable
private fun OptionRow(label: String, modifier: Modifier, leading: @Composable () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 20.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(24.dp)) { leading() }
        Text(
            text = label,
            fontSize = 15.sp,
            lineHeight = 21.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).padding(vertical = 14.dp),
        )
    }
}

@Composable
private fun NumberBadge(number: Int) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(24.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)) {
        Text(text = number.toString(), fontFamily = TensaiMono, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun Checkbox(on: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(6.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(20.dp)
            .background(if (on) primary else Color.Transparent, shape)
            .border(2.dp, if (on) primary else MaterialTheme.tensaiTints.outlineStrong, shape),
    ) {
        if (on) Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
    }
}

/** The 32dp round send of the free answer, the pill's own look: primary 24% with text, 8% and a faded arrow without. */
@Composable
private fun SendAnswerButton(enabled: Boolean, onClick: () -> Unit) {
    val tints = MaterialTheme.tensaiTints
    val primary = MaterialTheme.colorScheme.primary
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(48.dp).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(32.dp).background(if (enabled) tints.primary24 else tints.primary08, CircleShape),
        ) {
            Icon(
                imageVector = Icons.Rounded.ArrowUpward,
                contentDescription = stringResource(R.string.ask_send),
                tint = if (enabled) primary else primary.copy(alpha = 0.38f),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Past this the options scroll inside the card. */
private val MaxHeight = 470.dp
