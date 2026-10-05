package io.github.hokanosekai.tensai.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.hokanosekai.tensai.R
import io.github.hokanosekai.tensai.ui.theme.TensaiMono
import io.github.hokanosekai.tensai.ui.theme.TensaiShapes
import io.github.hokanosekai.tensai.ui.theme.TensaiTheme
import io.github.hokanosekai.tensai.ui.theme.ThemeMode

/** Opacity of everything on this screen except the explainer: it all describes a feature that isn't there yet. */
private const val PreviewAlpha = 0.5f

/**
 * Server (TEN-40): static "Coming in V2" preview of the LAN API server, marked "V2" in the drawer.
 *
 * Zero logic on purpose. The rows below the chip show what the screen will hold once the server
 * exists, and none of them reacts to anything: the toggle is disabled, the icons are plain icons
 * rather than buttons, and the whole preview block swallows pointer input before its children see
 * it. The top bar and its back arrow come from `TensaiShell` (detail chrome), like Settings.
 */
@Composable
fun ServerScreen(modifier: Modifier = Modifier) {
    // Centered when it fits, scrollable when it doesn't (landscape, large font scale): a scrolling
    // Column has no height of its own to center in, so it is given the viewport's as a minimum.
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val viewportHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = viewportHeight)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            ServerContent()
        }
    }
}

@Composable
private fun ServerContent() {
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            modifier = Modifier.alpha(PreviewAlpha),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Dns,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(48.dp),
                )
            }
            Text(
                text = stringResource(R.string.server_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 20.dp),
            )
        }

        Text(
            text = stringResource(R.string.server_explainer),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )

        Surface(
            shape = TensaiShapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.padding(top = 16.dp).alpha(PreviewAlpha),
        ) {
            Text(
                text = stringResource(R.string.server_coming_in_v2),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }

        PreviewRows(
            modifier = Modifier
                .padding(top = 32.dp)
                .widthIn(max = 480.dp)
                .fillMaxWidth()
                .alpha(PreviewAlpha),
        )
    }
}

/** The disabled preview: toggle, endpoint, token. Eats every pointer event so nothing below reacts. */
@Composable
private fun PreviewRows(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }
        },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PreviewRow {
            Text(
                text = stringResource(R.string.server_serve_on_lan),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = false,
                onCheckedChange = null,
                enabled = false,
                // The row is already faded to PreviewAlpha; M3's own disabled colors on top of that
                // would leave an outline nobody can see. Keep the enabled "off" look instead.
                colors = SwitchDefaults.colors(
                    disabledUncheckedThumbColor = MaterialTheme.colorScheme.outline,
                    disabledUncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    disabledUncheckedBorderColor = MaterialTheme.colorScheme.outline,
                ),
            )
        }
        PreviewRow {
            Text(
                text = stringResource(R.string.server_endpoint_example),
                fontFamily = TensaiMono,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            PreviewIcon(Icons.Rounded.ContentCopy)
        }
        PreviewRow {
            Text(
                text = stringResource(R.string.server_token_example),
                fontFamily = TensaiMono,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            PreviewIcon(Icons.Rounded.Refresh)
        }
    }
}

/** Same surface as the Settings rows, minus the click. */
@Composable
private fun PreviewRow(content: @Composable RowScope.() -> Unit) {
    Surface(
        shape = TensaiShapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
private fun PreviewIcon(imageVector: ImageVector) {
    Icon(
        imageVector = imageVector,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
    )
}

@Preview(name = "Server - Dark", showBackground = true, backgroundColor = 0xFF0F0E17)
@Composable
private fun ServerScreenPreviewDark() {
    TensaiTheme(themeMode = ThemeMode.Dark) {
        ServerScreen()
    }
}

@Preview(name = "Server - Light", showBackground = true)
@Composable
private fun ServerScreenPreviewLight() {
    TensaiTheme(themeMode = ThemeMode.Light) {
        ServerScreen()
    }
}
