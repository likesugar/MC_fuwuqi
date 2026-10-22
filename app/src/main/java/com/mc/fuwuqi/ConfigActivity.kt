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
import android.widget.SeekBar
import android.widget.TextView
import org.json.JSONObject
import java.io.File

/** 服务器配置：整页 server.properties + 内存滑条 */
class ConfigActivity : androidx.appcompat.app.AppCompatActivity() {

    private lateinit var srv: JSONObject
    private lateinit var dir: File
    private var props = LinkedHashMap<String, String>()
    private val edits = HashMap<String, EditText>()
    private val toggles = HashMap<String, Boolean>()
    private val chipsSel = HashMap<String, String>()
    private var memory = 2048
    private var memText: TextView? = null

    // 字段规格：key, 标题, 说明, 类型, 默认, 选项
    data class F(val k: String, val t: String, val d: String, val type: String,
                 val def: String = "", val opts: List<String> = emptyList())

    val basic = listOf(
        F("motd", "服务器标语", "玩家在多人游戏列表里看到的那行字，支持 § 颜色代码", "text"),
        F("server-port", "服务器端口", "玩家连接用的端口，默认 25565。同一台手机开多个服时不能重复", "text"),
        F("max-players", "最大玩家数", "同时在线的上限。手机建议 5~10，开太多会卡", "text"),
        F("online-mode", "正版验证", "开启后只有正版账号能进。关掉才能让离线（盗版）客户端连接", "bool"),
        F("enforce-secure-profile", "强制安全签名", "1.19+ 新增。开启后离线客户端会被直接拒绝，即使是正版启动器也可能报错。离线服必须关掉", "bool"),
        F("white-list", "启用白名单", "只允许白名单里的玩家进入，用 whitelist add 命令添加", "bool"),
    )
    val play = listOf(
        F("gamemode", "默认游戏模式", "新玩家进入时的模式", "chip", "survival",
            listOf("生存:survival", "创造:creative", "冒险:adventure", "旁观:spectator")),
        F("force-gamemode", "强制游戏模式", "玩家每次进入都重置为默认模式", "bool"),
        F("difficulty", "游戏难度", "影响怪物强度和饥饿机制", "chip", "easy",
            listOf("和平:peaceful", "简单:easy", "普通:normal", "困难:hard")),
        F("hardcore", "极限模式", "死亡后只能旁观，无法复活", "bool"),
        F("pvp", "允许 PVP", "玩家之间可以互相伤害", "bool", "true"),
        F("allow-flight", "允许飞行", "关闭时使用飞行类插件的玩家会被踢出", "bool", "true"),
        F("enable-command-block", "启用命令方块", "允许使用命令方块", "bool"),
        F("op-permission-level", "OP 权限等级", "1=绕过出生点保护 2=可用命令方块 3=可封禁玩家 4=完全权限", "text", "2"),
        F("player-idle-timeout", "挂机踢出（分钟）", "0 表示不踢。玩家挂机超过这个时间会被踢下线", "text", "0"),
    )
    val world = listOf(
        F("level-name", "世界名称", "世界文件夹的名字，改这个会读取另一个世界", "text", "world"),
        F("level-seed", "世界种子", "留空则随机生成。填数字或文字可以复现同一个世界", "text"),
        F("level-type", "世界类型", "生成世界的算法", "chip", "minecraft\\:normal",
            listOf("默认:normal", "超平坦:flat", "巨型生物群系:large_biomes", "放大化:amplified")),
        F("spawn-protection", "出生点保护半径", "出生点周围多少格内非 OP 不能破坏方块，0 为关闭", "text", "0"),
        F("generate-structures", "生成结构", "生成村庄、要塞等结构", "bool", "true"),
        F("spawn-monsters", "生成怪物", "关闭后世界不会刷怪", "bool", "true"),
        F("spawn-animals", "生成动物", "关闭后不会自然生成动物", "bool", "true"),
        F("spawn-npcs", "生成村民", "关闭后不会生成村民", "bool", "true"),
        F("allow-nether", "允许下界", "玩家可以通过传送门去下界", "bool", "true"),
    )
    val net = listOf(
        F("enable-status", "允许查询状态", "关闭后服务器列表里看不到在线人数和 MOTD", "bool", "true"),
        F("network-compression-threshold", "网络压缩阈值", "超过多少字节的数据包才压缩，-1 关闭。手机建议 256", "text", "256"),
    )
    val perf = listOf(
        F("view-distance", "视距（区块）", "玩家能看到多远。手机建议 4~6，调到 10 以上会明显卡顿", "text", "6"),
        F("simulation-distance", "模拟距离（区块）", "怪物、作物、红石的活动范围。手机建议 4，最低 3", "text", "4"),
        F("sync-chunk-writes", "同步区块写入", "关闭可以提升性能，但突然断电可能损坏存档", "bool"),
        F("use-native-transport", "使用原生传输", "Linux 下的网络优化，建议开启", "bool", "true"),
    )
    val adv = listOf(
        F("enforce-whitelist", "强制白名单", "白名单外的在线玩家会被立刻踢出", "bool"),
        F("max-world-size", "世界边界半径", "单位方块，默认 29999984。改小可以限制地图大小", "text"),
        F("server-ip", "绑定 IP", "一般留空（监听所有网卡）。只有在多网卡服务器上才需要填", "text"),
        F("prevent-proxy-connections", "阻止代理连接", "开启后通过 VPN/代理连接的玩家会被拒绝", "bool"),
        F("enable-rcon", "启用 RCON", "远程控制台，一般用不到", "bool"),
        F("rcon.port", "RCON 端口", "启用 RCON 后的监听端口", "text"),
        F("rcon.password", "RCON 密码", "启用 RCON 必填", "text"),
        F("entity-broadcast-range-percentage", "实体广播范围", "降低这个值能减少带宽占用（默认 100）", "text"),
    )

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val id = intent.getStringExtra("id") ?: ""
        srv = Store.findServer(id) ?: JSONObject()
        dir = Store.serverDir(id)
        loadProps()

        val root = Ui.linear(this, true)
        root.setBackgroundColor(getColor(R.color.bg))
        root.addView(Ui.topBar(this, "服务器配置",
            "${if (!srv.optBoolean("onlineMode")) "离线可进" else "正版验证"} · ${srv.optString("core").lowercase()} ${srv.optString("mcVersion")}",
            "保存" to View.OnClickListener { save() }))

        val scroll = Ui.scroll(this)
        val c = Ui.linear(this, true)
        c.setPadding(Ui.px(this, 20), Ui.px(this, 4), Ui.px(this, 20), Ui.px(this, 24))

        // 内存分配
        memory = srv.optInt("memoryMb", 2048)
        val memCard = Ui.card(this)
        memCard.setPadding(Ui.px(this, 18), Ui.px(this, 14), Ui.px(this, 18), Ui.px(this, 16))
        val mrow = Ui.linear(this, false)
        mrow.gravity = Gravity.CENTER_VERTICAL
        memText = Ui.text(this, "$memory MB", 26f, getColor(R.color.green), true)
        mrow.addView(memText, Ui.lp(0, -2, 1f))
        mrow.addView(Ui.text(this, "当前值", 13f, getColor(R.color.text_sub)))
        memCard.addView(mrow)
        val sb = SeekBar(this)
        sb.max = 14 // 512..4096 step 256
        sb.progress = (memory - 512) / 256
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                memory = 512 + p * 256
                memText?.text = "$memory MB"
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        val sbp = Ui.lp(-1, -2); sbp.topMargin = Ui.px(this, 6)
        memCard.addView(sb, sbp)
        memCard.addView(Ui.text(this, "开服后也能改。4GB 机型建议 2048MB，8GB 机型可以给 3072~4096MB；改完要重启服务器才生效。", 12f, getColor(R.color.text_sub)))
        c.addView(memCard)

        c.addView(section("基础"))
        for (f in basic) addField(c, f)
        c.addView(section("玩法"))
        for (f in play) addField(c, f)
        c.addView(section("世界"))
        for (f in world) addField(c, f)
        c.addView(section("网络"))
        for (f in net) addField(c, f)
        c.addView(section("性能"))
        for (f in perf) addField(c, f)

        val advHead = Ui.linear(this, false)
        advHead.gravity = Gravity.CENTER_VERTICAL
        advHead.setPadding(Ui.px(this, 6), Ui.px(this, 22), Ui.px(this, 6), Ui.px(this, 6))
        advHead.addView(Ui.text(this, "展开高级设置", 15f, getColor(R.color.green), true), Ui.lp(0, -2, 1f))
        advHead.addView(Ui.text(this, "${adv.size} 项", 13f, getColor(R.color.text_sub)))
        c.addView(advHead)
        val advWrap = Ui.linear(this, true)
        for (f in adv) addField(advWrap, f)
        advWrap.visibility = View.GONE
        c.addView(advWrap)
        advHead.setOnClickListener { advWrap.visibility = if (advWrap.visibility == View.GONE) View.VISIBLE else View.GONE }

        c.addView(Ui.text(this, "提示：修改需要重启服务器才生效。世界相关参数（世界类型、种子）只在生成新世界时起作用，改了对已有存档无效。", 12f, getColor(R.color.text_sub)).also {
            it.setPadding(Ui.px(this, 6), Ui.px(this, 16), Ui.px(this, 6), 0)
        })

        scroll.addView(c, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, Ui.lp(-1, 0, 1f))
        setContentView(root)
        Ui.immersive(this)
    }

    private fun section(title: String): View {
        val t = Ui.text(this, title, 16f, getColor(R.color.text_sub), true)
        t.setPadding(Ui.px(this, 6), Ui.px(this, 20), 0, Ui.px(this, 4))
        return t
    }

    private fun loadProps() {
        val f = File(dir, "server.properties")
        if (f.exists()) {
            for (l in f.readLines()) {
                val i = l.indexOf('=')
                if (i > 0 && !l.startsWith("#")) props[l.substring(0, i)] = l.substring(i + 1)
            }
        }
    }

    private fun addField(parent: LinearLayout, f: F) {
        val card = Ui.card(this)
        card.setPadding(Ui.px(this, 18), Ui.px(this, 14), Ui.px(this, 18), Ui.px(this, 16))
        val head = Ui.linear(this, false)
        head.addView(Ui.text(this, f.t, 16f, getColor(R.color.text), true), Ui.lp(-2, -2))
        val key = Ui.text(this, "  ${f.k}", 12f, Color.parseColor("#B9BDBB"))
        key.paint.isFakeBoldText = false
        (head as LinearLayout).addView(key)
        card.addView(head)
        val desc = Ui.text(this, f.d, 13f, getColor(R.color.text_sub))
        desc.setPadding(0, Ui.px(this, 4), 0, 0)
        card.addView(desc)

        val cur = props[f.k] ?: f.def
        when (f.type) {
            "text" -> {
                val e = EditText(this)
                e.setText(cur)
                e.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15f)
                e.setBackgroundResource(R.drawable.bg_console)
                e.setPadding(Ui.px(this, 14), Ui.px(this, 10), Ui.px(this, 14), Ui.px(this, 10))
                e.setSingleLine(f.k != "motd")
                val ep = Ui.lp(-1, -2); ep.topMargin = Ui.px(this, 10)
                card.addView(e, ep)
                edits[f.k] = e
            }
            "bool" -> {
                val on = cur == "true"
                toggles[f.k] = on
                val row = Ui.linear(this, false)
                row.gravity = Gravity.CENTER_VERTICAL
                val st = Ui.text(this, if (on) "已开启" else "已关闭", 15f,
                    if (on) getColor(R.color.green) else getColor(R.color.text_sub), true)
                row.addView(st, Ui.lp(0, -2, 1f))
                val sw = SwitchView(this, on) {
                    toggles[f.k] = it
                    st.text = if (it) "已开启" else "已关闭"
                    st.setTextColor(getColor(if (it) R.color.green else R.color.text_sub))
                }
                row.addView(sw, Ui.lp(Ui.px(this, 52), Ui.px(this, 30)))
                val rp = Ui.lp(-1, -2); rp.topMargin = Ui.px(this, 12)
                card.addView(row, rp)
            }
            "chip" -> {
                val selected = cur
                chipsSel[f.k] = selected
                val row = Ui.linear(this, false)
                for (o in f.opts) {
                    val label = o.substringBefore(':')
                    val value = o.substringAfter(':')
                    val chip = Ui.text(this, label, 14f,
                        if (selected == value || normalizeChip(f.k, selected) == value) getColor(R.color.green) else getColor(R.color.text_sub), true)
                    chip.setBackgroundResource(if (normalizeChip(f.k, selected) == value) R.drawable.bg_tab_active else R.drawable.bg_console)
                    chip.setPadding(Ui.px(this, 14), Ui.px(this, 8), Ui.px(this, 14), Ui.px(this, 8))
                    chip.setOnClickListener {
                        chipsSel[f.k] = value
                        for (i in 0 until row.childCount) {
                            val ch = row.getChildAt(i) as TextView
                            val v = f.opts[i].substringAfter(':')
                            val sel2 = v == value
                            ch.setTextColor(getColor(if (sel2) R.color.green else R.color.text_sub))
                            ch.setBackgroundResource(if (sel2) R.drawable.bg_tab_active else R.drawable.bg_console)
                        }
                    }
                    val cp = Ui.lp(-2, -2); cp.rightMargin = Ui.px(this, 8); cp.topMargin = Ui.px(this, 10)
                    row.addView(chip, cp)
                }
                card.addView(row)
            }
        }
        val p = Ui.lp(-1, -2); p.topMargin = Ui.px(this, 12)
        parent.addView(card, p)
    }

    private fun normalizeChip(k: String, v: String): String = v.substringAfterLast(':')

    private fun save() {
        // 合并回 props
        for ((k, e) in edits) props[k] = e.text?.toString() ?: ""
        for ((k, v) in toggles) props[k] = if (v) "true" else "false"
        for ((k, v) in chipsSel) props[k] = v
        val f = File(dir, "server.properties")
        f.writeText(props.entries.joinToString("\n") { "${it.key}=${it.value}" } + "\n")
        Store.updateServer(srv.optString("id"), JSONObject().put("memoryMb", memory)
            .put("port", props["server-port"]?.toIntOrNull() ?: srv.optInt("port", 25565))
            .put("motd", props["motd"] ?: srv.optString("motd"))
            .put("maxPlayers", props["max-players"]?.toIntOrNull() ?: srv.optInt("maxPlayers", 20))
            .put("onlineMode", props["online-mode"] == "true"))
        Ui.toast(this, "已保存，重启服务器后生效")
    }

    /** 简易开关控件（避免引入 Material 依赖） */
    class SwitchView(ctx: Activity, init: Boolean, cb: (Boolean) -> Unit) : View(ctx) {
        private var on = init
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        init {
            setOnClickListener {
                on = !on
                cb(on)
                invalidate()
            }
        }
        override fun onDraw(cv: android.graphics.Canvas) {
            val a = context as Activity
            val h = height.toFloat(); val w = width.toFloat()
            val r = h / 2
            paint.color = if (on) a.getColor(R.color.green) else 0xFFD8DAD8.toInt()
            cv.drawRoundRect(0f, 0f, w, h, r, r, paint)
            paint.color = Color.WHITE
            val cx = if (on) w - r else r
            cv.drawCircle(cx, r, r - Ui.px(a, 3).toFloat(), paint)
        }
    }
}
