package io.github.hokanosekai.tensai.ui.screens

import io.github.hokanosekai.tensai.data.models.ModelEntity
import java.io.File

/** What Home's input pill offers, given what is loaded and what has been imported — see [HomePill.state]. */
sealed interface HomePillState {

    /** A model is resident: the pill is a real text field and can send. [modelName] labels its chip. */
    data class Ready(val modelName: String) : HomePillState

    /**
     * Nothing is loaded, but [displayName] is sitting on disk ready to be: the pill becomes a
     * "load this to start" affordance that routes to [modelId]'s load.
     */
    data class LoadNeeded(val modelId: String, val displayName: String) : HomePillState

    /** No usable model at all: the pill becomes a call to action towards the Models screen. */
    data object NoModel : HomePillState
}

/**
 * Home's pill state, as a function of the runtime and the model library.
 *
 * Shares [OnboardingGate][io.github.hokanosekai.tensai.ui.screens.onboarding.OnboardingGate]'s one
 * rule about what counts as a model: **a row whose file is gone is not a model**. Storage wiped
 * from the system settings, or an app-data restore that brought rows back without files, leaves
 * `ModelEntity` rows pointing at nothing — offering to load one would only fail at the loading
 * screen, so Home treats that install as having no model at all and sends the user to import one,
 * exactly as a cold start would.
 *
 * A resident model wins over everything, including its own row having vanished: it is in RAM, it
 * can answer, and the only honest thing to show is a working pill.
 */
object HomePill {

    /**
     * @param loadedModelName the resident model's name, `null` if none — callers pass
     *   [io.github.hokanosekai.tensai.runtime.residentModel]`?.name`, which stays non-null across a
     *   generation. A load that is still *in progress* reads as `null` here and therefore as
     *   [HomePillState.LoadNeeded]; that is transient chrome on a screen the user is normally not
     *   looking at (they are on the loading screen), and tapping it just returns them there.
     * @param models every imported model, most recently imported first (see
     *   [io.github.hokanosekai.tensai.data.models.ModelDao.observeAll]) — so the one offered by
     *   [HomePillState.LoadNeeded] is the newest whose file survives.
     * @param fileExists existence check for a [ModelEntity.filePath] — injectable for tests, same
     *   as `OnboardingGate.entry`.
     */
    fun state(
        loadedModelName: String?,
        models: List<ModelEntity>,
        fileExists: (String) -> Boolean = { File(it).exists() },
    ): HomePillState {
        if (loadedModelName != null) return HomePillState.Ready(loadedModelName)
        val usable = models.firstOrNull { fileExists(it.filePath) } ?: return HomePillState.NoModel
        return HomePillState.LoadNeeded(modelId = usable.id, displayName = usable.shownName)
    }
}
