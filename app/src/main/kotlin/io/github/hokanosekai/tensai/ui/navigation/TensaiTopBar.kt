package io.github.hokanosekai.tensai.ui.navigation

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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.hokanosekai.tensai.R
import io.github.hokanosekai.tensai.ui.theme.TensaiShapes

/**
 * Top bar for [TensaiDestination.Home] and [TensaiDestination.Chat] — hamburger opens the drawer,
 * the centered pill shows the active model (name + effective context size, from `TensaiShell`'s
 * live [io.github.hokanosekai.tensai.runtime.QueueState]) or "no model" if none is loaded. Tap
 * always routes to [TensaiDestination.Models], regardless of whether a model is loaded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TensaiTopBar(
    onMenuClick: () -> Unit,
    onModelPillClick: () -> Unit,
    modifier: Modifier = Modifier,
    modelLabel: String = stringResource(R.string.top_bar_no_model),
) {
    CenterAlignedTopAppBar(
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onMenuClick) {
                Icon(imageVector = Icons.Default.Menu, contentDescription = stringResource(R.string.top_bar_open_menu))
            }
        },
        title = {
            Surface(
                onClick = onModelPillClick,
                shape = TensaiShapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = modelLabel,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        },
        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
        ),
    )
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
