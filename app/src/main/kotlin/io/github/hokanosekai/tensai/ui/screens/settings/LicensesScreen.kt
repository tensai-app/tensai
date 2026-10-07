package io.github.hokanosekai.tensai.ui.screens.settings

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hokanosekai.tensai.R
import io.github.hokanosekai.tensai.ui.theme.TensaiMono
import io.github.hokanosekai.tensai.ui.theme.tensaiTints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What a row shows, whichever list it comes from. [text] is read only once the row is open. */
private class LicenseRowModel(
    val key: String,
    val name: String,
    val license: String,
    val author: String,
    val copyright: String,
    val url: String,
    val text: (Context) -> String,
)

/**
 * Open-source licenses (TEN-120): "tensai components" (llama.kt, the native code compiled into it,
 * the packaged native libraries, the fonts: [ThirdPartyLicenses]) then "Libraries" (every Maven
 * dependency, from the report generated at build time: [loadGeneratedLicenses]).
 *
 * Lazy: the Maven list is ~90 rows. Each row opens in place on the full license text, in mono.
 */
@Composable
fun LicensesScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // 60 KB of JSON: parsed off the main thread, the components list shows meanwhile.
    val libraries by produceState(initialValue = emptyList<GeneratedLicense>()) {
        value = withContext(Dispatchers.Default) { loadGeneratedLicenses(context) }
    }

    val components = ThirdPartyLicenses.map { entry ->
        LicenseRowModel(
            key = "component:${entry.name}",
            name = entry.name,
            license = entry.license,
            author = entry.author,
            copyright = entry.copyright,
            url = entry.url,
            text = { it.readRaw(entry.textRes) },
        )
    }
    val maven = libraries.map { library ->
        LicenseRowModel(
            key = "library:${library.name}:${library.version}",
            name = library.name,
            license = library.license,
            author = library.author.ifBlank { library.version },
            copyright = library.author,
            url = library.url,
            text = { ctx -> library.textRes?.let(ctx::readRaw) ?: library.licenseUrl },
        )
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
    ) {
        item(key = "eyebrow:components") { SectionLabel(stringResource(R.string.licenses_group_components)) }
        cardRows(components)
        if (maven.isNotEmpty()) {
            item(key = "eyebrow:libraries") { SectionLabel(stringResource(R.string.licenses_group_libraries)) }
            cardRows(maven)
        }
    }
}

/**
 * The rows of one card as separate lazy items: each carries its slice of the card's outline (20dp
 * corners on the first and last only), and every row after the first sits 1dp over the previous
 * one so the two hairlines become one divider.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.cardRows(rows: List<LicenseRowModel>) {
    items(rows, key = { it.key }) { row ->
        val first = row === rows.first()
        val last = row === rows.last()
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
            LicenseItem(row)
        }
    }
}

private fun Modifier.overlapPrevious(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val overlap = 1.dp.roundToPx()
    layout(placeable.width, placeable.height - overlap) { placeable.place(0, -overlap) }
}

/**
 * A row of the licenses card, 48dp at least. Tapping it unfolds the project URL, the copyright line
 * and the license text under it. The text is read on a background dispatcher, and only once the row
 * is open.
 */
@Composable
private fun LicenseItem(row: LicenseRowModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var open by rememberSaveable(row.key) { mutableStateOf(false) }
    val stateText = stringResource(if (open) R.string.settings_licenses_expanded else R.string.settings_licenses_collapsed)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button) { open = !open }
                .semantics { stateDescription = stateText }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.settings_license_line, row.license, row.author),
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Chevron(rotation = if (open) 90f else 0f)
        }
        if (open) {
            val body by produceState<String?>(initialValue = null, row.key) {
                value = withContext(Dispatchers.IO) { row.text(context) }
            }
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp)) {
                if (row.url.isNotBlank()) {
                    SelectionContainer {
                        Text(
                            text = row.url,
                            fontFamily = TensaiMono,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                    }
                }
                if (row.copyright.isNotBlank()) {
                    Text(
                        text = stringResource(R.string.licenses_copyright_line, row.copyright),
                        fontFamily = TensaiMono,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                body?.let {
                    Text(
                        text = it,
                        fontFamily = TensaiMono,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun Context.readRaw(res: Int): String = resources.openRawResource(res).bufferedReader().use { it.readText() }
