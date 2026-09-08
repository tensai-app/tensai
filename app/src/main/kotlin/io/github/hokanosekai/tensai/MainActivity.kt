package io.github.hokanosekai.tensai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import io.github.hokanosekai.tensai.data.AppLanguage
import io.github.hokanosekai.tensai.data.withAppLanguage
import io.github.hokanosekai.tensai.runtime.ActiveSessionTracker
import io.github.hokanosekai.tensai.runtime.AppForegroundTracker
import io.github.hokanosekai.tensai.runtime.RuntimeGraph
import io.github.hokanosekai.tensai.ui.navigation.TensaiShell
import io.github.hokanosekai.tensai.ui.screens.onboarding.OnboardingEntry
import io.github.hokanosekai.tensai.ui.screens.onboarding.OnboardingGate
import io.github.hokanosekai.tensai.ui.theme.TensaiTheme
import io.github.hokanosekai.tensai.ui.theme.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val themeRepository by lazy { RuntimeGraph.themeRepository }

    private val languageRepository by lazy { RuntimeGraph.languageRepository }

    /** Null until the first-run check below resolves — the splash screen stays up until then. */
    private val onboardingEntryState = mutableStateOf<OnboardingEntry?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        lifecycleScope.launch {
            onboardingEntryState.value = OnboardingGate.entry(
                models = RuntimeGraph.modelRepository.models.first(),
                skipped = RuntimeGraph.onboardingRepository.skipped.first(),
            )
        }
        splashScreen.setKeepOnScreenCondition { onboardingEntryState.value == null }

        setContent {
            val themeMode by themeRepository.themeMode.collectAsState(initial = ThemeMode.System)
            val language by languageRepository.language.collectAsState(initial = AppLanguage.System)
            val onboardingEntry = onboardingEntryState.value

            // Both preferences are applied the same way and for the same reason: they are read
            // from their store at composition, so writing one from Settings (TEN-38) re-renders
            // the app under it immediately, with no restart and no recreate. The language one
            // goes through `LocalContext` because that is what `stringResource` resolves against
            // — see [withAppLanguage] for why it wraps rather than replaces the context.
            val localizedContext = remember(language, this) { withAppLanguage(language) }

            CompositionLocalProvider(LocalContext provides localizedContext) {
                TensaiTheme(themeMode = themeMode) {
                    if (onboardingEntry != null) {
                        TensaiShell(onboardingEntry = onboardingEntry)
                    }
                }
            }
        }
    }

    /** See [AppForegroundTracker] — the other half of [onStop]'s report. */
    override fun onStart() {
        super.onStart()
        AppForegroundTracker.onActivityStarted()
    }

    /**
     * TEN-34's second save trigger: the app going to background. This is the app's only
     * `Activity`, so `onStop` here reliably means "no longer in the foreground" rather than an
     * internal Compose navigation (which never stops the `Activity`) — no
     * `ProcessLifecycleOwner`/extra dependency needed for that distinction.
     *
     * **Launched on [RuntimeGraph.applicationScope], not `lifecycleScope`** (TEN-78). The scope is
     * the whole trigger: `lifecycleScope` is cancelled on `onDestroy`, and leaving by the back
     * button runs `onStop` and `onDestroy` back to back, so this save was cancelled within
     * milliseconds of starting and the app's most ordinary exit persisted nothing, ever. It was
     * "best-effort" in name and never once effected. `applicationScope` is what the swap hook
     * (`RuntimeGraph.init`) and every `ChatViewModel` already use, for this exact reason, and the
     * foreground service keeps the process alive while a model is resident — which is precisely as
     * long as there is any state worth writing.
     *
     * Still best-effort in the honest sense: a process killed under real memory pressure shortly
     * after backgrounding can cut the dump short. `SessionRepository` writes to a temp file and
     * renames, so what that loses is the save, never the previous session.
     *
     * A save is *declined* rather than lost if a generation is still streaming here — the queue
     * cannot dump mid-decode (see [io.github.hokanosekai.tensai.runtime.InferenceQueue.saveSession])
     * and answers `SaveResult.NotAttempted`. That case is covered from the other end, by
     * [io.github.hokanosekai.tensai.runtime.saveSessionAfterBackgroundedGeneration], which is why
     * the flag below is reported *before* the save is launched.
     */
    override fun onStop() {
        super.onStop()
        AppForegroundTracker.onActivityStopped()
        val (conversationId, key) = ActiveSessionTracker.current.value ?: return
        RuntimeGraph.applicationScope.launch {
            runCatching {
                RuntimeGraph.sessionRepository.save(conversationId, key, saveOnEngine = RuntimeGraph.inferenceQueue::saveSession)
            }
        }
    }
}
