package io.github.hokanosekai.tensai.ui.screens.models

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
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
import io.github.hokanosekai.tensai.ui.components.TensaiChip
import io.github.hokanosekai.tensai.ui.components.TensaiMonoBadge
import io.github.hokanosekai.tensai.ui.theme.TensaiShapes
import io.github.hokanosekai.tensai.ui.theme.tensaiTints

/** Cards shown as skeletons while the first search is in flight, as in the kit's loading state. */
private const val SKELETON_COUNT = 3

/**
 * The Discover tab: the search field, the filter chips (Fits my phone, Tools, Vision) and the sort
 * menu, then either the home ("Recommended for your device" from the embedded catalog, offline, and
 * "Popular on Hugging Face") or the results of the search. Cards are skeletons until their detail
 * arrives; the next page loads when the list reaches its end. Offline, rate limit and error states
 * replace the body.
 *
 * A Hugging Face link pasted in the field opens its repo ([onOpenRepo]); any other URL shows an
 * inline error and nothing is requested (see [DiscoverViewModel]).
 *
 * @param focusSearch the search field should take focus ("From Hugging Face" in the add sheet);
 *   [onFocusSearchHandled] clears it once done, so coming back to the tab later does not refocus.
 * @param onOpenRepo a card was tapped or a link pasted: repo path, and the file name to preselect
 *   (a link to a file) or null.
 */
@Composable
internal fun DiscoverPane(
    viewModel: DiscoverViewModel,
    focusSearch: Boolean,
    onFocusSearchHandled: () -> Unit,
    onOpenRepo: (String, String?) -> Unit,
    onDeviceRepos: Set<String>,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()

    // The field's text is held here (a text field wants its value updated in the same frame), the
    // state holder gets every change. Seeded from it so a tab switch keeps what was typed; after a
    // rotation the saved text is pushed back into the fresh state holder.
    var query by rememberSaveable { mutableStateOf(viewModel.state.value.query) }
    LaunchedEffect(viewModel) {
        viewModel.start()
        if (query != viewModel.state.value.query) viewModel.onQueryChange(query)
    }
    // A pasted repo link: taken once from the state, the field is emptied so back lands on the home.
    val pendingOpen = state.pendingOpen
    LaunchedEffect(pendingOpen) {
        if (pendingOpen != null) {
            viewModel.consumeOpen()
            query = ""
            onOpenRepo(pendingOpen.id, pendingOpen.file)
        }
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusSearch) {
        if (focusSearch) {
            focusRequester.requestFocus()
            onFocusSearchHandled()
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.padding(start = 20.dp, top = 12.dp, end = 20.dp)) {
            HfSearchField(
                value = query,
                onValueChange = {
                    query = it
                    viewModel.onQueryChange(it)
                },
                focusRequester = focusRequester,
            )
            if (state.linkError) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .padding(start = 16.dp, top = 8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.discover_link_unsupported),
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
        FilterRow(
            filters = state.filters,
            onFits = viewModel::setFits,
            onTools = viewModel::setTools,
            onVision = viewModel::setVision,
            onSort = viewModel::setSort,
        )
        val results = state.results
        val failure = (state.popular as? FeedState.Failed)?.error
        when {
            results != null -> ResultsBody(
                state = state,
                results = results,
                viewModel = viewModel,
                onOpenRepo = onOpenRepo,
                onDeviceRepos = onDeviceRepos,
                modifier = Modifier.weight(1f),
            )
            failure != null -> FailureBody(failure, onRetry = viewModel::load, modifier = Modifier.weight(1f))
            else -> HomeBody(state, viewModel, onOpenRepo, onDeviceRepos, modifier = Modifier.weight(1f))
        }
    }
}

/** The kit's chip row: Fits my phone, Tools, Vision as toggles, then the sort menu. Scrolls sideways when it must. */
@Composable
private fun FilterRow(
    filters: DiscoverFilters,
    onFits: (Boolean) -> Unit,
    onTools: (Boolean) -> Unit,
    onVision: (Boolean) -> Unit,
    onSort: (DiscoverSort) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp),
    ) {
        TensaiChip(
            label = stringResource(R.string.discover_filter_fits),
            selected = filters.fits,
            icon = if (filters.fits) Icons.Default.Check else null,
            onClick = { onFits(!filters.fits) },
        )
        TensaiChip(
            label = stringResource(R.string.discover_badge_tools),
            selected = filters.tools,
            icon = if (filters.tools) Icons.Default.Check else null,
            onClick = { onTools(!filters.tools) },
        )
        if (SHOW_VISION) {
            TensaiChip(
                label = stringResource(R.string.discover_badge_vision),
                selected = filters.vision,
                icon = if (filters.vision) Icons.Default.Check else null,
                onClick = { onVision(!filters.vision) },
            )
        }
        SortMenu(filters.sort, onSort)
    }
}

/** The sort chip with its dropdown: the label says what is picked, the menu lists the four orders. */
@Composable
private fun SortMenu(sort: DiscoverSort, onSort: (DiscoverSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TensaiChip(
            label = stringResource(sort.labelRes),
            selected = false,
            icon = Icons.Default.SwapVert,
            onClick = { open = true },
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            shape = TensaiShapes.medium,
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, MaterialTheme.tensaiTints.outline),
            modifier = Modifier.widthIn(min = 172.dp),
        ) {
            DiscoverSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(option.labelRes),
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (option == sort) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    trailingIcon = {
                        if (option == sort) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    },
                    onClick = {
                        open = false
                        onSort(option)
                    },
                )
            }
        }
    }
}

private val DiscoverSort.labelRes: Int
    get() = when (this) {
        DiscoverSort.Downloads -> R.string.discover_sort_downloads
        DiscoverSort.Likes -> R.string.discover_sort_likes
        DiscoverSort.Recent -> R.string.discover_sort_recent
        DiscoverSort.Size -> R.string.discover_sort_size
    }

@Composable
private fun HomeBody(
    state: DiscoverState,
    viewModel: DiscoverViewModel,
    onOpenRepo: (String, String?) -> Unit,
    onDeviceRepos: Set<String>,
    modifier: Modifier = Modifier,
) {
    val feed = state.popular
    val loading = feed is FeedState.Loading
    val shown = state.popularShown
    val recommended = state.recommendedShown
    val loadingText = stringResource(R.string.discover_loading)
    // The recommended cards are inserted above the popular header once the device profile is read:
    // LazyColumn keeps the item it showed in place, which would leave the list scrolled under the
    // search field. Items below (popular cards turning from skeleton to card) never move it.
    val listState = rememberLazyListState()
    val hasRecommended = recommended.isNotEmpty()
    LaunchedEffect(hasRecommended) { listState.scrollToItem(0) }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (recommended.isNotEmpty()) {
            item(key = "recommended-header") {
                SectionHeader(
                    title = stringResource(R.string.discover_section_recommended),
                    badge = stringResource(R.string.discover_ram_badge, formatGigabytes(state.deviceRamBytes)),
                )
            }
            items(recommended, key = { "recommended/${it.id}" }) { card ->
                RepoCard(card, onClick = { onOpenRepo(card.id, null) }, onDevice = card.id.lowercase() in onDeviceRepos)
            }
        }
        if (loading || shown.isNotEmpty()) {
            item(key = "popular-header") {
                // Sections sit 24dp apart, the list's own gap is 12dp.
                SectionHeader(
                    title = stringResource(R.string.discover_section_popular),
                    modifier = if (recommended.isNotEmpty()) Modifier.padding(top = 12.dp) else Modifier,
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
                // Keyed on the slot being absent too: a card that gave up on a flaky call is asked again.
                LaunchedEffect(repo.id, state.slots[repo.id] == null) { viewModel.onVisible(repo.id) }
                when (val slot = state.slots[repo.id]) {
                    is CardSlot.Ready -> RepoCard(slot.card, onClick = { onOpenRepo(repo.id, null) }, onDevice = repo.id.lowercase() in onDeviceRepos)
                    else -> SkeletonCard()
                }
            }
            (feed as? FeedState.Loaded)?.let { loaded ->
                item(key = "popular-more") { MoreFooter(loaded, listState, viewModel::loadMore) }
            }
            if (shown.isEmpty() && recommended.isEmpty() && (feed as? FeedState.Loaded)?.let { it.next == null || it.stalled } != false) {
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

/** Results of a text search: skeletons, the cards, "no results", or the failure. */
@Composable
private fun ResultsBody(
    state: DiscoverState,
    results: FeedState,
    viewModel: DiscoverViewModel,
    onOpenRepo: (String, String?) -> Unit,
    onDeviceRepos: Set<String>,
    modifier: Modifier = Modifier,
) {
    when (results) {
        FeedState.Loading -> {
            val searching = stringResource(R.string.discover_searching)
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = modifier
                    .fillMaxSize()
                    .padding(start = 20.dp, end = 20.dp, top = 4.dp)
                    .semantics(mergeDescendants = true) { contentDescription = searching },
            ) {
                repeat(SKELETON_COUNT) { SkeletonCard() }
            }
        }
        is FeedState.Failed -> FailureBody(results.error, onRetry = viewModel::retrySearch, modifier = modifier)
        is FeedState.Loaded -> {
            val shown = state.resultsShown
            val exhausted = results.next == null || results.stalled
            val resultsListState = rememberLazyListState()
            if (shown.isEmpty() && exhausted) {
                Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    DiscoverEmpty(
                        icon = Icons.Default.SearchOff,
                        title = stringResource(R.string.discover_no_results_title),
                        body = stringResource(
                            if (state.filters.fits) R.string.discover_no_results_fits_body else R.string.discover_no_results_body,
                        ),
                        action = if (state.filters.fits) {
                            {
                                TensaiButton(
                                    text = stringResource(R.string.discover_show_all_sizes),
                                    onClick = { viewModel.setFits(false) },
                                    variant = TensaiButtonVariant.Text,
                                )
                            }
                        } else {
                            null
                        },
                    )
                }
            } else {
                LazyColumn(
                    state = resultsListState,
                    modifier = modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "results-header") { SectionHeader(title = stringResource(R.string.discover_section_results)) }
                    items(shown, key = { "result/${it.id}" }) { repo ->
                        // Keyed on the slot being absent too: a card that gave up on a flaky call is asked again.
                LaunchedEffect(repo.id, state.slots[repo.id] == null) { viewModel.onVisible(repo.id) }
                        when (val slot = state.slots[repo.id]) {
                            is CardSlot.Ready -> RepoCard(slot.card, onClick = { onOpenRepo(repo.id, null) }, onDevice = repo.id.lowercase() in onDeviceRepos)
                            else -> SkeletonCard()
                        }
                    }
                    item(key = "results-more") { MoreFooter(results, resultsListState, viewModel::loadMore) }
                }
            }
        }
    }
}

/**
 * The last item of a feed: composed only when the list reaches its end, which is what asks for the
 * next page (once per page fetched, [LaunchedEffect] is keyed on the count). A skeleton while the
 * page is in flight, "Try again" after a failure instead of looping on it.
 *
 * A footer that appears while the user is scrolling is a real scroll to the end ([onLoadMore] with
 * `auto = false`, always honoured). One that appears on its own (short list, cards dropping out under
 * the filters) is automatic paging, which the state holder bounds, see [DiscoverViewModel.loadMore].
 */
@Composable
private fun MoreFooter(feed: FeedState.Loaded, listState: LazyListState, onLoadMore: (auto: Boolean) -> Unit) {
    if (feed.next == null) return
    when {
        feed.moreFailed -> Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TensaiButton(
                text = stringResource(R.string.discover_try_again),
                onClick = { onLoadMore(false) },
                variant = TensaiButtonVariant.Tonal,
                icon = Icons.Default.Refresh,
            )
        }
        feed.loadingMore -> SkeletonCard()
        else -> {
            LaunchedEffect(feed.repos.size) { onLoadMore(!listState.isScrollInProgress) }
            Box(modifier = Modifier.height(1.dp))
        }
    }
}

@Composable
private fun FailureBody(error: HfError, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val (icon, title, body) = when (error) {
        HfError.Offline -> Triple(Icons.Default.CloudOff, R.string.discover_offline_title, R.string.discover_offline_body)
        is HfError.RateLimited -> Triple(Icons.Default.Timer, R.string.discover_rate_limited_title, R.string.discover_rate_limited_body)
        else -> Triple(Icons.Default.ErrorOutline, R.string.discover_error_title, R.string.discover_error_body)
    }
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        DiscoverEmpty(
            icon = icon,
            title = stringResource(title),
            body = stringResource(body),
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
internal fun DiscoverEmpty(
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
