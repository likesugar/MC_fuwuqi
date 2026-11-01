package com.mc.fuwuqi

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray

/** 插件管理：常用 Paper 插件一键装（raw.githubusercontent.com 直链下载，不套 gh-proxy） */
class PluginActivity : androidx.appcompat.app.AppCompatActivity() {

    private lateinit var id: String
    private lateinit var mcVersion: String
    private lateinit var listWrap: LinearLayout
    private var installing = false

    private val plugins = listOf(
        Triple("vault", "Vault", "权限/经济基础，多数插件的前置"),
        Triple("essentialsx", "EssentialsX", "基础命令全家桶：家/传送/背包"),
        Triple("luckperms", "LuckPerms", "权限管理"),
        Triple("worldedit", "WorldEdit", "建筑编辑神器"),
        Triple("worldguard", "WorldGuard", "领地/区域保护（需 WorldEdit）"),
        Triple("coreprotect", "CoreProtect", "查熊孩子/回滚破坏"))

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        id = intent.getStringExtra("id") ?: ""
        mcVersion = intent.getStringExtra("mcVersion") ?: ""
        val root = Ui.linear(this, true)
        root.setBackgroundColor(getColor(R.color.bg))
        root.addView(Ui.topBar(this, "插件管理", "Paper 插件 · 装完需重启服务器"))
        val scroll = Ui.scroll(this)
        listWrap = Ui.linear(this, true)
        listWrap.setPadding(Ui.px(this, 16), Ui.px(this, 10), Ui.px(this, 16), Ui.px(this, 20))
        scroll.addView(listWrap)
        root.addView(scroll, Ui.lp(-1, -1))
        setContentView(root)
        onWindowFocusChanged(true)
        render()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) { Ui.immersive(this); render() }
    }

    private fun pluginDir() = java.io.File(Store.serverDir(id), "plugins")

    private fun installedFor(p: Triple<String, String, String>): Boolean {
        val d = pluginDir()
        val name = p.second.lowercase()
        return d.listFiles()?.any { it.name.lowercase().startsWith(name) && it.extension == "jar" } ?: false
    }

    private fun render() {
        listWrap.removeAllViews()
        val tip = Ui.text(this, "插件从 GitHub 仓库 raw 直链下载到服务器 plugins 目录，安装后需「停止」再「启动」生效。", 12f, getColor(R.color.text_sub))
        tip.setPadding(0, 0, 0, Ui.px(this, 8))
        listWrap.addView(tip)
        for (p in plugins) {
            val done = installedFor(p)
            val row = Ui.linear(this, false)
            row.gravity = Gravity.CENTER_VERTICAL
            row.setBackgroundResource(R.drawable.bg_card)
            row.setPadding(Ui.px(this, 16), Ui.px(this, 14), Ui.px(this, 16), Ui.px(this, 14))
            val info = Ui.linear(this, true)
            info.addView(Ui.text(this, p.second, 16f, if (done) getColor(R.color.green) else getColor(R.color.text), true))
            info.addView(Ui.text(this, p.third, 12f, getColor(R.color.text_sub)))
            row.addView(info, Ui.lp(0, -2, 1f))
            val btn = TextView(this)
            btn.text = if (done) "✅ 已安装" else "安装"
            btn.gravity = Gravity.CENTER
            btn.setPadding(Ui.px(this, 16), Ui.px(this, 8), Ui.px(this, 16), Ui.px(this, 8))
            btn.setBackgroundResource(if (done) R.drawable.bg_console else R.drawable.bg_btn_green)
            btn.setTextColor(if (done) getColor(R.color.text_sub) else Color.WHITE)
            btn.setOnClickListener {
                if (installing) { Ui.toast(this, "有插件正在安装中…"); return@setOnClickListener }
                if (done) { Ui.toast(this, "已安装，重复安装会覆盖最新版"); return@setOnClickListener }
                install(p)
            }
            row.addView(btn)
            val lp = Ui.lp(-1, -2); lp.topMargin = Ui.px(this, 8)
            listWrap.addView(row, lp)
        }
    }

    private fun install(p: Triple<String, String, String>) {
        installing = true
        val slug = p.first; val name = p.second
        val go = Ui.text(this, "安装中 …", 13f, getColor(R.color.amber_text), true)
        go.setPadding(0, Ui.px(this, 6), 0, 0)
        listWrap.addView(go)
        Thread {
            var msg = ""
            try {
                // 插件下载：严格走 raw.githubusercontent.com 直链（用户指定加速前缀，不套 gh-proxy）
                val url = "https://raw.githubusercontent.com/likesugar/MC_fuwuqi/main/plugins/$slug.jar"
                val dest = java.io.File(pluginDir(), "$slug.jar")
                dest.delete()
                val ok = rawDownload(url, dest) { c, t ->
                    runOnUiThread { go.text = "下载 $name ${Container.fmt(c)}" + (if (t > 0) " / ${Container.fmt(t)}" else "") } }
                require(ok && dest.isFile && dest.length() > 0) { "下载失败，请检查网络或稍后重试" }
                msg = "OK"
            } catch (e: Exception) { msg = e.message ?: e.toString() }
            val finalMsg = msg
            runOnUiThread {
                installing = false
                go.text = if (finalMsg == "OK") "$name 安装完成 ✅（重启服务器生效）" else "失败：$finalMsg"
                render()
            }
        }.start()
    }

    /** 仅从单个 raw 直链下载（不套 gh-proxy），带断点续传与进度回调；成功返回 true */
    private fun rawDownload(urlStr: String, destFile: java.io.File, cb: (Long, Long) -> Unit): Boolean {
        destFile.parentFile?.mkdirs()
        val tmp = java.io.File(destFile.absolutePath + ".part")
        var start = if (tmp.isFile) tmp.length() else 0L
        try {
            val conn = java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "MCFuwuqi/1.0")
            if (start > 0) conn.setRequestProperty("Range", "bytes=$start-")
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) { conn.disconnect(); return false }
            var total = conn.contentLengthLong
            if (code == 200 && start > 0) start = 0L else if (code == 206) total += start
            conn.inputStream.use { ins ->
                val raf = java.io.RandomAccessFile(tmp, "rw"); raf.seek(start)
                try {
                    val buf = ByteArray(64 * 1024); var got = start; var since = 0L
                    while (true) {
                        val n = ins.read(buf); if (n < 0) break
                        raf.write(buf, 0, n); got += n; since += n
                        if (since > 200_000) { since = 0; cb(got, total) }
                    }
                } finally { raf.close() }
            }
            conn.disconnect()
            if (tmp.length() == 0L) return false
            tmp.renameTo(destFile)
            return true
        } catch (e: Exception) {
            return false
        }
    }
}
