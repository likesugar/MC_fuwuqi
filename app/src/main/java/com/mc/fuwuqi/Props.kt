package com.mc.fuwuqi

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

// 字段规格：key, 标题, 说明, 类型, 默认, 选项
data class F(val k: String, val t: String, val d: String, val type: String,
             val def: String = "", val opts: List<String> = emptyList())

val SPEC_BASIC = listOf(
    F("motd", "服务器标语", "玩家在多人游戏列表里看到的那行字，支持 § 颜色代码", "text"),
    F("server-port", "服务器端口", "玩家连接用的端口，默认 25565。同一台手机开多个服时不能重复", "text"),
    F("max-players", "最大玩家数", "同时在线的上限。手机建议 5~10，开太多会卡", "text"),
    F("online-mode", "正版验证", "开启后只有正版账号能进。关掉才能让离线（盗版）客户端连接", "bool"),
    F("enforce-secure-profile", "强制安全签名", "1.19+ 新增。开启后离线客户端会被直接拒绝，即使是正版启动器也可能报错。离线服必须关掉", "bool"),
    F("white-list", "启用白名单", "只允许白名单里的玩家进入，用 whitelist add 命令添加", "bool"),
)
val SPEC_PLAY = listOf(
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
val SPEC_WORLD = listOf(
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
val SPEC_NET = listOf(
    F("enable-status", "允许查询状态", "关闭后服务器列表里看不到在线人数和 MOTD", "bool", "true"),
    F("network-compression-threshold", "网络压缩阈值", "超过多少字节的数据包才压缩，-1 关闭。手机建议 256", "text", "256"),
)
val SPEC_PERF = listOf(
    F("view-distance", "视距（区块）", "玩家能看到多远。手机建议 4~6，调到 10 以上会明显卡顿", "text", "6"),
    F("simulation-distance", "模拟距离（区块）", "怪物、作物、红石的活动范围。手机建议 4，最低 3", "text", "4"),
    F("sync-chunk-writes", "同步区块写入", "关闭可以提升性能，但突然断电可能损坏存档", "bool"),
    F("use-native-transport", "使用原生传输", "Linux 下的网络优化，建议开启", "bool", "true"),
)
val SPEC_ADV = listOf(
    F("enforce-whitelist", "强制白名单", "白名单外的在线玩家会被立刻踢出", "bool"),
    F("max-world-size", "世界边界半径", "单位方块，默认 29999984。改小可以限制地图大小", "text"),
    F("server-ip", "绑定 IP", "一般留空（监听所有网卡）。只有在多网卡服务器上才需要填", "text"),
    F("prevent-proxy-connections", "阻止代理连接", "开启后通过 VPN/代理连接的玩家会被拒绝", "bool"),
    F("enable-rcon", "启用 RCON", "远程控制台，一般用不到", "bool"),
    F("rcon.port", "RCON 端口", "启用 RCON 后的监听端口", "text"),
    F("rcon.password", "RCON 密码", "启用 RCON 必填", "text"),
    F("entity-broadcast-range-percentage", "实体广播范围", "降低这个值能减少带宽占用（默认 100）", "text"),
)
val SPEC_ALL get() = SPEC_BASIC + SPEC_PLAY + SPEC_WORLD + SPEC_NET + SPEC_PERF + SPEC_ADV

/** 共享配置编辑器：把 SPEC 渲染到容器，值写进 values（key=properties键） */
class PropsEditor(private val a: Activity, private val values: LinkedHashMap<String, String>) {
    private val edits = HashMap<String, EditText>()
    private val toggles = HashMap<String, Boolean>()
    private val chipsSel = HashMap<String, String>()

    fun section(title: String): TextView {
        val t = Ui.text(a, title, 16f, a.getColor(R.color.text_sub), true)
        t.setPadding(Ui.px(a, 6), Ui.px(a, 20), 0, Ui.px(a, 4))
        return t
    }

    fun addSections(parent: LinearLayout, showAdvanced: Boolean) {
        parent.addView(section("基础")); for (f in SPEC_BASIC) addField(parent, f)
        parent.addView(section("玩法")); for (f in SPEC_PLAY) addField(parent, f)
        parent.addView(section("世界")); for (f in SPEC_WORLD) addField(parent, f)
        parent.addView(section("网络")); for (f in SPEC_NET) addField(parent, f)
        parent.addView(section("性能")); for (f in SPEC_PERF) addField(parent, f)
        if (showAdvanced) {
            val advHead = LinearLayout(a)
            advHead.orientation = LinearLayout.HORIZONTAL
            advHead.gravity = Gravity.CENTER_VERTICAL
            advHead.setPadding(Ui.px(a, 6), Ui.px(a, 22), Ui.px(a, 6), Ui.px(a, 6))
            advHead.addView(Ui.text(a, "展开高级设置", 15f, a.getColor(R.color.green), true), Ui.lp(0, -2, 1f))
            advHead.addView(Ui.text(a, "${SPEC_ADV.size} 项", 13f, a.getColor(R.color.text_sub)))
            parent.addView(advHead)
            val advWrap = LinearLayout(a)
            advWrap.orientation = LinearLayout.VERTICAL
            advWrap.visibility = if (showAdvanced) android.view.View.VISIBLE else android.view.View.GONE
            for (f in SPEC_ADV) addField(advWrap, f)
            parent.addView(advWrap)
            advHead.setOnClickListener { advWrap.visibility = if (advWrap.visibility == android.view.View.GONE) android.view.View.VISIBLE else android.view.View.GONE }
        }
    }

    fun collect() {
        for ((k, e) in edits) values[k] = e.text?.toString() ?: ""
        for ((k, v) in toggles) values[k] = if (v) "true" else "false"
        for ((k, v) in chipsSel) values[k] = v
    }

    private fun addField(parent: LinearLayout, f: F) {
        val card = Ui.card(a)
        card.setPadding(Ui.px(a, 18), Ui.px(a, 14), Ui.px(a, 18), Ui.px(a, 16))
        val head = Ui.linear(a, false)
        head.addView(Ui.text(a, f.t, 16f, a.getColor(R.color.text), true), Ui.lp(-2, -2))
        head.addView(Ui.text(a, "  ${f.k}", 12f, Color.parseColor("#B9BDBB")))
        card.addView(head)
        val desc = Ui.text(a, f.d, 13f, a.getColor(R.color.text_sub))
        desc.setPadding(0, Ui.px(a, 4), 0, 0)
        card.addView(desc)

        val cur = values[f.k] ?: f.def
        when (f.type) {
            "text" -> {
                val e = EditText(a)
                e.setText(cur)
                e.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15f)
                e.setBackgroundResource(R.drawable.bg_console)
                e.setPadding(Ui.px(a, 14), Ui.px(a, 10), Ui.px(a, 14), Ui.px(a, 10))
                e.setSingleLine(f.k != "motd")
                val ep = Ui.lp(-1, -2); ep.topMargin = Ui.px(a, 10)
                card.addView(e, ep)
                edits[f.k] = e
            }
            "bool" -> {
                val on = cur == "true"
                toggles[f.k] = on
                val row = Ui.linear(a, false)
                row.gravity = Gravity.CENTER_VERTICAL
                val st = Ui.text(a, if (on) "已开启" else "已关闭", 15f,
                    if (on) a.getColor(R.color.green) else a.getColor(R.color.text_sub), true)
                row.addView(st, Ui.lp(0, -2, 1f))
                val sw = ConfigActivity.SwitchView(a, on) {
                    toggles[f.k] = it
                    st.text = if (it) "已开启" else "已关闭"
                    st.setTextColor(a.getColor(if (it) R.color.green else R.color.text_sub))
                }
                row.addView(sw, Ui.lp(Ui.px(a, 52), Ui.px(a, 30)))
                val rp = Ui.lp(-1, -2); rp.topMargin = Ui.px(a, 12)
                card.addView(row, rp)
            }
            "chip" -> {
                val selected = values[f.k] ?: f.def
                chipsSel[f.k] = selected
                val row = Ui.linear(a, false)
                for (o in f.opts) {
                    val label = o.substringBefore(':')
                    val value = o.substringAfter(':')
                    val chip = Ui.text(a, label, 14f,
                        if (selected == value || normalizeChip(f.k, selected) == value) a.getColor(R.color.green) else a.getColor(R.color.text_sub), true)
                    chip.setBackgroundResource(if (normalizeChip(f.k, selected) == value) R.drawable.bg_tab_active else R.drawable.bg_console)
                    chip.setPadding(Ui.px(a, 14), Ui.px(a, 8), Ui.px(a, 14), Ui.px(a, 8))
                    chip.setOnClickListener {
                        chipsSel[f.k] = value
                        for (i in 0 until row.childCount) {
                            val ch = row.getChildAt(i) as TextView
                            val v2 = f.opts[i].substringAfter(':')
                            val sel2 = v2 == value
                            ch.setTextColor(a.getColor(if (sel2) R.color.green else R.color.text_sub))
                            ch.setBackgroundResource(if (sel2) R.drawable.bg_tab_active else R.drawable.bg_console)
                        }
                    }
                    val cp = Ui.lp(-2, -2); cp.rightMargin = Ui.px(a, 8); cp.topMargin = Ui.px(a, 10)
                    row.addView(chip, cp)
                }
                card.addView(row)
            }
        }
        val p = Ui.lp(-1, -2); p.topMargin = Ui.px(a, 12)
        parent.addView(card, p)
    }

    private fun normalizeChip(k: String, v: String): String = v.substringAfterLast(':')
}
