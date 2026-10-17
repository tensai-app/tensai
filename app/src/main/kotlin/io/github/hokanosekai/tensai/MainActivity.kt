package io.github.hokanosekai.tensai

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.hokanosekai.tensai.agent.LaunchedPermission
import io.github.hokanosekai.tensai.agent.PermissionLaunchState
import io.github.hokanosekai.tensai.agent.PermissionRequest
import io.github.hokanosekai.tensai.agent.PermissionResult
import io.github.hokanosekai.tensai.data.AppLanguage
import io.github.hokanosekai.tensai.data.withAppLanguage
import io.github.hokanosekai.tensai.download.LoadRequests
import io.github.hokanosekai.tensai.runtime.ActiveSessionTracker
import io.github.hokanosekai.tensai.runtime.AppForegroundTracker
import io.github.hokanosekai.tensai.runtime.RuntimeGraph
import io.github.hokanosekai.tensai.ui.navigation.TensaiShell
import io.github.hokanosekai.tensai.ui.screens.onboarding.OnboardingEntry
import io.github.hokanosekai.tensai.ui.screens.onboarding.OnboardingGate
import io.github.hokanosekai.tensai.ui.theme.TensaiTheme
import io.github.hokanosekai.tensai.ui.theme.ThemeMode
import io.github.hokanosekai.tensai.ui.theme.isDark
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val themeRepository by lazy { RuntimeGraph.themeRepository }

    private val languageRepository by lazy { RuntimeGraph.languageRepository }

    /** Null until the first-run check below resolves — the splash screen stays up until then. */
    private val onboardingEntryState = mutableStateOf<OnboardingEntry?>(null)

    private val permissionLaunch = PermissionLaunchState()

    private val permissionPrefs by lazy { getSharedPreferences("permission_asks", MODE_PRIVATE) }

    /**
     * The Android permission dialog of the tools (TEN-186): the permission a tool waits on in
     * [RuntimeGraph.permissionGate] is asked here, the one place with an Activity, and the outcome goes back to that request.
     */
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val launched = permissionLaunch.finish() ?: return@registerForActivityResult
        val gate = RuntimeGraph.permissionGate
        val asked = permissionPrefs.getBoolean(launched.permission, false)
        permissionPrefs.edit().putBoolean(launched.permission, true).apply()
        val rationale = shouldShowRequestPermissionRationale(launched.permission)
        gate.complete(launched.id, PermissionResult.of(granted, launched.rationaleBefore, rationale, asked))
        // A request that queued behind this one.
        gate.pending.value?.let(::launchPermission)
    }

    /** Exactly once per request, see [PermissionLaunchState]. */
    private fun launchPermission(request: PermissionRequest) {
        if (permissionLaunch.begin(request, shouldShowRequestPermissionRationale(request.permission))) {
            permissionLauncher.launch(request.permission)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        permissionLaunch.launched?.let {
            outState.putLong(STATE_PERMISSION_ID, it.id)
            outState.putString(STATE_PERMISSION, it.permission)
            outState.putBoolean(STATE_PERMISSION_RATIONALE, it.rationaleBefore)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        savedInstanceState?.getString(STATE_PERMISSION)?.let {
            permissionLaunch.restore(LaunchedPermission(savedInstanceState.getLong(STATE_PERMISSION_ID), it, savedInstanceState.getBoolean(STATE_PERMISSION_RATIONALE)))
        }
        // Started only: a dialog over a stopped activity would be lost, the request waits for the user to come back.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                RuntimeGraph.permissionGate.pending.collect { request ->
                    request?.let(::launchPermission)
                }
            }
        }
        // Only a fresh launch: after process death the saved intent would replay an old tap.
        if (savedInstanceState == null) LoadRequests.offer(intent)

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

            // The system bars' icons follow the app's *effective* theme, not the phone's (TEN-99).
            // The bare `enableEdgeToEdge()` above decides light or dark icons once, from the
            // system setting, so picking "Light" on a dark phone left white clock and battery
            // icons on a light background. Re-applying it whenever `isDark` flips keeps the bars
            // in step with the hot theme switch of TEN-38. The scrims are activity's own defaults.
            val isDark = themeMode.isDark()
            DisposableEffect(isDark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { isDark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_NAV_SCRIM, DARK_NAV_SCRIM) { isDark },
                )
                onDispose {}
            }

            CompositionLocalProvider(LocalContext provides localizedContext) {
                TensaiTheme(themeMode = themeMode) {
                    if (onboardingEntry != null) {
                        TensaiShell(onboardingEntry = onboardingEntry)
                    }
                }
            }
        }
    }

    /** A tap on "Load" in the download-ready notification while the app is already open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        LoadRequests.offer(intent)
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

private const val STATE_PERMISSION_ID = "permission_id"
private const val STATE_PERMISSION = "permission"
private const val STATE_PERMISSION_RATIONALE = "permission_rationale"

/** `androidx.activity`'s `DefaultLightScrim` / `DefaultDarkScrim`, which it keeps private. */
private val LIGHT_NAV_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DARK_NAV_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
