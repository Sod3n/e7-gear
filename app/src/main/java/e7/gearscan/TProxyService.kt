/*
 ============================================================================
 Name        : TProxyService.kt
 Author      : hev <r@hev.cc>, adapted for E7 Gear Scan
 Copyright   : Copyright (c) 2024 xyz
 Description : Per-app VPN for the game; TCP goes to the in-app CaptureProxy
 ============================================================================
 */

package e7.gearscan

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager.NameNotFoundException
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.IOException

class TProxyService : VpnService() {
    private var tunFd: ParcelFileDescriptor? = null
    private var proxy: CaptureProxy? = null
    private val handler = Handler(Looper.getMainLooper())
    private var alerted = false
    private val tick = object : Runnable {
        override fun run() {
            if (tunFd == null) return
            val kb = Capture.totalBytes() / 1024
            val lobby = kb >= LOBBY_KB
            getSystemService(NotificationManager::class.java).notify(1, progressNotification(kb, lobby))
            if (lobby && !alerted) { alerted = true; alertLobby() }
            handler.postDelayed(this, 2000)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // a null intent is a system restart after the process died; never resume capturing on our own
        if (intent == null || intent.action == ACTION_DISCONNECT) stopCapture()
        else startCapture(intent.getStringExtra(EXTRA_GAME)!!, intent.getStringExtra(EXTRA_UPSTREAM).orEmpty())
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        stopCapture()
        super.onRevoke()
    }

    private fun startCapture(game: String, upstream: String) {
        if (tunFd != null) return
        val proxy = try {
            CaptureProxy(upstream).also { it.start() }
        } catch (e: IOException) {
            stopSelf()
            return
        }
        this.proxy = proxy

        val builder = Builder()
            .setBlocking(false)
            .setMtu(MTU)
            .addAddress(TUN_IPV4, 32)
            .addRoute("0.0.0.0", 0)
            .addAddress(TUN_IPV6, 128)
            .addRoute("::", 0)
            .addDnsServer(MAPPED_DNS)
            .setSession("E7 Gear")
        try {
            builder.addAllowedApplication(game)
        } catch (e: NameNotFoundException) {
            stopCapture()
            return
        }
        val fd = builder.establish()
        if (fd == null) {
            stopCapture()
            return
        }
        tunFd = fd

        val conf = File(cacheDir, "tproxy.conf")
        try {
            conf.writeText("""
                |misc:
                |  task-stack-size: 81920
                |tunnel:
                |  mtu: $MTU
                |  icmp: 'reply'
                |socks5:
                |  port: ${proxy.port}
                |  address: '127.0.0.1'
                |  udp: 'udp'
                |mapdns:
                |  address: $MAPPED_DNS
                |  port: 53
                |  network: 240.0.0.0
                |  netmask: 240.0.0.0
                |  cache-size: 10000
                |""".trimMargin())
        } catch (e: IOException) {
            stopCapture()
            return
        }
        // fake-IP DNS (mapdns): names reach CaptureProxy as SOCKS domain requests, so no UDP is needed
        TProxyStartService(conf.absolutePath, fd.fd)
        alerted = false
        running = true
        startForegroundNotification()
        handler.postDelayed(tick, 2000)
    }

    private fun stopCapture() {
        handler.removeCallbacks(tick)
        running = false
        getSystemService(NotificationManager::class.java).cancel(2)
        tunFd?.let {
            stopForeground(STOP_FOREGROUND_REMOVE)
            TProxyStopService()
            runCatching { it.close() }
        }
        tunFd = null
        proxy?.stop()
        proxy = null
        stopSelf()
    }

    private fun openApp(): PendingIntent {
        val open = Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_CAPTURE, true)
            .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun progressNotification(kb: Long, lobby: Boolean): Notification =
        Notification.Builder(this, CHANNEL)
            .setContentTitle(if (lobby) "Scanning · your box has arrived" else "Scanning the game's login")
            .setContentText(when {
                lobby -> "$kb KB captured. Tap to come back and import."
                kb > 0 -> "$kb KB captured · waiting for the lobby"
                else -> "Log in to the game; waiting for its connection"
            })
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .build()

    /** One heads-up when the lobby data is in, since the progress notification is silent and the game hides the status bar. */
    private fun alertLobby() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "Scan ready to import", NotificationManager.IMPORTANCE_HIGH))
        nm.notify(2, Notification.Builder(this, CHANNEL_DONE)
            .setContentTitle("Your heroes and gear have arrived")
            .setContentText("Tap to go back to E7 Gear and import them.")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build())
    }

    private fun startForegroundNotification() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Scan in progress", NotificationManager.IMPORTANCE_LOW))
        val n = progressNotification(0, false)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) startForeground(1, n)
        else startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    companion object {
        const val ACTION_CONNECT = "e7.gearscan.CONNECT"
        const val ACTION_DISCONNECT = "e7.gearscan.DISCONNECT"
        const val EXTRA_GAME = "game"
        const val EXTRA_UPSTREAM = "upstream"
        /** Matches AppModel.lobbyLikely: the lobby's login reply carries the whole box. */
        const val LOBBY_KB = 100L
        private const val CHANNEL = "capture"
        private const val CHANNEL_DONE = "capture-done"

        @Volatile var running = false
            private set

        private const val TUN_IPV4 = "198.18.0.1"
        private const val TUN_IPV6 = "fc00::1"
        private const val MAPPED_DNS = "198.18.0.2"
        private const val MTU = 8500

        init {
            System.loadLibrary("hev-socks5-tunnel")
        }

        // registered by name from hev-jni.c onto this class, so they must stay static members of TProxyService
        @JvmStatic private external fun TProxyStartService(configPath: String, fd: Int): Boolean
        @JvmStatic private external fun TProxyStopService(): Boolean
        @JvmStatic private external fun TProxyIsRunning(): Boolean
        @JvmStatic private external fun TProxyGetStats(): LongArray
    }
}
