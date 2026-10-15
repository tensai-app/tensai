package io.github.hokanosekai.tensai.ui.navigation

import android.net.Uri

/** Top-level routes for the app's [androidx.navigation.NavHost]. */
sealed class TensaiDestination(val route: String) {

    data object Onboarding : TensaiDestination("onboarding")
    data object Home : TensaiDestination("home")
    data object Models : TensaiDestination("models")
    data object Settings : TensaiDestination("settings")
    data object Device : TensaiDestination("settings/device")
    data object Licenses : TensaiDestination("settings/licenses")
    data object Server : TensaiDestination("server")

    data object Chat : TensaiDestination("chat/{conversationId}?prompt={prompt}") {
        const val ARG_CONVERSATION_ID = "conversationId"

        /**
         * Optional: the first turn's text, handed over by Home (TEN-37). Home navigates to a new
         * chat ([NEW_CONVERSATION_ID]); the message itself is sent from `ChatScreen`, so the reply
         * streams where the user can see it. Carried on the route rather than in a process-wide
         * holder so it survives with the back stack: a chat restored after process death still
         * knows what it was opened to ask.
         */
        const val ARG_PROMPT = "prompt"

        /**
         * Sentinel for a new chat rather than a saved one: drawer "New chat", Home's send, a load
         * landing on a fresh chat. It opens a draft with no row behind it (TEN-106); the row is
         * created by the first send, see `ChatViewModel`. The route keeps this value after that,
         * `ChatScreen` holds the real id.
         */
        const val NEW_CONVERSATION_ID = "new"

        fun route(conversationId: String) = "chat/$conversationId"

        /** [route] with a first turn attached — see [ARG_PROMPT]. */
        fun route(conversationId: String, prompt: String) =
            "chat/$conversationId?prompt=${Uri.encode(prompt)}"
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
