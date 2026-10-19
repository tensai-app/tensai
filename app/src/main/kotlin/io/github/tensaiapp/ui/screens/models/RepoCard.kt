package io.github.tensaiapp.ui.screens.models

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tensaiapp.R
import io.github.tensaiapp.ui.components.TensaiBadge
import io.github.tensaiapp.ui.components.TensaiBadgeTone
import io.github.tensaiapp.ui.theme.TensaiMono
import io.github.tensaiapp.ui.theme.TensaiShapes
import io.github.tensaiapp.ui.theme.tensaiTints

/**
 * Discover's repo card on the shared [ModelCardFrame]: name, verdict top-right, "author · 1.7B
 * params", "Q4_K_M · 1.9 GB" of the recommended file, the chips, then the download and like counts
 * (none on the recommended cards). The whole card is the tap target.
 */
@Composable
internal fun RepoCard(card: RepoCardModel, onClick: () -> Unit, modifier: Modifier = Modifier, onDevice: Boolean = false) {
    val params = if (card.paramCount > 0) formatParamCount(card.paramCount) else null
    ModelCardFrame(
        title = card.name,
        status = { StatusIndicator(color = verdictColor(card.verdict), label = compatLabel(card.verdict)) },
        subtitle = cardSubtitle(card.author, params),
        facts = cardFacts(card.quant, card.sizeBytes, backend = null),
        chips = cardChips(onDevice = onDevice, tools = card.tools, thinking = card.thinking, noTemplate = card.noTemplate, gated = card.gated),
        modifier = modifier,
        onClick = onClick,
        extra = if (card.downloads != null && card.likes != null) {
            { RepoStats(downloads = card.downloads, likes = card.likes, modifier = Modifier.padding(top = 12.dp)) }
        } else {
            null
        },
    )
}

/**
 * Whether Discover shows the Vision badge and filter. Off until the app can send images to a model
 * (TEN-12): a badge would promise what it cannot do yet (TEN-156). `hasVision` is still read.
 */
internal const val SHOW_VISION = false

/** Downloads and likes, mono 12sp, each with its 15dp icon. Read as "182400 downloads", not as a glyph and a number. */
@Composable
internal fun RepoStats(downloads: Long, likes: Long, modifier: Modifier = Modifier) {
    val downloadsText = stringResource(R.string.discover_downloads_count, downloads.toString())
    val likesText = stringResource(R.string.discover_likes_count, likes.toString())
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = "$downloadsText, $likesText" },
    ) {
        Stat(Icons.Default.Download, DiscoverLogic.formatCount(downloads))
        Stat(Icons.Default.Favorite, DiscoverLogic.formatCount(likes))
    }
}

@Composable
private fun Stat(icon: ImageVector, text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clearAndSetSemantics {},
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(15.dp),
        )
        Text(
            text = text,
            fontFamily = TensaiMono,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** Placeholder while a card's detail is loading, same bones as [RepoCard]: surface-variant bars on the card. */
@Composable
internal fun SkeletonCard(modifier: Modifier = Modifier) {
    val bar = MaterialTheme.colorScheme.surfaceVariant
    @Composable
    fun Bar(width: Modifier, height: Int, top: Int = 0) {
        Box(modifier = width.padding(top = top.dp).height(height.dp).background(bar, RoundedCornerShape(6.dp)))
    }
    Surface(
        shape = TensaiShapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.tensaiTints.outline),
        modifier = modifier.fillMaxWidth().clearAndSetSemantics {},
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Bar(Modifier.fillMaxWidth(0.55f), 16)
            Bar(Modifier.fillMaxWidth(0.38f), 12, top = 8)
            Bar(Modifier.fillMaxWidth(0.62f), 12, top = 14)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 14.dp).fillMaxWidth()) {
                Box(modifier = Modifier.width(56.dp).height(22.dp).background(bar, CircleShape))
                Box(modifier = Modifier.width(56.dp).height(22.dp).background(bar, CircleShape))
                Box(modifier = Modifier.weight(1f))
                Box(modifier = Modifier.width(72.dp).height(14.dp).background(bar, RoundedCornerShape(6.dp)))
            }
        }
    }
}
