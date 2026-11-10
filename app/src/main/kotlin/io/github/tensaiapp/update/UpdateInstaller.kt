package io.github.tensaiapp.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import io.github.tensaiapp.runtime.AppForegroundTracker
import io.github.tensaiapp.runtime.RuntimeGraph
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Hands a verified update to Android's installer (TEN-227) through a `PackageInstaller` session: no file is shared
 * with another app, the bytes are copied into the session. The install is never silent: user action is required
 * explicitly (API 31+), and Android shows its own confirmation, see [UpdateInstallReceiver].
 */
class UpdateInstaller(private val context: Context) {

    /** Whether the user allowed tensai to install apps ("Install unknown apps"). */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        // A session an earlier attempt left open (process killed mid-copy) would only hold space.
        installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                // Mutable: the installer fills in the status and, for the confirmation, the intent to start.
                val status = PendingIntent.getBroadcast(
                    context,
                    id,
                    Intent(context, UpdateInstallReceiver::class.java).setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(status.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }

    companion object {
        /**
         * Android's confirmation, when it came while tensai was in the background: an activity started from there would
         * be blocked. [io.github.tensaiapp.ui.screens.update.UpdateCardHost] starts it on the next resume.
         */
        val pendingConfirmation = MutableStateFlow<Intent?>(null)

        /** Android's "Install unknown apps" page for this app. */
        fun permissionSettings(context: Context): Intent =
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
    }
}

/**
 * The session's status. Pending user action starts Android's confirmation dialog, at once in the foreground, else on
 * the next resume ([UpdateInstaller.pendingConfirmation]); success needs nothing (the update replaces this process); a
 * cancel or a failure goes back to [UpdateManager.onInstallResult].
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.i(UpdateLogTag, "installer status $status ${message.orEmpty()}")
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
                if (AppForegroundTracker.isForeground.value) {
                    context.startActivity(confirm)
                } else {
                    UpdateInstaller.pendingConfirmation.value = confirm
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> if (RuntimeGraph.isInitialized) {
                RuntimeGraph.updateManager.onInstallResult(aborted = status == PackageInstaller.STATUS_FAILURE_ABORTED, message = message)
            }
        }
    }
}

/** Logcat tag of the update check and install. */
const val UpdateLogTag = "tensai.update"
