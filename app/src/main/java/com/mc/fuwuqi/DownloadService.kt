package com.mc.fuwuqi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/** 前台服务：下载期间保活，锁屏/后台不被冻结 */
class DownloadService : Service() {

    companion object {
        private const val CH = "dl"
        private const val NOTI_ID = 2001
        private var running = false
        @Volatile var title: String = "正在下载"
        @Volatile var cur: Long = 0
        @Volatile var total: Long = -1
        fun start(ctx: Context, t: String) {
            title = t
            ctx.startForegroundService(Intent(ctx, DownloadService::class.java))
        }
        fun stop(ctx: Context) {
            if (running) ctx.stopService(Intent(ctx, DownloadService::class.java))
        }
        fun progress(c: Long, t: Long) { cur = c; total = t }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val b = Notification.Builder(this@DownloadService, CH)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(if (total > 0) "${fmt(cur)} / ${fmt(total)}" else fmt(cur))
                .setProgress(100, if (total > 0) ((cur * 100) / total).toInt() else 0, total <= 0)
                .setOngoing(true)
            nm.notify(NOTI_ID, b.build())
            handler.postDelayed(this, 600)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH, "下载中", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (running) return START_STICKY
        running = true
        startForeground(NOTI_ID, Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title).setOngoing(true).build())
        handler.post(tick)
        return START_STICKY
    }
    override fun onDestroy() {
        running = false
        handler.removeCallbacks(tick)
        super.onDestroy()
    }
    private fun fmt(b: Long) = if (b >= 1_000_000) "${"%.1f".format(b / 1_000_000f)}MB" else "${b / 1024}KB"
}
