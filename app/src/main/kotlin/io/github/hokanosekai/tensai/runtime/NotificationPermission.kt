package io.github.hokanosekai.tensai.runtime

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import io.github.hokanosekai.tensai.data.NotificationPermissionRepository

/** What [NotificationPermissionGate.decide] concluded about one load, and why. */
enum class NotificationAsk {
    /** Put the system dialog up. The only outcome that spends an ask. */
    Ask,

    /** API < 33: the permission doesn't exist there and notifications are granted implicitly. */
    SkipUnsupportedApi,

    /** Already granted — nothing to ask for. */
    SkipAlreadyGranted,

    /** Asked once before and not granted, i.e. refused. Never ask a second time — see the gate's KDoc. */
    SkipAlreadyAsked,
}

/**
 * Whether a model load should put the `POST_NOTIFICATIONS` dialog up (TEN-75).
 *
 * The rule this exists to enforce is **at most one dialog, ever**. Android hands an app exactly
 * two `POST_NOTIFICATIONS` dialogs per install and turns the second denial into a permanent
 * "don't ask again" that no later version of the app can undo from the inside. The pre-TEN-75
 * guard was `checkSelfPermission != GRANTED`, which only stops re-asking someone who *accepted* —
 * someone who refused fell straight through it on the next load and spent the second ask on the
 * same bare dialog that had just been refused. Keeping that ask unspent is worth more than any
 * chance of catching a change of mind: a user who wants notifications after refusing can grant
 * them from the system settings, and a future version can spend the ask on a rationale-first flow
 * that has some reason to expect a different answer. That flow would have to pass
 * `askedBefore = false` deliberately rather than reset the stored flag — the flag records that a
 * dialog was spent, which stays true however the app later feels about it, and
 * [NotificationPermissionRepository] keeps it one-way for that reason.
 *
 * The cost of the rule, stated plainly: the ask is spent on *showing* the dialog, so a user who
 * dismissed it by accident — a rotation, a stray tap — is never asked again either. There is no
 * way to tell that apart from a refusal from inside the app, and of the two ways to be wrong,
 * silently having no notifications is recoverable from system settings while a permanent
 * "don't ask again" is not.
 *
 * Pure and total — [decide] is the whole rule, and every input is a plain value, so the sequence
 * that used to be a Compose-only behaviour is now `NotificationPermissionTest`'s to prove.
 */
object NotificationPermissionGate {

    /**
     * @param sdkInt [Build.VERSION.SDK_INT] on the running device.
     * @param granted `POST_NOTIFICATIONS` is currently held.
     * @param askedBefore this install has already shown the dialog once — see
     *   [NotificationPermissionRepository], which persists it.
     */
    fun decide(sdkInt: Int, granted: Boolean, askedBefore: Boolean): NotificationAsk = when {
        sdkInt < Build.VERSION_CODES.TIRAMISU -> NotificationAsk.SkipUnsupportedApi
        granted -> NotificationAsk.SkipAlreadyGranted
        askedBefore -> NotificationAsk.SkipAlreadyAsked
        else -> NotificationAsk.Ask
    }
}

/**
 * Runs [NotificationPermissionGate.decide] and, if it says [NotificationAsk.Ask], spends the ask —
 * with every effect injected so the whole sequence runs in a JVM test (the pattern
 * `ModelImporter`/`OnboardingGate`/`HomePill` already use here).
 *
 * **[markAsked] completes before [launchRequest] runs, and that order is the point.** The dialog's
 * result is delivered asynchronously to a callback that may never arrive — the user backgrounds
 * the app with it up, the process is killed under memory pressure, the screen is left. Marking on
 * the *result* would leave the flag unset in all those cases and hand out a second dialog on the
 * next load, which is exactly the bug. Marking on the *request* costs, in the worst case, an ask
 * whose dialog was never actually seen; that is the cheap direction to be wrong in, since the
 * expensive one is permanent.
 *
 * @param isGranted `checkSelfPermission(POST_NOTIFICATIONS) == PERMISSION_GRANTED`.
 * @param wasAsked reads the persisted flag.
 * @param markAsked writes it, durably, before the dialog goes up.
 * @param launchRequest launches the `RequestPermission` contract. Its result is deliberately
 *   ignored: a refusal is not a failure, the load carries on without a persistent notification
 *   behind it (see [InferenceService], which documents that window).
 * @return what was decided, for tests and for callers that want to log it. Nothing branches on it.
 */
suspend fun requestNotificationPermissionOnce(
    sdkInt: Int,
    isGranted: () -> Boolean,
    wasAsked: suspend () -> Boolean,
    markAsked: suspend () -> Unit,
    launchRequest: () -> Unit,
): NotificationAsk {
    val decision = NotificationPermissionGate.decide(
        sdkInt = sdkInt,
        granted = isGranted(),
        askedBefore = wasAsked(),
    )
    if (decision == NotificationAsk.Ask) {
        markAsked()
        launchRequest()
    }
    return decision
}

/**
 * The `POST_NOTIFICATIONS` runtime permission request that [InferenceService]'s persistent
 * notification needs on API 33+, bound to the current composition's activity-result launcher.
 *
 * Per the TEN-27 spec, this is requested at the moment the user first triggers a model load from
 * the UI — not at app startup, where a permission prompt is much more likely to get a hard
 * "deny, don't ask again". That call site is
 * [io.github.hokanosekai.tensai.ui.screens.loading.LoadingScreen], which invokes the returned
 * lambda from the same `LaunchedEffect` that starts the load, immediately *after* it (see that
 * screen for why the ordering matters). A refusal is not treated as a failure: the load proceeds,
 * only without a persistent notification behind it.
 *
 * Since TEN-75 the dialog goes up at most once per install rather than once per entry to a
 * re-entrable screen — [NotificationPermissionGate] holds that rule and its reasoning, and
 * [NotificationPermissionRepository] is what makes it survive the process.
 */
@Composable
fun rememberNotificationPermissionRequester(
    repository: NotificationPermissionRepository = RuntimeGraph.notificationPermissionRepository,
): suspend () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return remember(context, repository) {
        {
            requestNotificationPermissionOnce(
                sdkInt = Build.VERSION.SDK_INT,
                isGranted = {
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) == PackageManager.PERMISSION_GRANTED
                },
                wasAsked = repository::wasRequested,
                markAsked = repository::markRequested,
                launchRequest = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            )
            Unit
        }
    }
}
