package io.github.tensaiapp.ui.screens.models

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tensaiapp.R
import io.github.tensaiapp.ui.theme.tensaiTints

/** The two panes of the Models screen (TEN-138): the library, and Hugging Face. */
internal enum class ModelsTab(@StringRes val labelRes: Int) {
    OnDevice(R.string.models_tab_device),
    Discover(R.string.models_tab_discover),
}

/**
 * Tab row from the kit (`ModelTabs`): two equal 48dp tabs, 14.5sp semibold, primary text and a
 * 56 x 3dp primary indicator (rounded on top) on the selected one, hairline under the row.
 */
@Composable
internal fun ModelTabs(selected: ModelsTab, onSelect: (ModelsTab) -> Unit, modifier: Modifier = Modifier) {
    val hairline = MaterialTheme.tensaiTints.outline
    Row(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val y = size.height - 0.5.dp.toPx()
                drawLine(hairline, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            .padding(horizontal = 12.dp)
            .selectableGroup(),
    ) {
        ModelsTab.entries.forEach { tab ->
            val on = tab == selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .selectable(selected = on, role = Role.Tab, onClick = { onSelect(tab) }),
            ) {
                Text(
                    text = stringResource(tab.labelRes),
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                if (on) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .width(56.dp)
                            .height(3.dp)
                            .background(
                                MaterialTheme.colorScheme.primary,
                                RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp),
                            ),
                    )
                }
            }
        }
    }
}
