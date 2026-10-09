package io.github.hokanosekai.tensai.ui.screens.onboarding

import io.github.hokanosekai.tensai.data.models.ModelEntity
import java.io.File

/**
 * The first-run flow's steps, in the order they are shown. [OnboardingScreen] walks this list by
 * ordinal (the step dots are one per entry), so a future step is added by inserting an entry at the
 * right place and giving it a body: V1.1's in-app download (TEN-45) belongs inside [Import], V3's
 * agent skills/permissions right before [Ready].
 */
enum class OnboardingStep {
    /** Glyph, name, one-line pitch, and the theme and language pills. */
    Welcome,

    /** Recommends a model for this device and guides a GGUF import through SAF. */
    Import,

    /** "Your model is ready" and hands off to the load. */
    Ready,
}

/** What a cold start should open with — see [OnboardingGate.entry]. */
sealed interface OnboardingEntry {
    /** No onboarding: the app opens at Home. */
    data object None : OnboardingEntry

    /** Show the onboarding, starting at [step]. */
    data class Show(val step: OnboardingStep) : OnboardingEntry
}

/**
 * Decides whether a launch goes through the first-run onboarding.
 *
 * One rule: **no usable model means onboarding**. A model row whose file is gone (storage wiped
 * from the system settings, an app-data restore that brought rows back without files) is not a
 * usable model — the app can't chat with it, so that install is treated exactly like a fresh one
 * rather than dropping the user at a Home screen that cannot do anything.
 *
 * Skipping does not turn the flow off — see [io.github.hokanosekai.tensai.data.OnboardingRepository]
 * for why, and for the one thing the persisted flag does change (which step a returning skipper
 * lands on).
 */
object OnboardingGate {

    /**
     * @param models every imported model, as persisted (see [io.github.hokanosekai.tensai.data.models.ModelRepository.models]).
     * @param skipped whether the user has already dismissed the onboarding at least once.
     * @param fileExists existence check for a [ModelEntity.filePath] — injectable for tests.
     */
    fun entry(
        models: List<ModelEntity>,
        skipped: Boolean,
        fileExists: (String) -> Boolean = { File(it).exists() },
    ): OnboardingEntry {
        if (models.any { fileExists(it.filePath) }) return OnboardingEntry.None
        return OnboardingEntry.Show(if (skipped) OnboardingStep.Import else OnboardingStep.Welcome)
    }
}
