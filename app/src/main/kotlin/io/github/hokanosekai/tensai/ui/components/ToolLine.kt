package io.github.hokanosekai.tensai.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hokanosekai.tensai.ui.theme.tensaiColors
import io.github.hokanosekai.tensai.ui.tools.LineStatus
import io.github.hokanosekai.tensai.ui.tools.ToolLineSummary
import io.github.hokanosekai.tensai.ui.tools.ToolSpinner
import io.github.hokanosekai.tensai.ui.tools.resolve

/**
 * The line a group of tool calls collapses into, between two assistant paragraphs (TEN-173): a text button
 * with no background, secondary-colored, a chevron at its end. A spinner leads it while a call runs, an
 * error icon on a failure, a block icon on a refusal; those two also tint the label.
 *
 * The button is 44dp tall for the touch target, and a negative vertical margin takes the extra height back so
 * the line does not widen the gap between paragraphs.
 */
@Composable
fun ToolLine(summary: ToolLineSummary, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val color = when (summary.status) {
        LineStatus.Error -> MaterialTheme.colorScheme.error
        LineStatus.Refused -> MaterialTheme.tensaiColors.warning
        LineStatus.Running, LineStatus.Ok, LineStatus.Stopped -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .negativeVerticalMargin(10.dp)
            .heightIn(min = 44.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(end = 6.dp),
    ) {
        when (summary.status) {
            LineStatus.Running -> ToolSpinner()
            LineStatus.Error -> LeadingIcon(Icons.Rounded.Error, color)
            LineStatus.Refused -> LeadingIcon(Icons.Rounded.Block, color)
            LineStatus.Ok, LineStatus.Stopped -> Unit
        }
        Text(
            text = summary.label.resolve(),
            fontSize = 14.sp,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(
            imageVector = Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(18.dp).offset(x = (-2).dp),
        )
    }
}

@Composable
private fun LeadingIcon(icon: ImageVector, tint: Color) {
    Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
}

/** Lays the content out [margin] shorter than it measures at the top and at the bottom, so it overlaps its neighbours' space. */
private fun Modifier.negativeVerticalMargin(margin: Dp) = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val cut = margin.roundToPx()
    layout(placeable.width, (placeable.height - 2 * cut).coerceAtLeast(0)) { placeable.place(0, -cut) }
}
