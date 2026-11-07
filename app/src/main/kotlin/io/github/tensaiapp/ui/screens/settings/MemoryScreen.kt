package io.github.tensaiapp.ui.screens.settings

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tensaiapp.R
import io.github.tensaiapp.ui.components.TensaiButton
import io.github.tensaiapp.ui.components.TensaiButtonSize
import io.github.tensaiapp.ui.components.TensaiButtonVariant
import io.github.tensaiapp.ui.components.TensaiDialog
import io.github.tensaiapp.ui.components.TensaiDialogAction
import io.github.tensaiapp.ui.components.rememberReducedMotion
import io.github.tensaiapp.ui.screens.chat.SwitchVisual
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Settings > Memory (TEN-176): what tensai remembers about the user, in the same grouped card as
 * Settings. One fact per row with a trailing delete, a note on where facts come from, and "Forget
 * everything" behind a confirmation.
 */
@Composable
fun MemoryScreen(
    modifier: Modifier = Modifier,
    viewModel: MemoryViewModel = remember { MemoryViewModel() },
) {
    val facts by viewModel.facts.collectAsState(initial = null)
    val list = facts
    val useInChats by viewModel.useInChats.collectAsState(initial = null)
    val reducedMotion = rememberReducedMotion()
    var confirmingClear by remember { mutableStateOf(false) }
    // The last fact deleted from the list behind the dialog: nothing left to forget.
    LaunchedEffect(list?.isEmpty()) { if (list?.isEmpty() == true) confirmingClear = false }
    // Read through LocalConfiguration so a locale change recomposes and rebuilds the format.
    val locale = LocalConfiguration.current.locales[0]
    val dates = remember(locale) { SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "MMMd"), locale) }

    if (list != null && list.isEmpty()) {
        Column(
            modifier = modifier.fillMaxSize().padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Bookmark,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp),
            )
            Text(
                text = stringResource(R.string.memory_empty_title),
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = stringResource(R.string.memory_empty_hint),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    } else if (list != null) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp),
        ) {
            // Hidden until the setting is read, so an off switch does not flash on.
            if (useInChats != null) {
                SectionCard {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(value = useInChats == true, role = Role.Switch, onValueChange = { viewModel.setUseInChats(it) })
                            .heightIn(min = 52.dp)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.memory_use_in_chats),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        SwitchVisual(useInChats == true, reducedMotion)
                    }
                }
                Text(
                    text = stringResource(R.string.memory_use_in_chats_note),
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 16.dp),
                )
            }
            SectionCard {
                list.forEachIndexed { index, fact ->
                    if (index > 0) RowDivider()
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = fact.text,
                                fontSize = 15.sp,
                                lineHeight = 21.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = dates.format(Date(fact.createdAt)),
                                fontSize = 12.5.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 1.dp),
                            )
                        }
                        IconButton(onClick = { viewModel.forget(fact.id) }) {
                            Icon(
                                imageVector = Icons.Rounded.Delete,
                                contentDescription = stringResource(R.string.memory_forget_fact, fact.text),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Text(
                text = stringResource(R.string.memory_note),
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp),
            )
            TensaiButton(
                text = stringResource(R.string.memory_forget_all),
                onClick = { confirmingClear = true },
                variant = TensaiButtonVariant.Text,
                size = TensaiButtonSize.Sm,
                contentColor = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 16.dp),
            )
        }
    }

    if (confirmingClear && list != null) {
        TensaiDialog(
            onDismissRequest = { confirmingClear = false },
            icon = Icons.Rounded.Delete,
            iconTint = MaterialTheme.colorScheme.error,
            title = stringResource(R.string.memory_forget_all_title),
            body = pluralStringResource(R.plurals.memory_forget_all_body, list.size, list.size),
            actions = listOf(
                TensaiDialogAction(stringResource(R.string.settings_storage_clear_cancel), onClick = { confirmingClear = false }),
                TensaiDialogAction(
                    text = stringResource(R.string.memory_forget_all_confirm),
                    onClick = {
                        confirmingClear = false
                        viewModel.forgetEverything()
                    },
                    destructive = true,
                ),
            ),
        )
    }
}
