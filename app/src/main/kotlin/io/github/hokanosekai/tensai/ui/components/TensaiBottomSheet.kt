package io.github.hokanosekai.tensai.ui.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
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
    content: @Composable ColumnScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
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
        content()
    }
}
