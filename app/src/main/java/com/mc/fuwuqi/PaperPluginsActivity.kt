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
        root.addView(Ui.topBar(this, "插件管理", "开启/关闭后重启服务器生效"))
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

        // 本服务器开关（开启时若未下载会自动下载）
        val sc = sectionCard("本服务器插件开关",
            "当前：${srv.optString("name", "服务器")}｜开启 → 放入 plugins/（重启生效）；关闭 → 移出（本地仓库保留）")
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
