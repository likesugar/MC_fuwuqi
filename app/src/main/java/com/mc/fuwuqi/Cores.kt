package com.mc.fuwuqi

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 服务器核心下载：Paper / Purpur / Vanilla（多源兜底，国内可达优先） */
object Cores {

    const val PAPER = "Paper"; const val PURPUR = "Purpur"; const val VANILLA = "Vanilla"; const val CROSSPLAY = "互通服"

    const val MANIFEST_V2 = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"
    const val MANIFEST_BMCL = "https://bmclapi2.bangbang93.com/version_manifest_v2.json"

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
                    val dl = o.optJSONArray("downloads")
                    if (dl != null) {
                        val u = dl.optJSONObject(0)?.optString("url") ?: ""
                        if (u.isNotEmpty()) l.add(u)
                    }
                } catch (_: Exception) {}
                if (l.isEmpty() && core == CROSSPLAY) {
                    cb("Paper 查询失败，改用原版核心 …")
                    val mf = JSONObject(tryFetch(listOf(MANIFEST_V2, MANIFEST_BMCL)))
                    var vurl = ""
                    val arr = mf.getJSONArray("versions")
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        if (o.optString("id") == version) { vurl = o.optString("url"); break }
                    }
                    require(vurl.isNotEmpty()) { "未找到版本 $version" }
                    val vj = JSONObject(tryFetch(listOf(vurl,
                        vurl.replace("launchermeta.mojang.com", "bmclapi2.bangbang93.com")
                            .replace("piston-meta.mojang.com", "bmclapi2.bangbang93.com"))))
                    val du = vj.getJSONObject("downloads").getJSONObject("server").getString("url")
                    listOf(du, du.replace("piston-data.mojang.com", "bmclapi2.bangbang93.com"))
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

    /** 互通服：下载 Geyser + Floodgate 插件并写配置（onlineMode=true 走 Xbox 验证可公开） */
    fun installCrossplayPlugins(dir: File, onlineMode: Boolean, cb: (String) -> Unit) {
        val plugins = File(dir, "plugins").apply { mkdirs() }
        val gdir = File(plugins, "Geyser-Spigot").apply { mkdirs() }
        cb("下载 Geyser 互通插件 …")
        Dl.download("https://download.geysermc.org/v2/projects/geyser/versions/latest/builds/latest/downloads/spigot", File(plugins, "Geyser-Spigot.jar")) { _, _ -> }
        cb("下载 Floodgate 插件 …")
        Dl.download("https://download.geysermc.org/v2/projects/floodgate/versions/latest/builds/latest/downloads/spigot", File(plugins, "Floodgate-Spigot.jar")) { _, _ -> }
        val auth = if (onlineMode) "floodgate" else "offline"
        val online = if (onlineMode) "true" else "false"
        val props = File(dir, "server.properties")
        val lines = if (props.exists()) props.readLines().map { if (it.startsWith("online-mode=")) "online-mode=$online" else it }.toMutableList()
        else mutableListOf("online-mode=$online")
        if (lines.none { it.startsWith("online-mode=") }) lines.add(0, "online-mode=$online")
        props.writeText(lines.joinToString("\n") + "\n")
        File(gdir, "config.yml").writeText(
            "floodgate-key-file: \"./floodgate/key.pem\"\n" +
            "remote:\n  address: 127.0.0.1\n  port: 25565\n" +
            "bedrock:\n  port: 19132\n" +
            "auth-type: $auth\n")
        cb(if (onlineMode)
            "互通插件安装完成：基岩玩家需 Xbox 验证（名字带 . 前缀），可公开 IP"
        else
            "互通插件安装完成：离线模式，任何人可直接进，严禁公开 IP")
    }

}