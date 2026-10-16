package io.github.hokanosekai.tensai.ui.screens.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.hokanosekai.tensai.R
import io.github.hokanosekai.tensai.ui.components.TensaiButton
import io.github.hokanosekai.tensai.ui.components.TensaiButtonSize
import io.github.hokanosekai.tensai.ui.components.TensaiButtonVariant
import io.github.hokanosekai.tensai.ui.components.rememberReducedMotion
import io.github.hokanosekai.tensai.ui.theme.TensaiMono
import io.github.hokanosekai.tensai.ui.theme.TensaiShapes
import io.github.hokanosekai.tensai.ui.theme.TensaiMotion
import io.github.hokanosekai.tensai.ui.theme.tensaiTints
import io.github.hokanosekai.tensai.ui.tools.ToolUiCatalog
import io.github.hokanosekai.tensai.ui.tools.resolve

/**
 * The inline "tensai wants to ..." card of a call that sends or deletes (TEN-174). Pending, it names the
 * action, lists the arguments and asks Deny / Allow; answered, it folds into one row, "Allowed" or "Denied".
 * Phone intents never show it (the target app confirms), except `open_url`: the browser acts on open.
 *
 * @param onAnswer `true` for Allow, `false` for Deny.
 */
@Composable
fun ToolConfirmCard(item: ConfirmationItem, onAnswer: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val action = ToolUiCatalog.confirmLabel(item.call.tool, item.call.argsJson).resolve()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val reducedMotion = rememberReducedMotion()
    Surface(
        shape = TensaiShapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.tensaiTints.outline),
        modifier = modifier
            // A card that appears is announced: the turn is waiting on the user.
            .then(if (item.decision == ConfirmDecision.Pending) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier)
            .fillMaxWidth()
            .animateContentSize(if (reducedMotion) snap() else tween(TensaiMotion.DurBase)),
    ) {
        if (item.decision == ConfirmDecision.Pending) {
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 10.dp)) {
                Text(
                    text = stringResource(R.string.tool_confirm_eyebrow),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.04.em,
                    color = muted,
                )
                Text(
                    text = action,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.01).em,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    for (input in ToolUiCatalog.confirmInputs(item.call.tool, item.call.argsJson)) {
                        HorizontalDivider(color = MaterialTheme.tensaiTints.outline)
                        Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(vertical = 8.dp)) {
                            Text(
                                text = input.key,
                                fontFamily = TensaiMono,
                                fontSize = 12.sp,
                                color = muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.width(52.dp),
                            )
                            Text(
                                text = input.value,
                                fontSize = 14.sp,
                                fontFamily = if (input.mono) TensaiMono else null,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                ) {
                    TensaiButton(
                        text = stringResource(R.string.tool_confirm_deny),
                        onClick = { onAnswer(false) },
                        variant = TensaiButtonVariant.Text,
                        size = TensaiButtonSize.Sm,
                    )
                    TensaiButton(
                        text = stringResource(R.string.tool_confirm_allow),
                        onClick = { onAnswer(true) },
                        variant = TensaiButtonVariant.Tonal,
                        size = TensaiButtonSize.Sm,
                    )
                }
            }
        } else {
            val allowed = item.decision == ConfirmDecision.Allowed
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Icon(
                    imageVector = if (allowed) Icons.Rounded.Check else Icons.Rounded.Block,
                    contentDescription = null,
                    tint = muted,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = action,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(
                        when (item.decision) {
                            ConfirmDecision.Allowed -> R.string.tool_confirm_allowed
                            ConfirmDecision.Stopped -> R.string.tool_status_stopped
                            else -> R.string.tool_confirm_denied
                        },
                    ),
                    fontSize = 14.sp,
                    color = muted,
                )
            }
        }
    }
}
