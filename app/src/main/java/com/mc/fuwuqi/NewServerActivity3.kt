package com.mc.fuwuqi

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONObject

/** 第3步：服务器配置（内存/人数/正版/难度/模式），创建按钮收尾 */
class NewServerActivity3 : androidx.appcompat.app.AppCompatActivity() {

    private var memory = 2048
    private var maxPlayers = 20
    private var onlineMode = false
    private var difficulty = "easy"
    private var gamemode = "survival"
    private var memText: TextView? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val core = intent.getStringExtra("core") ?: Cores.VANILLA
        val version = intent.getStringExtra("version") ?: ""
        val name = intent.getStringExtra("name") ?: "我的服务器"
        val port = intent.getIntExtra("port", 25565)
        val motd = intent.getStringExtra("motd") ?: "Minecraft Server"

        val root = Ui.linear(this, true)
        root.setBackgroundColor(getColor(R.color.bg))
        root.addView(Ui.topBar(this, "服务器配置", "$core $version · 第 3 步 / 共 3 步"))

        val scroll = Ui.scroll(this)
        val c = Ui.linear(this, true)
        c.setPadding(Ui.px(this, 20), Ui.px(this, 4), Ui.px(this, 20), Ui.px(this, 16))

        // 服务器配置（全字段，与配置页一致）
        val values = LinkedHashMap<String, String>()
        values["motd"] = motd
        values["server-port"] = port.toString()
        values["online-mode"] = "false"
        values["enforce-secure-profile"] = "false"
        values["view-distance"] = "4"
        if (core == Cores.CROSSPLAY) {
            // 显式模式选择按钮（正主/离线），写 values["online-mode"]
            val modeRow = android.widget.LinearLayout(this)
            modeRow.orientation = android.widget.LinearLayout.HORIZONTAL
            modeRow.gravity = Gravity.CENTER
            modeRow.setPadding(0, Ui.px(this, 4), 0, Ui.px(this, 8))
            fun modeBtn(label: String, online: Boolean): TextView {
                val t = TextView(this); t.text = label; t.tag = online
                t.gravity = Gravity.CENTER; t.setPadding(Ui.px(this,18), Ui.px(this,10), Ui.px(this,18), Ui.px(this,10))
                return t }
            val bOff = modeBtn("⚪ 离线模式", false)
            val bOn = modeBtn("🟢 正版模式", true)
            fun refreshMode(){ val online = values["online-mode"]=="true"
                for (b in listOf(bOff,bOn)) { val on = (b.tag as Boolean)==online
                    b.setTextColor(if (on) Color.WHITE else getColor(R.color.text))
                    b.setBackgroundResource(if (on) R.drawable.bg_btn_green else R.drawable.bg_card) } }
            bOff.setOnClickListener { values["online-mode"]="false"; refreshMode() }
            bOn.setOnClickListener { values["online-mode"]="true"; refreshMode() }
            modeRow.addView(bOff); modeRow.addView(bOn)
            val modeParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            modeParams.setMargins(Ui.px(this,20), 0, Ui.px(this,20), Ui.px(this,4))
            root.addView(modeRow, modeParams)
            refreshMode()
            root.addView(Ui.text(this,
                "📱 互通服提示：基岩版（手机）玩家填「服务器 IP + 端口 19132」即可，局域网里也能直接搜到。\n" +
                "· 正版模式：基岩玩家需 Xbox 账号验证，进服名字会带 \".\" 前缀，可公开 IP\n" +
                "· 离线模式：任何人可直接进，仅限熟人使用，严禁公开 IP\n" +
                "· 外网联机：路由器需放行 UDP 19132（新版基岩客户端还会用 UDP 19133），端口写错的典型现象就是“一直显示正在连接”", 12.5f, getColor(R.color.text_sub)))
            (root.getChildAt(root.childCount - 1) as TextView).setPadding(Ui.px(this, 20), Ui.px(this, 10), Ui.px(this, 20), Ui.px(this, 4))
            // 基岩版版本：手机端版本必须与 Geyser 支持的版本一致，否则一直卡在“正在连接”
            val bvText = Ui.text(this, "· 基岩版版本：检测中 …", 12.5f, getColor(R.color.green), true)
            bvText.setPadding(Ui.px(this, 20), 0, Ui.px(this, 20), Ui.px(this, 4))
            root.addView(bvText)
            Thread {
                val jar = Cores.installedGeyserJar()
                val bv = jar?.let { Cores.bedrockVersion(it) }
                val gv = jar?.let { Cores.geyserVersion(it) }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    bvText.text = when {
                        bv != null -> "· 基岩版版本：$bv" +
                            (if (gv != null) "（Geyser $gv）" else "") + " —— 手机端必须是这个版本才能进服"
                        else -> "· 基岩版版本：创建完成后自动显示（手机端需与 Geyser 支持的版本一致）"
                    }
                }
            }.start()
        }
        values["difficulty"] = difficulty
        values["gamemode"] = gamemode
        values["max-players"] = maxPlayers.toString()
        val editor = PropsEditor(this, values)

        // 内存
        val memCard = Ui.card(this)
        memCard.setPadding(Ui.px(this, 18), Ui.px(this, 14), Ui.px(this, 18), Ui.px(this, 16))
        val mrow = Ui.linear(this, false)
        mrow.gravity = Gravity.CENTER_VERTICAL
        memText = Ui.text(this, "$memory MB", 24f, getColor(R.color.green), true)
        mrow.addView(memText, Ui.lp(0, -2, 1f))
        mrow.addView(Ui.text(this, "当前值", 13f, getColor(R.color.text_sub)))
        memCard.addView(mrow)
        val sb = SeekBar(this)
        sb.max = 14
        sb.progress = (memory - 512) / 256
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                memory = 512 + p * 256
                memText?.text = "$memory MB"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        memCard.addView(sb, Ui.lp(-1, -2))
        val mdesc = Ui.text(this, "开服后也能改。4GB 机型建议 2048MB，8GB 机型可以给 3072~4096MB；改完要重启服务器才生效。", 12f, getColor(R.color.text_sub))
        mdesc.setPadding(0, Ui.px(this, 8), 0, 0)
        memCard.addView(mdesc)
        c.addView(memCard, Ui.lp(-1, -2))

        editor.addSections(c, false)

        scroll.addView(c, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, Ui.lp(-1, 0, 1f))

        // 创建（固定底部）
        val go = Ui.text(this, "创 建", 17f, Color.WHITE, true)
        go.gravity = Gravity.CENTER
        go.setBackgroundResource(R.drawable.bg_btn_green)
        go.setPadding(0, Ui.px(this, 15), 0, Ui.px(this, 15))
        val gp = Ui.lp(-1, -2)
        gp.setMargins(Ui.px(this, 20), Ui.px(this, 10), Ui.px(this, 20), Ui.px(this, 24))
        root.addView(go, gp)

        go.setOnClickListener {
            if (!Ui.needStorage(this)) return@setOnClickListener
            editor.collect()
            go.isEnabled = false; go.text = "创建中 …"
            val o = JSONObject()
            o.put("id", Store.newId())
            o.put("name", name)
            o.put("core", core)
            o.put("mcVersion", version)
            o.put("javaVersion", Container.javaForMc(version))
            o.put("port", values["server-port"]?.toIntOrNull() ?: port)
            o.put("motd", values["motd"] ?: motd)
            o.put("maxPlayers", values["max-players"]?.toIntOrNull() ?: maxPlayers)
            o.put("onlineMode", values["online-mode"] == "true")
            o.put("memoryMb", memory)
            val po = JSONObject()
            for ((k2, v2) in values) po.put(k2, v2)
            po.put("max-tick-time", "-1")
            o.put("props", po)
            Thread {
                try {
                    val arr = Store.loadServers(); arr.put(o); Store.saveServers(arr)
                    val dir = Store.serverDir(o.optString("id")); dir.mkdirs()
                    Cores.download(core, version, dir) { s -> runOnUiThread { go.text = s } }
                    var cross: Cores.Crossplay? = null
                    if (core == Cores.CROSSPLAY) {
                        cross = Cores.installCrossplayPlugins(dir, o.optBoolean("onlineMode", false), o.optInt("port", 25565)) { s -> runOnUiThread { go.text = s } }
                        // 记下这版 Geyser 支持的基岩版版本：首页卡片、控制台页都靠它显示
                        cross?.bedrockVersion?.let { bv ->
                            o.put("bedrockVersion", bv)
                            Store.updateServer(o.optString("id"),
                                JSONObject().put("bedrockVersion", bv).put("geyserVersion", cross?.geyserVersion ?: ""))
                        }
                    }
                    // 把第 3 步的用户配置写入 server.properties（内存等 JVM 参数在启动时另用）
                    val propsFile = java.io.File(dir, "server.properties")
                    val existing = if (propsFile.exists()) propsFile.readLines().toMutableList() else mutableListOf()
                    val po = o.optJSONObject("props")
                    if (po != null) {
                        for (k in po.keys()) {
                            val line = "$k=${po.optString(k)}"
                            val idx = existing.indexOfFirst { it.startsWith("$k=") }
                            if (idx >= 0) existing[idx] = line else existing.add(line)
                        }
                    }
                    if (existing.none { it.startsWith("online-mode=") })
                        existing.add(0, "online-mode=${o.optBoolean("onlineMode", false)}")
                    propsFile.writeText(existing.joinToString("\n") + "\n")
                    runOnUiThread {
                        val bv = o.optString("bedrockVersion")
                        Ui.toast(this, if (bv.isNotEmpty()) "创建完成 · 基岩版 $bv（端口 19132）" else "创建完成")
                        val i3 = Intent(this, ConsoleActivity::class.java).putExtra("id", o.optString("id"))
                        startActivity(i3)
                        finish()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        go.isEnabled = true; go.text = "创 建"
                        if (e.message?.contains("互通服需要 Paper") == true) {
                            androidx.appcompat.app.AlertDialog.Builder(this)
                                .setTitle("无法创建互通服")
                                .setMessage(e.message + "\n\n未来 Paper/Geyser 支持该版本后，即可正常创建。")
                                .setCancelable(false)
                                .setPositiveButton("确认") { _, _ -> finish() }
                                .show()
                        } else {
                            go.text = "重试：${e.message}"
                        }
                    }
                }
            }.start()
        }

        setContentView(root)
        Ui.immersive(this)
    }

    private fun subTitle(t: String): TextView {
        val v = Ui.text(this, t, 15f, getColor(R.color.text_sub), true)
        v.setPadding(Ui.px(this, 4), Ui.px(this, 16), 0, Ui.px(this, 8))
        return v
    }

    private fun chip(label: String, sel: Boolean, onClick: () -> Unit): TextView {
        val t = Ui.text(this, label, 14f, if (sel) Color.WHITE else getColor(R.color.text), true)
        t.gravity = Gravity.CENTER
        t.setBackgroundResource(if (sel) R.drawable.bg_btn_green else R.drawable.bg_card)
        t.setPadding(Ui.px(this, 10), Ui.px(this, 10), Ui.px(this, 10), Ui.px(this, 10))
        t.setOnClickListener { onClick() }
        return t
    }

    private fun refreshChips(list: List<Pair<String, String>>, cur: String, onSel: (String, Boolean) -> Unit) {}

    private fun mark(container: LinearLayout, label: String) {
        for (i in 0 until container.childCount) {
            val ch = container.getChildAt(i) as TextView
            val sel = ch.tag == label
            ch.setTextColor(if (sel) Color.WHITE else getColor(R.color.text))
            ch.setBackgroundResource(if (sel) R.drawable.bg_btn_green else R.drawable.bg_card)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Ui.immersive(this)
    }
}
