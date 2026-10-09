package io.github.hokanosekai.tensai.ui.screens.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.hokanosekai.tensai.R
import io.github.hokanosekai.tensai.ui.components.KanjiGlyph
import io.github.hokanosekai.tensai.ui.components.TensaiMonoBadge
import io.github.hokanosekai.tensai.ui.screens.chat.SheetHandleHeight
import io.github.hokanosekai.tensai.ui.screens.chat.SheetShape
import io.github.hokanosekai.tensai.ui.screens.chat.drawTopHairline
import io.github.hokanosekai.tensai.ui.theme.TensaiMono
import io.github.hokanosekai.tensai.ui.theme.tensaiTints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Open-source licenses (TEN-120, re-skinned in TEN-125): an intro, then one outlined card per
 * [LicenseGroup] ([groupLicenses]: what tensai ships, then the Maven report), then the total. A tap
 * opens the license in a bottom sheet.
 *
 * Lazy: the Maven side is ~90 rows, each a lazy item carrying its slice of the card.
 */
@Composable
fun LicensesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // 60 KB of JSON: parsed off the main thread, the hand list shows meanwhile.
    val loaded by produceState<List<GeneratedLicense>?>(initialValue = null) {
        value = withContext(Dispatchers.Default) { loadGeneratedLicenses(context) }
    }
    val groups = remember(loaded) { groupLicenses(ThirdPartyLicenses, loaded.orEmpty()) }
    val total = remember(groups) { groups.sumOf { it.second.size } }
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
    ) {
        item(key = "intro") { Intro() }
        groups.forEach { (group, rows) ->
            item(key = "eyebrow:$group") { SectionLabel(stringResource(group.label())) }
            cardRows(rows) { openKey = it.key }
        }
        item(key = "total") {
            Text(
                text = stringResource(R.string.licenses_total, total),
                fontFamily = TensaiMono,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            )
        }
    }

    val open = openKey?.let { key -> groups.firstNotNullOfOrNull { g -> g.second.firstOrNull { it.key == key } } }
    if (open != null) LicenseSheet(open, onDismiss = { openKey = null })
    // A saved key the lists no longer hold (restored after an update): forget it once both have loaded.
    LaunchedEffect(loaded, openKey) {
        if (loaded != null && openKey != null && open == null) openKey = null
    }
}

private fun LicenseGroup.label(): Int = when (this) {
    LicenseGroup.Inference -> R.string.licenses_group_inference
    LicenseGroup.Fonts -> R.string.licenses_group_fonts
    LicenseGroup.App -> R.string.licenses_group_app
    LicenseGroup.AndroidX -> R.string.licenses_group_androidx
}

@Composable
private fun Intro() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 10.dp),
    ) {
        KanjiGlyph(size = 36.dp)
        Text(
            text = stringResource(R.string.licenses_intro),
            fontSize = 14.sp,
            lineHeight = 20.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The rows of one card as separate lazy items: each carries its slice of the card's outline (20dp
 * corners on the first and last only), and every row after the first sits 1dp over the previous
 * one so the two hairlines become one divider.
 */
private fun LazyListScope.cardRows(
    rows: List<LicenseRow>,
    onOpen: (LicenseRow) -> Unit,
) {
    itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
        val first = index == 0
        val last = index == rows.lastIndex
        val shape = RoundedCornerShape(
            topStart = if (first) 20.dp else 0.dp,
            topEnd = if (first) 20.dp else 0.dp,
            bottomStart = if (last) 20.dp else 0.dp,
            bottomEnd = if (last) 20.dp else 0.dp,
        )
        Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.tensaiTints.outline),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (first) Modifier else Modifier.overlapPrevious()),
        ) {
            LicenseItem(row, onClick = { onOpen(row) })
        }
    }
}

private fun Modifier.overlapPrevious(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val overlap = 1.dp.roundToPx()
    layout(placeable.width, placeable.height - overlap) { placeable.place(0, -overlap) }
}

/** A row of a card, 48dp at least: name, author on one line, license badge, chevron. */
@Composable
private fun LicenseItem(row: LicenseRow, onClick: () -> Unit) {
    val description = listOf(row.name, row.author, row.license).filter { it.isNotBlank() }.joinToString(", ")
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = row.author,
                fontSize = 12.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (row.license.isNotBlank()) TensaiMonoBadge(row.license)
        Chevron()
    }
}

/**
 * The license of one row: name and author, close, license badge and project link, then the
 * copyright line and the full text in mono. The text is read off the main thread. Same hairline
 * and status-bar cap as the chat settings sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LicenseSheet(row: LicenseRow, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = SheetShape,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        scrimColor = MaterialTheme.tensaiTints.scrim,
        dragHandle = {
            val hairline = MaterialTheme.tensaiTints.outline
            Box(
                contentAlignment = Alignment.TopCenter,
                modifier = Modifier.fillMaxWidth().drawBehind { drawTopHairline(hairline) },
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 10.dp, bottom = 2.dp)
                        .size(width = 32.dp, height = 4.dp)
                        .background(MaterialTheme.tensaiTints.outlineStrong, RoundedCornerShape(2.dp)),
                )
            }
        },
    ) {
        // remember + LaunchedEffect rather than a keyed produceState: lint's
        // ProduceStateDoesNotAssignValue misreads the keyed overloads.
        var body by remember(row.key) { mutableStateOf<String?>(null) }
        LaunchedEffect(row.key) {
            body = withContext(Dispatchers.IO) { runCatching { row.text(context) }.getOrElse { row.fallbackText.ifBlank { row.url } } }
        }
        val copyrightLine = if (row.copyright.isBlank()) "" else stringResource(R.string.licenses_copyright_line, row.copyright)
        val density = LocalDensity.current
        val windowHeight = LocalView.current.rootView.height
        val maxContentHeight = with(density) {
            (windowHeight - WindowInsets.statusBars.getTop(density)).toDp() - SheetHandleHeight
        }
        Column(modifier = Modifier.heightIn(max = maxContentHeight).navigationBarsPadding()) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.name,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.01).em,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (row.author.isNotBlank()) {
                        Text(
                            text = row.author,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.action_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 14.dp),
            ) {
                if (row.license.isNotBlank()) TensaiMonoBadge(row.license)
                licenseLink(row.url)?.let { link -> ProjectLink(row.url, link, modifier = Modifier.weight(1f, fill = false)) }
            }
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            ) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    SelectionContainer {
                        Text(
                            text = licenseBody(copyrightLine, body),
                            fontFamily = TensaiMono,
                            fontSize = 12.sp,
                            lineHeight = 19.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        )
                    }
                }
            }
        }
    }
}

/** The project URL in primary mono with an open-in-new icon; opens the browser, or says there is none. */
@Composable
private fun ProjectLink(url: String, link: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val description = stringResource(R.string.licenses_open_url, url)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button) { openLink(context, link) }
            .semantics { contentDescription = description },
    ) {
        Text(
            text = url,
            fontFamily = TensaiMono,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f, fill = false).clearAndSetSemantics { },
        )
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(15.dp),
        )
    }
}

internal fun openLink(context: Context, link: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.licenses_no_browser, Toast.LENGTH_SHORT).show()
    }
}

/** Copyright line (when the row has one, already formatted), a blank line, then the text; just the line while the text loads. */
private fun licenseBody(copyrightLine: String, text: String?): String = listOfNotNull(
    copyrightLine.takeIf { it.isNotBlank() },
    text,
).joinToString("\n\n")

private fun LicenseRow.text(context: Context): String =
    textRes?.let { context.resources.openRawResource(it).bufferedReader().use { r -> r.readText() } } ?: fallbackText
