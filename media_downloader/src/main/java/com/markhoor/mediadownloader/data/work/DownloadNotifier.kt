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
import com.markhoor.mediadownloader.domain.models.DownloadState
import com.markhoor.mediadownloader.domain.models.remainingMillisOf

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
    /** Both asked each time: a host app may change them after start; see `MediaDownloader.reconfigure`. */
    private val smallIcon: () -> Int,
    private val openActivity: () -> Class<out Activity>?,
) {

    private val manager = NotificationManagerCompat.from(context)

    fun progressId(id: Long): Int = (id * 2).toInt()

    private fun resultId(id: Long): Int = (id * 2 + 1).toInt()

    /**
     * The notification of a running download: how much of how big on the line, how fast beside
     * it, how long is left in the header, and the bar. [totalBytes] `null` - a stream whose size
     * is still being worked out - leaves the bar indeterminate and says only what has arrived.
     *
     * Everything but the bar is left out rather than guessed. A speed needs two reports before it
     * exists and a time left needs a size, so the first seconds show neither and the line grows
     * into its full shape, rather than showing a zero that means "not known yet".
     */
    fun progress(
        entity: DownloadEntity,
        downloadedBytes: Long,
        totalBytes: Long?,
        bytesPerSecond: Long? = null,
    ): Notification {
        ensureChannel()
        val total = totalBytes?.takeIf { it > 0 }
        val speed = bytesPerSecond?.takeIf { it > 0 }?.let { Formatter.formatShortFileSize(context, it) }
        val got = Formatter.formatShortFileSize(context, downloadedBytes)
        val text = when {
            downloadedBytes <= 0 -> context.getString(R.string.media_downloader_preparing)
            total == null && speed == null -> got
            total == null -> context.getString(R.string.media_downloader_downloaded_with_speed, got, speed)
            speed == null ->
                context.getString(R.string.media_downloader_progress, got, Formatter.formatShortFileSize(context, total))
            else -> context.getString(
                R.string.media_downloader_progress_with_speed,
                got,
                Formatter.formatShortFileSize(context, total),
                speed,
            )
        }
        val percent = total?.let { (downloadedBytes * 100 / it).toInt().coerceIn(0, 100) }
        return builder(entity)
            .setContentText(text)
            .setSubText(timeLeftOrNull(remainingMillisOf(downloadedBytes, total, bytesPerSecond)))
            .setProgress(100, percent ?: 0, percent == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            // A clock on a notification that rewrites itself every second is noise. The time
            // worth showing is the one left, and that is in the header.
            .setShowWhen(false)
            .build()
    }

    fun showProgress(entity: DownloadEntity, downloadedBytes: Long, totalBytes: Long?, bytesPerSecond: Long? = null) {
        post(progressId(entity.id), progress(entity, downloadedBytes, totalBytes, bytesPerSecond))
    }

    /**
     * Every byte is in and the file is being finished - joined to its sound, stitched together,
     * copied into the public folder. Said in words, because the alternative is a full bar and a
     * time left of nothing, which is how a download still working looks exactly like one that has
     * hung. Indeterminate, because none of this part is counted in bytes.
     * See [DownloadState.Finishing].
     */
    fun finishing(entity: DownloadEntity): Notification {
        ensureChannel()
        return builder(entity)
            .setContentText(context.getString(R.string.media_downloader_finishing))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .build()
    }

    fun showFinishing(entity: DownloadEntity) {
        post(progressId(entity.id), finishing(entity))
    }

    /**
     * How long is left, in words. Minutes are rounded up so the last of them reads "1 min left"
     * and never "0 min left"; `null` - no size yet, nothing moving, or nearly done - leaves the
     * header empty rather than saying something untrue.
     */
    private fun timeLeftOrNull(remainingMillis: Long?): String? {
        val seconds = (remainingMillis ?: return null) / 1_000
        val minutes = (seconds + 59) / 60
        return when {
            seconds < 60 -> context.getString(R.string.media_downloader_time_left_seconds, seconds)
            minutes < 60 -> context.getString(R.string.media_downloader_time_left_minutes, minutes)
            else -> context.getString(R.string.media_downloader_time_left_hours, seconds / 3_600, seconds % 3_600 / 60)
        }
    }

    fun showWaitingForNetwork(entity: DownloadEntity) {
        post(
            resultId(entity.id),
            builder(entity).setContentText(context.getString(R.string.media_downloader_waiting_for_network)).build(),
        )
    }

    /** Says the size it came to as well: the one fact somebody checks a finished download for. */
    fun showCompleted(entity: DownloadEntity) {
        manager.cancel(progressId(entity.id))
        val done = context.getString(R.string.media_downloader_completed)
        val size = (entity.totalBytes ?: entity.downloadedBytes).takeIf { it > 0 }
        post(
            resultId(entity.id),
            builder(entity)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentText(
                    size?.let {
                        context.getString(
                            R.string.media_downloader_completed_with_size,
                            done,
                            Formatter.formatShortFileSize(context, it),
                        )
                    } ?: done,
                )
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
            .setSmallIcon(smallIcon())
            .setContentTitle(entity.title.ifBlank { entity.fileName })
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            // Shown when it is posted, rather than ten seconds later. The platform holds back a
            // foreground service's notification for about that long by default, so that a job
            // over in a moment never flashes one up - but a download is not that job, and the
            // wait was measured here at 10.4 seconds, by which time 44 MB of a 386 MB file had
            // arrived with nothing on screen to say a download had even started.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply { openAppIntent(entity.id)?.let(::setContentIntent) }

    private fun openAppIntent(id: Long): PendingIntent? {
        val launch = openActivity()?.let { Intent(context, it) }
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
