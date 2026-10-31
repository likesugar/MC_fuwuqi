package com.mc.fuwuqi

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 服务器核心下载：Paper / Purpur / Vanilla（多源兜底，国内可达优先） */
object Cores {

    const val PAPER = "Paper"; const val PURPUR = "Purpur"; const val VANILLA = "Vanilla"; const val CROSSPLAY = "互通服"

    const val MANIFEST_V2 = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"
    const val MANIFEST_BMCL = "https://bmclapi2.bangbang93.com/version_manifest_v2.json"

    /** 互通服用：Geyser 要求的 Java 协议比核心新，靠 ViaVersion 桥接 */
    const val VIA_VER = "5.12.2"

    private fun arr2list(a: JSONArray): List<String> =
        (0 until a.length()).map { a.getString(it) }

    private fun tryFetch(urls: List<String>): String {
        var last: Exception? = null
        for (u in urls) {
            try { return Dl.fetchText(u) } catch (e: Exception) { last = e }
        }
        throw last ?: RuntimeException("所有源均不可达")
    }

    private fun paperVersions(): List<String> {
        // 参照物同款：fill.papermc.io v3 为主，v2 兜底；两种返回结构都兼容
        try {
            val v3 = tryFetch(listOf("https://fill.papermc.io/v3/projects/paper"))
            val o = JSONObject(v3)
            if (o.has("versions")) {
                val a = o.optJSONArray("versions")
                if (a != null) {
                    if (a.length() > 0 && a.optJSONObject(0) != null)
                        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("version") }
                    return arr2list(a)
                }
                val m = o.getJSONObject("versions")
                val keys = m.keys().asSequence().toList()
                if (keys.isNotEmpty()) return keys.sortedWith { a, b ->
                    val va = parseVer(a); val vb = parseVer(b)
                    for (i in 0 until maxOf(va.size, vb.size)) {
                        val c = (va.getOrNull(i) ?: 0).compareTo(vb.getOrNull(i) ?: 0)
                        if (c != 0) return@sortedWith c
                    }
                    b.compareTo(a)
                }
            }
        } catch (_: Exception) {}
        return try {
            val v2 = tryFetch(listOf(
                "https://api.papermc.io/v2/projects/paper",
                "https://fill.papermc.io/v2/projects/paper"))
            arr2list(JSONObject(v2).getJSONArray("versions")).reversed()
        } catch (e: Exception) { emptyList() }
    }

    private fun parseVer(v: String): List<Int> =
        v.split('.').map { it.replace(Regex("[^0-9].*"), "").toIntOrNull() ?: 0 }

    private fun purpurVersions(): List<String> = try {
        arr2list(JSONObject(tryFetch(listOf(
            "https://api.purpurmc.org/v2/purpur",
            "https://purpurmc.org/api/v2/purpur"))).getJSONArray("versions")).reversed()
    } catch (e: Exception) { emptyList() }

    private fun vanillaVersions(): List<String> = try {
        val mf = JSONObject(tryFetch(listOf(MANIFEST_V2, MANIFEST_BMCL)))
        val a = mf.getJSONArray("versions")
        (0 until a.length()).mapNotNull {
            val o = a.optJSONObject(it) ?: return@mapNotNull null
            if (o.optString("type") == "release") o.optString("id") else null
        }
    } catch (e: Exception) { emptyList() }

    /** Bukkit 系核心才支持 Paper 插件 */
    fun isBukkitLike(core: String): Boolean =
        core.equals(PAPER, true) || core.equals(CROSSPLAY, true) || core.equals(PURPUR, true)

    fun list(core: String): List<String> = when (core) {
        PAPER, CROSSPLAY -> paperVersions().asReversed()
        PURPUR -> purpurVersions()
        else -> vanillaVersions()
    }

    /** 下载 server.jar 到 dir；返回文件 */
    fun download(core: String, version: String, dir: File, cb: (String) -> Unit): File {
        dir.mkdirs()
        val jar = File(dir, "server.jar")
        var urls: List<String> = when (core) {
            PAPER, CROSSPLAY -> {
                cb("查询 Paper $version 构建号 …")
                val l = mutableListOf<String>()
                // 1) v2 API
                try {
                    val p2 = tryFetch(listOf(
                        "https://api.papermc.io/v2/projects/paper/versions/$version",
                        "https://fill.papermc.io/v2/projects/paper/versions/$version"))
                    val builds = JSONObject(p2).getJSONArray("builds")
                    val b = builds.getString(builds.length() - 1)
                    l.add("https://api.papermc.io/v2/projects/paper/versions/$version/builds/$b/downloads/paper-$version-$b.jar")
                } catch (_: Exception) {}
                // 2) fill v3 API（downloads 是 list，取 primary）
                try {
                    val v3 = tryFetch(listOf(
                        "https://fill.papermc.io/v3/projects/paper/versions/$version/builds/latest"))
                    val o = JSONObject(v3)
                    val dl = o.optJSONObject("downloads")
                    if (dl != null) {
                        // fill v3 结构：downloads = { "server:default": { url, sha256, ... } }
                        val u = dl.optJSONObject("server:default")?.optString("url")
                            ?: dl.optJSONObject(dl.keys().next())?.optString("url") ?: ""
                        if (u.isNotEmpty()) l.add(u)
                    }
                } catch (_: Exception) {}
                // 兜底：fill v2 API
                try {
                    if (l.isEmpty()) {
                        val b = JSONObject(tryFetch(listOf(
                            "https://fill.papermc.io/v3/projects/paper/versions/$version/builds")))
                        val arr = b.getJSONArray("builds")
                        val last = arr.getJSONObject(0)
                        val u2 = last.getJSONObject("downloads").getJSONObject("server:default").getString("url")
                        if (u2.isNotEmpty()) l.add(u2)
                    }
                } catch (_: Exception) {}
                if (l.isEmpty() && core == CROSSPLAY) {
                    throw RuntimeException("互通服需要 Paper 内核（Geyser 插件依赖它），Paper 尚未发布 $version 的构建。请改选 1.21.x 等已有构建的版本。")
                }
                require(l.isNotEmpty()) { "Paper 构建查询失败" }
                l
            }
            PURPUR -> {
                cb("查询 Purpur $version 构建号 …")
                val latest = JSONObject(tryFetch(listOf(
                    "https://api.purpurmc.org/v2/purpur/$version",
                    "https://purpurmc.org/api/v2/purpur/$version")))
                    .optJSONObject("build")?.optString("latest") ?: "latest"
                listOf(
                    "https://api.purpurmc.org/v2/purpur/$version/$latest/download",
                    "https://purpurmc.org/api/v2/purpur/$version/$latest/download")
            }
            else -> {
                cb("查询 Mojang $version …")
                val mf = JSONObject(tryFetch(listOf(MANIFEST_V2, MANIFEST_BMCL)))
                var vurl = ""
                val arr = mf.getJSONArray("versions")
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    if (o.optString("id") == version) { vurl = o.optString("url"); break }
                }
                require(vurl.isNotEmpty()) { "未找到版本 $version" }
                // 版本 json 双路（bmclapi 域名替换）
                val vj = JSONObject(tryFetch(listOf(vurl,
                    vurl.replace("launchermeta.mojang.com", "bmclapi2.bangbang93.com")
                        .replace("piston-meta.mojang.com", "bmclapi2.bangbang93.com"))))
                val du = vj.getJSONObject("downloads").getJSONObject("server").getString("url")
                // 下载双路：官方 piston-data + bmclapi
                listOf(du,
                    du.replace("piston-data.mojang.com", "bmclapi2.bangbang93.com"),
                    du.replace("launchermeta.mojang.com", "bmclapi2.bangbang93.com"))
            }
        }
        var last: Exception? = null
        for (u in urls) {
            try {
                Dl.download(u, jar) { cur, tot ->
                    cb("下载核心 ${Container.fmt(cur)}" + (if (tot > 0) " / ${Container.fmt(tot)}" else ""))
                }
                return jar
            } catch (e: Exception) { last = e }
        }
        throw last ?: RuntimeException("核心下载失败")
    }

    /** 动态查询 Geyser master 支持的 Java 版本（来自 MCProtocolLib 源码，发新 build 适配后自动更新） */
    fun geyserSupportedJava(): List<String> = try {
        val src = tryFetch(listOf(
            "https://gh-proxy.com/https://raw.githubusercontent.com/GeyserMC/MCProtocolLib/master/protocol/src/main/java/org/geysermc/mcprotocollib/protocol/codec/MinecraftCodec.java",
            "https://raw.githubusercontent.com/GeyserMC/MCProtocolLib/master/protocol/src/main/java/org/geysermc/mcprotocollib/protocol/codec/MinecraftCodec.java"))
        val all = Regex("minecraftVersion\\(\"([^\"]+)\"\\)").findAll(src).toList()
        listOf(all.last().groupValues[1])
    } catch (e: Exception) { listOf("26.3") }

    /** 互通插件解析结果 */
    data class Crossplay(val bedrockVersion: String?, val geyserVersion: String?)

    // 版本解析结果缓存（key = jar 路径|大小|修改时间），避免反复读几十 MB 的 jar
    private val bvCache = HashMap<String, String?>()

    /**
     * Geyser 插件对应的基岩版版本。
     *
     * Geyser 的 jar 里每个基岩协议都带一个 `Bedrock_vXXXX.class`，类里写着自己对应的
     * Minecraft 版本串（如 1.26.60）；取其中最大的一个，就是这版 Geyser 支持的基岩端版本。
     * 实测与服务器 RakNet pong 回报的版本一致（1.26.60 ↔ 协议 2225），所以不需要联网、
     * 也不需要先启动服务器。
     */
    fun bedrockVersion(jar: File): String? {
        if (!jar.isFile || jar.length() < 1024) return null
        val key = "${jar.absolutePath}|${jar.length()}|${jar.lastModified()}"
        synchronized(bvCache) { bvCache[key]?.let { return it } }
        val v = try {
            java.util.zip.ZipFile(jar).use { z ->
                var best: String? = null
                val nameRe = Regex("""^Bedrock_v\d+(_hotfix\d+)?\.class$""")
                val verRe = Regex("""1\.\d{1,2}\.\d{1,2}""")
                for (e in z.entries()) {
                    if (!nameRe.matches(e.name.substringAfterLast('/'))) continue
                    val s = String(z.getInputStream(e).readBytes(), Charsets.ISO_8859_1)
                    for (m in verRe.findAll(s)) {
                        if (best == null || verCmp(m.value, best) > 0) best = m.value
                    }
                }
                best
            }
        } catch (_: Exception) { null }
        synchronized(bvCache) { bvCache[key] = v }
        return v
    }

    /** Geyser 插件自身版本（BuildData 里的 2.12.1-b1279），解析不到就返回 null */
    fun geyserVersion(jar: File): String? = try {
        if (!jar.isFile) null else java.util.zip.ZipFile(jar).use { z ->
            val e = z.getEntry("org/geysermc/geyser/BuildData.class") ?: return null
            val s = String(z.getInputStream(e).readBytes(), Charsets.ISO_8859_1)
            Regex("""\d+\.\d+\.\d+-b\d+""").find(s)?.value
        }
    } catch (_: Exception) { null }

    /**
     * 这版 Geyser 是否带 NetherNet（基岩 26.60 起的新传输层）。
     * 按包里有没有 nethernet 实现判断，比猜版本号阈值可靠。
     */
    fun supportsNetherNet(jar: File): Boolean = try {
        jar.isFile && java.util.zip.ZipFile(jar).use { z ->
            z.getEntry("org/geysermc/geyser/network/bedrock/nethernet/NetherNetServer.class") != null
        }
    } catch (_: Exception) { false }

    /**
     * 基岩 26.60（2026-10-29 官方网络迁移）起客户端从 RakNet 改走 NetherNet，
     * 只认支持 nethernet 的 Geyser。老互通服创建时下的是旧版 Geyser（只有 RakNet），
     * 即使配置里写了 transport: nethernet 也不认，客户端直接报 Terracotta / InitialConnection-149
     * （官方称 Unsupported Transport）。这里在启动前探能力，不支持就换最新版；新服不受影响。
     */
    fun ensureModernGeyser(dir: File, cb: (String) -> Unit) {
        val plugins = File(dir, "plugins").apply { mkdirs() }
        val gjar = File(plugins, "Geyser-Spigot.jar")
        if (supportsNetherNet(gjar)) return
        val old = if (gjar.isFile) geyserVersion(gjar) ?: "未知版本" else "未安装"
        cb("Geyser $old 不支持基岩 26.60 的 NetherNet，正在更新（约 50MB，请稍候）…")
        val tmp = File(plugins, "Geyser-Spigot.new.jar")
        tmp.delete()
        val ok = try {
            Dl.download(
                "https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest/downloads/spigot",
                tmp) { _, _ -> }
        } catch (e: Exception) {
            cb("Geyser 更新失败：${e.message}")
            false
        }
        if (!ok || !supportsNetherNet(tmp)) {
            tmp.delete()
            cb("Geyser 仍是旧版：基岩 26.60+ 会报 Terracotta / 149，请联网后重启服务器再试")
            return
        }
        // 先备份再替换，替换失败可回滚
        val bak = File(plugins, "Geyser-Spigot.old.jar")
        bak.delete()
        gjar.renameTo(bak)
        if (!tmp.renameTo(gjar)) {
            bak.renameTo(gjar)
            tmp.delete()
            cb("Geyser 替换失败：基岩 26.60+ 会报 149")
            return
        }
        bak.delete()
        cb("Geyser 已更新到 ${geyserVersion(gjar) ?: "最新版"}（支持基岩 26.60 NetherNet）")
    }

    /** 版本号按数值逐段比较（1.26.60 > 1.9.0） */
    fun verCmp(a: String, b: String): Int {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = (x.getOrNull(i) ?: 0) - (y.getOrNull(i) ?: 0)
            if (d != 0) return d
        }
        return 0
    }

    /** 本机任意一个互通服里已装好的 Geyser 插件（用于创建前预显示基岩版版本） */
    fun installedGeyserJar(): File? {
        val arr = try { Store.loadServers() } catch (_: Exception) { return null }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("core") != CROSSPLAY) continue
            val f = File(Store.serverDir(o.optString("id")), "plugins/Geyser-Spigot.jar")
            if (f.isFile) return f
        }
        return null
    }

    /** 互通服：下载 Geyser + Floodgate 插件并写配置（onlineMode=true 走 Xbox 验证可公开） */
    fun installCrossplayPlugins(dir: File, onlineMode: Boolean, javaPort: Int, cb: (String) -> Unit): Crossplay {
        val plugins = File(dir, "plugins").apply { mkdirs() }
        File(plugins, "Geyser-Spigot").mkdirs()
        cb("下载 Geyser 互通插件 …")
        val gjar = File(plugins, "Geyser-Spigot.jar")
        Dl.download("https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest/downloads/spigot", gjar) { _, _ -> }
        cb("下载 Floodgate 插件 …")
        Dl.download("https://download.geysermc.org/v2/projects/floodgate/versions/latest/builds/latest/downloads/spigot", File(plugins, "Floodgate-Spigot.jar")) { _, _ -> }
        ensureViaVersion(dir, cb)
        // server.properties 由创建流程按用户配置写入（含 online-mode），这里只写 Geyser 的 config.yml
        // 信令地址必须显式给本机局域网 IP：留空时 Geyser 会通告 STUN 探到的公网地址，
        // 手机在 Wi-Fi NAT 后面没有端口转发，基岩客户端连过去就是 Terracotta / InitialConnection-149
        ensureCrossplayConfig(dir, onlineMode, javaPort, ServerManager.localIp())
        val bv = bedrockVersion(gjar)
        val gv = geyserVersion(gjar)
        val verLine = if (bv != null) "，对应基岩版 $bv" else ""
        cb(if (onlineMode)
            "互通插件安装完成$verLine：基岩玩家需 Xbox 验证（名字带 . 前缀），可公开 IP"
        else
            "互通插件安装完成$verLine：离线模式，任何人可直接进，严禁公开 IP")
        return Crossplay(bv, gv)
    }

    /**
     * ViaVersion 版本桥接（内置优先，联网兜底）。
     *
     * Geyser 2.12 是以 Java 26.3 的身份连后端服务器的，而 App 提供的核心基本是 1.21.x。
     * 不装这个，Geyser 启动时只会打一行 WARN，等基岩玩家真的连进来才因为版本不符被踢，
     * 表现就是"能连上服务器、进世界时失败"。所以互通服必须带上它。
     *
     * 自 v1.0-72 起，ViaVersion 随 APK 内置（assets/bridge/ViaVersion-<ver>.jar，约 5MB），
     * 创建/启动互通服时本地直接拷进 plugins 即可，无需联网、不受 geysermc.org/GitHub 抖动影响；
     * 仅当内置包缺失（如精简构建或资产未打包）或拷贝失败时，才回退到联网下载。
     */
    fun ensureViaVersion(dir: File, cb: (String) -> Unit): Boolean {
        val plugins = File(dir, "plugins").apply { mkdirs() }
        val exists = plugins.listFiles()?.any {
            it.isFile && it.name.startsWith("ViaVersion") && it.name.endsWith(".jar") && it.length() > 1_000_000
        } == true
        if (exists) return true
        // 1) 优先用内置版本桥接插件（离线、稳）
        val assetName = "bridge/ViaVersion-$VIA_VER.jar"
        val tmp = File(Store.runDir, "via-$VIA_VER.tmp")
        try {
            App.inst.assets.open(assetName).use { ins ->
                tmp.parentFile?.mkdirs()
                tmp.outputStream().use { ins.copyTo(it) }
            }
            val dest = File(plugins, "ViaVersion-$VIA_VER.jar")
            if (!dest.exists() || dest.length() != tmp.length()) tmp.copyTo(dest, overwrite = true)
            tmp.delete()
            cb("已写入内置版本桥接插件 ViaVersion $VIA_VER")
            return true
        } catch (_: Exception) {
            tmp.delete()
            // 2) 兜底：内置包缺失或拷贝失败，回退联网下载
            cb("内置版本桥接不可用，尝试联网下载 …")
            return try {
                Dl.download(
                    "https://github.com/ViaVersion/ViaVersion/releases/download/$VIA_VER/ViaVersion-$VIA_VER.jar",
                    File(plugins, "ViaVersion-$VIA_VER.jar")) { _, _ -> }
            } catch (e2: Exception) {
                cb("ViaVersion 下载失败：${e2.message}（基岩版可能进不去）")
                false
            }
        }
    }

    /**
     * Geyser 2.x 的配置骨架。
     *
     * 注意（2026-10 线上问题复盘）：
     * - auth-type 在 Geyser 2.x 里属 java 段，写成顶层键会被整段丢弃，Geyser 退回 online，
     *   基岩玩家于是被要求用 Java 正版账号登录，表现就是"开了互通但基岩版进不去"。
     * - 基岩 26.60（2026-10-27）起客户端不再走 RakNet，改走 NetherNet（TCP 信令 + WebRTC/UDP）。
     *   用 transport: nethernet + webrtc-port: 0，TCP/UDP 都落在 19132，玩家只填一个端口；
     *   旧的 both + 19133 要多占一个 UDP 端口，路由器少开一个、或客户端缓存了旧连接信息就连不上。
     * - java.port 必须跟随用户实际设置的 Java 端口，否则改了端口后 Geyser 连不上后端。
     */
    fun crossplayConfig(onlineMode: Boolean, javaPort: Int, advertiseIp: String = ""): String {
        // 始终通告 127.0.0.1：同一台手机开服又用基岩版连时，填 127.0.0.1 走本机回环，
        // 不会触发 Android 对"LAN IP 出 wlan0 又回 wlan0"的 hairpin 限制（UDP 握手必失败）。
        // 再补一个局域网 IP，给同一 WiFi 下别的设备用。
        val adv = if (advertiseIp.isNotBlank() && advertiseIp != "127.0.0.1")
            "[\"127.0.0.1:19132\", \"$advertiseIp:19132\"]" else "[\"127.0.0.1:19132\"]"
        return "bedrock:\n" +
        "  address: 0.0.0.0\n" +
        "  port: 19132\n" +
        "  webrtc-port: 0\n" +
        "  transport: nethernet\n" +
        "  clone-remote-port: false\n" +
        "  signaling:\n" +
        "    mode: builtin\n" +
        "    advertise-addresses: $adv\n" +
        "    stun-discovery: ${advertiseIp.isBlank()}\n" +
        "java:\n" +
        "  address: 127.0.0.1\n" +
        "  port: $javaPort\n" +
        "  auth-type: ${if (onlineMode) "floodgate" else "offline"}\n" +
        "advanced:\n" +
        "  floodgate-key-file: ../floodgate/key.pem\n"
    }

    /** 确保 Geyser 配置存在且关键项正确：新建的服写全量，老版本建过的服就地改几行，不覆盖用户自定义 */
    fun ensureCrossplayConfig(dir: File, onlineMode: Boolean, javaPort: Int, advertiseIp: String = "") {
        val gdir = File(dir, "plugins/Geyser-Spigot").apply { mkdirs() }
        val f = File(gdir, "config.yml")
        val auth = if (onlineMode) "floodgate" else "offline"
        if (!f.isFile || f.length() == 0L) {
            f.writeText(crossplayConfig(onlineMode, javaPort, advertiseIp))
            return
        }
        var t = f.readText()
        // 老版本 App 写的配置：顶层 remote/auth-type 段（Geyser 2.x 不认），整份重写
        if (!Regex("(?m)^java:\\s*$").containsMatchIn(t)) {
            f.writeText(crossplayConfig(onlineMode, javaPort, advertiseIp))
            return
        }
        t = patchSection(t, "bedrock", "port", "19132")
        // 基岩 26.60（2026-10-27）起客户端不再走 RakNet，改走 NetherNet（TCP 信令 + WebRTC/UDP）。
        // 用 nethernet 单端口：TCP+UDP 都走 19132，玩家只填一个端口，少一层出错机会。
        t = patchSection(t, "bedrock", "webrtc-port", "0")
        t = patchSection(t, "bedrock", "transport", "nethernet")
        t = patchSection(t, "bedrock", "clone-remote-port", "false")
        // 信令阶段告诉客户端"来这个地址连我"。留空时 Geyser 会通告本机所有地址 + STUN 猜的公网地址，
        // 手机在 Wi-Fi 的 NAT 后面时，客户端拿到公网地址却连不上，正是 Terracotta / InitialConnection-149 的成因。
        t = ensureSignalingBlock(t)
        t = patchSignalingAddress(t, advertiseIp)
        t = patchSection(t, "java", "address", "127.0.0.1")
        t = patchSection(t, "java", "port", javaPort.toString())
        t = patchSection(t, "java", "auth-type", auth)
        f.writeText(t)
    }

    /**
     * 把 bedrock.signaling.advertise-addresses 固定成本机局域网地址。
     *
     * 这是 NetherNet 的关键一步：客户端先走 TCP 信令问"服务器在哪"，服务端回一个地址，
     * 客户端再连那个地址的 UDP。地址留空时 Geyser 会用 STUN 探到的公网地址，
     * 家用/热点网络通常没有端口转发 → 客户端连公网地址必然失败 → 报 Terracotta / 149。
     */
    internal fun patchSignalingAddress(text: String, addr: String): String {
        if (addr.isBlank()) return text
        val lines = text.lines().toMutableList()
        val sig = lines.indexOfFirst { it.trimEnd() == "  signaling:" }
        if (sig < 0) return text
        // signaling 段范围：后续非空且不是四空格缩进的行即为段结束
        var end = lines.size
        for (i in sig + 1 until lines.size) {
            val l = lines[i]
            if (l.isNotBlank() && !l.startsWith("    ")) { end = i; break }
        }
        val at = (sig + 1 until end).firstOrNull { lines[it].trimStart().startsWith("advertise-addresses:") }
        // 同机自连必须通告 127.0.0.1，否则基岩版填 LAN IP 时会卡在 UDP 握手（hairpin 被 Android 挡）
        val adv = if (addr.isNotBlank() && addr != "127.0.0.1")
            "[\"127.0.0.1:19132\", \"$addr:19132\"]" else "[\"127.0.0.1:19132\"]"
        if (at == null) {
            lines.add(sig + 1, "    advertise-addresses: $adv")
            return lines.joinToString("\n")
        }
        // 旧值可能是 []，也可能是多行列表，一并清掉
        var stop = at + 1
        while (stop < end && lines[stop].trimStart().startsWith("-")) stop++
        lines[at] = "    advertise-addresses: $adv"
        if (stop > at + 1) lines.subList(at + 1, stop).clear()
        return lines.joinToString("\n")
    }

    /**
     * 老配置里可能没有 signaling 段（早期 Geyser 没有这个键），补一份最小可用骨架。
     * 已有该段则原样保留，交给 patchSignalingAddress 只改地址那一行。
     */
    internal fun ensureSignalingBlock(text: String): String {
        if (text.lines().any { it.trimEnd() == "  signaling:" }) return text
        val lines = text.lines().toMutableList()
        val bed = lines.indexOfFirst { it.trimEnd() == "bedrock:" }
        if (bed < 0) return text
        var end = lines.size
        for (i in bed + 1 until lines.size) {
            val l = lines[i]
            if (l.isNotBlank() && !l.startsWith(" ")) { end = i; break }
        }
        var ins = bed + 1
        for (i in bed + 1 until end) if (lines[i].startsWith(" ")) ins = i + 1
        lines.addAll(ins, listOf(
            "  signaling:",
            "    mode: builtin",
            "    advertise-addresses: []",
            "    stun-discovery: true"))
        return lines.joinToString("\n")
    }

    /** 只改 YAML 里某一段（两空格缩进）下的某个键；键不存在则插到该段最后一个缩进行之后 */
    internal fun patchSection(text: String, section: String, key: String, value: String): String {
        val lines = text.lines().toMutableList()
        val sec = lines.indexOfFirst { it.trimEnd() == "$section:" }
        if (sec < 0) return text
        var end = lines.size
        for (i in sec + 1 until lines.size) {
            val l = lines[i]
            if (l.isNotEmpty() && !l[0].isWhitespace() && !l.startsWith("#")) { end = i; break }
        }
        val idx = (sec + 1 until end).firstOrNull { lines[it].trimStart().startsWith("$key:") }
        if (idx != null) {
            lines[idx] = "  $key: $value"
        } else {
            var ins = sec + 1
            for (i in sec + 1 until end) if (lines[i].startsWith(" ")) ins = i + 1
            lines.add(ins, "  $key: $value")
        }
        return lines.joinToString("\n")
    }

}