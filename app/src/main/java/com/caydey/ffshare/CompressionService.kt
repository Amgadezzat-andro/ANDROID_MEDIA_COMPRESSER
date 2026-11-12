package com.caydey.ffshare

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.caydey.ffshare.utils.Utils
import timber.log.Timber

// keeps the app alive (and the cpu awake) while a batch compresses, so the user can leave the app
class CompressionService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = progressNotification(this, getString(R.string.notification_starting), 0, 0)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(PROGRESS_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(PROGRESS_NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            // battery saver or a used up background time budget can refuse it. the batch still runs while
            // the app is open, it just can't keep going in the background
            Timber.e(e, "Foreground service refused")
            stopSelf()
            return START_NOT_STICKY
        }
        isRunning = true
        // a small batch can finish before android gets this service going (slow phone, battery saver).
        // it was asked to stop while starting, which only works now that startForeground has been called
        if (!batchActive) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            // screen off would otherwise pause compression; timeout is a safety net if we never get stopped
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FFShare:compress").apply {
                acquire(WAKE_LOCK_TIMEOUT)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "compression"
        private const val PROGRESS_NOTIFICATION_ID = 1
        private const val DONE_NOTIFICATION_ID = 2
        private const val WAKE_LOCK_TIMEOUT = 6 * 60 * 60 * 1000L
        // android drops notification updates sent faster than a few per second
        private const val UPDATE_INTERVAL = 500L

        @Volatile private var lastUpdate = 0L
        @Volatile var isRunning = false
            private set

        // false once the batch ended, a service still starting up then stops itself right away
        @Volatile private var batchActive = false

        fun start(context: Context) {
            batchActive = true
            try {
                ContextCompat.startForegroundService(context, Intent(context, CompressionService::class.java))
            } catch (e: Exception) {
                // not allowed right now (e.g. app not in front), compressing works without it
                Timber.e(e, "Unable to start compression service")
            }
        }

        // stopping a service that hasn't called startForeground yet crashes the app, so one that is still
        // starting is only flagged and stops itself in onStartCommand
        fun stop(context: Context) {
            batchActive = false
            if (isRunning) {
                context.stopService(Intent(context, CompressionService::class.java))
            }
        }

        fun updateProgress(context: Context, done: Int, total: Int, savedBytes: Long, timeLeft: String?) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastUpdate < UPDATE_INTERVAL && done < total) return
            lastUpdate = now
            var text = if (total == 1) {
                context.getString(R.string.progress_single)
            } else {
                context.getString(R.string.notification_progress, done, total, Utils(context).bytesToHuman(maxOf(savedBytes, 0L)))
            }
            if (timeLeft != null) text += " · $timeLeft"
            // a single file has no "x of y", its bar runs indeterminate
            val progressTotal = if (total == 1) 0 else total
            notify(context, PROGRESS_NOTIFICATION_ID, progressNotification(context, text, done, progressTotal))
        }

        // shown when a batch ends while the user is in another app
        fun showDone(context: Context, title: String, text: String, openResults: Boolean) {
            createChannel(context)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(if (openResults) openProgressIntent(context) else openAppIntent(context))
                .build()
            notify(context, DONE_NOTIFICATION_ID, notification)
        }

        private fun progressNotification(context: Context, text: String, done: Int, total: Int): Notification {
            createChannel(context)
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.notification_title))
                .setContentText(text)
                .setProgress(total, done, total == 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setContentIntent(openProgressIntent(context))
                .build()
        }

        private fun openProgressIntent(context: Context): PendingIntent {
            val intent = Intent(context, HandleMediaActivity::class.java)
                .putExtra(HandleMediaActivity.EXTRA_FROM_NOTIFICATION, true)
            return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        private fun openAppIntent(context: Context): PendingIntent {
            // back to the main screen the app already has open, not a second one on top of it
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        private fun notify(context: Context, id: Int, notification: Notification) {
            // without the permission (android 13+) the work still runs, just without a visible notification
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return
            }
            NotificationManagerCompat.from(context).notify(id, notification)
        }

        private fun createChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
