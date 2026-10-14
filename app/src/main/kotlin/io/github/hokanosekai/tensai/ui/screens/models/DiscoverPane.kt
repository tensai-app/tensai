package io.github.hokanosekai.tensai.ui.screens.models

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.hokanosekai.tensai.R

/**
 * The Discover tab (TEN-138 placeholder, filled by TEN-137).
 *
 * @param focusSearch the search field should take focus ("From Hugging Face" in the add sheet);
 *   [onFocusSearchHandled] clears it once done, so coming back to the tab later does not refocus.
 */
@Composable
internal fun DiscoverPane(
    focusSearch: Boolean,
    onFocusSearchHandled: () -> Unit,
    onOpenRepo: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(contentAlignment = Alignment.Center, modifier = modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.models_tab_discover),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
