package io.github.hokanosekai.tensai.ui.navigation

import android.net.Uri

/** Top-level routes for the app's [androidx.navigation.NavHost]. */
sealed class TensaiDestination(val route: String) {

    data object Onboarding : TensaiDestination("onboarding")
    data object Chats : TensaiDestination("chats")
    data object Models : TensaiDestination("models")
    data object Settings : TensaiDestination("settings")
    data object Device : TensaiDestination("settings/device")
    data object Memory : TensaiDestination("settings/memory")
    data object Tools : TensaiDestination("settings/tools")
    data object ToolsOnPhone : TensaiDestination("settings/tools/phone")
    data object ToolsPhoneActions : TensaiDestination("settings/tools/actions")
    data object ToolsInternet : TensaiDestination("settings/tools/internet")
    data object Licenses : TensaiDestination("settings/licenses")
    data object Server : TensaiDestination("server")

    data object Chat : TensaiDestination("chat/{conversationId}") {
        const val ARG_CONVERSATION_ID = "conversationId"

        /**
         * Sentinel for a new chat rather than a saved one: the app's start, drawer "New chat", a load
         * landing on a fresh chat. It opens a draft with no row behind it (TEN-106); the row is
         * created by the first send, see `ChatViewModel`. The route keeps this value after that,
         * `ChatScreen` holds the real id.
         */
        const val NEW_CONVERSATION_ID = "new"

        fun route(conversationId: String) = "chat/$conversationId"
    }

    data object ModelDetail : TensaiDestination("model/{modelId}") {
        const val ARG_MODEL_ID = "modelId"
        fun route(modelId: String) = "model/$modelId"
    }

    /**
     * A Hugging Face repo (TEN-140), opened from a Discover card or a pasted link. Both arguments are
     * query parameters: the repo id holds a slash (`author/name`) and travels encoded.
     * [ARG_FILE] is the file a link pointed at, selected on open, absent otherwise.
     */
    data object Repo : TensaiDestination("repo?id={id}&file={file}") {
        const val ARG_ID = "id"
        const val ARG_FILE = "file"
        const val PREFIX = "repo?"

        fun route(repoId: String, file: String? = null) =
            "repo?id=${Uri.encode(repoId)}" + (file?.let { "&file=${Uri.encode(it)}" } ?: "")
    }

    /** Drives [io.github.hokanosekai.tensai.ui.screens.loading.LoadModelViewModel] for [ARG_MODEL_ID]. */
    data object Loading : TensaiDestination("loading/{modelId}?returnToChat={returnToChat}") {
        const val ARG_MODEL_ID = "modelId"

        /**
         * Optional: the load was started from a chat that is still on the stack. Success then pops
         * back to that conversation instead of landing on a new chat.
         */
        const val ARG_RETURN_TO_CHAT = "returnToChat"

        fun route(modelId: String, returnToChat: Boolean = false) =
            "loading/$modelId" + if (returnToChat) "?$ARG_RETURN_TO_CHAT=true" else ""
    }
}
