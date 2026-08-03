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
        fun route(conversationId: String) = "chat/$conversationId"
    }

    data object ModelDetail : TensaiDestination("model/{modelId}") {
        const val ARG_MODEL_ID = "modelId"
        fun route(modelId: String) = "model/$modelId"
    }

    /**
     * TEN-30 wires the actual [io.github.hokanosekai.tensai.runtime.InferenceQueue.loadModel]
     * call for [ARG_MODEL_ID] — for now this route only shows the fake looping glyph.
     */
    data object Loading : TensaiDestination("loading/{modelId}") {
        const val ARG_MODEL_ID = "modelId"
        fun route(modelId: String) = "loading/$modelId"
    }
}
