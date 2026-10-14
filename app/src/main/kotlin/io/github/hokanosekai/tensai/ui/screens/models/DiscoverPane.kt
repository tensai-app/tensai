package io.github.hokanosekai.tensai.ui.screens.models

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hokanosekai.tensai.R
import io.github.hokanosekai.tensai.hf.HfError
import io.github.hokanosekai.tensai.ui.components.TensaiButton
import io.github.hokanosekai.tensai.ui.components.TensaiButtonVariant
import io.github.hokanosekai.tensai.ui.components.TensaiMonoBadge

/** Cards shown as skeletons while the first search is in flight, as in the kit's loading state. */
private const val SKELETON_COUNT = 3

/**
 * The Discover tab (TEN-137), no query: the search field, "Recommended for your device" (the
 * embedded catalog, offline), then "Popular on Hugging Face" (most downloaded GGUF repos that fit,
 * each card a skeleton until its detail arrives). Offline and error states replace the body.
 *
 * Typing in the search field does nothing yet, TEN-139 wires it.
 *
 * @param focusSearch the search field should take focus ("From Hugging Face" in the add sheet);
 *   [onFocusSearchHandled] clears it once done, so coming back to the tab later does not refocus.
 * @param onOpenRepo a card was tapped, with its repo path. No-op until TEN-140 adds the repo screen.
 */
@Composable
internal fun DiscoverPane(
    viewModel: DiscoverViewModel,
    focusSearch: Boolean,
    onFocusSearchHandled: () -> Unit,
    onOpenRepo: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(viewModel) { viewModel.start() }

    var query by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusSearch) {
        if (focusSearch) {
            focusRequester.requestFocus()
            onFocusSearchHandled()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        HfSearchField(
            value = query,
            onValueChange = { query = it },
            focusRequester = focusRequester,
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 12.dp),
        )
        val failure = (state.popular as? PopularState.Failed)?.error
        if (failure != null) {
            FailureBody(failure, onRetry = viewModel::load, modifier = Modifier.weight(1f))
        } else {
            HomeBody(state, viewModel::onVisible, onOpenRepo, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun HomeBody(
    state: DiscoverState,
    onCardVisible: (String) -> Unit,
    onOpenRepo: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val loading = state.popular is PopularState.Loading
    val shown = state.popularShown
    val loadingText = stringResource(R.string.discover_loading)
    // The recommended cards are inserted above the popular header once the device profile is read:
    // LazyColumn keeps the item it showed in place, which would leave the list scrolled under the
    // search field. Items below (popular cards turning from skeleton to card) never move it.
    val listState = rememberLazyListState()
    val hasRecommended = state.recommended.isNotEmpty()
    LaunchedEffect(hasRecommended) { listState.scrollToItem(0) }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.recommended.isNotEmpty()) {
            item(key = "recommended-header") {
                SectionHeader(
                    title = stringResource(R.string.discover_section_recommended),
                    badge = stringResource(R.string.discover_ram_badge, formatGigabytes(state.deviceRamBytes)),
                )
            }
            items(state.recommended, key = { "recommended/${it.id}" }) { card ->
                RepoCard(card, onClick = { onOpenRepo(card.id) })
            }
        }
        if (loading || shown.isNotEmpty()) {
            item(key = "popular-header") {
                // Sections sit 24dp apart, the list's own gap is 12dp.
                SectionHeader(
                    title = stringResource(R.string.discover_section_popular),
                    modifier = if (state.recommended.isNotEmpty()) Modifier.padding(top = 12.dp) else Modifier,
                )
            }
        }
        if (loading) {
            item(key = "popular-skeletons") {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = loadingText },
                ) {
                    repeat(SKELETON_COUNT) { SkeletonCard() }
                }
            }
        } else {
            items(shown, key = { "popular/${it.id}" }) { repo ->
                // Composed only when it nears the screen: that is what makes the detail fetch lazy.
                LaunchedEffect(repo.id) { onCardVisible(repo.id) }
                when (val slot = state.slots[repo.id]) {
                    is CardSlot.Ready -> RepoCard(slot.card, onClick = { onOpenRepo(repo.id) })
                    else -> SkeletonCard()
                }
            }
            if (shown.isEmpty() && state.recommended.isEmpty()) {
                item(key = "nothing") {
                    DiscoverEmpty(
                        icon = Icons.Default.Search,
                        title = stringResource(R.string.discover_none_title),
                        body = stringResource(R.string.discover_none_body),
                    )
                }
            }
        }
    }
}

@Composable
private fun FailureBody(error: HfError, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val offline = error == HfError.Offline
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        DiscoverEmpty(
            icon = if (offline) Icons.Default.CloudOff else Icons.Default.ErrorOutline,
            title = stringResource(if (offline) R.string.discover_offline_title else R.string.discover_error_title),
            body = stringResource(if (offline) R.string.discover_offline_body else R.string.discover_error_body),
            action = {
                TensaiButton(
                    text = stringResource(R.string.discover_try_again),
                    onClick = onRetry,
                    variant = TensaiButtonVariant.Tonal,
                    icon = Icons.Default.Refresh,
                )
            },
        )
    }
}

/** The kit's `HFEmpty`: 32dp icon, 17sp title, 14sp body (280dp wide at most), optional action. */
@Composable
private fun DiscoverEmpty(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 48.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(32.dp),
        )
        Text(
            text = title,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = body,
            fontSize = 14.sp,
            lineHeight = 20.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 280.dp),
        )
        if (action != null) Box(modifier = Modifier.padding(top = 8.dp)) { action() }
    }
}

/** Eyebrow from the kit: 12sp bold caps, 0.06em tracking, secondary colour, optional mono badge after it. */
@Composable
private fun SectionHeader(title: String, modifier: Modifier = Modifier, badge: String? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.padding(horizontal = 4.dp),
    ) {
        Text(
            text = title.uppercase(),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.72.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (badge != null) TensaiMonoBadge(badge)
    }
}

/**
 * The kit's `HFSearchField`: 48dp pill on surface-variant, search icon, 15sp text, a primary
 * hairline while focused and a clear button once there is text.
 */
@Composable
private fun HfSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
            .border(1.dp, if (focused) primary else Color.Transparent, CircleShape)
            .padding(start = 16.dp, end = 4.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = secondary,
            modifier = Modifier.size(22.dp),
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = TextStyle(fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused },
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            text = stringResource(R.string.discover_search_hint),
                            fontSize = 15.sp,
                            color = secondary,
                            maxLines = 1,
                        )
                    }
                    inner()
                }
            },
        )
        if (value.isNotEmpty()) {
            IconButton(onClick = { onValueChange("") }) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.discover_search_clear),
                    tint = secondary,
                )
            }
        } else {
            Box(modifier = Modifier.size(12.dp))
        }
    }
}
