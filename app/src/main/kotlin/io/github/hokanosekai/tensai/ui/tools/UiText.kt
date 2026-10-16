package io.github.hokanosekai.tensai.ui.tools

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/**
 * A piece of user-facing text that is not resolved yet: a string resource with its arguments (which may be
 * [UiText] themselves), or text that has no resource (an unknown tool's name). Lets the label logic stay a pure
 * function a JVM test can compare, and the composable that draws it resolve it against the app language.
 */
sealed interface UiText {
    data class Res(@StringRes val res: Int, val args: List<Any> = emptyList()) : UiText

    /** A plural resource: the first of [args] is the quantity. */
    data class Plural(@PluralsRes val res: Int, val args: List<Any>) : UiText

    data class Raw(val text: String) : UiText

    companion object {
        fun of(@StringRes res: Int, vararg args: Any): UiText = Res(res, args.toList())
    }
}

@Composable
fun UiText.resolve(): String = when (this) {
    is UiText.Raw -> text
    is UiText.Res -> {
        val resolved = args.map { if (it is UiText) it.resolve() else it }
        stringResource(res, *resolved.toTypedArray())
    }
    is UiText.Plural -> {
        val resolved = args.map { if (it is UiText) it.resolve() else it }
        pluralStringResource(res, args[0] as Int, *resolved.toTypedArray())
    }
}
