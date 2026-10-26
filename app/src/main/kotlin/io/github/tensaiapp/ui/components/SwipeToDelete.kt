package io.github.tensaiapp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.tensaiapp.ui.theme.tensaiTints

/** How far, as a share of the row width, the finger must pull the row for a delete. */
private const val DELETE_THRESHOLD = 0.4f

/**
 * A row that deletes on a swipe from end to start (TEN-193): the error-12 underlay with a delete
 * icon and [label] shows behind it while it slides, and [onDelete] runs once the row has settled
 * out with the finger up, after the finger itself pulled it past 40% of the width. Dragging back
 * before letting go, or a short drag however fast, puts it back. The row stays slid out; the caller
 * removes it from the list. The swipe state is not saved, so a row put back under the same key (an
 * undo) comes back settled.
 *
 * [content] must paint an opaque background, or the underlay shows through at rest (TEN-190).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToDelete(
    label: String,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val currentOnDelete by rememberUpdatedState(onDelete)
    val density = LocalDensity.current
    // Plain remember, not rememberSwipeToDismissBoxState: a LazyColumn keeps a removed item's saved
    // state under its key, and the row would come back slid out.
    val state = remember(density) {
        SwipeToDismissBoxState(
            initialValue = SwipeToDismissBoxValue.Settled,
            density = density,
            positionalThreshold = { distance -> distance * DELETE_THRESHOLD },
        )
    }
    // The state can reach EndToStart while the finger still holds the row at the edge, and through
    // M3's velocity threshold (private, so not settable) after a slow short drag: only a settled swipe
    // with no finger down whose drag itself went past the threshold deletes. Anything else that
    // settled out goes back.
    var pressed by remember { mutableStateOf(false) }
    var pulledFarEnough by remember { mutableStateOf(false) }
    val settledOut = !pressed &&
        state.currentValue == SwipeToDismissBoxValue.EndToStart &&
        state.targetValue == SwipeToDismissBoxValue.EndToStart
    LaunchedEffect(settledOut) {
        if (!settledOut) return@LaunchedEffect
        if (pulledFarEnough) currentOnDelete() else state.reset()
    }
    SwipeToDismissBox(
        state = state,
        modifier = modifier.pointerInput(Unit) {
            // Watches, never consumes: the box's own drag sees the same events. The Final pass reads
            // the offset after the drag has applied the event.
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                pressed = true
                pulledFarEnough = false
                do {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    if (-state.requireOffset() >= size.width * DELETE_THRESHOLD) pulledFarEnough = true
                } while (event.changes.any { it.pressed })
                pressed = false
            }
        },
        enableDismissFromStartToEnd = false,
        gesturesEnabled = enabled,
        backgroundContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.tensaiTints.error12)
                    .padding(horizontal = 20.dp),
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Text(label, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
            }
        },
        content = content,
    )
}
