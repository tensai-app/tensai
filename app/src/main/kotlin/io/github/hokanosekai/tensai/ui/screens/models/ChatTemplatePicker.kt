package io.github.hokanosekai.tensai.ui.screens.models

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.hokanosekai.tensai.R
import io.github.hokanosekai.tensai.data.models.ChatTemplateChoice
import io.github.hokanosekai.tensai.data.models.ModelEntity
import io.github.hokanosekai.tensai.ui.screens.settings.PickerOption

/** The words for a [ChatTemplateChoice] where it is shown as a value: `Mistral`, `Llama 3`. */
@Composable
internal fun ChatTemplateChoice.label(): String = stringResource(
    when (this) {
        ChatTemplateChoice.ChatML -> R.string.chat_template_option_chatml
        ChatTemplateChoice.Mistral -> R.string.chat_template_option_mistral
        ChatTemplateChoice.Llama3 -> R.string.chat_template_option_llama3
        ChatTemplateChoice.Gemma -> R.string.chat_template_option_gemma
        ChatTemplateChoice.Phi3 -> R.string.chat_template_option_phi3
    },
)

/**
 * What the "Chat template" row of the capabilities section says (TEN-159): the user's own choice
 * by name, else "From file" when the GGUF ships one, else which format Auto falls back to.
 */
@Composable
internal fun chatTemplateStatus(model: ModelEntity): String {
    model.chatTemplateChoice?.let { return it.label() }
    if (model.hasChatTemplate) return stringResource(R.string.model_detail_chat_template_from_file)
    return stringResource(R.string.model_detail_chat_template_missing, model.chatTemplateGuess.label())
}

/** The rows of the per-model picker: Auto (`null`) then every [ChatTemplateChoice]. */
@Composable
internal fun chatTemplatePickerOptions(
    selected: ChatTemplateChoice?,
    onSelect: (ChatTemplateChoice?) -> Unit,
): List<PickerOption> = listOf<ChatTemplateChoice?>(null).plus(ChatTemplateChoice.entries).map { choice ->
    PickerOption(
        title = choice?.label() ?: stringResource(R.string.chat_template_option_auto),
        subtitle = if (choice == null) stringResource(R.string.chat_template_option_auto_subtitle) else null,
        selected = choice == selected,
        onSelect = { onSelect(choice) },
    )
}
