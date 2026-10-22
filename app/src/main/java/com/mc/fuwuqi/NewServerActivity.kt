package com.mc.fuwuqi

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/** 新建服务器：第1步选核心+版本（2步流程，与参照一致） */
class NewServerActivity : androidx.appcompat.app.AppCompatActivity() {

    private var core = Cores.VANILLA
    private var versions: List<String> = emptyList()
    private var filtered: List<String> = emptyList()
    private var chosen: String? = null
    private var loading = false

    private lateinit var chips: LinearLayout
    private lateinit var search: EditText
    private lateinit var listWrap: LinearLayout
    private lateinit var nextBtn: TextView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = Ui.linear(this, true)
        root.setBackgroundColor(getColor(R.color.bg))
        root.addView(Ui.topBar(this, "选择版本", "第 1 步 / 共 3 步"))

        val scroll = Ui.scroll(this)
        val content = Ui.linear(this, true)
        content.setPadding(Ui.px(this, 20), Ui.px(this, 4), Ui.px(this, 20), Ui.px(this, 16))

        chips = Ui.linear(this, false)
        chips.setPadding(0, Ui.px(this, 6), 0, 0)
        buildChips()
        content.addView(chips)

        search = EditText(this)
        search.hint = "搜索版本，例如 1.21 或 1.20.4"
        search.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15f)
        search.setBackgroundResource(R.drawable.bg_console)
        search.setPadding(Ui.px(this, 16), Ui.px(this, 12), Ui.px(this, 16), Ui.px(this, 12))
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { renderList() }
            override fun beforeTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) {}
            override fun onTextChanged(s: CharSequence?, p1: Int, p2: Int, p3: Int) {}
        })
        val sp = Ui.lp(-1, -2); sp.topMargin = Ui.px(this, 12)
        content.addView(search, sp)

        listWrap = Ui.linear(this, true)
        content.addView(listWrap)

        nextBtn = Ui.text(this, "先选一个版本", 16f, Color.WHITE, true)
        nextBtn.gravity = Gravity.CENTER
        nextBtn.setBackgroundResource(R.drawable.bg_console)
        nextBtn.setPadding(0, Ui.px(this, 14), 0, Ui.px(this, 14))
        nextBtn.setOnClickListener {
            val v = chosen ?: return@setOnClickListener
            val i = Intent(this, NewServerActivity2::class.java)
            i.putExtra("core", core); i.putExtra("version", v)
            startActivity(i); finish()
        }
        scroll.addView(content, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, Ui.lp(-1, 0, 1f))
        // 下一步固定在底部，不用滚下去点
        val np = Ui.lp(-1, -2)
        np.setMargins(Ui.px(this, 20), Ui.px(this, 10), Ui.px(this, 20), Ui.px(this, 24))
        root.addView(nextBtn, np)
        setContentView(root)
        Ui.immersive(this)
        load()
    }

    private fun buildChips() {
        chips.removeAllViews()
        for (name in listOf(Cores.PAPER to "插件服", Cores.PURPUR to "插件服", Cores.VANILLA to "纯净服")) {
            val sel = name.first == core
            val c = Ui.linear(this, true)
            c.gravity = Gravity.CENTER_HORIZONTAL
            c.setBackgroundResource(if (sel) R.drawable.bg_tab_active else R.drawable.bg_card)
            c.setPadding(Ui.px(this, 14), Ui.px(this, 14), Ui.px(this, 14), Ui.px(this, 14))
            c.addView(Ui.text(this, name.first, 17f, if (sel) getColor(R.color.green) else getColor(R.color.text), true))
            val sub = Ui.text(this, name.second, 12f, getColor(R.color.text_sub))
            sub.setPadding(0, Ui.px(this, 3), 0, 0)
            c.addView(sub)
            val lp = Ui.lp(0, -2, 1f)
            c.setOnClickListener { if (!loading && core != name.first) { core = name.first; chosen = null; buildChips(); load() } }
            val p = lp; p.rightMargin = Ui.px(this, 10)
            chips.addView(c, p)
        }
    }

    private fun load() {
        loading = true
        listWrap.removeAllViews()
        val tip = Ui.text(this, "加载版本列表中 …", 14f, getColor(R.color.text_sub))
        tip.gravity = Gravity.CENTER
        val tp = Ui.lp(-1, -2); tp.topMargin = Ui.px(this, 30)
        listWrap.addView(tip, tp)
        Thread {
            versions = try { Cores.list(core) } catch (e: Exception) { emptyList() }
            loading = false
            runOnUiThread { renderList() }
        }.start()
    }

    private fun renderList() {
        if (loading) return
        listWrap.removeAllViews()
        val q = search.text?.toString()?.trim() ?: ""
        filtered = if (q.isEmpty()) versions else versions.filter { it.contains(q) }
        if (filtered.isEmpty()) {
            val t = Ui.text(this, if (versions.isEmpty()) "获取失败，点击重试" else "无匹配版本", 14f,
                getColor(R.color.text_sub))
            t.gravity = Gravity.CENTER
            t.setOnClickListener { if (versions.isEmpty()) { loading = true; load() } }
            val tp = Ui.lp(-1, -2); tp.topMargin = Ui.px(this, 30)
            listWrap.addView(t, tp)
            return
        }
        for (v in filtered) {
            val sel = v == chosen
            val row = Ui.linear(this, false)
            row.gravity = Gravity.CENTER_VERTICAL
            row.setBackgroundResource(if (sel) R.drawable.bg_tab_active else R.drawable.bg_card)
            row.setPadding(Ui.px(this, 18), Ui.px(this, 16), Ui.px(this, 18), Ui.px(this, 16))
            row.addView(Ui.text(this, v, 19f, if (sel) getColor(R.color.green) else getColor(R.color.text), true), Ui.lp(0, -2, 1f))
            val jv = Container.javaForMc(v)
            val badge = Ui.text(this, "Java $jv", 14f, getColor(R.color.amber_text), true)
            badge.setBackgroundResource(R.drawable.bg_console)
            badge.setPadding(Ui.px(this, 12), Ui.px(this, 4), Ui.px(this, 12), Ui.px(this, 4))
            row.addView(badge)
            row.setOnClickListener { chosen = v; nextBtn.text = "下一步：命名并创建"; nextBtn.setBackgroundResource(R.drawable.bg_btn_green); renderList() }
            val p = Ui.lp(-1, -2); p.topMargin = Ui.px(this, 10)
            listWrap.addView(row, p)
        }
    }
}

/** 第2步：服务器名 + 内存 + motd/端口，创建 */
class NewServerActivity2 : androidx.appcompat.app.AppCompatActivity() {

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val core = intent.getStringExtra("core") ?: Cores.VANILLA
        val version = intent.getStringExtra("version") ?: ""
        val root = Ui.linear(this, true)
        root.setBackgroundColor(getColor(R.color.bg))
        root.addView(Ui.topBar(this, "创建服务器", "$core $version · 第 2 步 / 共 3 步"))

        val scroll = Ui.scroll(this)
        val c = Ui.linear(this, true)
        c.setPadding(Ui.px(this, 20), Ui.px(this, 4), Ui.px(this, 20), Ui.px(this, 16))

        fun field(label: String, hint: String, def: String, mono: Boolean = false): EditText {
            val card = Ui.card(this)
            card.setPadding(Ui.px(this, 18), Ui.px(this, 14), Ui.px(this, 18), Ui.px(this, 16))
            card.addView(Ui.text(this, label, 15f, getColor(R.color.text), true))
            val e = EditText(this)
            e.hint = hint
            e.setText(def)
            e.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15f)
            e.setBackgroundResource(android.R.color.transparent)
            e.setSingleLine(true)
            val ep = Ui.lp(-1, -2); ep.topMargin = Ui.px(this, 8)
            card.addView(e, ep)
            val cp = Ui.lp(-1, -2); cp.topMargin = Ui.px(this, 12)
            c.addView(card, cp)
            return e
        }

        val nameE = field("服务器名称", "例如 老王家生存服", "我的服务器")
        val portE = field("端口", "默认 25565", "25565")
        val motdE = field("标语 motd", "多人游戏列表里看到的那行字", "Minecraft Server")

        val tip = Ui.text(this,
            "创建时会自动下载核心（约 50MB）并写入 eula/配置。Java 版本按 MC 版本自动匹配。",
            13f, getColor(R.color.text_sub))
        tip.setPadding(Ui.px(this, 6), Ui.px(this, 14), Ui.px(this, 6), 0)
        c.addView(tip)

        val go = Ui.text(this, "下一步", 17f, Color.WHITE, true)
        go.gravity = Gravity.CENTER
        go.setBackgroundResource(R.drawable.bg_btn_green)
        go.setPadding(0, Ui.px(this, 15), 0, Ui.px(this, 15))
        scroll.addView(c, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, Ui.lp(-1, 0, 1f))
        val gp = Ui.lp(-1, -2)
        gp.setMargins(Ui.px(this, 20), Ui.px(this, 10), Ui.px(this, 20), Ui.px(this, 24))
        root.addView(go, gp)

        go.setOnClickListener {
            Ui.needStorage(this)
            val i2 = Intent(this, NewServerActivity3::class.java)
            i2.putExtra("core", core)
            i2.putExtra("version", version)
            i2.putExtra("name", nameE.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: "我的服务器")
            i2.putExtra("port", portE.text?.toString()?.toIntOrNull() ?: 25565)
            i2.putExtra("motd", motdE.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: "Minecraft Server")
            startActivity(i2)
        }

        setContentView(root)
        Ui.immersive(this)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Ui.immersive(this)
    }
}
