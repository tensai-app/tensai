package io.github.hokanosekai.tensai.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hokanosekai.tensai.ui.theme.tensaiTints

enum class TensaiButtonVariant { Filled, Tonal, Outlined, Text }

/** Heights and type from the kit's `Button`. [Lg] is the full-width action at the foot of a page (onboarding). */
enum class TensaiButtonSize(val height: Dp, val horizontalPadding: Dp, val fontSize: Int) {
    Sm(36.dp, 16.dp, 13),
    Md(44.dp, 22.dp, 14),
    Lg(52.dp, 24.dp, 15),
}

/**
 * Pill button from the Calm Night kit (`Button`): 600 weight, 8dp icon gap, scale 0.96 while
 * pressed. [TensaiButtonVariant.Tonal] is primary-16 fill + primary text, [Outlined][TensaiButtonVariant.Outlined]
 * a strong hairline + body text, [Text][TensaiButtonVariant.Text] primary text on nothing,
 * [Filled][TensaiButtonVariant.Filled] primary fill. The height is a minimum so a large font scale
 * grows the pill instead of clipping it. Pass `Modifier.fillMaxWidth()` for the kit's `full`.
 * [containerColor]/[contentColor] override the variant's colors (the destructive "Remove"). [trailingIcon]
 * follows the label, for a forward action ("Continue" with an arrow).
 */
@Composable
fun TensaiButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: TensaiButtonVariant = TensaiButtonVariant.Filled,
    size: TensaiButtonSize = TensaiButtonSize.Md,
    icon: ImageVector? = null,
    trailingIcon: ImageVector? = null,
    enabled: Boolean = true,
    containerColor: Color? = null,
    contentColor: Color? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val tints = MaterialTheme.tensaiTints
    val (container, content) = when (variant) {
        TensaiButtonVariant.Filled -> scheme.primary to scheme.onPrimary
        TensaiButtonVariant.Tonal -> tints.primary16 to scheme.primary
        TensaiButtonVariant.Outlined -> Color.Transparent to scheme.onSurface
        TensaiButtonVariant.Text -> Color.Transparent to scheme.primary
    }
    val border = if (variant == TensaiButtonVariant.Outlined) BorderStroke(1.dp, tints.outlineStrong) else null
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reducedMotion = rememberReducedMotion()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.96f else 1f,
        animationSpec = if (reducedMotion) snap() else spring(),
        label = "tensaiButtonScale",
    )
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = containerColor ?: container,
        contentColor = contentColor ?: content,
        border = border,
        interactionSource = interaction,
        modifier = modifier
            .minimumInteractiveComponentSize()
            .alpha(if (enabled) 1f else 0.38f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            modifier = Modifier
                .defaultMinSize(minHeight = size.height)
                .padding(horizontal = size.horizontalPadding),
        ) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size((size.fontSize + 4).dp))
            }
            Text(text = text, fontSize = size.fontSize.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (trailingIcon != null) {
                Icon(imageVector = trailingIcon, contentDescription = null, modifier = Modifier.size((size.fontSize + 4).dp))
            }
        }
    }
}
