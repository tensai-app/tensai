package io.github.tensaiapp.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tensaiapp.R
import io.github.tensaiapp.data.Changelog
import io.github.tensaiapp.data.ChangelogGroup
import io.github.tensaiapp.data.ChangelogRelease
import io.github.tensaiapp.runtime.RuntimeGraph
import io.github.tensaiapp.ui.components.TensaiBadge
import io.github.tensaiapp.ui.components.TensaiBadgeTone
import io.github.tensaiapp.ui.theme.TensaiMono
import io.github.tensaiapp.ui.theme.TensaiShapes
import io.github.tensaiapp.ui.theme.tensaiTints
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What's new (TEN-193): one card per release of [Changelog], newest first. The newest is always
 * open, the older ones fold to header and intro behind a text button. Opening the screen marks the
 * installed version's notes as seen, which clears the dot on Settings' Version row.
 */
@Composable
fun ChangelogScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val installed = RuntimeGraph.appVersionName
    // Tiny asset, but still read off the main thread; null until loaded so the empty state does not flash.
    val releases by produceState<List<ChangelogRelease>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { Changelog.load(context, installed) }
    }
    LaunchedEffect(installed) {
        if (installed.isNotBlank()) RuntimeGraph.changelogRepository.markSeen(installed)
    }

    val loaded = releases ?: return
    if (loaded.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.changelog_empty),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        loaded.forEachIndexed { index, release ->
            ReleaseCard(release, newest = index == 0)
        }
        Text(
            text = stringResource(R.string.changelog_footer),
            fontSize = 12.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
    }
}

@Composable
private fun ReleaseCard(release: ChangelogRelease, newest: Boolean) {
    var expanded by rememberSaveable(release.version) { mutableStateOf(false) }
    val open = newest || expanded
    Surface(
        shape = TensaiShapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.tensaiTints.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "v${release.version}",
                        fontFamily = TensaiMono,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    release.title?.let {
                        Text(
                            text = it,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    if (release.installed) TensaiBadge(stringResource(R.string.changelog_installed), tone = TensaiBadgeTone.Neutral)
                }
                formatDate(release.date)?.let {
                    Text(
                        text = it,
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 14.dp)) {
                if (release.intro.isNotBlank()) {
                    Text(
                        text = release.intro,
                        fontSize = 14.sp,
                        lineHeight = 21.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (open) release.groups.forEach { GroupBlock(it) }
                if (!newest && release.hasGroups) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(stringResource(if (expanded) R.string.changelog_hide else R.string.changelog_show))
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupBlock(group: ChangelogGroup) {
    Column(modifier = Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = group.label,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        group.items.forEach { item ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = "•", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text = item,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** `2026-10-17` as "Oct 17, 2026" in the app's locale; null for a blank date, the raw text for one that is not ISO. */
@Composable
private fun formatDate(iso: String): String? {
    if (iso.isBlank()) return null
    val locale = LocalConfiguration.current.locales[0]
    return remember(iso, locale) {
        runCatching { LocalDate.parse(iso).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)) }
            .getOrDefault(iso)
    }
}
