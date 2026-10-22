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

/** 首页：服务器列表 + 底部三 Tab */
class MainActivity : androidx.appcompat.app.AppCompatActivity() {

    private lateinit var body: LinearLayout
    private lateinit var tabHome: LinearLayout
    private lateinit var tabNew: LinearLayout
    private lateinit var tabSet: LinearLayout

    override fun onCreate(b: Bundle?) {
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            if (getSharedPreferences("app", 0).getBoolean("dark", false))
                androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO)
        super.onCreate(b)
        installCrashHook()
        val root = Ui.linear(this, true)
        root.setBackgroundColor(getColor(R.color.bg))

        val head = Ui.linear(this, true)
        head.setPadding(Ui.px(this, 22), Ui.px(this, 48), Ui.px(this, 22), Ui.px(this, 8))
        head.addView(Ui.text(this, "MC开服", 30f, getColor(R.color.text), true))
        val tag = Ui.text(this, getString(R.string.tagline), 14f, getColor(R.color.text_sub))
        tag.setPadding(0, Ui.px(this, 4), 0, 0)
        head.addView(tag)

        body = Ui.linear(this, true)
        body.setPadding(Ui.px(this, 22), Ui.px(this, 8), Ui.px(this, 22), Ui.px(this, 12))

        val scroll = Ui.scroll(this)
        scroll.addView(body, ViewGroup.LayoutParams(-1, -2))

        val bottom = buildBottomBar()

        root.addView(head)
        root.addView(scroll, Ui.lp(-1, 0, 1f))
        root.addView(bottom)
        // 授权门：无存储权限先显示应用内授权页（参照物同款）
        if (!Ui.needStorageSilent(this)) {
            setContentView(permPage())
            return
        }
        setContentView(root)
        autoExtractContainer()
    }

    private var permShown = false

    private fun permPage(): android.view.View {
        permShown = true
        val c = Ui.linear(this, true)
        c.setBackgroundColor(getColor(R.color.bg))
        c.gravity = android.view.Gravity.CENTER
        val pad = Ui.px(this, 32)
        c.setPadding(pad, pad, pad, pad)
        val icon = android.widget.ImageView(this)
        icon.setImageResource(R.mipmap.ic_launcher)
        icon.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        c.addView(icon, Ui.lp(Ui.px(this, 96), Ui.px(this, 96)))
        val t = Ui.text(this, "MC开服", 24f, getColor(R.color.text), true)
        t.gravity = android.view.Gravity.CENTER
        t.setPadding(0, Ui.px(this, 20), 0, Ui.px(this, 8))
        c.addView(t)
        val sub = Ui.text(this, "开服前需要「所有文件访问权限」\n用于存放服务器文件、解压容器和运行核心", 15f, getColor(R.color.text_sub))
        sub.gravity = android.view.Gravity.CENTER
        c.addView(sub)
        val btn = Ui.text(this, "授予权限", 17f, Color.WHITE, true)
        btn.gravity = android.view.Gravity.CENTER
        btn.setBackgroundResource(R.drawable.bg_btn_green)
        val bp = Ui.lp(-1, Ui.px(this, 52))
        bp.topMargin = Ui.px(this, 36)
        c.addView(btn, bp)
        btn.setOnClickListener {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                try {
                    startActivity(android.content.Intent(
                        android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
                        android.net.Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        }
        return c
    }

    override fun onResume() {
        super.onResume()
        if (permShown && Ui.needStorageSilent(this)) {
            permShown = false
            recreate()
        }
        if (Ui.needStorage(this)) render()
        UiBus.subscribe { runOnUiThread { render() } }
    }

    override fun onPause() {
        super.onPause()
        UiBus.unsubscribe { render() }
    }

    fun render() {
        Store.ensureDirs()
        body.removeAllViews()
        addPermCard()
        if (ServerManager.runningId != null) {
            val cur = Store.findServer(ServerManager.runningId!!)
            if (cur != null) addServerCard(cur, true)
        }
        val arr = Store.loadServers()
        var any = false
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("id") == ServerManager.runningId) { any = true; continue }
            any = true
            addServerCard(o, false)
        }
        if (!any && ServerManager.runningId == null) addEmpty()
    }

    private fun autoExtractContainer() {
        // 参照物同款：首次使用自动解压内置容器（后台，无需手点）
        if (!Store.hasRootfs()) {
            // 有权限直接解压；没权限先弹授权页，回来 onResume 会再触发
            if (Ui.needStorageSilent(this)) {
                Thread {
                    try { Container.ensureRootfs(applicationContext) { _ -> } } catch (_: Exception) {}
                }.start()
            } else if (android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) {
                Ui.needStorage(this)
            }
        }
    }

    private fun installCrashHook() {
        val app = applicationContext
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val f = java.io.File(Store.base, "crash-" + System.currentTimeMillis() + ".txt")
                f.parentFile?.mkdirs()
                f.writeText(java.io.StringWriter().apply {
                    e.printStackTrace(java.io.PrintWriter(this))
                }.toString())
            } catch (_: Exception) {}
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    private fun addPermCard() {
        // 所有文件访问权限
        if (android.os.Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()) {
            val c = Ui.card(this)
            c.setPadding(Ui.px(this, 18), Ui.px(this, 16), Ui.px(this, 18), Ui.px(this, 16))
            c.addView(Ui.text(this, "⚠ 需要文件访问权限", 16f, getColor(R.color.amber_text), true))
            val d = Ui.text(this,
                "用于存放服务器文件（/sdcard/MCFuwuqi）。点下方去系统设置，打开「允许访问所有文件」。", 13f, getColor(R.color.text_sub))
            d.setPadding(0, Ui.px(this, 6), 0, Ui.px(this, 12))
            c.addView(d)
            val btn = Ui.text(this, "去授权", 15f, Color.WHITE, true)
            btn.gravity = Gravity.CENTER
            btn.setBackgroundResource(R.drawable.bg_btn_green)
            btn.setPadding(Ui.px(this, 24), Ui.px(this, 10), Ui.px(this, 24), Ui.px(this, 10))
            btn.setOnClickListener { Ui.needStorage(this); }
            c.addView(btn, Ui.lp(-2, -2))
            body.addView(c, Ui.lp(-1, -2).also { it.bottomMargin = Ui.px(this, 12) })
        }
        // 通知权限（运行中通知用）
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 9)
        }
    }

    private fun addEmpty() {
        val box = Ui.linear(this, true)
        box.gravity = Gravity.CENTER
        box.setPadding(0, Ui.px(this, 110), 0, Ui.px(this, 110))
        box.addView(Ui.text(this, "还没有服务器", 20f, getColor(R.color.text), true))
        val s = Ui.text(this, "选一个版本，一分钟就能开服", 14f, getColor(R.color.text_sub))
        s.setPadding(0, Ui.px(this, 8), 0, Ui.px(this, 28))
        box.addView(s)
        val btn = Ui.text(this, "＋ 新建服务器", 17f, Color.WHITE, true)
        btn.gravity = Gravity.CENTER
        btn.setBackgroundResource(R.drawable.bg_btn_green)
        btn.setPadding(Ui.px(this, 36), Ui.px(this, 13), Ui.px(this, 36), Ui.px(this, 13))
        btn.setOnClickListener { startActivity(Intent(this, NewServerActivity::class.java)) }
        box.addView(btn, Ui.lp(-2, -2))
        body.addView(box)
    }

    private fun addServerCard(o: JSONObject, running: Boolean) {
        val id = o.optString("id")
        val c = Ui.card(this)
        c.setPadding(Ui.px(this, 18), Ui.px(this, 16), Ui.px(this, 18), Ui.px(this, 16))
        val row = Ui.linear(this, false)
        row.gravity = Gravity.CENTER_VERTICAL
        val nameCol = Ui.linear(this, true)
        nameCol.addView(Ui.text(this, o.optString("name", "服务器"), 17f, getColor(R.color.text), true))
        val dot = if (running) "● 运行中" else "○ ${o.optString("core")} ${o.optString("mcVersion")} · :${o.optInt("port", 25565)}"
        val st = Ui.text(this, dot, 13f, if (running) getColor(R.color.green) else getColor(R.color.text_sub))
        st.setPadding(0, Ui.px(this, 4), 0, 0)
        nameCol.addView(st)
        row.addView(nameCol, Ui.lp(0, -2, 1f))
        row.addView(Ui.text(this, if (running) "控制台 ›" else "启动 ›", 15f, getColor(R.color.green), true))
        row.setOnClickListener {
            startActivity(Intent(this, ConsoleActivity::class.java).putExtra("id", id))
        }
        c.addView(row)
        body.addView(c, Ui.lp(-1, -2).also { it.topMargin = Ui.px(this, 12) })
    }

    private fun buildBottomBar(): View {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setBackgroundColor(getColor(R.color.card))
        bar.setPadding(Ui.px(this, 8), Ui.px(this, 8), Ui.px(this, 8), Ui.px(this, 10))

        // 参照物同款：图标居中在上、文字居中在下；中间新建=浅绿圆底+绿加号
        fun tab(label: String, iconRes: Int, center: Boolean): LinearLayout {
            val t = LinearLayout(this)
            t.orientation = LinearLayout.VERTICAL
            t.gravity = Gravity.CENTER_HORIZONTAL
            val iv = android.widget.ImageView(this)
            iv.setImageResource(iconRes)
            iv.setColorFilter(getColor(R.color.text_sub))
            if (center) {
                iv.setColorFilter(getColor(R.color.green))
                val pill = android.widget.FrameLayout(this)
                pill.setBackgroundResource(R.drawable.bg_circle_pale)
                val ip = android.widget.FrameLayout.LayoutParams(Ui.px(this, 26), Ui.px(this, 26))
                ip.gravity = Gravity.CENTER
                pill.addView(iv, ip)
                val pp = Ui.lp(-2, -2); pp.topMargin = Ui.px(this, 2)
                t.addView(pill, pp)
            } else {
                val ip = Ui.lp(Ui.px(this, 26), Ui.px(this, 26)); ip.topMargin = Ui.px(this, 5)
                t.addView(iv, ip)
            }
            val tv = Ui.text(this, label, 13f,
                if (center) getColor(R.color.green) else getColor(R.color.text_sub), true)
            tv.gravity = Gravity.CENTER_HORIZONTAL
            tv.setPadding(0, Ui.px(this, 5), 0, 0)
            t.addView(tv, Ui.lp(-2, -2))
            return t
        }
        tabHome = tab("服务器", R.drawable.ic_home, false)
        tabNew = tab("新建", R.drawable.ic_plus, true)
        tabSet = tab("设置", R.drawable.ic_gear, false)

        fun add(t: LinearLayout) {
            val p = LinearLayout.LayoutParams(0, -2, 1f)
            bar.addView(t, p)
        }
        add(tabHome); add(tabNew); add(tabSet)
        tabHome.setOnClickListener { render() }
        tabNew.setOnClickListener { startActivity(Intent(this, NewServerActivity::class.java)) }
        tabSet.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        return bar
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Ui.immersive(this)
    }
}
