package io.github.hokanosekai.tensai.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

/**
 * The one bottom sheet of the app, with the Claude app's behaviour:
 * - opens fully expanded,
 * - a first swipe down settles at half the screen height (Material's PartiallyExpanded anchor),
 * - a second swipe dismisses,
 * - the content scrolls first (ModalBottomSheet's nested scroll hands the drag to the sheet only
 *   once the content is at its top).
 *
 * Material3 only creates the PartiallyExpanded anchor when the content is taller than half the
 * window, so a short sheet opens at its own height and one swipe dismisses it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TensaiBottomSheet(
    onDismissRequest: () -> Unit,
    shape: Shape,
    containerColor: Color,
    scrimColor: Color,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    fullHeight: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val scope = rememberCoroutineScope()
    // Whether the sheet last came to rest fully open. Read when the content changes size: Material
    // rebuilds the anchors then and always picks PartiallyExpanded when there is one, which dropped
    // the conversation settings to half height the moment a section was unfolded.
    var restedExpanded by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        shape = shape,
        containerColor = containerColor,
        tonalElevation = 0.dp,
        scrimColor = scrimColor,
        dragHandle = dragHandle,
    ) {
        // ModalBottomSheet's own show() targets PartiallyExpanded for tall content. Expanding
        // before it starts does nothing (the sheet has no anchors yet and show() then wins), so
        // wait for show() to set its target, then retarget: the running animation is interrupted
        // and carries on up to Expanded in one movement.
        LaunchedEffect(sheetState) {
            snapshotFlow { sheetState.targetValue }.first { it != SheetValue.Hidden }
            if (sheetState.targetValue != SheetValue.Expanded) sheetState.expand()
        }
        LaunchedEffect(sheetState) {
            snapshotFlow { sheetState.currentValue to sheetState.targetValue }
                .collect { (current, target) -> if (current == target) restedExpanded = current == SheetValue.Expanded }
        }
        // fullHeight: open means up to the top of the screen, as in the Claude app, with the
        // content's own scroll inside; without it a sheet stops at its content's height.
        // The sheet's window is edge to edge, so filling it would slide the drag handle under the
        // status bar icons: the full height stops one status bar plus a small gap short of it.
        val density = LocalDensity.current
        val topReserve = WindowInsets.statusBars.getTop(density) + with(density) { 8.dp.roundToPx() }
        Column(
            modifier = (if (fullHeight) Modifier.fullHeightBelow(topReserve) else Modifier)
                .onSizeChanged { if (restedExpanded) scope.launch { sheetState.expand() } },
            content = content,
        )
    }
}

/** All the height the sheet offers, minus [reservePx] kept free above it. */
private fun Modifier.fullHeightBelow(reservePx: Int) = layout { measurable, constraints ->
    val height = (constraints.maxHeight - reservePx).coerceAtLeast(constraints.minHeight)
    val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
    layout(placeable.width, height) { placeable.place(0, 0) }
}
