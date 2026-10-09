package io.github.hokanosekai.tensai.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.hokanosekai.tensai.R
import io.github.hokanosekai.tensai.ui.theme.TensaiShapes

/**
 * One suggestion chip. Label and prompt are the same string: tapping a chip fills the input pill
 * with its text rather than sending it, so the user can edit before committing.
 *
 * The design allows **four** chips; this enum is the whole set, shared by Home and the empty chat.
 * Adding a fifth means dropping one, not widening the row.
 */
private enum class Suggestion(@StringRes val labelRes: Int) {
    SummarizeLog(R.string.home_suggestion_summarize_log),
    ExplainError(R.string.home_suggestion_explain_error),
    DraftReply(R.string.home_suggestion_draft_reply),
    CommitMessage(R.string.home_suggestion_commit_message),
}

/**
 * The suggestion chips, [onPick] getting the chip's text. One line that scrolls horizontally by
 * default (Home): four chips do not fit across a phone, and a single line keeps the screen's
 * vertical rhythm fixed whatever the translation's word lengths turn out to be (TEN-41). With
 * [wrap] they flow onto as many lines as they need (the empty chat, which has the height).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SuggestionChips(onPick: (String) -> Unit, modifier: Modifier = Modifier, wrap: Boolean = false) {
    val chips: @Composable () -> Unit = {
        Suggestion.entries.forEach { suggestion ->
            val label = stringResource(suggestion.labelRes)
            Surface(
                onClick = { onPick(label) },
                shape = TensaiShapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
    if (wrap) {
        FlowRow(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { chips() }
    } else {
        Row(
            modifier = modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) { chips() }
    }
}
