package io.github.hokanosekai.tensai.ui.navigation

/** Top-level routes for the app's [androidx.navigation.NavHost]. */
sealed class TensaiDestination(val route: String) {

    data object Onboarding : TensaiDestination("onboarding")
    data object Home : TensaiDestination("home")
    data object Models : TensaiDestination("models")
    data object Settings : TensaiDestination("settings")
    data object Server : TensaiDestination("server")

    data object Chat : TensaiDestination("chat/{conversationId}") {
        const val ARG_CONVERSATION_ID = "conversationId"

        /**
         * Sentinel used when a load's success navigates to a brand new conversation rather than
         * resuming a saved one — [io.github.hokanosekai.tensai.ui.navigation.TensaiNavGraph]
         * resolves this into a real, persisted conversation id before `ChatScreen`/`ChatViewModel`
         * ever see it; their contract is "conversationId always names an existing conversation".
         */
        const val NEW_CONVERSATION_ID = "new"

        fun route(conversationId: String) = "chat/$conversationId"
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
