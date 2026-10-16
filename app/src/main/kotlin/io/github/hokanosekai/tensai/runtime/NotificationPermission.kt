package io.github.hokanosekai.tensai.runtime

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch
import io.github.hokanosekai.tensai.data.NotificationPermissionRepository

/** What [NotificationPermissionGate.decide] concluded about one ask site, and why. */
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
 * Whether a screen should still offer to ask: [canAsk] is exactly "[NotificationPermissionGate]
 * says [NotificationAsk.Ask]", so an offer on screen and the ask it triggers can't disagree.
 * [granted] is the permission itself, which is false below API 33 (nothing to grant there).
 */
data class NotificationAskState(val granted: Boolean, val canAsk: Boolean)

/**
 * Whether the `POST_NOTIFICATIONS` dialog should be put up (TEN-75, TEN-127).
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

/** [NotificationPermissionGate.decide] folded into what a screen needs to draw an offer. Pure. */
fun notificationAskState(sdkInt: Int, granted: Boolean, askedBefore: Boolean): NotificationAskState =
    NotificationAskState(
        granted = granted,
        canAsk = NotificationPermissionGate.decide(sdkInt, granted, askedBefore) == NotificationAsk.Ask,
    )

/**
 * Runs [NotificationPermissionGate.decide] and, if it says [NotificationAsk.Ask], spends the ask —
 * with every effect injected so the whole sequence runs in a JVM test (the pattern
 * `ModelImporter`/`OnboardingGate` already use here).
 *
 * **The claim completes before [launchRequest] runs, and that order is the point.** The dialog's
 * result is delivered asynchronously to a callback that may never arrive: the user backgrounds
 * the app with it up, the process is killed under memory pressure, the screen is left. Marking on
 * the *result* would leave the flag unset in all those cases and hand out a second dialog on the
 * next load, which is exactly the bug. Marking on the *request* costs, in the worst case, an ask
 * whose dialog was never actually seen; that is the cheap direction to be wrong in, since the
 * expensive one is permanent.
 *
 * **The claim is atomic, and only the claimer launches.** [wasAsked] alone is a check-then-act:
 * two calls in flight (a double tap) would both read "not asked" and both launch. [claimAsk] is
 * the transaction that settles it, see [NotificationPermissionRepository.claimRequest].
 *
 * @param isGranted `checkSelfPermission(POST_NOTIFICATIONS) == PERMISSION_GRANTED`.
 * @param wasAsked reads the persisted flag.
 * @param claimAsk sets the flag durably and returns whether this call was the one that set it.
 * @param launchRequest launches the `RequestPermission` contract. Its result is deliberately
 *   ignored: a refusal is not a failure, the load carries on without a persistent notification
 *   behind it (see [InferenceService], which documents that window).
 * @return what was decided, for tests and for callers that want to log it. Nothing branches on it.
 */
suspend fun requestNotificationPermissionOnce(
    sdkInt: Int,
    isGranted: () -> Boolean,
    wasAsked: suspend () -> Boolean,
    claimAsk: suspend () -> Boolean,
    launchRequest: () -> Unit,
): NotificationAsk {
    val decision = NotificationPermissionGate.decide(
        sdkInt = sdkInt,
        granted = isGranted(),
        askedBefore = wasAsked(),
    )
    if (decision != NotificationAsk.Ask) return decision
    if (!claimAsk()) return NotificationAsk.SkipAlreadyAsked
    launchRequest()
    return decision
}

/**
 * The same ask for a caller that cannot wait on I/O ("Continue in background" navigates right
 * away): the decision comes from a [state] read earlier, the dialog goes up synchronously, and the
 * claim is persisted by [persistClaim], which the caller runs on a scope the screen can't cancel.
 *
 * **[inFlight] is what keeps this at one dialog without the transaction.** The persisted flag lands
 * a moment after the launch, so for that moment a second tap would still see a stale "can ask".
 * The process-wide latch closes the gap, and [persistClaim] makes it permanent: the one window
 * left is a process death between the launch and the write, where the ask costs one stray extra
 * dialog at worst. A simpler shape than awaiting the claim first, which is the wait this avoids.
 *
 * @return whether the dialog was launched.
 */
fun requestNotificationPermissionNow(
    state: NotificationAskState?,
    inFlight: AtomicBoolean,
    persistClaim: () -> Unit,
    launchRequest: () -> Unit,
): Boolean {
    if (state?.canAsk != true || !inFlight.compareAndSet(false, true)) return false
    launchRequest()
    persistClaim()
    return true
}

/**
 * The `POST_NOTIFICATIONS` runtime permission request that [InferenceService]'s persistent
 * notification needs on API 33+, bound to the current composition's activity-result launcher.
 *
 * Per the TEN-27 spec this is never requested at app startup, where a permission prompt is much
 * more likely to get a hard "deny, don't ask again". Since TEN-127 it is not requested on a model
 * load either (it popped with no context over the loading screen). The call sites are the ones
 * where the user can see why: the onboarding Ready step's "Allow" button and the loading screen's
 * "Continue in background" tap, i.e. the moment the service is about to matter. A refusal is not
 * treated as a failure: the load proceeds, only without a persistent notification behind it.
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
                claimAsk = repository::claimRequest,
                launchRequest = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            )
            Unit
        }
    }
}

/** Shared by every [rememberNotificationAskLauncher], so no two screens can race a second dialog. */
private val askInFlight = AtomicBoolean(false)

/**
 * Non-suspending variant of [rememberNotificationPermissionRequester] for taps that must not wait:
 * pass the [rememberNotificationAskState] read (null means "not read yet", which asks nothing).
 * The claim is persisted on `RuntimeGraph.applicationScope`, so leaving the screen can't drop it.
 */
@Composable
fun rememberNotificationAskLauncher(
    repository: NotificationPermissionRepository = RuntimeGraph.notificationPermissionRepository,
): (NotificationAskState?) -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return remember(repository) {
        { state ->
            requestNotificationPermissionNow(
                state = state,
                inFlight = askInFlight,
                persistClaim = { RuntimeGraph.applicationScope.launch { repository.claimRequest() } },
                launchRequest = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            )
        }
    }
}

/**
 * Bumps every time the host goes through `ON_RESUME`, so a state that can change outside the app
 * (the permission dialog, the system notification settings) can key a re-read on it.
 */
@Composable
internal fun rememberResumeCount(): Int {
    val lifecycleOwner = LocalLifecycleOwner.current
    var count by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) count++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return count
}

/**
 * [NotificationAskState] for the current device, re-read on every resume so it is right after the
 * system dialog closes. `null` until the first read of the persisted flag lands. Built on
 * [notificationAskState], so the gate stays the only place the rule lives.
 */
@Composable
fun rememberNotificationAskState(
    repository: NotificationPermissionRepository = RuntimeGraph.notificationPermissionRepository,
): NotificationAskState? {
    val context = LocalContext.current
    val resumes = rememberResumeCount()
    var state by remember { mutableStateOf<NotificationAskState?>(null) }
    LaunchedEffect(resumes, repository) {
        state = notificationAskState(
            sdkInt = Build.VERSION.SDK_INT,
            granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED,
            askedBefore = repository.wasRequested(),
        )
    }
    return state
}
