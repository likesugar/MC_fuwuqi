package com.mc.fuwuqi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder

/**
 * 前台服务：App 切后台 / 锁屏后服务器不被冻结或回收。
 *
 * 2026-10 线上问题复盘（客户端报 "Connection reset"）：
 * 1. 旧代码是「有通知权限才启动前台服务」，Android 13+ 用户拒绝通知后等于裸跑，
 *    切后台几分钟就被系统冻结/杀掉 → 端口直接关闭，客户端收到 RST。现在无条件启动。
 * 2. 缺 Wi-Fi 锁：息屏后 Wi-Fi 进省电模式，包被丢弃/重连换 IP，长连接一样会断。
 * 3. 缺 Doze 豁免：设备静止+息屏进入 Doze 会掐断网络（控制台页有提示入口）。
 * 4. 服务被系统重建时（START_STICKY，intent 为空）自动把服务器拉起来。
 */
class ServerService : Service() {

    companion object {
        private const val CH = "srv"
        private const val NOTI_ID = 1001
        private const val PREFS = "keepalive"
        private const val KEY_ID = "run_id"

        fun start(ctx: Context, id: String, name: String, port: Int) {
            val i = Intent(ctx, ServerService::class.java)
                .putExtra("id", id).putExtra("name", name).putExtra("port", port)
            try {
                ctx.startForegroundService(i)
            } catch (_: Exception) {
                try { ctx.startService(i) } catch (_: Exception) {}
            }
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, ServerService::class.java)) } catch (_: Exception) {}
        }

        /** 记下「这个服应该在跑」，服务被系统重建后据此自动拉起 */
        fun markRunning(ctx: Context, id: String) {
            ctx.getSharedPreferences(PREFS, 0).edit().putString(KEY_ID, id).apply()
        }

        fun clearRunning(ctx: Context) {
            ctx.getSharedPreferences(PREFS, 0).edit().remove(KEY_ID).apply()
        }
    }

    private var wifiLock: WifiManager.WifiLock? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastAttemptMs = 0L

    /** 存活看护：通知还在但 java 子进程被 LMK 杀掉时，这里负责把它拉回来 */
    private val tick = object : Runnable {
        override fun run() {
            watchdog()
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CH, "服务器运行中", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) }
        )
        acquireWifiLock()
        handler.postDelayed(tick, 20_000)
    }

    /** 息屏后别让 Wi-Fi 进省电模式：长连接最怕这个 */
    private fun acquireWifiLock() {
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "MCFuwuqi:server").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            ServerManager.stop()
            clearRunning(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val prefs = getSharedPreferences(PREFS, 0)
        val id = intent?.getStringExtra("id") ?: prefs.getString(KEY_ID, "").orEmpty()
        val srv = if (id.isNotEmpty()) Store.findServer(id) else null
        val name = intent?.getStringExtra("name") ?: srv?.optString("name") ?: "服务器"
        val port = intent?.getIntExtra("port", 25565) ?: srv?.optInt("port", 25565) ?: 25565
        if (id.isNotEmpty()) markRunning(this, id)

        // 必须在 5 秒内 startForeground（Android 12+）。这里不看通知权限：
        // 没有 POST_NOTIFICATIONS 时通知不展示，但服务照样是前台优先级，进程不会被冻结。
        try {
            startForeground(NOTI_ID, buildNotification(id, name, port))
        } catch (_: Exception) {}

        // intent 为空 = 系统重建的服务（进程被回收/被划掉），此时若服务器没在跑就拉起来
        if (intent == null) watchdog()
        return START_STICKY
    }

    private fun buildNotification(id: String, name: String, port: Int): Notification {
        val ip = ServerManager.localIp()
        val isCross = try { Store.findServer(id)?.optString("core") == Cores.CROSSPLAY } catch (_: Exception) { false }
        val where = if (ip.isEmpty()) "端口 $port" else "$ip:$port"
        val text = if (isCross) "Java $where · 基岩 $ip:19132" else "$where · 后台保活中"
        val b = Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentTitle("服务器运行中 · $name")
            .setContentText(text)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(
                this, 0,
                Intent(this, ConsoleActivity::class.java).putExtra("id", id),
                PendingIntent.FLAG_IMMUTABLE))
            .addAction(Notification.Action.Builder(
                null, "停止",
                PendingIntent.getService(this, 1,
                    Intent(this, ServerService::class.java).setAction("STOP"),
                    PendingIntent.FLAG_IMMUTABLE)).build())
        if (Build.VERSION.SDK_INT >= 31) {
            b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return b.build()
    }

    /** 系统重建服务后 / 子进程被回收时，把服务器重新拉起来 */
    private fun watchdog() {
        val id = getSharedPreferences(PREFS, 0).getString(KEY_ID, null) ?: return
        if (ServerManager.runningId == id || ServerManager.starting) return
        // 别和 ServerManager 自带的「崩溃 5 秒后重启」撞车
        val now = System.currentTimeMillis()
        if (now - lastAttemptMs < 20_000) return
        lastAttemptMs = now
        val srv = Store.findServer(id) ?: run { clearRunning(this); return }
        Store.initInternal(applicationContext)
        ServerManager.logLine("· 前台服务被系统重建，正在恢复服务器 …")
        ServerManager.start(this, srv) { r ->
            if (r.startsWith("ERR")) ServerManager.logLine("· 自动恢复失败：${r.removePrefix("ERR:")}")
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 从最近任务划掉不关服：服务器继续在后台跑，要停请用通知栏或 App 内按钮
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
        wifiLock = null
        super.onDestroy()
    }
}
