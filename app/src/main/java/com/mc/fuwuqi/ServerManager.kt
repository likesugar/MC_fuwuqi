package com.mc.fuwuqi

import android.os.Environment
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Inet4Address
import java.net.NetworkInterface
import org.json.JSONObject

/**
 * 服务器进程管理：proot 跑 java。
 * 单例持有当前运行中的服（同一时间一个），控制台输出环形缓冲。
 */
object ServerManager {
    private val backupHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var backupRunnable: Runnable? = null
    private val KEEP = 5

    private var proc: Process? = null
    @Volatile private var userStop = false
    @Volatile private var restartCount = 0
    @Volatile private var lastCtx: android.content.Context? = null
    private var stdin: PrintWriter? = null
    private var watcher: Thread? = null

    @Volatile var runningId: String? = null
        private set
    @Volatile var starting: Boolean = false
        private set

    private val buf = StringBuilder()
    private val listeners = mutableListOf<(String) -> Unit>()
    private var wakeLock: android.os.PowerManager.WakeLock? = null

    const val MAX_BUF = 100_000

    fun onLine(cb: (String) -> Unit) { synchronized(listeners) { listeners.add(cb) } }
    fun offLine(cb: (String) -> Unit) { synchronized(listeners) { listeners.remove(cb) } }

    private fun emit(s: String) {
        synchronized(buf) {
            buf.append(s).append('\n')
            if (buf.length > MAX_BUF) buf.delete(0, buf.length - MAX_BUF)
        }
        val line = s
        synchronized(listeners) { listeners.toList().forEach { it(line) } }
    }

    fun consoleText(): String = synchronized(buf) { buf.toString() }

    fun logLine(s: String) { emit(s) }
    fun serverPid(): Int? = try {
        val p = proc ?: return null
        val f = p.javaClass.getDeclaredField("pid"); f.isAccessible = true; f.getInt(p)
    } catch (_: Exception) { null }

    private fun notifyCrash(ctx: android.content.Context?, restarting: Boolean) {
        try {
            val c = ctx ?: return
            val nm = c.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            nm.createNotificationChannel(android.app.NotificationChannel("crash", "服务器崩溃", android.app.NotificationManager.IMPORTANCE_DEFAULT))
            val b = android.app.Notification.Builder(c, "crash")
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle(if (restarting) "⚠️ 服务器崩溃" else "⚠️ 服务器反复崩溃")
                .setContentText(if (restarting) "正在自动重启…" else "已停止自动重启，请检查内存/插件")
                .setAutoCancel(true)
            nm.notify(3001, b.build())
        } catch (_: Exception) {}
    }
    fun isRunning(id: String): Boolean = runningId == id && proc?.isAlive == true

    fun localIp(): String = try {
        var found = ""
        val nis = NetworkInterface.getNetworkInterfaces()
        for (ni in nis) {
            for (ia in ni.inetAddresses) {
                if (!ia.isLoopbackAddress && ia is Inet4Address) { found = ia.hostAddress ?: ""; break }
            }
            if (found.isNotEmpty()) break
        }
        found
    } catch (e: Exception) { "" }

    /** 启动（异步，进度经 cb 回调）。成功回调 "OK"，失败回调 "ERR:xxx" */
    fun start(ctx: android.content.Context, srv: JSONObject, cb: (String) -> Unit) {
        userStop = false; restartCount = 0; lastCtx = ctx
        val sid = srv.optString("id")
        val periodic = object : Runnable {
            override fun run() {
                if (isRunning(sid)) autoBackup(sid)
                backupHandler.postDelayed(this, 30 * 60 * 1000L)
            }
        }
        backupHandler.postDelayed(periodic, 30 * 60 * 1000L)
        backupRunnable = periodic
        if (runningId != null || starting) { cb("ERR:已有服务器在运行"); return }
        starting = true
        Store.initInternal(ctx.applicationContext)
        Thread {
            try {
                val appCtx = ctx.applicationContext
                if (!Container.hasProot(appCtx)) throw RuntimeException("proot 未就绪")
                if (!Store.hasRootfs()) {
                    Container.ensureRootfs(appCtx) { emit("· $it") }
                }
                val jv = srv.optInt("javaVersion", Container.javaForMc(srv.optString("mcVersion")))
                if (!Store.hasJdk(jv)) Container.downloadJdk(jv) { emit("· $it") }
                val dir = Store.serverDir(srv.optString("id"))
                dir.mkdirs()
                if (!File(dir, "server.jar").isFile) throw RuntimeException("server.jar 缺失")
                // eula + 默认配置
                val eula = File(dir, "eula.txt")
                if (!eula.exists()) eula.writeText("#Auto\neula=true\n")
                val props = File(dir, "server.properties")
                if (!props.exists()) {
                    val custom = srv.optJSONObject("props")
                    if (custom != null) {
                        props.writeText(custom.keys().asSequence().joinToString("\n") { "$it=${custom.optString(it)}" } + "\n")
                    }
                }
                if (!props.exists()) {
                    props.writeText("server-port=${srv.optInt("port", 25565)}\n" +
                        "motd=${srv.optString("motd", "A Minecraft Server")}\n" +
                        "max-players=${srv.optInt("maxPlayers", 20)}\n" +
                        "online-mode=${srv.optBoolean("onlineMode", false)}\n" +
                        "difficulty=${srv.optString("difficulty", "easy")}\ngamemode=${srv.optString("gamemode", "survival")}\nenable-rcon=false\nspawn-protection=0\nview-distance=6\nsimulation-distance=4\nsync-chunk-writes=false\nuse-native-transport=true\nnetwork-compression-threshold=256\nmax-tick-time=-1\n")
                }
                // 互通服：每次启动校正 Geyser 配置（老版本 App 建的服会写成 Geyser 2.x 不认的旧键名）
                if (srv.optString("core") == Cores.CROSSPLAY) {
                    try {
                        Cores.ensureCrossplayConfig(dir, srv.optBoolean("onlineMode", false), srv.optInt("port", 25565))
                        emit("· 互通配置已校正：Geyser 基岩端口 19132（UDP）")
                    } catch (e: Exception) { emit("· 互通配置校正失败：${e.message}") }
                    // 这版 Geyser 支持的基岩版版本：手机端必须一致，否则一直卡在“正在连接”
                    Cores.bedrockVersion(File(dir, "plugins/Geyser-Spigot.jar"))?.let { bv ->
                        emit("· 基岩版对应版本 $bv（手机端需同版本，地址 IP:19132）")
                        if (bv != srv.optString("bedrockVersion"))
                            Store.updateServer(srv.optString("id"), JSONObject().put("bedrockVersion", bv))
                    }
                }
                val mem = srv.optInt("memoryMb", 2048)
                val flags = appCtx.getSharedPreferences("app", 0)
                    .getString("jvm_flags", DEFAULT_FLAGS) ?: DEFAULT_FLAGS

                val proot = Container.prootBin(appCtx).absolutePath
                val loader = File(appCtx.applicationInfo.nativeLibraryDir, "libprootloader.so").absolutePath
                val rootfs = Store.rootfsDir().absolutePath
                val cmd = listOf(
                    proot,
                    "-r", rootfs,
                    "-0", "-w", "/sdcard" + dir.absolutePath.removePrefix("/storage/emulated/0"),
                    "-b", "/dev", "-b", "/proc", "-b", "/sys",
                    "-b", "/sdcard:/sdcard",
                    "-b", "/storage/emulated/0:/storage/emulated/0",
                    "--kill-on-exit",
                    "/opt/jdk-$jv/bin/java", "-Xms${mem}M", "-Xmx${mem}M",
                    "-Dfile.encoding=UTF-8", "-Dterminal.jline=false", "-Dterminal.ansi=true",
                    "-Dlog4j2.formatMsgNoLookups=true"
                ) + flags.split(Regex("\\s+")).filter { it.isNotBlank() } +
                    listOf("-jar", "server.jar", "nogui")

                emit("· 启动：Java $jv / ${mem}MB / ${srv.optString("core")} ${srv.optString("mcVersion")}")
                synchronized(this) {
                    val pb = ProcessBuilder(cmd)
                    pb.redirectErrorStream(true)
                    pb.environment()["HOME"] = "/root"
                    pb.environment()["PATH"] = "/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin"
                    pb.environment()["LANG"] = "C.UTF-8"
                    pb.environment()["LC_ALL"] = "C.UTF-8"
                    pb.environment()["TERM"] = "xterm-256color"
                    pb.environment()["PROOT_TMP_DIR"] = Store.runDir.absolutePath
                    pb.environment()["PROOT_LOADER"] = loader
                    pb.environment()["PROOT_DONT_POLLUTE_ROOTFS"] = "1"
                    pb.environment()["PROOT_NO_SECCOMP"] = "1"
                    pb.environment()["LD_LIBRARY_PATH"] = appCtx.applicationInfo.nativeLibraryDir
                    proc = pb.start()
                    runningId = srv.optString("id")
                    stdin = PrintWriter(proc!!.outputStream, true)
                    UiBus.post()
                }
                cb("OK")
                // 输出泵 + logs/console.log 镜像（参照物同款）
                val logFile = File(dir, "logs/console.log").apply { parentFile?.mkdirs(); if (!exists()) writeText("") }
                val logOut = java.io.BufferedWriter(java.io.OutputStreamWriter(java.io.FileOutputStream(logFile, true)))
                val p = proc!!
                val t = Thread {
                    try {
                        BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8)).use { r ->
                            while (true) {
                                val l = r.readLine() ?: break
                                emit(l)
                                try { logOut.write(l); logOut.newLine(); logOut.flush() } catch (_: Exception) {}
                            }
                        }
                    } catch (_: Exception) {}
                    val code = try { p.waitFor(); p.exitValue() } catch (e: Exception) {
                        try { p.destroyForcibly(); p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
                        -1
                    }
                    emit("· 进程已退出 exit=$code")
                    UiBus.post()
                    val crashed = !userStop && runningId == srv.optString("id") &&
                        code != 0 && code != 143 && code != 137
                    synchronized(this) {
                        if (runningId == srv.optString("id")) {
                            runningId = null; stdin = null; proc = null
                            try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Exception) {}
                            wakeLock = null
                        }
                    }
                    // 9/5：异常退出自动重启（连续 3 次放弃，防 OOM 死循环）
                    if (crashed) {
                        if (restartCount >= 2) {
                            logLine("· 已连续崩溃 3 次，停止自动重启（请检查内存/插件）")
                            notifyCrash(lastCtx, false)
                        } else {
                            restartCount++
                            logLine("· 服务器崩溃，5 秒后自动重启（第 $restartCount 次）…")
                            notifyCrash(lastCtx, true)
                            try { Thread.sleep(5000) } catch (_: Exception) {}
                            start(lastCtx ?: return@Thread, srv) {
                                logLine(if (it == "OK") "· 自动重启成功" else "· 自动重启失败：$it")
                            }
                        }
                    }
                }
                t.isDaemon = true
                t.start()
                // 唤醒锁：防深睡
                val pm = appCtx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
                wakeLock = pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "MCFuwuqi:server")
                wakeLock?.acquire()
            } catch (e: Exception) {
                emit("· 启动失败：${e.message}")
                cb("ERR:${e.message}")
            } finally {
                starting = false
            }
        }.start()
    }

    fun send(cmd: String): Boolean {
        val w = stdin ?: return false
        w.println(cmd); return true
    }

    // ---------- 自动备份（7/5） ----------
    fun autoBackup(id: String) {
        Thread {
            try {
                val dir = Store.serverDir(id)
                val world = java.io.File(dir, "world")
                if (!world.isDirectory) return@Thread
                val out = java.io.File(Store.base, "backups").apply { mkdirs() }
                val f = java.io.File(out, "${id}-${System.currentTimeMillis()}.zip")
                val zos = java.util.zip.ZipOutputStream(java.io.FileOutputStream(f))
                dir.walkTopDown().filter { it.isFile && it.name != "server.jar" &&
                    !it.name.endsWith(".part") && !it.absolutePath.contains("backups") }.forEach { file ->
                    zos.putNextEntry(java.util.zip.ZipEntry(dir.toPath().relativize(file.toPath()).toString()))
                    java.io.FileInputStream(file).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
                zos.close()
                // 保留最近 KEEP 份
                out.listFiles()?.filter { it.name.startsWith("$id-") }?.sortedBy { it.name }?.let { list ->
                    if (list.size > KEEP) list.take(list.size - KEEP).forEach { it.delete() }
                }
                logLine("已自动备份：${f.name}")
            } catch (e: Exception) { logLine("自动备份失败：${e.message}") }
        }.start()
    }

    fun stop(): Boolean {
        userStop = true
        backupRunnable?.let { backupHandler.removeCallbacks(it) }; backupRunnable = null
        autoBackup(runningId ?: "")
        val ok = send("stop")
        // 优雅停止给 4 秒，超时强杀（proot + java 一起收）
        Thread {
            val p = proc
            if (p != null) {
                try { p.waitFor(4, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
                if (p.isAlive) {
                    try {
                        p.destroy()
                        p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                    } catch (_: Exception) {}
                    if (p.isAlive) p.destroyForcibly()
                }
                try { p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
            }
        }.start()
        return ok
    }

    const val DEFAULT_FLAGS =
        "-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200 " +
        "-XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:+AlwaysPreTouch " +
        "-XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M " +
        "-XX:G1ReservePercent=20 -XX:InitiatingHeapOccupancyPercent=15 -XX:G1MixedGCCountTarget=32 " +
        "-XX:SurvivorRatio=32 -XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1 " +
        "-Dusing.aikars.flags=https://mcflags.emc.gs -Daikars.new.flags=true"
}
