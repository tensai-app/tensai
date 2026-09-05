package io.github.hokanosekai.tensai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import io.github.hokanosekai.tensai.runtime.ActiveSessionTracker
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
            val onboardingEntry = onboardingEntryState.value
            TensaiTheme(themeMode = themeMode) {
                if (onboardingEntry != null) {
                    TensaiShell(onboardingEntry = onboardingEntry)
                }
            }
        }
    }

    /**
     * TEN-34's second save trigger: the app going to background. This is the app's only
     * `Activity`, so `onStop` here reliably means "no longer in the foreground" rather than an
     * internal Compose navigation (which never stops the `Activity`) — no
     * `ProcessLifecycleOwner`/extra dependency needed for that distinction.
     *
     * Best-effort: `lifecycleScope` survives past `onStop` (cancelled on `onDestroy`), so this save
     * has a chance to finish even though the coroutine isn't awaited here — but a save can still be
     * cut short if the process is killed for real memory pressure shortly after backgrounding.
     * There is no stronger guarantee available without extending the foreground service's
     * lifetime past when a model stops being *used*, which is well beyond this ticket's scope.
     */
    override fun onStop() {
        super.onStop()
        val (conversationId, key) = ActiveSessionTracker.current.value ?: return
        lifecycleScope.launch {
            runCatching {
                RuntimeGraph.sessionRepository.save(conversationId, key, saveOnEngine = RuntimeGraph.inferenceQueue::saveSession)
            }
        }
    }
}
