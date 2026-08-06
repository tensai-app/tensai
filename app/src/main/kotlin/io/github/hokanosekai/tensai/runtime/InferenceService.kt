package io.github.hokanosekai.tensai.runtime

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import io.github.hokanosekai.tensai.MainActivity
import io.github.hokanosekai.tensai.R
import kotlinx.coroutines.Job
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

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // startForeground must be called within a few seconds of the service
        // starting — post an initial notification synchronously from
        // whatever the current state already is, then let the observer
        // below keep it in sync from that point on.
        startForegroundCompat(buildNotification(RuntimeGraph.inferenceQueue.state.value))

        observerJob = RuntimeGraph.applicationScope.launch {
            RuntimeGraph.inferenceQueue.state.collect { state ->
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
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        RuntimeGraph.applicationScope.launch { RuntimeGraph.inferenceQueue.unloadModel() }
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        observerJob?.cancel()
        super.onDestroy()
    }

    /**
     * Without `POST_NOTIFICATIONS` granted (API 33+, not yet requested at
     * this point in V1 — see [rememberNotificationPermissionRequester]),
     * this call is a documented no-op: no `SecurityException`, the
     * notification is simply not shown while the foreground service keeps
     * running regardless. Safe to call unconditionally.
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
        val channel = NotificationChannel(CHANNEL_ID, "Inference", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Model loading and generation status"
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
                    .setContentTitle("Loading model…")
                    .setContentText("${(state.progress * 100).toInt()}%")
                    .setProgress(100, (state.progress * 100).toInt(), false)
            }

            is QueueState.ModelReady -> {
                builder
                    .setContentTitle("${state.info.name} loaded")
                    // LoadedModelInfo currently has no RAM-size field to surface here — see
                    // the ticket's "taille RAM si dispo" note; nothing to show yet.
                    .setContentText("Ready · tap to open")
                    .addAction(R.drawable.ic_launcher_monochrome, "Unload", actionPendingIntent(ACTION_UNLOAD))
            }

            is QueueState.Generating -> {
                builder
                    .setContentTitle("${state.info.name} is thinking…")
                    .setProgress(0, 0, true)
                    .addAction(R.drawable.ic_launcher_monochrome, "Stop", actionPendingIntent(ACTION_STOP_GENERATION))
            }

            QueueState.Idle -> {
                // Never actually rendered — the Idle branch in the observer stops the
                // service before this would be posted. Only reachable from the
                // synchronous bootstrap call in onCreate() if the service somehow starts
                // while already Idle.
                builder.setContentTitle("Idle")
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
