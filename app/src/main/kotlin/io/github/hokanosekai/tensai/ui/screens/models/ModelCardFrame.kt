package io.github.hokanosekai.tensai.ui.screens.models

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Construction
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hokanosekai.tensai.R
import io.github.hokanosekai.tensai.ui.components.TensaiBadge
import io.github.hokanosekai.tensai.ui.components.TensaiBadgeTone
import io.github.hokanosekai.tensai.ui.components.rememberReducedMotion
import io.github.hokanosekai.tensai.ui.theme.TensaiMono
import io.github.hokanosekai.tensai.ui.theme.TensaiShapes
import io.github.hokanosekai.tensai.ui.theme.tensaiTints

/**
 * The one card skeleton of the kit's `ModelCard`, shared by [ModelCard] (Models > On device, the
 * onboarding) and [RepoCard] (Models > Discover) so the two cannot drift: name and fit [status] in
 * the header, [subtitle], mono [facts], the [chips] row, then [extra] (link row, stats) and
 * [footer] (the action). Bordered in primary when [highlighted]; [onClick] `null` makes it
 * read-only. A press just settles the card to 0.99, no ripple on a card this big.
 */
@Composable
internal fun ModelCardFrame(
    title: String,
    status: @Composable () -> Unit,
    subtitle: String?,
    facts: String?,
    chips: List<CardChip>,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    extra: (@Composable ColumnScope.() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reducedMotion = rememberReducedMotion()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.99f else 1f,
        animationSpec = if (reducedMotion) snap() else spring(),
        label = "modelCardScale",
    )
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(TensaiShapes.large)
            .then(
                if (onClick == null) {
                    Modifier
                } else {
                    Modifier.clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClickLabel = onClickLabel,
                        role = Role.Button,
                        onClick = onClick,
                    )
                },
            ),
        shape = TensaiShapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.tensaiTints.outline),
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 22.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // At least 22dp like the first title line, so the dot sits on its middle.
                Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.heightIn(min = 22.dp).widthIn(max = 140.dp)) { status() }
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            CardFacts(facts, modifier = Modifier.padding(top = 8.dp))
            CardChips(chips, modifier = Modifier.padding(top = 12.dp))
            extra?.invoke(this)
            footer?.let { Column(modifier = Modifier.padding(top = 16.dp)) { it() } }
        }
    }
}

/** Mono facts line, one line with an ellipsis; nothing when [facts] is `null`. */
@Composable
internal fun CardFacts(facts: String?, modifier: Modifier = Modifier) {
    if (facts == null) return
    Text(
        text = facts,
        fontFamily = TensaiMono,
        fontSize = 12.5.sp,
        lineHeight = 18.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** The [CardChip] row, not drawn at all when [chips] is empty. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CardChips(chips: List<CardChip>, modifier: Modifier = Modifier) {
    if (chips.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        chips.forEach { CardChipBadge(it) }
    }
}

/** One [CardChip] as a badge: the single place a chip's label, icon and tone are decided. */
@Composable
internal fun CardChipBadge(chip: CardChip, modifier: Modifier = Modifier) {
    when (chip) {
        CardChip.Loaded -> TensaiBadge(stringResource(R.string.model_loaded_badge), modifier, tone = TensaiBadgeTone.Primary)
        CardChip.OnDevice -> TensaiBadge(stringResource(R.string.repo_badge_on_device), modifier, Icons.Default.Check, TensaiBadgeTone.Neutral)
        CardChip.Tools -> TensaiBadge(stringResource(R.string.discover_badge_tools), modifier, Icons.Default.Construction, TensaiBadgeTone.Neutral)
        CardChip.Thinking -> TensaiBadge(stringResource(R.string.discover_badge_thinking), modifier, Icons.Default.Psychology, TensaiBadgeTone.Neutral)
        CardChip.Vision -> TensaiBadge(stringResource(R.string.discover_badge_vision), modifier, Icons.Default.Visibility, TensaiBadgeTone.Neutral)
        CardChip.Gated -> TensaiBadge(stringResource(R.string.discover_badge_gated), modifier, Icons.Default.Lock, TensaiBadgeTone.Warning)
        CardChip.Imported -> TensaiBadge(stringResource(R.string.onboarding_imported_badge), modifier, tone = TensaiBadgeTone.Neutral)
    }
}
