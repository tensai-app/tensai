package io.github.tensaiapp.ui.screens.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.tensaiapp.R
import io.github.tensaiapp.compat.DeviceProfile
import io.github.tensaiapp.data.AutoMemoryShare
import io.github.tensaiapp.data.ContextSetting
import io.github.tensaiapp.data.models.ModelEntity
import io.github.tensaiapp.runtime.LoadPolicy
import io.github.tensaiapp.ui.screens.models.formatGigabytes
import java.util.Locale

/**
 * The words for a [ContextSetting] where it is shown as a value: `Auto`, `8K`, `Max`.
 */
@Composable
internal fun ContextSetting.label(): String = stringResource(
    when (this) {
        ContextSetting.Auto -> R.string.context_option_auto
        ContextSetting.K4 -> R.string.context_option_4k
        ContextSetting.K8 -> R.string.context_option_8k
        ContextSetting.K16 -> R.string.context_option_16k
        ContextSetting.K32 -> R.string.context_option_32k
        ContextSetting.Max -> R.string.context_option_max
    },
)

/**
 * The rows of a context picker (TEN-150), shared by Settings (the global setting) and the model
 * detail screen (a model's own override).
 *
 * Each row carries what choosing it would cost, taken from [LoadPolicy.preview] (the same plan
 * the load will run) when a [model] and a [profile] are in hand: the resolved size for Auto, the
 * KV cache size, the total estimate against the memory budget, and a warning when the phone cannot
 * back the choice and the load would start lower. Without a [model] (the global picker with nothing
 * loaded and nothing on disk) there is nothing to price, so the rows carry a plain description
 * instead of a made-up figure.
 *
 * @param choices the rows in order; `null` stands for "use the global setting" (the model screen).
 * @param selected the current choice, `null` meaning "use the global setting".
 * @param autoShare the share of memory Auto may spend, see [AutoMemoryShare].
 * @param global the global setting, what the `null` row follows and is priced as.
 */
@Composable
internal fun contextPickerOptions(
    choices: List<ContextSetting?>,
    selected: ContextSetting?,
    global: ContextSetting,
    model: ModelEntity?,
    profile: DeviceProfile?,
    autoShare: AutoMemoryShare,
    onSelect: (ContextSetting?) -> Unit,
): List<PickerOption> = choices.map { choice ->
    val priced = choice ?: global
    val preview = if (model != null && profile != null) LoadPolicy.preview(model, profile, priced, autoShare) else null
    val lines = mutableListOf<String>()

    if (choice == null) lines += stringResource(R.string.context_option_global_subtitle, global.label())
    if (preview == null) {
        when (choice) {
            ContextSetting.Auto -> lines += stringResource(R.string.context_option_auto_subtitle)
            ContextSetting.Max -> lines += stringResource(R.string.context_option_max_subtitle)
            else -> Unit
        }
    } else {
        if (priced == ContextSetting.Auto) {
            lines += stringResource(R.string.context_option_resolves_to, groupedNumber(preview.requestedCtx))
        }
        lines += stringResource(
            R.string.context_option_memory,
            formatGigabytes(preview.kvBytes),
            formatGigabytes(preview.totalBytes),
            formatGigabytes(preview.budgetBytes),
        )
    }

    PickerOption(
        title = when {
            choice == null -> stringResource(R.string.context_option_global)
            choice == ContextSetting.Max && preview != null ->
                stringResource(R.string.context_option_max_tokens, groupedNumber(preview.requestedCtx))
            else -> choice.label()
        },
        subtitle = lines.joinToString("\n").ifEmpty { null },
        selected = choice == selected,
        onSelect = { onSelect(choice) },
        warning = preview?.takeIf { !it.fits }?.let {
            if (it.feasible) {
                stringResource(R.string.context_option_warning, groupedNumber(it.plannedCtx))
            } else {
                stringResource(R.string.context_option_infeasible)
            }
        },
    )
}

private fun groupedNumber(value: Int): String = String.format(Locale.US, "%,d", value)
