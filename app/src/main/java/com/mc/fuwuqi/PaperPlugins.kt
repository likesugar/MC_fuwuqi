package com.mc.fuwuqi

import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Paper 插件统一仓库：从 GitHub raw 直链下载到本地仓库，再按服务器开关 */
object PaperPlugins {
    data class P(val slug: String, val name: String, val desc: String)

    val LIST = listOf(
        P("vault", "Vault", "权限/经济基础，多数插件的前置"),
        P("essentialsx", "EssentialsX", "基础命令全家桶：家/传送/背包"),
        P("luckperms", "LuckPerms", "权限管理"),
        P("worldedit", "WorldEdit", "建筑编辑神器"),
        P("worldguard", "WorldGuard", "领地/区域保护（需 WorldEdit）"),
        P("coreprotect", "CoreProtect", "查熊孩子/回滚破坏"),
        P("multiverse-core", "Multiverse-Core", "多世界管理（创建/传送世界）"),
        P("squaremap", "Squaremap", "网页地图（浏览器查看 2D 地图）"))

    private const val REPO = "https://raw.githubusercontent.com/likesugar/MC_fuwuqi/main/plugins"

    fun storeDir(): File = File(Store.base, "paper-plugins").apply { mkdirs() }
    fun globalFile(slug: String): File = File(storeDir(), "$slug.jar")
    fun isDownloaded(slug: String): Boolean = globalFile(slug).isFile && globalFile(slug).length() > 0

    /** 下载到全局仓库（断点续传）；cb(当前, 总)；成功返回 true */
    fun downloadGlobal(slug: String, cb: (Long, Long) -> Unit): Boolean {
        val dest = globalFile(slug)
        if (dest.isFile && dest.length() > 0) return true
        return rawDownload("$REPO/$slug.jar", dest, cb)
    }

    /**
     * 多线程并行下载所有未下载的插件（固定线程池，互不阻塞），
     * cb(已完成数, 总数) 在主线程外回调；全部成功返回 true。
     * 各插件写入独立文件，无共享写冲突。
     */
    fun downloadAll(poolSize: Int = 3, cb: (Int, Int) -> Unit): Boolean {
        val pending = LIST.filter { !isDownloaded(it.slug) }
        if (pending.isEmpty()) { cb(0, 0); return true }
        val total = pending.size
        val done = AtomicInteger(0)
        val failed = AtomicBoolean(false)
        val latch = CountDownLatch(total)
        val pool = Executors.newFixedThreadPool(minOf(poolSize, total))
        for (p in pending) {
            pool.submit {
                try {
                    if (!downloadGlobal(p.slug) { _, _ -> }) failed.set(true)
                } finally {
                    cb(done.incrementAndGet(), total)
                    latch.countDown()
                }
            }
        }
        latch.await()
        pool.shutdown()
        return !failed.get()
    }

    /** 对某服务器启用插件：确保全局已下载，再拷到该服 plugins/；cb 仅用于下载进度 */
    fun enableFor(slug: String, serverDir: File, cb: (Long, Long) -> Unit): Boolean {
        if (!isDownloaded(slug) && !downloadGlobal(slug, cb)) return false
        val plugins = File(serverDir, "plugins").apply { mkdirs() }
        val target = File(plugins, "$slug.jar")
        if (target.isFile && target.length() == globalFile(slug).length()) return true
        globalFile(slug).copyTo(target, overwrite = true)
        // Squaremap 启用后写入中文配置（汉化包），让网页地图界面显示中文
        if (slug == "squaremap") applySquaremapZh(serverDir)
        return true
    }

    /** 对某服务器禁用插件：从服务器 plugins/ 删除（全局仓库保留） */
    fun disableFor(slug: String, serverDir: File) {
        val plugins = File(serverDir, "plugins")
        plugins.listFiles()?.forEach { f ->
            if (f.nameWithoutExtension.equals(slug, true)) f.delete()
        }
    }

    fun isEnabled(slug: String, serverDir: File): Boolean =
        File(serverDir, "plugins").listFiles()?.any { it.nameWithoutExtension.equals(slug, true) } ?: false

    /**
     * Squaremap 网页 UI 中文汉化配置。
     * 网页前端的可见文字（标题/世界列表标签/玩家列表标签/坐标格式/玩家追踪标签）均来自 config.yml，
     * 并非写死在前端 JS 里，因此汉化只需提供一份中文配置即可。这里只设置 ui / player-tracker / commands
     * 文案，其余（web 地址、端口、渲染参数等）由插件自身默认值补齐，避免覆盖用户自定义项。
     */
    private const val SQUAREMAP_ZH = """ui:
  title: '服务器地图'
  coordinates:
    enabled: true
    html: '<span class="coords">X: {x}　Z: {z}</span>'
  link:
    enabled: true
  sidebar:
    pinned: hide
    world-list-label: '世界'
    player-list-label: '玩家: {cur}/{max}'
player-tracker:
  enabled: true
  label: '玩家'
  show-controls: true
  default-hidden: false
  update-interval: 1
commands:
  main-command-label: 'map'
"""

    /**
     * 启用 Squaremap 时写入中文 config.yml（即「汉化包」）。
     * - 首次：直接写整份中文配置；
     * - 已存在：仅把英文默认标签替换为中文，保留用户其它配置（web 地址/端口/渲染等）。
     */
    fun applySquaremapZh(serverDir: File) {
        val dir = File(serverDir, "plugins/squaremap").apply { mkdirs() }
        val cfg = File(dir, "config.yml")
        if (!cfg.exists()) { cfg.writeText(SQUAREMAP_ZH); return }
        val lines = cfg.readLines().toMutableList()
        for (i in lines.indices) {
            val raw = lines[i]
            val ind = raw.takeWhile { it == ' ' }
            val t = raw.trim()
            lines[i] = when {
                t.startsWith("world-list-label:") -> ind + "world-list-label: '世界'"
                t.startsWith("player-list-label:") -> ind + "player-list-label: '玩家: {cur}/{max}'"
                t.startsWith("html:") -> ind + "html: '<span class=\"coords\">X: {x}　Z: {z}</span>'"
                t == "title: 'squaremap'" -> ind + "title: '服务器地图'"
                t == "label: 'Players'" -> ind + "label: '玩家'"
                else -> raw
            }
        }
        cfg.writeText(lines.joinToString("\n"))
    }

    /** 单一 raw 直链下载（断点续传 + 进度），成功返回 true */
    fun rawDownload(urlStr: String, destFile: File, cb: (Long, Long) -> Unit): Boolean {
        destFile.parentFile?.mkdirs()
        val tmp = File(destFile.absolutePath + ".part")
        var start = if (tmp.isFile) tmp.length() else 0L
        try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "MCFuwuqi/1.0")
            if (start > 0) conn.setRequestProperty("Range", "bytes=$start-")
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) { conn.disconnect(); return false }
            var total = conn.contentLengthLong
            if (code == 200 && start > 0) start = 0L else if (code == 206) total += start
            conn.inputStream.use { ins ->
                val raf = RandomAccessFile(tmp, "rw"); raf.seek(start)
                try {
                    val buf = ByteArray(64 * 1024); var got = start; var since = 0L
                    while (true) {
                        val n = ins.read(buf); if (n < 0) break
                        raf.write(buf, 0, n); got += n; since += n
                        if (since > 200_000) { since = 0; cb(got, total) }
                    }
                } finally { raf.close() }
            }
            conn.disconnect()
            if (tmp.length() == 0L) return false
            tmp.renameTo(destFile)
            return true
        } catch (e: Exception) { return false }
    }
}
