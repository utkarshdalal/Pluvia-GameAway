package app.gamenative.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Context.NOTIFICATION_SERVICE
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.net.toUri
import app.gamenative.MainActivity
import app.gamenative.data.DownloadInfo
import app.gamenative.PrefManager
import app.gamenative.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class NotificationHelper @Inject constructor(@ApplicationContext private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "pluvia_foreground_service"
        private const val CHANNEL_NAME = "GameNative Foreground Service"
        private const val GROUP_KEY = "app.gamenative.services"

        const val NOTIFICATION_ID_STEAM = 1
        const val NOTIFICATION_ID_GOG = 2
        const val NOTIFICATION_ID_EPIC = 3
        const val NOTIFICATION_ID_AMAZON = 4
        const val NOTIFICATION_ID_DOWNLOAD = 5
        private const val NOTIFICATION_ID_SUMMARY = 100

        const val ACTION_EXIT = "com.oxgames.pluvia.EXIT"

        private const val NO_PROGRESS = -2
    }

    private val notificationManager: NotificationManager =
        context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager

    private val activeServices = mutableSetOf<Int>()

    @Volatile
    private var summaryContent: String = "Connected"

    init {
        createNotificationChannel()
    }

    private fun serviceNameFor(id: Int): String = when (id) {
        NOTIFICATION_ID_STEAM -> "Steam"
        NOTIFICATION_ID_GOG -> "GOG"
        NOTIFICATION_ID_EPIC -> "Epic Games"
        NOTIFICATION_ID_AMAZON -> "Amazon Games"
        NOTIFICATION_ID_DOWNLOAD -> "Downloads"
        else -> context.getString(R.string.app_name)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Allows to display GameNative foreground notifications"
            setShowBadge(false)
        }

        notificationManager.createNotificationChannel(channel)
    }

    @Synchronized
    fun notify(content: String, id: Int = NOTIFICATION_ID_STEAM) {
        summaryContent = content
        val notification = createServiceNotification(id, content)
        notificationManager.notify(id, notification)
        activeServices.add(id)
        refreshSummary()
    }

    /**
     * Updates a foreground-service notification to reflect an active data-transfer task
     * (download/cloud sync), with an optional progress bar. [progress]: 0..100 = determinate,
     * -1 = indeterminate (e.g. syncing), any other value = no progress bar.
     */
    @Synchronized
    fun notifyProgress(content: String, progress: Int, id: Int = NOTIFICATION_ID_STEAM) {
        summaryContent = content
        val notification = buildNotification(serviceNameFor(id), content, isSummary = false, progress = progress)
        notificationManager.notify(id, notification)
        activeServices.add(id)
        refreshSummary()
    }

    @Synchronized
    fun cancel(id: Int = NOTIFICATION_ID_STEAM) {
        notificationManager.cancel(id)
        if (activeServices.remove(id)) refreshSummary()
    }

    /**
     * Builds a per-service foreground notification. Each foreground service must
     * post its own notification (Android requires one notification per FGS), but
     * they share a notification group so the system collapses them into a single
     * "GameNative · Connected" entry in the shade.
     *
     * Callers must invoke [markActive] after their `startForeground(...)` call
     * so the group summary is posted/updated.
     */
    fun createServiceNotification(id: Int, content: String): Notification =
        buildNotification(
            title = serviceNameFor(id),
            content = content,
            isSummary = false,
        )

    /** Legacy single-notification helper. Defaults to the Steam service entry. */
    fun createForegroundNotification(content: String): Notification =
        createServiceNotification(NOTIFICATION_ID_STEAM, content)

    /**
     * Drives a live "Downloading <name> · X%" progress notification off [downloadInfo]'s existing
     * progress callbacks (event-driven, throttled to whole-percent changes). Reverts to idle when
     * the download finishes. Call once per download, per service [id].
     */
    fun trackDownload(downloadInfo: DownloadInfo, name: String, id: Int = NOTIFICATION_ID_STEAM) {
        val title = name.ifBlank { "your game" }
        var lastPct = -1
        downloadInfo.addProgressListener { fraction ->
            val pct = (fraction * 100f).toInt().coerceIn(0, 100)
            if (pct != lastPct) {
                lastPct = pct
                if (pct >= 100) showIdle(id) else notifyProgress("Downloading $title · $pct%", pct, id)
            }
        }
    }

    fun showSyncing(id: Int = NOTIFICATION_ID_STEAM) = notifyProgress("Syncing cloud saves…", -1, id)

    fun showIdle(id: Int = NOTIFICATION_ID_STEAM) = notifyProgress("Connected", -2, id)

    @Synchronized
    fun markActive(id: Int) {
        if (activeServices.add(id)) refreshSummary()
    }

    private fun refreshSummary() {
        if (activeServices.isEmpty()) {
            notificationManager.cancel(NOTIFICATION_ID_SUMMARY)
            return
        }
        notificationManager.notify(NOTIFICATION_ID_SUMMARY, buildSummary())
    }

    private fun buildSummary(): Notification = buildNotification(
        title = context.getString(R.string.app_name),
        content = summaryContent,
        isSummary = true,
    )

    private fun buildNotification(title: String, content: String, isSummary: Boolean, progress: Int = NO_PROGRESS): Notification {
        val intent = Intent(
            Intent.ACTION_VIEW,
            "pluvia://home".toUri(),
            context,
            MainActivity::class.java,
        ).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // Route Exit through a BroadcastReceiver, NOT through startForegroundService.
        // The latter would oblige whichever service was named in the Intent to call
        // startForeground(...) within ~5s of being started — but the ACTION_EXIT branch
        // in SteamService just emits EndProcess and returns, which crashes the app when
        // the targeted service wasn't already running (e.g. Exit tapped on a GOG
        // notification with no active Steam session).
        val stopIntent = Intent(context, NotificationActionReceiver::class.java).apply {
            action = ACTION_EXIT
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE,
        )

        val smallIconRes = if (PrefManager.useAltNotificationIcon) {
            R.drawable.ic_notification_alt
        } else {
            R.drawable.ic_notification
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(smallIconRes)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setAutoCancel(false)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setGroup(GROUP_KEY)
            .addAction(0, "Exit", stopPendingIntent)

        when {
            progress == NO_PROGRESS -> {}
            progress < 0 -> builder.setProgress(0, 0, true)
            else -> builder.setProgress(100, progress.coerceIn(0, 100), false)
        }

        if (isSummary) builder.setGroupSummary(true)

        return builder.build()
    }
}
