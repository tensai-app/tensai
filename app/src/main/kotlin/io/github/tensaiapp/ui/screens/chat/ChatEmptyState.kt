package io.github.tensaiapp.ui.screens.chat

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.tensaiapp.R
import io.github.tensaiapp.ui.components.KanjiGlyph
import io.github.tensaiapp.ui.screens.Greeting
import io.github.tensaiapp.ui.screens.greetingFor
import java.time.LocalTime

/** Opacity of the 天 watermark, per the design's "barely there" background mark. */
private const val WatermarkAlpha = 0.08f

/** Side of the watermark glyph. Larger than the screen's margins on purpose, it is meant to bleed. */
private val WatermarkSize = 300.dp

/** How far past the right edge the watermark sits, so only part of the glyph is on screen. */
private val WatermarkOffsetX = 84.dp

@StringRes
private fun Greeting.labelRes(): Int = when (this) {
    Greeting.Morning -> R.string.home_greeting_morning
    Greeting.Afternoon -> R.string.home_greeting_afternoon
    Greeting.Evening -> R.string.home_greeting_evening
    Greeting.Night -> R.string.home_greeting_night
}

/**
 * What a conversation with no message shows behind its input pill (TEN-169): the greeting for the
 * time of day, the subtitle and the 天 watermark. The suggestions and the pill are the caller's, they
 * sit at the bottom. The caller fades this out at the first message: a conversation that has messages
 * has no watermark, for readability.
 */
@Composable
fun ChatEmptyState(modifier: Modifier = Modifier) {
    // Read on every composition rather than remembered: it is not a ticking clock, a chat left
    // untouched across 18:00 keeps saying "Good afternoon" until something recomposes it, and that
    // is fine.
    val greeting = greetingFor(LocalTime.now().hour)

    Box(modifier = modifier.fillMaxSize()) {
        KanjiGlyph(
            size = WatermarkSize,
            // Off to the right and bleeding past the edge. KanjiGlyph draws into a Canvas with
            // cleared semantics and takes no click, so this stays background texture: it is not
            // reachable by touch or by the accessibility cursor.
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .offset(x = WatermarkOffsetX)
                .alpha(WatermarkAlpha),
        )

        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = stringResource(greeting.labelRes()),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.home_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Whether the empty state shows: the conversation has no message. Before Room has emitted ([messageCount]
 * `null`) a draft counts as empty, so it opens on the greeting at full alpha with no animation, while a
 * saved conversation does not, so it never flashes the greeting while it loads.
 */
internal fun showsEmptyState(messageCount: Int?, isDraft: Boolean): Boolean =
    if (messageCount == null) isDraft else messageCount == 0

/** The pill's model chip: the resident model's name, or the call to open the picker when none is loaded. */
internal fun modelChipLabel(residentModelName: String?, loadLabel: String): String = residentModelName ?: loadLabel
