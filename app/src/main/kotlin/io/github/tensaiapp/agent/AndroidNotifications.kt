package io.github.tensaiapp.agent

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.tensaiapp.runtime.RuntimeGraph
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Catches notifications as they are posted, for `read_notifications` (TEN-211). Android binds it only once the user has
 * allowed tensai in Settings > Notification access; nothing in the app can turn that on.
 *
 * Records only while [ToolTier.PersonalData] is granted, and never tensai's own or an excluded app's (see
 * [NotificationReader.entryOf]). Losing the access clears what was caught; a disconnect with the access still on (the
 * system restarting the listener) asks to be bound again instead.
 */
class TensaiNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        connected = this
    }

    override fun onListenerDisconnected() {
        connected = null
        if (notificationAccessEnabled(this)) {
            requestRebind(ComponentName(this, TensaiNotificationListener::class.java))
        } else {
            RuntimeGraph.notificationBuffer.clear()
        }
    }

    override fun onDestroy() {
        RuntimeGraph.notificationBuffer.flush()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val posted = runCatching { postedOf(sbn, packageManager) }.getOrNull() ?: return
        // Off the main thread: the tier grant is read from the store there, see ToolSettingsRepository.granted.
        RuntimeGraph.applicationScope.launch {
            val settings = RuntimeGraph.toolSettings
            if (ToolTier.PersonalData !in settings.granted()) return@launch
            NotificationReader.entryOf(posted, settings.excludedNotificationApps.first())?.let(RuntimeGraph.notificationBuffer::add)
        }
    }

    internal companion object {
        /** The bound instance, for what is in the shade now: `null` while Android has not bound it. */
        @Volatile
        var connected: TensaiNotificationListener? = null
    }
}

/** [sbn] as plain fields. Messages come from `MessagingStyle`, the app name from the package manager (the package if hidden). */
internal fun postedOf(sbn: StatusBarNotification, packageManager: PackageManager): PostedNotification {
    val notification = sbn.notification
    val extras = notification.extras
    val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
    val label = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
    }.getOrDefault(sbn.packageName)
    val template = extras.getString(Notification.EXTRA_TEMPLATE).orEmpty()
    return PostedNotification(
        key = sbn.key,
        packageName = sbn.packageName,
        appLabel = label,
        postTime = sbn.postTime,
        title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
        text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
        bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
        messages = style?.messages.orEmpty().map { NotificationMessage(it.person?.name?.toString(), it.text?.toString().orEmpty()) },
        conversationTitle = style?.conversationTitle?.toString(),
        ongoing = sbn.isOngoing,
        foregroundService = notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0,
        groupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
        media = extras.containsKey(Notification.EXTRA_MEDIA_SESSION) || template.contains("MediaStyle"),
        progress = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0 || extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE),
    )
}

/** Notification access as Android reports it, and the shade through the bound [TensaiNotificationListener]. */
class AndroidNotificationAccess(private val context: Context, private val excluded: suspend () -> Set<String>) : NotificationAccess {
    override fun enabled(): Boolean = notificationAccessEnabled(context)

    override suspend fun active(): List<NotificationEntry> {
        val listener = TensaiNotificationListener.connected ?: return emptyList()
        val skip = excluded()
        val shade = runCatching { listener.activeNotifications.orEmpty().toList() }.getOrDefault(emptyList())
        return shade.mapNotNull { sbn -> runCatching { NotificationReader.entryOf(postedOf(sbn, context.packageManager), skip) }.getOrNull() }
    }
}

/** Whether the user allowed tensai in Android's Notification access. */
fun notificationAccessEnabled(context: Context): Boolean =
    context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

/** Android's Notification access screen, where only the user can allow tensai. */
fun notificationAccessSettingsIntent(): Intent =
    Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/**
 * [NotificationBuffer]'s file, under `noBackupFilesDir`: other people's messages are never in a backup or a device
 * transfer. Written to a temporary file then renamed, so a kill mid-write leaves the previous day, not half a file.
 */
class NotificationBufferFile(private val file: File) : NotificationFile {
    override fun read(): String? = runCatching { file.takeIf { it.exists() }?.readText() }.getOrNull()

    override fun write(text: String) {
        runCatching {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    override fun delete() {
        file.delete()
        File(file.parentFile, file.name + ".tmp").delete()
    }
}
