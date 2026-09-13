package app.gamenative.service.download

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.gamenative.R
import app.gamenative.service.NotificationHelper
import timber.log.Timber

/**
 * Dedicated foreground service whose ONLY job is to anchor the process while
 * any store download is transferring. Store services (Steam/Epic/GOG/Amazon)
 * own their sessions (the Steam CM connection, store auth) and run the
 * download coroutines, but process survival during a download no longer
 * depends on any of them: [GameDownloadService] starts this service when the
 * first download becomes active and stops it when the queue drains — the same
 * transitions as the download wake lock.
 *
 * foregroundServiceType=dataSync, same as the store services.
 */
class DownloadForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Timber.tag(TAG).i("Download queue drained — stopping download foreground service")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val notification = NotificationHelper(this).createServiceNotification(
            NotificationHelper.NOTIFICATION_ID_DOWNLOAD,
            getString(R.string.downloads_notification_active),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationHelper.NOTIFICATION_ID_DOWNLOAD,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NotificationHelper.NOTIFICATION_ID_DOWNLOAD, notification)
        }
        return START_STICKY
    }

    companion object {
        private const val TAG = "DownloadFgService"
        private const val ACTION_STOP = "app.gamenative.action.STOP_DOWNLOAD_FG"

        fun start(context: Context) {
            try {
                val intent = Intent(context, DownloadForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // FGS-from-background restrictions can reject the start in edge
                // cases; the store services' own foreground state still anchors
                // the process, so log and continue.
                Timber.tag(TAG).w(e, "Failed to start download foreground service")
            }
        }

        fun stop(context: Context) {
            try {
                context.startService(
                    Intent(context, DownloadForegroundService::class.java).setAction(ACTION_STOP),
                )
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to stop download foreground service")
            }
        }
    }
}
