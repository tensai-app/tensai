package io.github.tensaiapp.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tensaiapp.ui.theme.TensaiMono
import io.github.tensaiapp.ui.theme.tensaiColors
import io.github.tensaiapp.ui.theme.tensaiTints

/**
 * Selectable pill from the Calm Night kit (`Chip`): 34dp, 13sp medium, primary-16 fill and primary
 * text when [selected], transparent with a strong hairline otherwise. Single-choice groups use it
 * as a radio, hence [Role.RadioButton]. [onClick] may be `null` for a display-only chip; [enabled] false draws it at 38% and makes it inert.
 */
@Composable
fun TensaiChip(
    label: String,
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    mono: Boolean = false,
    enabled: Boolean = true,
) {
    val content = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val color = if (selected) MaterialTheme.tensaiTints.primary16 else Color.Transparent
    val border = if (selected) null else BorderStroke(1.dp, MaterialTheme.tensaiTints.outlineStrong)
    val chipModifier = modifier
        .alpha(if (enabled) 1f else 0.38f)
        .minimumInteractiveComponentSize()
        .defaultMinSize(minHeight = 34.dp)
        .semantics {
            role = Role.RadioButton
            this.selected = selected
        }
    val body: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            if (icon != null) Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(
                text = label,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = if (mono) TensaiMono else null,
                maxLines = 1,
            )
        }
    }
    // A null onClick is a chip that only reports state (the settings sheet's "Custom"): drawn the
    // same, but not clickable, so it is not announced or focusable as an action that does nothing.
    if (onClick != null) {
        Surface(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            color = color,
            contentColor = content,
            border = border,
            modifier = chipModifier,
            content = body,
        )
    } else {
        Surface(shape = CircleShape, color = color, contentColor = content, border = border, modifier = chipModifier, content = body)
    }
}

/** Tone of a [TensaiBadge]: [Neutral] is surface-variant, [Primary] primary-16, [Warning] warning-14. */
enum class TensaiBadgeTone { Neutral, Primary, Warning }

/**
 * Badge from the kit (`Badge`): 22dp-minimum pill, 9dp side padding, optional 13dp [icon] with a 5dp
 * gap, 12sp semibold text with 0.01em tracking. [tone] picks the colors: neutral (tools, thinking,
 * on device), primary (loaded) or warning (gated).
 */
@Composable
fun TensaiBadge(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, tone: TensaiBadgeTone = TensaiBadgeTone.Primary) {
    val content = when (tone) {
        TensaiBadgeTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
        TensaiBadgeTone.Primary -> MaterialTheme.colorScheme.primary
        TensaiBadgeTone.Warning -> MaterialTheme.tensaiColors.warning
    }
    val fill = when (tone) {
        TensaiBadgeTone.Neutral -> MaterialTheme.colorScheme.surfaceVariant
        TensaiBadgeTone.Primary -> MaterialTheme.tensaiTints.primary16
        TensaiBadgeTone.Warning -> MaterialTheme.tensaiTints.warning12
    }
    Surface(shape = CircleShape, color = fill, modifier = modifier.heightIn(min = 22.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 2.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(13.dp),
                )
            }
            Text(
                text = text,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.12.sp,
                color = content,
                maxLines = 1,
            )
        }
    }
}

/** Mono metadata badge from the kit (`Badge mono`): 22dp pill, surface-variant fill, 11sp mono. */
@Composable
fun TensaiMonoBadge(text: String, modifier: Modifier = Modifier) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = modifier.defaultMinSize(minHeight = 22.dp)) {
        Text(
            text = text,
            fontFamily = TensaiMono,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
}
