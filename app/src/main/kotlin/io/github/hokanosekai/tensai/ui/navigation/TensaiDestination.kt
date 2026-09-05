package io.github.hokanosekai.tensai.ui.navigation

import android.net.Uri

/** Top-level routes for the app's [androidx.navigation.NavHost]. */
sealed class TensaiDestination(val route: String) {

    data object Onboarding : TensaiDestination("onboarding")
    data object Home : TensaiDestination("home")
    data object Models : TensaiDestination("models")
    data object Settings : TensaiDestination("settings")
    data object Server : TensaiDestination("server")

    data object Chat : TensaiDestination("chat/{conversationId}?prompt={prompt}") {
        const val ARG_CONVERSATION_ID = "conversationId"

        /**
         * Optional: the first turn's text, handed over by Home (TEN-37). Home creates the
         * conversation and navigates; the message itself is sent from `ChatScreen`, so the reply
         * streams where the user can see it. Carried on the route rather than in a process-wide
         * holder so it survives with the back stack: a chat restored after process death still
         * knows what it was opened to ask.
         */
        const val ARG_PROMPT = "prompt"

        /**
         * Sentinel used when a load's success navigates to a brand new conversation rather than
         * resuming a saved one — [io.github.hokanosekai.tensai.ui.navigation.TensaiNavGraph]
         * resolves this into a real, persisted conversation id before `ChatScreen`/`ChatViewModel`
         * ever see it; their contract is "conversationId always names an existing conversation".
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

    /** Drives [io.github.hokanosekai.tensai.ui.screens.loading.LoadModelViewModel] for [ARG_MODEL_ID]. */
    data object Loading : TensaiDestination("loading/{modelId}") {
        const val ARG_MODEL_ID = "modelId"
        fun route(modelId: String) = "loading/$modelId"
    }
}
