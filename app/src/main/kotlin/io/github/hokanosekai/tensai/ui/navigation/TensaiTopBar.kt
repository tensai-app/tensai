package io.github.hokanosekai.tensai.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hokanosekai.tensai.R
import io.github.hokanosekai.tensai.ui.components.KanjiGlyph

/**
 * Top bar for [TensaiDestination.Chat], hamburger opens the drawer, the title is display only
 * (TEN-129): the conversation's title, left-aligned, or the 天 glyph and "tensai" centered while the chat
 * has no message (TEN-169). The model moved to the input pill's chip.
 *
 * @param title the chat's title, one line, ellipsized; null for an empty chat, which shows the brand instead.
 * @param actions trailing icon buttons: the chat's params button (TEN-115), the ring (once there is a message) and the chat settings button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TensaiTopBar(
    onMenuClick: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val menu: @Composable () -> Unit = {
        IconButton(onClick = onMenuClick) {
            Icon(imageVector = Icons.Default.Menu, contentDescription = stringResource(R.string.top_bar_open_menu))
        }
    }
    if (title == null) {
        CenterAlignedTopAppBar(
            modifier = modifier,
            navigationIcon = menu,
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    KanjiGlyph(size = 24.dp)
                    Text(
                        text = "tensai",
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold),
                    )
                }
            },
            actions = actions,
            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
        )
    } else {
        // Left-aligned next to the menu: the title takes what the actions leave and ellipsizes (TEN-160).
        TopAppBar(
            modifier = modifier,
            navigationIcon = menu,
            title = {
                Text(
                    text = title,
                    modifier = Modifier.padding(start = 6.dp),
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
            ),
        )
    }
}

/**
 * Simple back-arrow top bar for [TensaiDestination.Models], [ModelDetail], [Settings] and [Server].
 *
 * @param actions trailing icon buttons for routes that have one (Models: import), empty otherwise.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TensaiDetailTopBar(
    title: String,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        modifier = modifier,
        title = { Text(text = title, style = MaterialTheme.typography.titleLarge) },
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
    )
}
