package com.mc.fuwuqi

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

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        preferId = intent?.getStringExtra("id") ?: ""
        val root = Ui.linear(this, true)
        root.setBackgroundColor(getColor(R.color.bg))
        root.addView(Ui.topBar(this, "Paper 插件", "统一下载 · 按服务器开关"))
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
        val serverDir = Store.serverDir(servers[selIdx].optString("id"))

        // 选择服务器
        listWrap.addView(Ui.text(this, "选择服务器", 15f, getColor(R.color.text), true))
        val serverRow = Ui.linear(this, false)
        serverRow.setPadding(0, Ui.px(this, 8), 0, 0)
        servers.forEachIndexed { i, s ->
            val chip = Ui.text(this, s.optString("name", "服务器"), 14f,
                if (i == selIdx) getColor(R.color.green) else getColor(R.color.text), true)
            chip.setBackgroundResource(if (i == selIdx) R.drawable.bg_tab_active else R.drawable.bg_card)
            chip.setPadding(Ui.px(this, 16), Ui.px(this, 8), Ui.px(this, 16), Ui.px(this, 8))
            chip.setOnClickListener { if (!busy) { selIdx = i; render() } }
            val p = Ui.lp(-2, -2); p.rightMargin = Ui.px(this, 8)
            serverRow.addView(chip, p)
        }
        listWrap.addView(serverRow)

        // 插件仓库（本地下载，下载一次可给任意服务器启用）
        val rc = sectionCard("插件仓库", "从本仓库下载到本地，下载一次后可给任意服务器启用（开启需重启服务器生效）")
        // 多线程「下载全部」：并行下载所有未下载的插件
        val allPending = PaperPlugins.LIST.count { !PaperPlugins.isDownloaded(it.slug) }
        if (allPending > 0) {
            val head = Ui.linear(this, false); head.gravity = Gravity.END or Gravity.CENTER_VERTICAL
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
                    runOnUiThread { busy = false; Ui.toast(this, if (ok) "全部下载完成 ✅" else "部分下载失败"); render() }
                }.start()
            }
            head.addView(allBtn)
            rc.addView(head)
            rc.addView(hLine())
        }
        for (p in PaperPlugins.LIST) {
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
                    runOnUiThread { busy = false; Ui.toast(this, if (ok) "${p.name} 已下载" else "下载失败"); render() }
                }.start()
            }
            row.addView(btn)
            rc.addView(row)
            if (p != PaperPlugins.LIST.last()) rc.addView(hLine())
        }
        listWrap.addView(rc, cardMargin())

        // 本服务器开关
        val sc = sectionCard("本服务器插件开关", "开启 → 放入该服 plugins/（重启生效）；关闭 → 移出（本地仓库保留）")
        for (p in PaperPlugins.LIST) {
            val on = PaperPlugins.isEnabled(p.slug, serverDir)
            val row = Ui.linear(this, false)
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, Ui.px(this, 12), 0, Ui.px(this, 12))
            val col = Ui.linear(this, true)
            col.addView(Ui.text(this, p.name, 16f, getColor(R.color.text), true))
            col.addView(Ui.text(this,
                if (on) "已启用（在 plugins/ 中）" else if (PaperPlugins.isDownloaded(p.slug)) "已下载，未启用" else "未下载",
                12f, getColor(R.color.text_sub)).also { it.setPadding(0, Ui.px(this, 2), 0, 0) })
            row.addView(col, Ui.lp(0, -2, 1f))
            val sw = ConfigActivity.SwitchView(this, on) { checked ->
                if (busy) { render(); return@SwitchView }
                busy = true
                Thread {
                    if (checked) PaperPlugins.enableFor(p.slug, serverDir) { _, _ -> }
                    else PaperPlugins.disableFor(p.slug, serverDir)
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
