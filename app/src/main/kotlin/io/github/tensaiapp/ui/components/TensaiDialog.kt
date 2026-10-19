package io.github.tensaiapp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tensaiapp.ui.theme.TensaiShapes

/** One text pill of a [TensaiDialog]. [destructive] colors it with the error role. */
data class TensaiDialogAction(
    val text: String,
    val onClick: () -> Unit,
    val destructive: Boolean = false,
)

/**
 * The kit's dialog: radius 28 on the surface, a leading [icon] tinted [iconTint] (the severity),
 * centered 20/600 [title], centered 14/20 secondary [body], and right-aligned text pill
 * [actions] (they wrap onto a second line rather than clip, under large fonts or three actions).
 * Each action keeps the 48dp touch target [TensaiButton] gives it. [content] goes under the body (a
 * checkbox, a pair of figures), 12dp below it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TensaiDialog(
    onDismissRequest: () -> Unit,
    icon: ImageVector,
    iconTint: Color,
    title: String,
    body: String,
    actions: List<TensaiDialogAction>,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        shape = TensaiShapes.extraLarge,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp)) },
        iconContentColor = iconTint,
        title = {
            Text(
                text = title,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().semantics { heading() },
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = body,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
                content?.invoke(this)
            }
        },
        confirmButton = {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
            ) {
                actions.forEach { action ->
                    TensaiButton(
                        text = action.text,
                        onClick = action.onClick,
                        variant = TensaiButtonVariant.Text,
                        size = TensaiButtonSize.Sm,
                        contentColor = if (action.destructive) MaterialTheme.colorScheme.error else null,
                    )
                }
            }
        },
    )
}
