package e7.gearscan

import android.app.Application
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
import e7.gearscan.state.ForegroundHost
import e7.gearscan.state.Work

class WorkService : Service(), ForegroundHost {
    private var lock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        live = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Launcher.shownWork?.cancel()
            return START_NOT_STICKY
        }
        val n = notification(this, Launcher.shownWork?.notice.orEmpty(), "Starting…", 0, 0)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) startForeground(ID, n)
        else startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        // a run that hangs must not drain the battery
        if (lock == null) lock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "e7gear:work")
            .apply { acquire(MAX_RUN_MS) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        lock?.takeIf { it.isHeld }?.release()
        lock = null
        if (live === this) live = null
        super.onDestroy()
    }

    override fun shown(work: Work<*>?) {}

    override fun progress(work: Work<*>, text: String, done: Int, total: Int) {
        getSystemService(NotificationManager::class.java).notify(ID, notification(this, work.notice, text, done, total))
    }

    class Launcher(private val app: Application) : ForegroundHost {
        override fun shown(work: Work<*>?) {
            shownWork = work
            if (work != null) app.startForegroundService(Intent(app, WorkService::class.java))
            else app.stopService(Intent(app, WorkService::class.java))
        }

        override fun progress(work: Work<*>, text: String, done: Int, total: Int) {
            live?.progress(work, text, done, total)
        }

        companion object {
            @Volatile var shownWork: Work<*>? = null
                private set
        }
    }

    companion object {
        const val ACTION_STOP = "e7.gearscan.WORK_STOP"
        private const val ID = 6
        private const val CHANNEL = "pool-run"
        private const val MAX_RUN_MS = 60 * 60 * 1000L
        @Volatile private var live: WorkService? = null

        private fun notification(ctx: Context, title: String, text: String, done: Int, total: Int): Notification {
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Search in progress", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(ctx, 7, Intent(ctx, MainActivity::class.java)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(ctx, 8, Intent(ctx, WorkService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
            return Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
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
