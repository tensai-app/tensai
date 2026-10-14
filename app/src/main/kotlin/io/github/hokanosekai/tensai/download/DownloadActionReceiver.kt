package io.github.hokanosekai.tensai.download

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.hokanosekai.tensai.runtime.RuntimeGraph
import kotlinx.coroutines.launch

/** Pause / Cancel buttons of the progress notification: they only call [DownloadManager]. */
class DownloadActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        val action = intent.action ?: return
        val pending = goAsync()
        RuntimeGraph.applicationScope.launch {
            try {
                when (action) {
                    ACTION_PAUSE -> RuntimeGraph.downloadManager.pause(id)
                    ACTION_CANCEL -> RuntimeGraph.downloadManager.cancel(id)
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PAUSE = "io.github.hokanosekai.tensai.action.DOWNLOAD_PAUSE"
        const val ACTION_CANCEL = "io.github.hokanosekai.tensai.action.DOWNLOAD_CANCEL"
        private const val EXTRA_ID = "downloadId"

        fun intent(context: Context, action: String, downloadId: String): PendingIntent = PendingIntent.getBroadcast(
            context,
            (action + downloadId).hashCode(),
            Intent(context, DownloadActionReceiver::class.java).setAction(action).putExtra(EXTRA_ID, downloadId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
