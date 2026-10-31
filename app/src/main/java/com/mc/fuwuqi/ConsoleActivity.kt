package com.mc.fuwuqi

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/** 控制台：日志 + 命令输入 + 联机地址 + 启停（vc25 语义回滚版） */
class ConsoleActivity : androidx.appcompat.app.AppCompatActivity() {

    private var id = ""
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var statusDot: TextView
    private lateinit var stopBtn: TextView
    private lateinit var addrCard: LinearLayout
    private lateinit var addrText: TextView
    private lateinit var bedrockText: TextView
    private lateinit var connHint: TextView
    private lateinit var keepAliveTip: TextView
    private lateinit var listenState: TextView
    private lateinit var input: EditText
    private lateinit var quick: LinearLayout
    private var following = false
    private lateinit var perfText: TextView
    private lateinit var perfHandler: android.os.Handler
    private lateinit var perfTick: Runnable
    private var readyNotified = false
    private var bvLoading = false

    private val lineCb: (String) -> Unit = { line ->
        runOnUiThread {
            if (!readyNotified && line.contains("Done (")) {
                readyNotified = true
                Ui.toast(this, "服务器开服了")
                logView.gravity = Gravity.LEFT
                render()
            }
            appendColored(line, lineColor(line))
            // 玩家进出 → 系统通知（APP 在后台也能收到）
            Regex("([A-Za-z0-9_.\\-]{2,16}) joined the game").find(line)?.let { m ->
                notifyPlayer(m.groupValues[1].trim(), true)
            }
            Regex("([A-Za-z0-9_.\\-]{2,16}) left the game").find(line)?.let { m ->
                notifyPlayer(m.groupValues[1].trim(), false)
            }
            if (following) logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        id = intent.getStringExtra("id") ?: ""
        val srv = Store.findServer(id) ?: JSONObject()
        val root = Ui.linear(this, true)
        root.setBackgroundColor(getColor(R.color.bg))

        // 顶栏：返回 + 名称/状态 + 停止/启动
        val bar = Ui.linear(this, false)
        bar.setPadding(Ui.px(this, 20), Ui.px(this, 36), Ui.px(this, 20), Ui.px(this, 10))
        bar.gravity = Gravity.CENTER_VERTICAL
        val back = Ui.text(this, "←", 24f, getColor(R.color.text))
        back.setPadding(0, 0, Ui.px(this, 16), 0)
        back.setOnClickListener { finish() }
        bar.addView(back)
        val col = Ui.linear(this, true)
        col.addView(Ui.text(this, srv.optString("name", "我的服务器"), 22f, getColor(R.color.text), true))
        statusDot = Ui.text(this, "", 13f, getColor(R.color.text_sub))
        col.addView(statusDot)
        bar.addView(col, Ui.lp(0, -2, 1f))
        stopBtn = Ui.text(this, "启动", 18f, getColor(R.color.green), true)
        stopBtn.setPadding(Ui.px(this, 14), 0, 0, 0)
        stopBtn.setOnClickListener { stopOrStart() }
        bar.addView(stopBtn)
        val menu = Ui.text(this, "⋮", 22f, getColor(R.color.text_sub), true)
        menu.setPadding(Ui.px(this, 14), 0, 0, 0)
        menu.setOnClickListener {
            val pm = android.widget.PopupMenu(this, menu)
            pm.menu.add("服务器配置").setOnMenuItemClickListener {
                startActivity(Intent(this, ConfigActivity::class.java).putExtra("id", id)); true
            }
            // Paper 插件只适用于 Bukkit 系核心（Paper/互通服/Purpur）
            if (Cores.isBukkitLike(srv.optString("core"))) pm.menu.add("插件管理").setOnMenuItemClickListener {
                startActivity(Intent(this, PluginActivity::class.java)
                    .putExtra("id", id).putExtra("mcVersion", srv.optString("mcVersion"))); true
            }
            pm.menu.add("备份世界").setOnMenuItemClickListener { backupWorld(); true }
            pm.menu.add(if (following) "恢复日志跟随" else "暂停日志跟随").setOnMenuItemClickListener {
                following = !following
                if (following) {
                    logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
                    Ui.toast(this, "已恢复日志跟随")
                } else Ui.toast(this, "已暂停日志跟随")
                true
            }
            val del = pm.menu.add("删除服务器")
            del.title = android.text.SpannableString("删除服务器").apply {
                setSpan(android.text.style.ForegroundColorSpan(0xFFC0392B.toInt()), 0, length, 0)
            }
            del.setOnMenuItemClickListener { deleteServer(); true }
            pm.show()
        }
        bar.addView(menu)
        root.addView(bar)

        // 联机地址卡（灰底）
        addrCard = Ui.linear(this, true)
        addrCard.setBackgroundResource(R.drawable.bg_console)
        addrCard.setPadding(Ui.px(this, 18), Ui.px(this, 14), Ui.px(this, 18), Ui.px(this, 16))
        val addrRow = Ui.linear(this, false)
        addrRow.gravity = Gravity.CENTER_VERTICAL
        addrRow.addView(Ui.text(this, "联机地址", 14f, getColor(R.color.text_sub)), Ui.lp(0, -2, 1f))
        listenState = Ui.text(this, "", 13f, getColor(R.color.text_sub))
        addrRow.addView(listenState)
        addrCard.addView(addrRow)
        addrText = Ui.text(this, "", 22f, getColor(R.color.green), true)
        addrText.setPadding(0, Ui.px(this, 6), 0, 0)
        addrCard.addView(addrText)
        val copyHint = Ui.text(this, "点击复制", 12f, getColor(R.color.text_sub))
        copyHint.gravity = Gravity.RIGHT
        copyHint.setPadding(0, Ui.px(this, 6), 0, 0)
        addrCard.addView(copyHint, Ui.lp(-1, -2))
        // 整卡可点：复制联机地址（空地址时提示）
        val doCopy = {
            val addr = addrText.text?.toString()?.trim() ?: ""
            if (addr.isEmpty()) {
                Ui.toast(this, "暂无联机地址（服务器未就绪）")
            } else {
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("addr", addr))
                Ui.toast(this, "已复制：$addr")
            }
            Unit
        }
        addrCard.setOnClickListener { doCopy() }
        addrText.setOnClickListener { doCopy() }
        copyHint.setOnClickListener { doCopy() }
        // 互通服：单独一行给基岩版（手机/平板）地址与对应版本
        bedrockText = Ui.text(this, "", 13f, getColor(R.color.text_sub))
        bedrockText.setPadding(0, Ui.px(this, 8), 0, 0)
        addrCard.addView(bedrockText)
        bedrockText.setOnClickListener {
            val a = bedrockText.tag?.toString().orEmpty()
            if (a.isEmpty()) {
                Ui.toast(this, "服务器未运行，暂无基岩地址")
            } else {
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("bedrock", a))
                Ui.toast(this, "已复制基岩地址：$a")
            }
        }
        root.addView(addrCard, Ui.lp(-1, -2).also { (it as LinearLayout.LayoutParams).setMargins(Ui.px(this, 20), 0, Ui.px(this, 20), 0) })

        // 电池优化未豁免时提示（Doze 会掐网，长连接直接 reset）
        keepAliveTip = Ui.text(this, "", 12f, getColor(R.color.amber_text), true)
        keepAliveTip.setPadding(Ui.px(this, 24), Ui.px(this, 10), Ui.px(this, 24), 0)
        keepAliveTip.setOnClickListener { openBatterySettings() }
        root.addView(keepAliveTip)

        connHint = Ui.text(this, "同一 WiFi 下，朋友在「多人游戏 → 直接连接」输入上面地址即可。端口不通时先看日志有没有 Done。", 12f, getColor(R.color.text_sub))
        connHint.setPadding(Ui.px(this, 24), Ui.px(this, 10), Ui.px(this, 24), 0)
        root.addView(connHint)

        // 性能面板（12/5）：CPU / 内存 实时
        perfText = Ui.text(this, "", 12f, getColor(R.color.text_sub))
        perfText.setPadding(Ui.px(this, 24), Ui.px(this, 6), Ui.px(this, 24), 0)
        root.addView(perfText)
        perfHandler = android.os.Handler(android.os.Looper.getMainLooper())
        perfTick = object : Runnable {
            var lastJf = 0L; var lastSys = 0L
            override fun run() {
                val pid = ServerManager.serverPid()
                if (pid == null) { perfText.text = ""; lastJf = 0; lastSys = 0 }
                else {
                    try {
                        val st = java.io.File("/proc/$pid/stat").readText()
                        val parts = st.substring(st.lastIndexOf(')') + 2).split(" ")
                        val utime = parts[11].toLong(); val stime = parts[12].toLong()
                        val rssKb = parts[21].toLong() * 4L
                        val sysStat = java.io.File("/proc/stat").readText().split("\n")[0].split(Regex("\\s+"))
                        var idle = 0L; var total = 0L
                        for (i in 1 until sysStat.size) { val v = sysStat[i].toLongOrNull() ?: 0L; total += v; if (i == 4 || i == 5) idle += v }
                        val jf = utime + stime
                        val cpu = if (lastJf > 0 && total > lastSys) {
                            val pct = (jf - lastJf) * 100 / (total - lastSys)
                            "$pct%"
                        } else "…"
                        lastJf = jf; lastSys = total
                        perfText.text = "CPU $cpu · 内存 ${rssKb / 1024}MB"
                    } catch (_: Exception) { perfText.text = "" }
                }
                perfHandler.postDelayed(this, 2000)
            }
        }
        perfHandler.post(perfTick)

        // 日志
        logView = TextView(this)
        logView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13f)
        logView.setTextColor(Color.parseColor("#3A3E3C"))
        logView.setTypeface(android.graphics.Typeface.MONOSPACE)
        logView.setPadding(Ui.px(this, 12), Ui.px(this, 12), Ui.px(this, 12), Ui.px(this, 12))
        logScroll = Ui.scroll(this)
        logScroll.addView(logView, ViewGroup.LayoutParams(-1, -2))
        // 中部用 RelativeLayout 硬锚定：日志框在 hint 下、底部条上，永不挤压
        val mid = android.widget.RelativeLayout(this)
        val bottom = LinearLayout(this)
        bottom.orientation = LinearLayout.VERTICAL
        bottom.id = View.generateViewId()
        val logWrap = LinearLayout(this)
        logWrap.orientation = LinearLayout.VERTICAL
        logWrap.setBackgroundResource(R.drawable.bg_console)
        logWrap.setPadding(Ui.px(this, 14), Ui.px(this, 14), Ui.px(this, 14), Ui.px(this, 14))
        logWrap.id = View.generateViewId()
        logWrap.addView(logScroll)
        val llp = android.widget.RelativeLayout.LayoutParams(-1, -1)
        llp.addRule(android.widget.RelativeLayout.ABOVE, bottom.id)
        mid.addView(logWrap, llp)
        // 快捷命令
        quick = Ui.linear(this, false)
        quick.setPadding(Ui.px(this, 20), 0, Ui.px(this, 20), Ui.px(this, 8))
        val quickScroll = android.widget.HorizontalScrollView(this)
        quickScroll.setHorizontalScrollBarEnabled(false)
        quickScroll.addView(quick)
        bottom.addView(quickScroll)

        // 输入行
        val inRow = Ui.linear(this, false)
        inRow.setPadding(Ui.px(this, 20), 0, Ui.px(this, 20), Ui.px(this, 14))
        inRow.gravity = Gravity.CENTER_VERTICAL
        input = EditText(this)
        input.hint = "输入命令，如 op Steve"
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15f)
        input.setBackgroundResource(R.drawable.bg_console)
        input.setPadding(Ui.px(this, 16), Ui.px(this, 12), Ui.px(this, 16), Ui.px(this, 12))
        input.setOnEditorActionListener { _, _, _ -> sendCmd(); true }
        inRow.addView(input, Ui.lp(0, -2, 1f))
        val send = Ui.text(this, "➤", 20f, getColor(R.color.green), true)
        send.setPadding(Ui.px(this, 14), 0, 0, 0)
        send.setOnClickListener { sendCmd() }
        inRow.addView(send)
        bottom.addView(inRow)
        val rlp = android.widget.RelativeLayout.LayoutParams(-1, -2)
        rlp.addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM)
        mid.addView(bottom, rlp)
        val mlp = Ui.lp(-1, 0, 1f)
        mlp.setMargins(Ui.px(this, 20), Ui.px(this, 12), Ui.px(this, 20), Ui.px(this, 10))
        root.addView(mid, mlp)

        setContentView(root)
        following = true
        readyNotified = ServerManager.isRunning(id)
        if (!readyNotified && ServerManager.consoleText().isBlank()) {
            logView.gravity = Gravity.LEFT
            logView.text = ""
        } else {
            ServerManager.consoleText().split('\n').forEach { if (it.isNotBlank()) appendColored(it, lineColor(it)) }
            logView.gravity = Gravity.LEFT
        }
        ServerManager.onLine(lineCb)
        render()
    }

    override fun onResume() { super.onResume(); render(); UiBus.subscribe { runOnUiThread { render() } } }
    override fun onPause() { super.onPause(); UiBus.unsubscribe { render() } }

    private fun notifyPlayer(name: String, joined: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("player", "玩家进出", NotificationManager.IMPORTANCE_DEFAULT))
        val b = Notification.Builder(this, "player")
            .setSmallIcon(android.R.drawable.ic_menu_myplaces)
            .setContentTitle(if (joined) "👋 玩家进入" else "👋 玩家离开")
            .setContentText(if (joined) "$name 加入了服务器" else "$name 离开了服务器")
            .setAutoCancel(true)
        nm.notify(name.hashCode(), b.build())
    }

    private fun lineColor(line: String): Int = when {
        line.contains("ERROR") || line.contains("Exception") || line.contains("FATAL") -> 0xFFC0392B.toInt()
        line.contains("WARN") || line.contains("WARNING") -> 0xFFB07818.toInt()
        line.startsWith("[MineHost]") || line.startsWith("[AnxServer]") -> 0xFF4FC3F7.toInt()
        line.contains("joined the game") -> 0xFF4FC3F7.toInt()
        line.contains("left the game") -> 0xFF9AA19D.toInt()
        line.contains("Done (") -> 0xFF4FC3F7.toInt()
        line.contains("Starting minecraft server") -> 0xFF2D6E9E.toInt()
        else -> 0xFF3A3E3C.toInt()
    }

    private fun appendColored(line: String, color: Int) {
        val ss = android.text.SpannableString(line + "\n")
        ss.setSpan(android.text.style.ForegroundColorSpan(color), 0, ss.length, 0)
        logView.append(ss)
    }

    private fun stopOrStart() {
        if (ServerManager.isRunning(id)) {
            ServerManager.stop()
            Ui.toast(this, "正在停止 …")
        } else if (ServerManager.runningId == null) {
            readyNotified = false
            statusDot.text = "● 开服中 · 正在启动服务进程 …"
            statusDot.setTextColor(getColor(R.color.amber_text))
            logView.gravity = Gravity.LEFT
            logView.text = ""
            appendColored("开服中，日志实时输出 …", 0xFFB07818.toInt())
            startServer()
        } else Ui.toast(this, "已有服务器在运行")
    }

    private fun startServer() {
        val srv = Store.findServer(id) ?: return
        // 先起前台服务再拉 java：解压/JRE 校验这段最容易被系统冻结
        ServerService.start(this, id, srv.optString("name"), srv.optInt("port", 25565))
        ServerManager.start(this, srv) { r ->
            runOnUiThread {
                if (r.startsWith("ERR")) {
                    readyNotified = false
                    ServerService.stop(this)
                    ServerService.clearRunning(this)
                    Ui.toast(this, "启动失败：${r.removePrefix("ERR:")}")
                    render()
                } else {
                    askKeepAliveOnce()
                    render()
                }
            }
        }
    }

    /** 首次开服提醒加入电池优化白名单：Doze 会掐网，表现为客户端 Connection reset */
    private fun askKeepAliveOnce() {
        try {
            val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) return
            val p = getSharedPreferences("app", 0)
            if (p.getBoolean("asked_battery", false)) return
            p.edit().putBoolean("asked_battery", true).apply()
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("开启后台保活，避免掉线")
                .setMessage("息屏或切后台一段时间后，系统会限制本应用的网络，客户端就会报「连接已丢失 / Connection reset」。\n\n加入电池优化白名单后，锁屏、切后台都能稳定开服。")
                .setPositiveButton("去开启") { _, _ -> openBatterySettings() }
                .setNegativeButton("稍后", null)
                .show()
        } catch (_: Exception) {}
    }

    private fun openBatterySettings() {
        try {
            startActivity(android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            try {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {}
        }
    }

    private fun sendCmd() {
        val c = input.text?.toString()?.trim() ?: return
        if (c.isEmpty()) return
        input.setText("")
        if (!ServerManager.isRunning(id)) { Ui.toast(this, "服务器未运行"); return }
        if (!ServerManager.send(c)) Ui.toast(this, "发送失败")
    }

    private fun sendText(c: String) { input.setText(c); sendCmd() }

    private fun render() {
        val srv = Store.findServer(id) ?: JSONObject()
        val running = ServerManager.isRunning(id)
        val port = srv.optInt("port", 25565)
        statusDot.text = if (running) "● 运行中 · ${srv.optString("core").lowercase()} ${srv.optString("mcVersion")} · :$port"
            else if (ServerManager.runningId != null) "● 开服中 · ${srv.optString("core").lowercase()} ${srv.optString("mcVersion")}"
            else "● 已停止 · ${srv.optString("core").lowercase()} ${srv.optString("mcVersion")} · :$port"
        statusDot.setTextColor(getColor(if (running) R.color.green else if (ServerManager.runningId != null) R.color.amber_text else R.color.text_sub))
        stopBtn.text = if (running) "停止" else "启动"
        stopBtn.setTextColor(getColor(if (running) R.color.danger else R.color.green))
        val ip = ServerManager.localIp()
        addrText.text = if (running && ip.isNotEmpty()) "$ip:$port" else "—"
        // 互通服：显示基岩版地址 + 这版 Geyser 支持的基岩版版本
        val isCross = srv.optString("core") == Cores.CROSSPLAY
        val bv = srv.optString("bedrockVersion")
        if (isCross) {
            val lan = if (running && ip.isNotEmpty()) "$ip:19132" else ""
            bedrockText.visibility = View.VISIBLE
            bedrockText.text = "基岩版连接地址：同机玩填 127.0.0.1:19132，同一 WiFi 其他设备填 " +
                "${lan.ifEmpty { "本机局域网IP" }}:19132 · 对应版本 " + bv.ifEmpty { "识别中…" } +
                "（点击复制 127.0.0.1:19132）"
            // 同机自连是最常见的失败场景（填 LAN IP 会卡在 UDP 握手），所以点击默认复制 127.0.0.1
            bedrockText.tag = "127.0.0.1:19132"
            if (bv.isEmpty() && !bvLoading) {
                bvLoading = true
                Thread {
                    try {
                        val jar = java.io.File(Store.serverDir(id), "plugins/Geyser-Spigot.jar")
                        Cores.bedrockVersion(jar)?.let { v ->
                            Store.updateServer(id, JSONObject().put("bedrockVersion", v))
                            if (!isFinishing && !isDestroyed) runOnUiThread { render() }
                        }
                    } catch (_: Exception) {
                    } finally { bvLoading = false }
                }.start()
            }
        } else {
            bedrockText.visibility = View.GONE
            bedrockText.tag = ""
        }
        connHint.text = if (isCross)
            "Java 版用「多人游戏 → 直接连接」输地址；基岩版用「游戏 → 服务器 → 添加服务器」。\n" +
            "详细连接方法（地址 / 端口 / 信任 / 版本一致）见「设置 → 连接说明」。"
        else
            "同一 WiFi 下，朋友在「多人游戏 → 直接连接」输入上面地址即可。端口不通时先看日志有没有 Done。"
        try {
            val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                keepAliveTip.visibility = View.GONE
            } else {
                keepAliveTip.visibility = View.VISIBLE
                keepAliveTip.text = "⚠ 未加入电池优化白名单：切后台/息屏久了会断网，客户端报 Connection reset。点这里开启 ›"
            }
        } catch (_: Exception) { keepAliveTip.visibility = View.GONE }
        listenState.text = if (running) "● 端口已监听" else "○ 未运行"
        listenState.setTextColor(getColor(if (running) R.color.green else R.color.text_sub))
        buildQuick(running)
    }

    private fun buildQuick(running: Boolean) {
        quick.removeAllViews()
        // 中文标签 to 实际指令
        val cmds = if (running) listOf(
            "在线玩家" to "list",
            "开服啦" to "say 服务器开服啦",
            "白天" to "time set day",
            "晴天" to "weather clear",
            "手动存档" to "save-all",
            "死亡不掉落" to "gamerule keepInventory true",
            "防炸地形" to "gamerule mobGriefing false",
            "开白名单" to "whitelist on",
            "白名单列表" to "whitelist list",
            "给OP" to "op ",
            "踢出" to "kick ",
            "封禁" to "ban "
        ) else emptyList()
        for ((label, c) in cmds) {
            val chip = Ui.text(this, label, 13f, getColor(R.color.text), true)
            chip.setBackgroundResource(R.drawable.bg_card)
            chip.setPadding(Ui.px(this, 16), Ui.px(this, 9), Ui.px(this, 16), Ui.px(this, 9))
            chip.setOnClickListener {
                if (c.endsWith(" ")) { input.setText(c); input.requestFocus() } else sendText(c)
            }
            val p = LinearLayout.LayoutParams(-2, -2)
            p.rightMargin = Ui.px(this, 8)
            quick.addView(chip, p)
        }
    }

    private fun backupWorld() {
        val dir = Store.serverDir(id)
        val world = java.io.File(dir, "world")
        if (!world.isDirectory) { Ui.toast(this, "没有 world 目录（还没开过服）"); return }
        Ui.toast(this, "正在备份 …")
        Thread {
            try {
                val out = java.io.File(Store.base, "backups").apply { mkdirs() }
                val f = java.io.File(out, "${id}-" + System.currentTimeMillis() + ".zip")
                val zos = java.util.zip.ZipOutputStream(java.io.FileOutputStream(f))
                dir.walkTopDown().filter { it.isFile && it.name != "server.jar" }.forEach { file ->
                    zos.putNextEntry(java.util.zip.ZipEntry(dir.toPath().relativize(file.toPath()).toString()))
                    java.io.FileInputStream(file).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
                zos.close()
                runOnUiThread { Ui.toast(this, "已备份：" + f.name) }
            } catch (e: Exception) {
                runOnUiThread { Ui.toast(this, "备份失败：" + e.message) }
            }
        }.start()
    }

    private fun deleteServer() {
        val dlg = android.app.AlertDialog.Builder(this)
            .setTitle("删除服务器")
            .setMessage("将删除服务器文件与存档，无法恢复。确定？")
            .setPositiveButton("删除", null)
            .setNegativeButton("取消", null)
            .show()
        dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setTextColor(getColor(R.color.danger))
        dlg.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setTextColor(getColor(R.color.text_sub))
        dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (ServerManager.isRunning(id)) ServerManager.stop()
            Store.serverDir(id).deleteRecursively()
            val arr = Store.loadServers()
            val out = org.json.JSONArray()
            for (i in 0 until arr.length()) if (arr.getJSONObject(i).optString("id") != id) out.put(arr.getJSONObject(i))
            Store.saveServers(out)
            dlg.dismiss()
            finish()
        }
    }

    override fun onDestroy() {
        if (this::perfHandler.isInitialized) perfHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
        ServerManager.offLine(lineCb)
        UiBus.unsubscribe { render() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Ui.immersive(this)
    }
}
