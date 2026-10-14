package io.github.hokanosekai.tensai.download

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.text.format.Formatter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.ForegroundInfo
import io.github.hokanosekai.tensai.MainActivity
import io.github.hokanosekai.tensai.R

/**
 * The two notifications of a download: the ongoing progress one (also the foreground notification
 * of the worker) and the "<model> is ready" one.
 *
 * It never asks for `POST_NOTIFICATIONS` (TEN-75/127: the one ask is spent elsewhere). Without the
 * permission the foreground service still runs, the notification is simply not shown, and
 * [notifyReady] checks before posting.
 *
 * @param context already wrapped with `withAppLanguage`, so every string follows the app language.
 */
class DownloadNotifier(private val context: Context) {

    fun createChannels() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(PROGRESS_CHANNEL, context.getString(R.string.download_channel_name), NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = context.getString(R.string.download_channel_description)
                    setSound(null, null)
                },
        )
        manager.createNotificationChannel(
            NotificationChannel(READY_CHANNEL, context.getString(R.string.download_ready_channel_name), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = context.getString(R.string.download_ready_channel_description) },
        )
    }

    /** Foreground info of the worker: one progress notification, replaced as the download advances. */
    fun foregroundInfo(row: DownloadEntity, progress: DownloadProgress): ForegroundInfo {
        createChannels()
        return ForegroundInfo(PROGRESS_ID, progressNotification(row, progress), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    /**
     * Foreground info before any row is known (WorkManager asks for it up front for expedited
     * work): a bare "Downloads" notification, replaced by [update] as soon as a row runs.
     */
    fun idleForegroundInfo(): ForegroundInfo {
        createChannels()
        val notification = NotificationCompat.Builder(context, PROGRESS_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.download_channel_name))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp())
            .build()
        return ForegroundInfo(PROGRESS_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    /** Removes the progress notification, whoever posted it. Safe to call when there is none. */
    fun cancelProgress() {
        NotificationManagerCompat.from(context).cancel(PROGRESS_ID)
    }

    /** Re-posts the foreground notification (same id) for the row now running. */
    @SuppressLint("MissingPermission")
    fun update(row: DownloadEntity, progress: DownloadProgress) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        manager.notify(PROGRESS_ID, progressNotification(row, progress))
    }

    /** "Download failed · <reason>" with the model name as text. Never asks for permission. */
    @SuppressLint("MissingPermission")
    fun notifyFailed(row: DownloadEntity, error: DownloadError) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        createChannels()
        val notification = NotificationCompat.Builder(context, READY_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.download_failed_title, reason(error)))
            .setContentText(row.displayName)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        manager.notify(readyId(row.id), notification)
    }

    private fun reason(error: DownloadError): String = when (error) {
        DownloadError.Gated -> context.getString(R.string.download_error_gated)
        DownloadError.InvalidToken -> context.getString(R.string.download_error_invalid_token)
        DownloadError.NotFound -> context.getString(R.string.download_error_not_found)
        DownloadError.ChecksumMismatch -> context.getString(R.string.download_error_checksum)
        DownloadError.SizeMismatch -> context.getString(R.string.download_error_size)
        DownloadError.DiskFull -> context.getString(R.string.download_error_disk_full)
        DownloadError.Network -> context.getString(R.string.download_error_network)
        DownloadError.AlreadyImported -> context.getString(R.string.download_error_already_imported)
        DownloadError.InvalidModel -> context.getString(R.string.download_error_invalid_model)
        is DownloadError.Http -> context.getString(R.string.download_error_http, error.status)
        DownloadError.Unknown -> context.getString(R.string.download_error_unknown)
    }

    private fun progressNotification(row: DownloadEntity, progress: DownloadProgress): Notification {
        val sizes = context.getString(
            R.string.download_notification_size,
            Formatter.formatShortFileSize(context, progress.bytesDone),
            Formatter.formatShortFileSize(context, progress.totalBytes),
        )
        val text = if (progress.bytesPerSecond > 0 && progress.etaSeconds != null) {
            context.getString(
                R.string.download_notification_detail,
                sizes,
                Formatter.formatShortFileSize(context, progress.bytesPerSecond),
                formatEta(progress.etaSeconds),
            )
        } else {
            sizes
        }
        val percent = if (progress.totalBytes > 0) (progress.bytesDone * 100 / progress.totalBytes).toInt() else 0
        return NotificationCompat.Builder(context, PROGRESS_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(row.displayName)
            .setContentText(text)
            .setProgress(100, percent, false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp())
            .addAction(0, context.getString(R.string.download_action_pause), DownloadActionReceiver.intent(context, DownloadActionReceiver.ACTION_PAUSE, row.id))
            .addAction(0, context.getString(R.string.download_action_cancel), DownloadActionReceiver.intent(context, DownloadActionReceiver.ACTION_CANCEL, row.id))
            .build()
    }

    /**
     * "<model> is ready" with a Load action. [modelId] is the new library row: the action opens the
     * app on the existing load screen for it (see [LoadRequests]).
     */
    @SuppressLint("MissingPermission")
    fun notifyReady(row: DownloadEntity, modelId: String) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return
        createChannels()
        val load = PendingIntent.getActivity(
            context,
            modelId.hashCode(),
            Intent(context, MainActivity::class.java)
                .setAction(LoadRequests.ACTION_LOAD_MODEL)
                .putExtra(LoadRequests.EXTRA_MODEL_ID, modelId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, READY_CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.download_ready_title, row.displayName))
            .setContentText(context.getString(R.string.download_ready_text))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .addAction(0, context.getString(R.string.download_action_load), load)
            .build()
        manager.notify(readyId(row.id), notification)
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun formatEta(seconds: Long): String = when {
        seconds >= 3600 -> context.getString(R.string.download_eta_hours, seconds / 3600, (seconds % 3600) / 60)
        seconds >= 60 -> context.getString(R.string.download_eta_minutes, (seconds + 30) / 60)
        else -> context.getString(R.string.download_eta_seconds, seconds.coerceAtLeast(1))
    }

    companion object {
        const val PROGRESS_CHANNEL = "downloads"
        const val READY_CHANNEL = "downloads_ready"
        const val PROGRESS_ID = 1001

        private fun readyId(downloadId: String) = 2000 + (downloadId.hashCode() and 0xFFFF)
    }
}
