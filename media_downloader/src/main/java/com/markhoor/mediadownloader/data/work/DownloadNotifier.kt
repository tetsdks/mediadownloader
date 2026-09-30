package com.markhoor.mediadownloader.data.work

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.markhoor.mediadownloader.MediaDownloader
import com.markhoor.mediadownloader.R
import com.markhoor.mediadownloader.core.Constants.Download
import com.markhoor.mediadownloader.data.local.DownloadEntity

/**
 * The download notifications. A running download shows progress under [progressId]; its outcome
 * is posted under [resultId], because the progress notification belongs to the foreground service
 * and is removed with it when the job ends.
 *
 * Tapping one opens the host's chosen screen (else its launcher activity) with
 * [MediaDownloader.EXTRA_DOWNLOAD_ID].
 * Without the notification permission nothing is shown and the download carries on.
 */
internal class DownloadNotifier(
    private val context: Context,
    private val smallIcon: Int,
    private val openActivity: Class<out Activity>?,
) {

    private val manager = NotificationManagerCompat.from(context)

    fun progressId(id: Long): Int = (id * 2).toInt()

    private fun resultId(id: Long): Int = (id * 2 + 1).toInt()

    /** The notification of a running download; [totalBytes] `null` shows it indeterminate. */
    fun progress(entity: DownloadEntity, downloadedBytes: Long, totalBytes: Long?): Notification {
        ensureChannel()
        val total = totalBytes?.takeIf { it > 0 }
        val text = when {
            downloadedBytes <= 0 -> context.getString(R.string.media_downloader_preparing)
            total == null -> Formatter.formatShortFileSize(context, downloadedBytes)
            else -> context.getString(
                R.string.media_downloader_progress,
                Formatter.formatShortFileSize(context, downloadedBytes),
                Formatter.formatShortFileSize(context, total),
            )
        }
        val percent = total?.let { (downloadedBytes * 100 / it).toInt().coerceIn(0, 100) }
        return builder(entity)
            .setContentText(text)
            .setProgress(100, percent ?: 0, percent == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    fun showProgress(entity: DownloadEntity, downloadedBytes: Long, totalBytes: Long?) {
        post(progressId(entity.id), progress(entity, downloadedBytes, totalBytes))
    }

    fun showWaitingForNetwork(entity: DownloadEntity) {
        post(
            resultId(entity.id),
            builder(entity).setContentText(context.getString(R.string.media_downloader_waiting_for_network)).build(),
        )
    }

    fun showCompleted(entity: DownloadEntity) {
        manager.cancel(progressId(entity.id))
        post(
            resultId(entity.id),
            builder(entity)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentText(context.getString(R.string.media_downloader_completed))
                .setAutoCancel(true)
                .build(),
        )
    }

    fun showFailed(entity: DownloadEntity) = showFailure(entity, R.string.media_downloader_failed)

    /**
     * A download that failed because the media was never there to save - a private or deleted post,
     * a link already spent. "Download failed" reads as a fault the user could retry away; this says
     * what actually happened, and trying again would get the same answer.
     */
    fun showUnavailable(entity: DownloadEntity) =
        showFailure(entity, R.string.media_downloader_unavailable)

    private fun showFailure(entity: DownloadEntity, text: Int) {
        manager.cancel(progressId(entity.id))
        post(
            resultId(entity.id),
            builder(entity)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentText(context.getString(text))
                .setAutoCancel(true)
                .build(),
        )
    }

    /** Removes every notification of a download. */
    fun cancel(id: Long) {
        guarded {
            manager.cancel(progressId(id))
            manager.cancel(resultId(id))
        }
    }

    /**
     * Removes a running download's progress. The system removes it with the foreground service,
     * but where that start was refused the notification is the module's own and stays until this.
     */
    fun cancelProgress(id: Long) {
        guarded { manager.cancel(progressId(id)) }
    }

    /** Clears an outcome notification left from an earlier run, e.g. "waiting for a connection". */
    fun clearResult(id: Long) {
        guarded { manager.cancel(resultId(id)) }
    }

    private fun builder(entity: DownloadEntity): NotificationCompat.Builder =
        NotificationCompat.Builder(context, Download.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setContentTitle(entity.title.ifBlank { entity.fileName })
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .apply { openAppIntent(entity.id)?.let(::setContentIntent) }

    private fun openAppIntent(id: Long): PendingIntent? {
        val launch = openActivity?.let { Intent(context, it) }
            ?: context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        launch.putExtra(MediaDownloader.EXTRA_DOWNLOAD_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            resultId(id),
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel() {
        guarded {
            manager.createNotificationChannel(
                NotificationChannelCompat.Builder(Download.NOTIFICATION_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(context.getString(R.string.media_downloader_channel_name))
                    .setDescription(context.getString(R.string.media_downloader_channel_description))
                    .build(),
            )
        }
    }

    private fun post(notificationId: Int, notification: Notification) {
        val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (!permitted) return
        ensureChannel()
        guarded { manager.notify(notificationId, notification) }
    }

    /**
     * A notification is a report, never the download itself: the system can refuse one - a
     * permission revoked meanwhile, a notification service restarting - and the download carries on.
     */
    private inline fun guarded(action: () -> Unit) {
        try {
            action()
        } catch (e: RuntimeException) {
            Log.w(Download.LOG_TAG, "Notification not shown", e)
        }
    }
}
