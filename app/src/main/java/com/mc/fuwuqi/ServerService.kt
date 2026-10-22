package com.mc.fuwuqi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import org.json.JSONObject

/** 前台服务：保活 + 状态通知 + 停止按钮 */
class ServerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("srv", "服务器运行中", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            ServerManager.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val id = intent?.getStringExtra("id") ?: ""
        val name = intent?.getStringExtra("name") ?: ""
        val port = intent?.getIntExtra("port", 25565) ?: 25565
        val b = Notification.Builder(this, "srv")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("服务器运行中 · $name")
            .setContentText("端口 $port · ${ServerManager.localIp()}:$port")
            .setOngoing(true)
            .setContentIntent(android.app.PendingIntent.getActivity(
                this, 0,
                Intent(this, ConsoleActivity::class.java).putExtra("id", id),
                android.app.PendingIntent.FLAG_IMMUTABLE))
            .addAction(Notification.Action.Builder(
                null, "停止",
                android.app.PendingIntent.getService(this, 1,
                    Intent(this, ServerService::class.java).setAction("STOP"),
                    android.app.PendingIntent.FLAG_IMMUTABLE)).build())
        startForeground(1001, b.build())
        return START_STICKY
    }
}
