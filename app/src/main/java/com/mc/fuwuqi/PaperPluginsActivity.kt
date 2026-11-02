package com.mc.fuwuqi

import android.app.Dialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.io.File

/** Paper 插件统一管理：本地仓库下载 + 按服务器开关 */
class PaperPluginsActivity : androidx.appcompat.app.AppCompatActivity() {

    private lateinit var listWrap: LinearLayout
    private var servers: List<JSONObject> = emptyList()
    private var selIdx = 0
    private var busy = false
    private var preferId = ""
    /** 简化模式（来自控制台 ⋮「插件管理」，只显示当前服务器的开关）；false = 设置页完整模式 */
    private var simple = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        preferId = intent?.getStringExtra("id") ?: ""
        simple = intent?.getBooleanExtra("simple", preferId.isNotEmpty()) ?: preferId.isNotEmpty()
        val root = Ui.linear(this, true)
        root.setBackgroundColor(getColor(R.color.bg))
        if (simple) root.addView(Ui.topBar(this, "插件管理", "开启/关闭后重启服务器生效"))
        else root.addView(Ui.topBar(this, "Paper 插件", "统一下载 · 按服务器开关",
            Pair("插件仓库", View.OnClickListener { showRepoDialog() })))
        val scroll = Ui.scroll(this)
        listWrap = Ui.linear(this, true)
        listWrap.setPadding(Ui.px(this, 20), Ui.px(this, 8), Ui.px(this, 20), Ui.px(this, 24))
        scroll.addView(listWrap, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, Ui.lp(-1, 0, 1f))
        setContentView(root)
        Ui.immersive(this)
        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        listWrap.removeAllViews()
        if (!Ui.needStorage(this)) return
        servers = (0 until Store.loadServers().length()).map { Store.loadServers().getJSONObject(it) }
            .filter { Cores.isBukkitLike(it.optString("core")) }
        if (servers.isEmpty()) {
            listWrap.addView(Ui.text(this,
                "还没有可用的 Bukkit 系服务器（Paper / 互通服 / Purpur）。先去创建一个吧。",
                14f, getColor(R.color.text_sub)).also { it.setPadding(0, Ui.px(this, 30), 0, 0) })
            return
        }
        // 从控制台「插件管理」进入时，自动选中当前服务器（仅首次）
        if (preferId.isNotEmpty()) {
            val idx = servers.indexOfFirst { it.optString("id") == preferId }
            if (idx >= 0) selIdx = idx
            preferId = ""
        }
        if (selIdx >= servers.size) selIdx = 0
        val srv = servers[selIdx]
        val serverDir = Store.serverDir(srv.optString("id"))

        if (!simple) {
            // 选择服务器（横向可滚动，标签保持单行不挤压）
            listWrap.addView(Ui.text(this, "选择服务器", 15f, getColor(R.color.text), true))
            val serverRow = Ui.linear(this, false)
            serverRow.setPadding(0, Ui.px(this, 8), Ui.px(this, 4), 0)
            servers.forEachIndexed { i, s ->
                val chip = Ui.text(this, s.optString("name", "服务器"), 14f,
                    if (i == selIdx) getColor(R.color.green) else getColor(R.color.text), true)
                chip.maxLines = 1                      // 单行，不换行
                chip.ellipsize = null
                chip.setBackgroundResource(if (i == selIdx) R.drawable.bg_tab_active else R.drawable.bg_card)
                chip.setPadding(Ui.px(this, 16), Ui.px(this, 8), Ui.px(this, 16), Ui.px(this, 8))
                chip.setOnClickListener { if (!busy) { selIdx = i; render() } }
                val p = Ui.lp(-2, -2); p.rightMargin = Ui.px(this, 8)
                serverRow.addView(chip, p)
            }
            val hs = android.widget.HorizontalScrollView(this)
            hs.setHorizontalScrollBarEnabled(false)
            hs.addView(serverRow, ViewGroup.LayoutParams(-2, -2))
            listWrap.addView(hs, Ui.lp(-1, -2))
        }

        // 本服务器开关（开启时若未下载会自动下载）
        val sc = sectionCard("本服务器插件开关",
            if (simple) "当前：${srv.optString("name", "服务器")}｜开启 → 放入 plugins/（重启生效）；关闭 → 移出（本地仓库保留）"
            else "开启 → 放入该服 plugins/（重启生效）；关闭 → 移出（本地仓库保留）")
        for (p in PaperPlugins.LIST) {
            val on = PaperPlugins.isEnabled(p.slug, serverDir)
            val row = Ui.linear(this, false)
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, Ui.px(this, 12), 0, Ui.px(this, 12))
            val col = Ui.linear(this, true)
            col.addView(Ui.text(this, p.name, 16f, getColor(R.color.text), true))
            val state = Ui.text(this,
                if (on) "已启用（在 plugins/ 中）" else if (PaperPlugins.isDownloaded(p.slug)) "已下载，未启用" else "未下载",
                12f, getColor(R.color.text_sub))
            state.setPadding(0, Ui.px(this, 2), 0, 0)
            col.addView(state)
            row.addView(col, Ui.lp(0, -2, 1f))
            val sw = ConfigActivity.SwitchView(this, on) { checked ->
                if (busy) { render(); return@SwitchView }
                busy = true
                Thread {
                    if (checked) {
                        if (!PaperPlugins.isDownloaded(p.slug)) runOnUiThread { state.text = "下载中 …" }
                        PaperPlugins.enableFor(p.slug, serverDir) { c, t ->
                            runOnUiThread {
                                state.text = if (t > 0) "下载中 ${(c * 100 / t).toInt()}%" else "下载中 ${c / 1024}KB"
                            }
                        }
                    } else PaperPlugins.disableFor(p.slug, serverDir)
                    runOnUiThread { busy = false; render() }
                }.start()
            }
            sw.layoutParams = Ui.lp(Ui.px(this, 46), Ui.px(this, 26))
            row.addView(sw)
            sc.addView(row)
            if (p != PaperPlugins.LIST.last()) sc.addView(hLine())
        }
        listWrap.addView(sc, cardMargin())
    }

    /** 右上角「插件仓库」按钮：弹出下载对话框（下载全部 + 逐个下载到本地仓库） */
    private fun showRepoDialog() {
        val dlg = Dialog(this)
        dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val root = Ui.linear(this, true)
        root.setBackgroundColor(getColor(R.color.bg))
        root.setPadding(Ui.px(this, 18), Ui.px(this, 16), Ui.px(this, 18), Ui.px(this, 18))
        // 标题行
        val hb = Ui.linear(this, false); hb.gravity = Gravity.CENTER_VERTICAL
        hb.addView(Ui.text(this, "插件仓库", 20f, getColor(R.color.text), true), Ui.lp(0, -2, 1f))
        val close = Ui.text(this, "✕", 22f, getColor(R.color.text_sub), true)
        close.setPadding(Ui.px(this, 10), 0, 0, 0)
        close.setOnClickListener { dlg.dismiss() }
        hb.addView(close)
        root.addView(hb)
        root.addView(Ui.text(this, "下载到本地后，可在下方「本服务器插件开关」中启用（需重启服务器）", 12.5f, getColor(R.color.text_sub)).also {
            it.setPadding(0, Ui.px(this, 6), 0, Ui.px(this, 10)) })
        // 下载全部
        val allPending = PaperPlugins.LIST.count { !PaperPlugins.isDownloaded(it.slug) }
        if (allPending > 0) {
            val allRow = Ui.linear(this, false); allRow.gravity = Gravity.END
            val allBtn = Ui.text(this, "下载全部 ($allPending)", 14f, getColor(R.color.green), true)
            allBtn.setBackgroundResource(R.drawable.bg_console)
            allBtn.setPadding(Ui.px(this, 14), Ui.px(this, 8), Ui.px(this, 14), Ui.px(this, 8))
            allBtn.setOnClickListener {
                if (busy) { Ui.toast(this, "有任务进行中"); return@setOnClickListener }
                busy = true
                val n0 = allPending
                allBtn.text = "下载中 0/$n0"
                Thread {
                    val ok = PaperPlugins.downloadAll(3) { d, t -> runOnUiThread { allBtn.text = "下载中 $d/$t" } }
                    runOnUiThread { busy = false; Ui.toast(this, if (ok) "全部下载完成 ✅" else "部分下载失败"); dlg.dismiss(); render() }
                }.start()
            }
            allRow.addView(allBtn)
            root.addView(allRow)
        }
        // 逐个下载
        PaperPlugins.LIST.forEachIndexed { i, p ->
            val row = Ui.linear(this, false)
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, Ui.px(this, 12), 0, Ui.px(this, 12))
            val col = Ui.linear(this, true)
            col.addView(Ui.text(this, p.name, 16f, getColor(R.color.text), true))
            col.addView(Ui.text(this, p.desc, 12f, getColor(R.color.text_sub)).also { it.setPadding(0, Ui.px(this, 2), 0, 0) })
            row.addView(col, Ui.lp(0, -2, 1f))
            val dled = PaperPlugins.isDownloaded(p.slug)
            val btn = Ui.text(this, if (dled) "已下载 ✓" else "下载", 14f,
                if (dled) getColor(R.color.text_sub) else getColor(R.color.green), true)
            btn.setBackgroundResource(R.drawable.bg_console)
            btn.setPadding(Ui.px(this, 14), Ui.px(this, 8), Ui.px(this, 14), Ui.px(this, 8))
            if (dled) btn.setOnClickListener(null) else btn.setOnClickListener {
                if (busy) { Ui.toast(this, "有任务进行中"); return@setOnClickListener }
                busy = true; btn.text = "下载中 …"
                Thread {
                    val ok = PaperPlugins.downloadGlobal(p.slug) { c, t ->
                        runOnUiThread { btn.text = "下载 ${c / 1024}KB" + if (t > 0) " / ${t / 1024}KB" else "" } }
                    runOnUiThread { busy = false; Ui.toast(this, if (ok) "${p.name} 已下载" else "下载失败"); dlg.dismiss(); render() }
                }.start()
            }
            row.addView(btn)
            root.addView(row)
            if (i < PaperPlugins.LIST.lastIndex) root.addView(hLine())
        }
        val scroll = Ui.scroll(this)
        scroll.addView(root, ViewGroup.LayoutParams(-1, -2))
        dlg.setContentView(scroll)
        dlg.window?.setLayout((resources.displayMetrics.widthPixels * 0.92).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
        dlg.show()
    }

    private fun sectionCard(title: String, sub: String): LinearLayout {
        listWrap.addView(Ui.text(this, title, 17f, getColor(R.color.text_sub), true).also {
            it.setPadding(Ui.px(this, 6), Ui.px(this, 20), 0, Ui.px(this, 6)) })
        val card = Ui.card(this)
        card.setPadding(Ui.px(this, 18), Ui.px(this, 8), Ui.px(this, 18), Ui.px(this, 8))
        card.addView(Ui.text(this, sub, 12.5f, getColor(R.color.text_sub)).also {
            it.setPadding(0, Ui.px(this, 8), 0, Ui.px(this, 4)) })
        return card
    }

    private fun cardMargin(): ViewGroup.LayoutParams =
        Ui.lp(-1, -2).also { (it as LinearLayout.LayoutParams).topMargin = Ui.px(this, 4) }

    private fun hLine(): View {
        val v = View(this)
        v.setBackgroundColor(getColor(R.color.line))
        v.layoutParams = Ui.lp(-1, Ui.px(this, 1))
        return v
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Ui.immersive(this)
    }
}
