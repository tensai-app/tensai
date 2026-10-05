package io.github.hokanosekai.tensai.runtime

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import io.github.hokanosekai.tensai.MainActivity
import io.github.hokanosekai.tensai.R
import io.github.hokanosekai.tensai.data.AppLanguage
import io.github.hokanosekai.tensai.data.withAppLanguage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Keeps the process foreground-priority for as long as a model is resident
 * in RAM (a 2-4 GB weight set is exactly the kind of thing the OOM killer
 * reclaims first from a background process), and renders [QueueState] as
 * the persistent notification described in the V1 spec.
 *
 * This service is purely a "RAM anchor + notification renderer" — it is
 * never bound. [RuntimeGraph.inferenceQueue] is a plain singleton already
 * reachable by every caller (Chat UI today, the V2 API server later — see
 * [InferenceQueue]'s KDoc), so binding would only add ceremony without
 * giving any caller something it doesn't already have. The two exceptions
 * are the notification's own actions ("Unload", "Stop"), which call
 * straight into that same singleton — see [onStartCommand].
 *
 * Lifecycle is entirely state-driven, split across two independent
 * observers of [RuntimeGraph.inferenceQueue]'s state, both running on
 * [RuntimeGraph.applicationScope]:
 * - `TensaiApp` starts this service (via `startForegroundService`) the
 *   moment state leaves [QueueState.Idle]. A stopped service cannot be
 *   restarted from the inside, so starting must come from outside.
 * - This service's own [onCreate] observer stops itself the moment state
 *   returns to [QueueState.Idle], and otherwise keeps the notification's
 *   content in sync with the current state.
 */
class InferenceService : Service() {

    private var observerJob: Job? = null

    /**
     * Where the notification's text is resolved (TEN-41). A notification has no Compose
     * `LocalContext` to inherit the in-app language from, so the service applies the same
     * [withAppLanguage] wrapper `MainActivity` does, re-derived on every [AppLanguage] change.
     * Starts as the service itself (system locale) because the synchronous bootstrap post in
     * [onCreate] cannot wait for the preference store; the observer re-posts under the stored
     * language on its first emission.
     */
    @Volatile
    private var localized: Context = this

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // startForeground must be called within a few seconds of the service
        // starting — post an initial notification synchronously from
        // whatever the current state already is, then let the observer
        // below keep it in sync from that point on.
        startForegroundCompat(buildNotification(RuntimeGraph.inferenceQueue.state.value))

        observerJob = RuntimeGraph.applicationScope.launch {
            var currentLanguage: AppLanguage? = null
            combine(
                RuntimeGraph.inferenceQueue.state,
                RuntimeGraph.languageRepository.language.distinctUntilChanged(),
                ::Pair,
            ).collect { (state, language) ->
                if (language != currentLanguage) {
                    currentLanguage = language
                    localized = withAppLanguage(language)
                    // Re-registering an existing channel id only updates its name and description,
                    // which is exactly what a language change needs in the system settings.
                    createNotificationChannel()
                }
                if (state is QueueState.Idle) {
                    ServiceCompat.stopForeground(this@InferenceService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    postNotification(buildNotification(state))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_UNLOAD -> RuntimeGraph.applicationScope.launch { RuntimeGraph.inferenceQueue.unloadModel() }
            ACTION_STOP_GENERATION -> RuntimeGraph.inferenceQueue.cancelActive()
        }
        // Not sticky: this service's lifecycle is entirely state-driven (see class
        // KDoc). If the system kills it, TensaiApp's observer restarts it on the
        // next non-Idle emission rather than the system guessing when to.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * The user swiped the task away from Recents. A resident model would
     * otherwise keep pinning RAM with no UI left to manage it, so unload it
     * and stop cleanly instead of leaving an orphaned foreground service +
     * notification behind.
     *
     * [stopSelf] runs **inside** the `launch`, after the unload (TEN-78). Calling it alongside the
     * launch — as this did — dropped the foreground protection immediately, while the unload it had
     * just started was still running: [InferenceQueue.unloadModel] fires
     * [InferenceQueue.onBeforeModelSwap], which is where TEN-34's save-before-swap writes a
     * multi-hundred-MB state dump, and the process was back at background priority for the whole of
     * it. Nothing corrupts — `SessionRepository` writes to a temp file and renames — the save is
     * simply lost, silently, on the exit path where it is worth the most.
     *
     * The [stopSelf] is close to redundant now: `unloadModel` settles [QueueState.Idle], and the
     * observer in [onCreate] stops the service on it. It stays because that is an inference across
     * two coroutines, and this method's contract is to stop.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        RuntimeGraph.applicationScope.launch {
            RuntimeGraph.inferenceQueue.unloadModel()
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        observerJob?.cancel()
        super.onDestroy()
    }

    /**
     * Without `POST_NOTIFICATIONS` granted (API 33+, requested from
     * `LoadingScreen` via [rememberNotificationPermissionRequester], and
     * refusable, so this stays a case that happens),
     * this call is a documented no-op: no `SecurityException`, the
     * notification is simply not shown while the foreground service keeps
     * running regardless. Safe to call unconditionally.
     *
     * **The commonest form of "not granted" is "not answered yet."**
     * `LoadingScreen` starts the load and then puts the dialog up without
     * waiting for the answer (see that screen for why waiting would risk
     * never starting the load at all), so on the one install where the
     * dialog is ever shown, this service starts and posts into the pending
     * window. Every post inside it is silently dropped, including for a
     * user who is about to grant.
     *
     * That is bounded rather than permanent: the observer in [onCreate]
     * re-posts on every [QueueState] emission and [QueueState.Loading]
     * emits on each progress step, so the notification appears on the first
     * step after the grant — a second or two of a load that takes tens.
     * The one case it does not repair is a load that reaches
     * [QueueState.ModelReady] *inside* that window: that state emits once,
     * so its "Ready · tap to open" notification and its Unload action stay
     * missing until the next state change. Reaching ModelReady before the
     * user can dismiss a dialog means a load of a couple of seconds, which
     * is the case where the notification was worth least; the fix, if it
     * ever bites, is an explicit re-post triggered from the permission
     * result rather than making the load wait.
     */
    @SuppressLint("MissingPermission")
    private fun postNotification(notification: Notification) {
        NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, notification)
    }

    private fun startForegroundCompat(notification: Notification) {
        // specialUse — see AndroidManifest.xml's PROPERTY_SPECIAL_USE_FGS_SUBTYPE for the
        // rationale (none of the predefined FGS types describe on-device LLM inference).
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            localized.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = localized.getString(R.string.notification_channel_description)
            setSound(null, null)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(state: QueueState): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)

        when (state) {
            is QueueState.Loading -> {
                // QueueState.Loading carries only progress, not a model name/path — the
                // queue only learns the model's name once loadModel() returns. Showing a
                // generic label here is a deliberate scope call for TEN-27; surfacing the
                // requested model's name during Loading would mean threading it through
                // QueueState, which is out of this ticket's scope.
                builder
                    .setContentTitle(localized.getString(R.string.notification_loading_title))
                    .setContentText(localized.getString(R.string.notification_loading_progress, (state.progress * 100).toInt()))
                    .setProgress(100, (state.progress * 100).toInt(), false)
            }

            is QueueState.ModelReady -> {
                builder
                    .setContentTitle(localized.getString(R.string.notification_ready_title, state.info.name))
                    // LoadedModelInfo currently has no RAM-size field to surface here — see
                    // the ticket's "taille RAM si dispo" note; nothing to show yet.
                    .setContentText(localized.getString(R.string.notification_ready_text))
                    .addAction(R.drawable.ic_launcher_monochrome, localized.getString(R.string.notification_action_unload), actionPendingIntent(ACTION_UNLOAD))
            }

            is QueueState.Generating -> {
                builder
                    .setContentTitle(localized.getString(R.string.notification_generating_title, state.info.name))
                    .setProgress(0, 0, true)
                    .addAction(R.drawable.ic_launcher_monochrome, localized.getString(R.string.notification_action_stop), actionPendingIntent(ACTION_STOP_GENERATION))
            }

            QueueState.Idle -> {
                // Never actually rendered — the Idle branch in the observer stops the
                // service before this would be posted. Only reachable from the
                // synchronous bootstrap call in onCreate() if the service somehow starts
                // while already Idle.
                builder.setContentTitle(localized.getString(R.string.notification_idle_title))
            }
        }

        return builder.build()
    }

    private fun actionPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, InferenceService::class.java).setAction(action)
        return PendingIntent.getService(this, action.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        private const val CHANNEL_ID = "inference"
        private const val NOTIFICATION_ID = 1

        private const val ACTION_UNLOAD = "io.github.hokanosekai.tensai.action.UNLOAD"
        private const val ACTION_STOP_GENERATION = "io.github.hokanosekai.tensai.action.STOP_GENERATION"

        // TODO(V2 API server slot): once the API server lands as a second client of
        // RuntimeGraph.inferenceQueue (see InferenceQueue's KDoc), a Generating state
        // triggered by an API request is indistinguishable here from one triggered by
        // the Chat UI, since QueueState doesn't carry an origin. Not needed for V1
        // (single client); revisit if the notification should ever say who's asking.
    }
}
