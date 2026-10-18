package e7.gearscan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Keeps a pool run going while the player is in the game or the screen is off: a foreground service with a progress
 * notification, and a wake lock so the CPU doesn't sleep mid-run. The run itself stays in [AppModel].
 */
class PoolService : Service() {
    private var lock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { onStop?.invoke(); stopSelf(); return START_NOT_STICKY }
        val n = notification(this, "Starting…", 0, 0)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) startForeground(ID, n)
        else startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (lock == null) lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "e7gear:pool")
            // a run that hangs must not drain the battery
            .apply { acquire(MAX_RUN_MS) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        lock?.takeIf { it.isHeld }?.release()
        lock = null
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "e7.gearscan.POOL_STOP"
        private const val ID = 6
        private const val CHANNEL = "pool-run"
        private const val MAX_RUN_MS = 60 * 60 * 1000L

        /** What Stop in the notification does; set by the model that started the run. */
        @Volatile var onStop: (() -> Unit)? = null

        fun start(ctx: Context, stop: () -> Unit) {
            onStop = stop
            ctx.startForegroundService(Intent(ctx, PoolService::class.java))
        }

        fun stop(ctx: Context) {
            onStop = null
            ctx.stopService(Intent(ctx, PoolService::class.java))
        }

        /** Replaces the running notification's text and bar. */
        fun progress(ctx: Context, text: String, done: Int, total: Int) {
            ctx.getSystemService(NotificationManager::class.java).notify(ID, notification(ctx, text, done, total))
        }

        private fun notification(ctx: Context, text: String, done: Int, total: Int): Notification {
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Pool run in progress", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(ctx, 7, Intent(ctx, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(ctx, 8, Intent(ctx, PoolService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
            return Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Optimizing pool")
                .setContentText(text)
                .setProgress(total, done, total == 0)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(open)
                .addAction(Notification.Action.Builder(null, "Stop", stop).build())
                .build()
        }
    }
}
