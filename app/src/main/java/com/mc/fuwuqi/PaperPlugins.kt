package com.mc.fuwuqi

import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Paper 插件统一仓库：从 GitHub raw 直链下载到本地仓库，再按服务器开关 */
object PaperPlugins {
    data class P(val slug: String, val name: String, val desc: String, val deps: List<String> = emptyList())

    val LIST = listOf(
        P("vault", "Vault", "权限/经济基础，多数插件的前置"),
        P("essentialsx", "EssentialsX", "基础命令全家桶：家/传送/背包"),
        P("luckperms", "LuckPerms", "权限管理"),
        P("worldedit", "WorldEdit", "建筑编辑神器"),
        P("worldguard", "WorldGuard", "领地/区域保护（需 WorldEdit）"),
        P("coreprotect", "CoreProtect", "查熊孩子/回滚破坏"),
        P("multiverse-core", "Multiverse-Core", "多世界管理（创建/传送世界）"),
        P("squaremap", "Squaremap", "网页地图（浏览器查看 2D 地图）"),
        P("uskyblock", "uSkyBlock", "空岛生存（建岛/挑战/团队）", listOf("worldedit", "worldguard")))

    private const val REPO = "https://raw.githubusercontent.com/likesugar/MC_fuwuqi/main/plugins"
    // 多源镜像：直链优先，失败/超时可并行切到 gh-proxy / ghproxy 代理，用于分块并行加速
    private val MIRRORS = listOf("", "https://gh-proxy.com/", "https://ghproxy.net/")
    private fun candidateUrls(slug: String): List<String> = MIRRORS.map { it + "$REPO/$slug.jar" }
    private const val CONNECT_TIMEOUT = 15000
    private const val READ_TIMEOUT = 20000        // 单块读取停顿超时，超时切下一源
    private const val MIN_CHUNK = 512 * 1024L     // 小于此不拆块，直接整文件下载
    private const val PROGRESS_STEP = 200_000L    // 进度回调节流字节数

    fun storeDir(): File = File(Store.base, "paper-plugins").apply { mkdirs() }
    fun globalFile(slug: String): File = File(storeDir(), "$slug.jar")
    fun isDownloaded(slug: String): Boolean = globalFile(slug).isFile && globalFile(slug).length() > 0

    /** 下载到全局仓库：多源分块并行加速，失败回退多源顺序直链；cb(当前, 总)；成功返回 true */
    fun downloadGlobal(slug: String, cb: (Long, Long) -> Unit): Boolean {
        val dest = globalFile(slug)
        if (dest.isFile && dest.length() > 0) return true
        return multiDownload(candidateUrls(slug), dest, cb)
    }

    /**
     * 多线程并行下载所有未下载的插件（固定线程池，互不阻塞），
     * cb(已完成数, 总数) 在主线程外回调；全部成功返回 true。
     * 各插件写入独立文件，无共享写冲突。
     */
    fun downloadAll(poolSize: Int = 3, cb: (Int, Int) -> Unit): Boolean {
        val need = LIST.flatMap { listOf(it.slug) + it.deps }.distinct()
        val pending = need.filter { !isDownloaded(it) }
        if (pending.isEmpty()) { cb(0, 0); return true }
        val total = pending.size
        val done = AtomicInteger(0)
        val failed = AtomicBoolean(false)
        val latch = CountDownLatch(total)
        val pool = Executors.newFixedThreadPool(minOf(poolSize, total))
        for (p in pending) {
            pool.submit {
                try {
                    if (!downloadGlobal(p) { _, _ -> }) failed.set(true)
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

    /** 对某服务器启用插件：先确保依赖已启用，再确保自身已下载并拷到该服 plugins/；cb 仅用于下载进度 */
    fun enableFor(slug: String, serverDir: File, cb: (Long, Long) -> Unit): Boolean {
        val p = LIST.find { it.slug == slug } ?: return false
        // 先递归启用依赖（如 uSkyBlock 依赖 WorldEdit + WorldGuard），保证插件能正常加载
        for (dep in p.deps) if (!enableFor(dep, serverDir, cb)) return false
        if (!isDownloaded(slug) && !downloadGlobal(slug, cb)) return false
        val plugins = File(serverDir, "plugins").apply { mkdirs() }
        val target = File(plugins, "$slug.jar")
        if (target.isFile && target.length() == globalFile(slug).length()) return true
        globalFile(slug).copyTo(target, overwrite = true)
        // Squaremap 启用后写入中文配置（汉化包），让网页地图界面显示中文
        if (slug == "squaremap") applySquaremapZh(serverDir)
        // uSkyBlock 启用后写入中文汉化包（config 设 zh_CN + 中文挑战）
        if (slug == "uskyblock") applyUSkyZh(serverDir)
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

    /** 下载非 jar 资源（如汉化包 yml）到目标文件；已存在则跳过；多源回退；成功返回 true */
    private fun downloadFile(relPath: String, dest: File, cb: (Long, Long) -> Unit): Boolean {
        if (dest.isFile && dest.length() > 0) return true
        for (m in MIRRORS) {
            if (rawDownload(m + "$REPO/$relPath", dest, cb)) return true
        }
        return false
    }

    /**
     * uSkyBlock 中文汉化包。
     * - config.yml：用官方默认配置（仅 language 改为 zh_CN），插件原生 UI/命令/提示即全中文（zh_CN.po 已内置）；
     * - challenges.yml：用预翻译的中文挑战（挑战名 100% 中文，描述大部分中文，各国料理名保留原名）。
     * 仅在插件配置目录尚无该文件时写入，避免覆盖用户后续自定义；插件启动读取即生效，首启即中文。
     */
    private fun applyUSkyZh(serverDir: File) {
        val dir = File(serverDir, "plugins/uSkyBlock").apply { mkdirs() }
        val cfg = File(dir, "config.yml")
        if (!cfg.exists()) downloadFile("uskyblock-config-zh.yml", cfg) { _, _ -> }
        val ch = File(dir, "challenges.yml")
        if (!ch.exists()) downloadFile("uskyblock-challenges-zh.yml", ch) { _, _ -> }
    }

    /** 单一源下载（断点续传 + 进度），用作多源回退兜底；成功返回 true */
    fun rawDownload(urlStr: String, destFile: File, cb: (Long, Long) -> Unit): Boolean {
        destFile.parentFile?.mkdirs()
        val tmp = File(destFile.absolutePath + ".part")
        var start = if (tmp.isFile) tmp.length() else 0L
        try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
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
                        if (since > PROGRESS_STEP) { since = 0; cb(got, total) }
                    }
                } finally { raf.close() }
            }
            conn.disconnect()
            if (tmp.length() == 0L) return false
            tmp.renameTo(destFile)
            return true
        } catch (e: Exception) { return false }
    }

    /**
     * 多源分块并行下载：先探测文件大小，将文件切成多块，各块从不同镜像并行拉取（HTTP Range），
     * 写入同一文件对应偏移后合并。任一镜像失败自动换源；全部源均不支持分块或无大小则回退整文件顺序直链。
     */
    private fun multiDownload(urls: List<String>, dest: File, cb: (Long, Long) -> Unit): Boolean {
        val tmp = File(dest.absolutePath + ".part")
        tmp.delete()
        val total = probeSize(urls)
        if (total <= 0) return sequentialFallback(urls, dest, cb)
        val chunkN = if (total < MIN_CHUNK * 4) 1 else minOf(12, maxOf(4, (total / (1024 * 1024)).toInt()))
        val ranges = buildRanges(total, chunkN)
        val ch = FileChannel.open(tmp.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.READ)
        return try {
            val pool = Executors.newFixedThreadPool(minOf(ranges.size, 6))
            val ok = AtomicBoolean(true)
            val latch = CountDownLatch(ranges.size)
            ranges.forEachIndexed { idx, (s, e) ->
                pool.submit {
                    try { if (!fetchChunkRoundRobin(urls, ch, s, e, idx, cb, total)) ok.set(false) }
                    catch (_: Exception) { ok.set(false) }
                    finally { latch.countDown() }
                }
            }
            latch.await(); pool.shutdown()
            val success = ok.get() && tmp.length() == total
            ch.close()
            if (success) { tmp.renameTo(dest); true }
            else { tmp.delete(); sequentialFallback(urls, dest, cb) }
        } catch (_: Exception) {
            try { ch.close() } catch (_: Exception) {}
            tmp.delete(); sequentialFallback(urls, dest, cb)
        }
    }

    /** 顺序尝试各源整文件下载（多源回退兜底） */
    private fun sequentialFallback(urls: List<String>, dest: File, cb: (Long, Long) -> Unit): Boolean {
        for (u in urls) if (rawDownload(u, dest, cb)) return true
        return false
    }

    /** 探测文件总大小（Range bytes=0-0 → Content-Range），拿不到返回 -1 */
    private fun probeSize(urls: List<String>): Long {
        for (u in urls) {
            try {
                val conn = URL(u).openConnection() as HttpURLConnection
                conn.connectTimeout = CONNECT_TIMEOUT
                conn.readTimeout = READ_TIMEOUT
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "MCFuwuqi/1.0")
                conn.setRequestProperty("Range", "bytes=0-0")
                conn.connect()
                val cr = conn.getHeaderField("Content-Range")
                conn.disconnect()
                if (!cr.isNullOrEmpty() && cr.contains("/")) {
                    cr.substringAfterLast("/").trim().toLongOrNull()?.let { if (it > 0) return it }
                }
            } catch (_: Exception) {}
        }
        return -1
    }

    /** 将 [0,total) 均分为 n 段（闭区间），返回每段 start/end */
    private fun buildRanges(total: Long, n: Int): List<Pair<Long, Long>> {
        val ranges = mutableListOf<Pair<Long, Long>>()
        val step = total / n
        var cur = 0L
        for (i in 0 until n) {
            val end = if (i == n - 1) total - 1 else cur + step - 1
            if (end >= cur) ranges.add(cur to end)
            cur = end + 1
        }
        if (ranges.isEmpty()) ranges.add(0L to total - 1)
        return ranges
    }

    /** 某块依次尝试各镜像，任一成功即返回 true（idx 错开起点以分散到不同源） */
    private fun fetchChunkRoundRobin(urls: List<String>, ch: FileChannel, s: Long, e: Long, idx: Int, cb: (Long, Long) -> Unit, total: Long): Boolean {
        for (attempt in urls.indices) {
            if (fetchToChannel(urls[(idx + attempt) % urls.size], ch, s, e, cb, total)) return true
        }
        return false
    }

    /** 单块从单源 Range 下载并写入文件对应偏移；不支持分块(非206)或异常返回 false */
    private fun fetchToChannel(url: String, ch: FileChannel, start: Long, end: Long, cb: (Long, Long) -> Unit, total: Long): Boolean {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "MCFuwuqi/1.0")
            conn.setRequestProperty("Range", "bytes=$start-$end")
            conn.connect()
            if (conn.responseCode != 206) { conn.disconnect(); return false }
            val buf = ByteArray(64 * 1024)
            var pos = start
            var since = 0L
            conn.inputStream.use { ins ->
                while (pos <= end) {
                    val n = ins.read(buf); if (n < 0) break
                    ch.write(ByteBuffer.wrap(buf, 0, n), pos)
                    pos += n; since += n
                    if (since > PROGRESS_STEP) { since = 0; cb(ch.size(), total) }
                }
                cb(ch.size(), total)
            }
            conn.disconnect()
            return pos - 1 == end
        } catch (_: Exception) { return false }
    }
}
