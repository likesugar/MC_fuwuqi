package com.mc.fuwuqi

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import org.apache.commons.compress.compressors.CompressorStreamFactory

/** HTTP 下载 + tar.xz / tar.gz 解包 */
object Dl {

    /** 下载到 destFile，cb(current,total) 每 200KB 回调一次，total 可为 -1 */
    fun download(urlStr: String, destFile: File, cb: (Long, Long) -> Unit = { _, _ -> }): Boolean {
        destFile.parentFile?.mkdirs()
        DownloadService.start(App.inst, "下载 ${destFile.name}")
        DownloadService.progress(0, -1)
        try {
            val urls = listOf(urlStr, "https://gh-proxy.com/$urlStr", "https://ghproxy.net/$urlStr")
            var lastErr: Exception? = null
            for (u in urls) {
                if (destFile.exists() && destFile.length() > 0) return true
                try {
                    downloadOne(u, destFile, cb)
                    if (destFile.length() > 0) return true
                } catch (e: Exception) { lastErr = e }
            }
            destFile.delete()
            lastErr?.let { throw it }
            return false
        } finally {
            DownloadService.stop(App.inst)
        }
    }

    private fun downloadOne(urlStr: String, destFile: File, cb: (Long, Long) -> Unit) {
        // 每个源独立断点文件，切换源不清零、重进 App 可续传
        val tmp = File(destFile.absolutePath + "." + Integer.toHexString(urlStr.hashCode()) + ".part")
        var start = if (tmp.isFile) tmp.length() else 0L
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "MCFuwuqi/1.0")
        if (start > 0) conn.setRequestProperty("Range", "bytes=$start-")
        conn.connect()
        val code = conn.responseCode
        if (code !in 200..299) { conn.disconnect(); throw RuntimeException("HTTP $code $urlStr") }
        var total = conn.contentLengthLong
        if (code == 200 && start > 0) {
            // 服务器不支持断点：从头来
            start = 0L
            total = conn.contentLengthLong
        } else if (code == 206) {
            total += start
        }
        conn.inputStream.use { ins ->
            val raf = java.io.RandomAccessFile(tmp, "rw")
            raf.seek(start)
            try {
                val buf = ByteArray(64 * 1024)
                var got = start; var since = 0L
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    raf.write(buf, 0, n)
                    got += n; since += n
                    if (since > 200_000) { since = 0; cb(got, total); DownloadService.progress(got, total) }
                }
            } finally { raf.close() }
        }
        conn.disconnect()
        if (tmp.length() == 0L) throw RuntimeException("empty download")
        tmp.renameTo(destFile)
    }

    fun fetchText(urlStr: String): String {
        val urls = listOf(urlStr, "https://gh-proxy.com/$urlStr")
        var lastErr: Exception? = null
        for (u in urls) {
            try {
                val conn = URL(u).openConnection() as HttpURLConnection
                conn.connectTimeout = 15000; conn.readTimeout = 30000
                conn.setRequestProperty("User-Agent", "MCFuwuqi/1.0")
                if (conn.responseCode in 200..299) {
                    val s = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()
                    return s
                }
                conn.disconnect()
            } catch (e: Exception) { lastErr = e }
        }
        throw lastErr ?: RuntimeException("fetch failed: $urlStr")
    }

    /** 解 tar.xz / tar.gz 到 outDir（魔数嗅探压缩格式，跳过绝对路径越界；stripComponents 去掉顶层 n 级目录） */
    fun extractTar(archive: File, outDir: File, cb: (Int) -> Unit = {}, stripComponents: Int = 0) {
        outDir.mkdirs()
        val raw = archive.inputStream().buffered()
        raw.mark(4)
        val b0 = raw.read(); val b1 = raw.read(); val b2 = raw.read(); val b3 = raw.read()
        raw.reset()
        val compressor = when {
            b0 == 0x1F && b1 == 0x8B -> CompressorStreamFactory().createCompressorInputStream(CompressorStreamFactory.GZIP, raw)
            b0 == 0xFD && b1 == 0x37 && b2 == 0x7A && b3 == 0x58 -> CompressorStreamFactory().createCompressorInputStream(CompressorStreamFactory.XZ, raw)
            else -> null
        }
        val stream = compressor ?: raw
        val tar = ArchiveStreamFactory().createArchiveInputStream("tar", stream) as TarArchiveInputStream
        var count = 0
        while (true) {
            val e = tar.nextEntry ?: break
            var rel = e.name.trim().removePrefix("./")
            if (stripComponents > 0) {
                val parts = rel.split('/').filter { it.isNotEmpty() }
                if (parts.size <= stripComponents) continue
                rel = parts.drop(stripComponents).joinToString("/")
            }
            if (rel.isEmpty() || rel.contains("..")) continue
            val out = File(outDir, rel.removePrefix("/"))
            if (!out.canonicalPath.startsWith(outDir.canonicalPath)) continue
            if (e.isDirectory) { out.mkdirs(); continue }
            out.parentFile?.mkdirs()
            if (e.isSymbolicLink) {
                try {
                    out.delete()
                    android.system.Os.symlink(e.linkName, out.absolutePath)
                } catch (ex: Exception) {
                    // FAT 等不支持 symlink：把目标内容拷过来兜底
                    try {
                        val t = File(outDir, e.linkName.removePrefix("/"))
                        if (t.isFile) t.copyTo(out, true)
                    } catch (_: Exception) {}
                }
                continue
            }
            FileOutputStream(out).use { tar.copyTo(it) }
            // 权限
            try {
                val mode = e.mode and 0b111_111_111
                android.system.Os.chmod(out.absolutePath, mode)
            } catch (_: Exception) {}
            count++
            if (count % 200 == 0) cb(count)
        }
        tar.close()
        (stream as? java.io.Closeable)?.close()
        if (compressor != null) raw.close() else raw.close()
    }
}
