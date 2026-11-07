package io.github.tensaiapp.agent

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
        group = style?.isGroupConversation == true,
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

    override fun replyable(): Set<String> {
        val listener = TensaiNotificationListener.connected ?: return emptySet()
        val shade = runCatching { listener.activeNotifications.orEmpty().toList() }.getOrDefault(emptyList())
        return shade.filter { replyAction(it.notification) != null }.map { it.key }.toSet()
    }

    /**
     * Fills the reply action's `RemoteInput`s with [text] and fires its PendingIntent, the way the shade's own inline
     * reply does. The action is the one on the notification showing now: the key alone, after a dismiss, is [ReplyOutcome.Gone].
     */
    override fun reply(key: String, text: String): ReplyOutcome {
        val listener = TensaiNotificationListener.connected ?: return ReplyOutcome.Gone
        val sbn = runCatching { listener.getActiveNotifications(arrayOf(key)) }.getOrNull()?.firstOrNull() ?: return ReplyOutcome.Gone
        val action = replyAction(sbn.notification) ?: return ReplyOutcome.NoAction
        val inputs = action.remoteInputs ?: return ReplyOutcome.NoAction
        val results = Bundle().apply {
            replyResults(inputs.map { RemoteInputInfo(it.resultKey, it.allowFreeFormInput) }, text).forEach { (k, v) -> putCharSequence(k, v) }
        }
        val intent = Intent()
        RemoteInput.addResultsToIntent(inputs, intent, results)
        return try {
            action.actionIntent.send(context, 0, intent)
            ReplyOutcome.Sent
        } catch (e: PendingIntent.CanceledException) {
            ReplyOutcome.Cancelled
        }
    }

    /**
     * [notification]'s reply action, see [pickReplyAction]. One whose PendingIntent is immutable (API 31+) drops the
     * filled-in text, so the app would get an empty reply: it is no reply action for tensai.
     */
    private fun replyAction(notification: Notification): Notification.Action? {
        val actions = notification.actions.orEmpty()
        val infos = actions.map { a ->
            ReplyActionInfo(a.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY, a.remoteInputs.orEmpty().any { it.allowFreeFormInput })
        }
        return pickReplyAction(infos)?.let { actions[it] }?.takeIf { action ->
            val intent = action.actionIntent
            intent != null && !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && intent.isImmutable)
        }
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
