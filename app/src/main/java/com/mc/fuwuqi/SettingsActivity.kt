package com.mc.fuwuqi

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject

/** 设置页：容器状态 / Java 运行时 / JVM 参数 / 关于 */
class SettingsActivity : androidx.appcompat.app.AppCompatActivity() {

    private lateinit var listWrap: LinearLayout
    private var busy = false

    override fun onResume() {
        super.onResume()
        render()
        UiBus.subscribe { runOnUiThread { render() } }
    }

    override fun onPause() {
        super.onPause()
        UiBus.unsubscribe { render() }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = Ui.linear(this, true)
        root.setBackgroundColor(getColor(R.color.bg))
        root.addView(Ui.topBar(this, "设置", "容器 · 运行时 · 高级"))

        listWrap = Ui.linear(this, true)
        listWrap.setPadding(Ui.px(this, 20), Ui.px(this, 4), Ui.px(this, 20), Ui.px(this, 24))
        val scroll = Ui.scroll(this)
        scroll.addView(listWrap, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, Ui.lp(-1, 0, 1f))
        setContentView(root)
        render()
    }

    private fun section(title: String): View {
        val t = Ui.text(this, title, 17f, getColor(R.color.text_sub), true)
        t.setPadding(Ui.px(this, 6), Ui.px(this, 20), 0, Ui.px(this, 6))
        return t
    }

    private fun render() {
        listWrap.removeAllViews()
        if (!Ui.needStorage(this)) return

        // Linux 容器
        listWrap.addView(section("Linux 容器"))
        val cc = Ui.card(this)
        cc.setPadding(Ui.px(this, 18), Ui.px(this, 14), Ui.px(this, 18), Ui.px(this, 16))
        cc.addView(urow("✓  内置容器 · Debian glibc 精简版",
            if (Store.hasRootfs()) "已就绪" else "待解压",
            Store.hasRootfs()))
        cc.addView(urow("✓  proot 运行时（随 APK 内置）",
            if (Container.hasProot(this)) "已内置" else "未就绪",
            Container.hasProot(this)))
        val exp = Ui.text(this,
            "Java 直接跑在这个容器里，不依赖 Termux 或任何 App。容器用的是 Debian 的 glibc 用户态，和电脑上开服的环境一致。",
            13f, getColor(R.color.text_sub))
        exp.setPadding(0, Ui.px(this, 8), 0, 0)
        cc.addView(exp)
        val act = Ui.text(this, if (Store.hasRootfs()) "" else "解压内置容器（无需下载）", 15f, getColor(R.color.green), true)
        act.setPadding(0, Ui.px(this, 12), 0, 0)
        act.setOnClickListener {
            if (busy) { Ui.toast(this, "有任务进行中，请稍候"); return@setOnClickListener }
            doDownload {
                if (it.startsWith("ERR")) Ui.toast(this, it) else Ui.toast(this, "容器已就绪")
                render()
            }
        }
        cc.addView(act)
        val prog = Ui.text(this, "", 12f, getColor(R.color.text_sub))
        cc.addView(prog)
        // 用 tag 存进度视图，供 doDownload 更新
        cc.tag = prog
        progressViews.add(prog)
        listWrap.addView(cc, Ui.lp(-1, -2).also { it.topMargin = Ui.px(this, 4) })

        // Java 运行时
        listWrap.addView(section("Java 运行时"))
        val jc = Ui.card(this)
        jc.setPadding(Ui.px(this, 18), Ui.px(this, 14), Ui.px(this, 18), Ui.px(this, 16))
        jc.addView(Ui.text(this, "内置 5 个 LTS 版本，创建服务器时按 MC 版本自动匹配。按需下载，用不到的不占空间。", 13f, getColor(R.color.text_sub)))
        for (v in intArrayOf(8, 11, 17, 21, 25)) {
            val installed = Store.hasJdk(v)
            val row = Ui.linear(this, false)
            row.gravity = Gravity.CENTER_VERTICAL
            row.setPadding(0, Ui.px(this, 14), 0, Ui.px(this, 14))
            val col = Ui.linear(this, true)
            col.addView(Ui.text(this, "Java $v", 16f, getColor(R.color.text), true))
            val d = Ui.text(this, Container.jdkDesc[v] ?: "", 12f, getColor(R.color.text_sub))
            d.setPadding(0, Ui.px(this, 2), 0, 0)
            col.addView(d)
            row.addView(col, Ui.lp(0, -2, 1f))
            val btn = Ui.text(this, if (installed) "已安装 ✓" else "安装", 15f,
                if (installed) getColor(R.color.text_sub) else getColor(R.color.green), true)
            btn.setOnClickListener {
                if (installed) return@setOnClickListener
                if (busy) { Ui.toast(this, "有任务进行中，请稍候"); return@setOnClickListener }
                doJdk(v) {
                    if (it.startsWith("ERR")) Ui.toast(this, it)
                    else Ui.toast(this, "Java $v 已就绪")
                    render()
                }
            }
            row.addView(btn)
            jc.addView(row)
            val rp = Ui.text(this, "", 12f, getColor(R.color.green))
            rp.setPadding(0, 0, 0, Ui.px(this, 4))
            jc.addView(rp)
            rowProgs[v] = rp
            if (v != 25) {
                val line = View(this)
                line.setBackgroundColor(getColor(R.color.line))
                jc.addView(line, Ui.lp(-1, Ui.px(this, 1)))
            }
        }
        val foot = Ui.linear(this, false)
        foot.gravity = Gravity.CENTER_VERTICAL
        val refresh = Ui.text(this, "刷新状态", 14f, getColor(R.color.green), true)
        refresh.setOnClickListener { render() }
        foot.addView(refresh, Ui.lp(0, -2, 1f))
        foot.addView(Ui.text(this, "每个版本约 45MB", 12f, getColor(R.color.text_sub)))
        foot.setPadding(0, Ui.px(this, 6), 0, 0)
        jc.addView(foot)
        listWrap.addView(jc, Ui.lp(-1, -2).also { it.topMargin = Ui.px(this, 4) })

        // 关于
        listWrap.addView(section("关于"))
        val ab = Ui.card(this)
        ab.setPadding(Ui.px(this, 18), Ui.px(this, 14), Ui.px(this, 18), Ui.px(this, 16))
        val ver = try { packageManager.getPackageInfo(packageName, 0) } catch (e: Exception) { null }
        ab.addView(Ui.text(this, "MC开服 v${ver?.versionName ?: "?"}", 15f, getColor(R.color.text), true))
        ab.addView(Ui.text(this,
            "容器方案：proot（ptrace 路径重定向）+ Debian glibc 精简 rootfs + Temurin JRE。服务器文件都在 ${Store.serversDir.absolutePath}/ ，可以用文件管理器直接改配置、塞插件。",
            12f, getColor(R.color.text_sub)).also { it.setPadding(0, Ui.px(this, 6), 0, 0) })
        listWrap.addView(ab, Ui.lp(-1, -2).also { it.topMargin = Ui.px(this, 4) })

        // 明暗模式
        listWrap.addView(section("外观"))
        val darkCard = Ui.card(this)
        darkCard.setPadding(Ui.px(this, 18), Ui.px(this, 14), Ui.px(this, 18), Ui.px(this, 16))
        val trow = Ui.linear(this, false)
        trow.gravity = Gravity.CENTER_VERTICAL
        trow.addView(Ui.text(this, "深色模式（纯黑）", 15f, getColor(R.color.text), true), Ui.lp(0, -2, 1f))
        val sw = ConfigActivity.SwitchView(this, getSharedPreferences("app", 0).getBoolean("dark", false)) { on ->
            getSharedPreferences("app", 0).edit().putBoolean("dark", on).apply()
            androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
                if (on) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)
            recreate()
        }
        sw.layoutParams = Ui.lp(Ui.px(this, 46), Ui.px(this, 26))
        trow.addView(sw)
        darkCard.addView(trow)
        listWrap.addView(darkCard, Ui.lp(-1, -2).also { it.topMargin = Ui.px(this, 4) })

        // 高级
        listWrap.addView(section("高级"))
        val ac = Ui.card(this)
        ac.setPadding(Ui.px(this, 18), Ui.px(this, 14), Ui.px(this, 18), Ui.px(this, 16))
        ac.addView(Ui.text(this, "JVM 启动参数", 16f, getColor(R.color.text), true))
        val e = EditText(this)
        val sp = getSharedPreferences("app", 0)
        e.setText(sp.getString("jvm_flags", ServerManager.DEFAULT_FLAGS))
        e.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 12f)
        e.setTextColor(Color.parseColor("#3A3E3C"))
        e.setTypeface(android.graphics.Typeface.MONOSPACE)
        e.setBackgroundResource(R.drawable.bg_console)
        e.setPadding(Ui.px(this, 14), Ui.px(this, 10), Ui.px(this, 14), Ui.px(this, 10))
        e.minLines = 3
        e.gravity = Gravity.TOP
        val ep = Ui.lp(-1, -2); ep.topMargin = Ui.px(this, 10)
        ac.addView(e, ep)
        ac.addView(Ui.text(this, "默认是 Paper 推荐的 G1GC 参数，针对小内存做过调整。不懂别改。", 12f, getColor(R.color.text_sub)).also {
            it.setPadding(0, Ui.px(this, 8), 0, 0)
        })
        val saveFlag = Ui.text(this, "保存参数", 15f, getColor(R.color.green), true)
        saveFlag.setPadding(0, Ui.px(this, 10), 0, 0)
        saveFlag.setOnClickListener {
            sp.edit().putString("jvm_flags", e.text?.toString()?.trim() ?: "").apply()
            Ui.toast(this, "已保存")
        }
        ac.addView(saveFlag)
        listWrap.addView(ac, Ui.lp(-1, -2).also { it.topMargin = Ui.px(this, 4) })

        // 性能（12/5）
        listWrap.addView(section("性能"))
        val pc = Ui.card(this)
        pc.setPadding(Ui.px(this, 18), Ui.px(this, 14), Ui.px(this, 18), Ui.px(this, 16))
        pc.addView(Ui.text(this, "电池优化豁免", 16f, getColor(R.color.text), true))
        pc.addView(Ui.text(this, "省电策略会限制 CPU 频率、冻结后台进程，导致 TPS 掉帧/服务器被杀。加入白名单后开服更稳。", 12f, getColor(R.color.text_sub)).also {
            it.setPadding(0, Ui.px(this, 4), 0, 0)
        })
        val batBtn = Ui.text(this, "申请忽略电池优化", 15f, getColor(R.color.green), true)
        batBtn.setPadding(0, Ui.px(this, 10), 0, 0)
        val pm = getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        fun refreshBat() {
            val ign = pm.isIgnoringBatteryOptimizations(packageName)
            batBtn.text = if (ign) "✓ 已忽略电池优化" else "申请忽略电池优化"
        }
        refreshBat()
        batBtn.setOnClickListener {
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                Ui.toast(this, "已经在白名单里了")
            } else {
                try {
                    startActivity(android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    try { startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
                }
            }
        }
        pc.addView(batBtn)
        listWrap.addView(pc, Ui.lp(-1, -2).also { it.topMargin = Ui.px(this, 4) })

        // 工具
        listWrap.addView(section("工具"))
        val tc = Ui.card(this)
        tc.setPadding(Ui.px(this, 18), Ui.px(this, 14), Ui.px(this, 18), Ui.px(this, 16))
        tc.addView(Ui.text(this, "执行指令", 16f, getColor(R.color.text), true))
        tc.addView(Ui.text(this, "在本机执行 shell 命令并查看输出（排障用）", 13f, getColor(R.color.text_sub)).also {
            it.setPadding(0, Ui.px(this, 4), 0, Ui.px(this, 8))
        })
        tc.setOnClickListener { startActivity(Intent(this, CmdActivity::class.java)) }
        listWrap.addView(tc, Ui.lp(-1, -2).also { it.topMargin = Ui.px(this, 4) })

    }

    private val progressViews = mutableListOf<TextView>()
    private val rowProgs = HashMap<Int, TextView>()

    private fun urow(left: String, right: String, ok: Boolean): View {
        val row = Ui.linear(this, false)
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, Ui.px(this, 4), 0, Ui.px(this, 4))
        row.addView(Ui.text(this, left, 15f, getColor(R.color.text), true), Ui.lp(0, -2, 1f))
        row.addView(Ui.text(this, right, 14f, getColor(if (ok) R.color.green else R.color.danger), true))
        return row
    }

    private fun doJdk(v: Int, done: (String) -> Unit) {
        busy = true
        Thread {
            var r = "OK"
            try {
                if (!Store.hasRootfs()) {
                    Container.ensureRootfs(applicationContext) { s -> runOnUiThread { rowProgs[v]?.text = s } }
                }
                Container.downloadJdk(v) { s -> runOnUiThread { rowProgs[v]?.text = s; progressViews.forEach { it.text = "" } } }
                if (!Store.hasJdk(v)) r = "ERR:安装后校验失败"
            } catch (e: Exception) {
                r = "ERR:" + (e.message ?: e.toString())
            } finally { busy = false }
            runOnUiThread { rowProgs[v]?.text = ""; done(r) }
        }.start()
    }

    private fun doDownload(done: (String) -> Unit) {
        busy = true
        Thread {
            try {
                if (!Store.hasRootfs()) {
                    Container.ensureRootfs(applicationContext) { s -> runOnUiThread { progressViews.forEach { it.text = s } } }
                }
                runOnUiThread { done("OK") }
            } catch (e: Exception) {
                runOnUiThread { done("ERR:${e.message}") }
            } finally { busy = false }
        }.start()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Ui.immersive(this)
    }
}
