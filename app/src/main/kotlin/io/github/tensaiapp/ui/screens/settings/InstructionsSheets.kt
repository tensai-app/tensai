package io.github.tensaiapp.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tensaiapp.R
import io.github.tensaiapp.agent.BasePrompt
import io.github.tensaiapp.data.InstructionsSettings
import io.github.tensaiapp.ui.components.TensaiBottomSheet
import io.github.tensaiapp.ui.screens.chat.DebouncedWrite
import io.github.tensaiapp.ui.theme.TensaiMono
import io.github.tensaiapp.ui.theme.TensaiShapes
import io.github.tensaiapp.ui.theme.tensaiTints
import java.time.LocalDate

/**
 * The base prompt, read-only: the text with every tool line in it ([BasePrompt.ToolsNamed]), under a
 * caption that says which lines a chat actually gets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BasePromptSheet(onDismiss: () -> Unit) {
    TensaiBottomSheet(
        onDismissRequest = onDismiss,
        shape = TensaiShapes.extraLarge.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)),
        containerColor = MaterialTheme.colorScheme.surface,
        scrimColor = MaterialTheme.tensaiTints.scrim,
        fullHeight = false,
    ) {
        val text = remember { BasePrompt.render(BasePrompt.ToolsNamed, LocalDate.now()) }
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_base_prompt),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
            )
            Text(
                text = stringResource(R.string.settings_base_prompt_body, BasePrompt.VERSION),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer {
                Text(
                    text = text,
                    fontFamily = TensaiMono,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
                        .padding(16.dp),
                )
            }
        }
    }
}

/**
 * "Your instructions": free text applied to every chat, see [InstructionsSettings]. Seeded once from
 * [initial] and written through [onChange] after a pause, like the chat sheet's own instructions.
 * Typing stops at [InstructionsSettings.MAX_CHARS]; the counter shows the estimated tokens against
 * [InstructionsSettings.MAX_TOKENS].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GlobalInstructionsSheet(initial: String, onChange: (String) -> Unit, onDismiss: () -> Unit) {
    TensaiBottomSheet(
        onDismissRequest = onDismiss,
        shape = TensaiShapes.extraLarge.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp)),
        containerColor = MaterialTheme.colorScheme.surface,
        scrimColor = MaterialTheme.tensaiTints.scrim,
        fullHeight = false,
    ) {
        var draft by remember { mutableStateOf(initial) }
        var focused by remember { mutableStateOf(false) }
        DebouncedWrite(value = draft, resetKey = Unit, write = onChange)
        val secondary = MaterialTheme.colorScheme.onSurfaceVariant
        val primary = MaterialTheme.colorScheme.primary
        val textStyle = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, color = MaterialTheme.colorScheme.onSurface)
        val fieldLabel = stringResource(R.string.settings_global_instructions)

        Column(
            modifier = Modifier
                .imePadding()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
        ) {
            Text(
                text = fieldLabel,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
            )
            Text(
                text = stringResource(R.string.settings_global_instructions_body),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = secondary,
            )
            BasicTextField(
                value = draft,
                onValueChange = { draft = it.take(InstructionsSettings.MAX_CHARS) },
                minLines = 4,
                maxLines = 10,
                textStyle = textStyle,
                cursorBrush = SolidColor(primary),
                decorationBox = { inner ->
                    Box {
                        if (draft.isEmpty()) {
                            Text(
                                text = stringResource(R.string.settings_global_instructions_placeholder),
                                style = textStyle.copy(color = secondary),
                            )
                        }
                        inner()
                    }
                },
                modifier = Modifier
                    .padding(top = 16.dp)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(14.dp))
                    .border(1.dp, if (focused) primary else Color.Transparent, RoundedCornerShape(14.dp))
                    .padding(16.dp)
                    .semantics { contentDescription = fieldLabel }
                    .onFocusChanged { focused = it.isFocused },
            )
            val atCap = draft.length >= InstructionsSettings.MAX_CHARS
            Text(
                text = stringResource(
                    R.string.settings_global_instructions_tokens,
                    InstructionsSettings.estimatedTokens(draft),
                    InstructionsSettings.MAX_TOKENS,
                ),
                fontFamily = TensaiMono,
                fontSize = 11.sp,
                color = if (atCap) MaterialTheme.colorScheme.error else secondary,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 4.dp),
            )
        }
    }
}
