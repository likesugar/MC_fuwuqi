package com.mc.fuwuqi

import android.content.Context
import java.io.File
import org.json.JSONArray

/** 容器层：proot（native libs，随 APK 内置）+ Debian rootfs（内置 assets）+ JRE 按需下载 */
object Container {

    /** 各 JRE：Adoptium API 直链（跟随重定向），失败走清华镜像目录解析 */
    const val ADOPTIUM = "https://api.adoptium.net/v3/binary/latest/%d/ga/linux/aarch64/jre/hotspot/normal/eclipse"
    const val TUNA_DIR = "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/%d/jre/aarch64/linux/"

    val jdkDesc = mapOf(
        8 to "支持 1.7 – 1.16.5",
        11 to "部分旧版整合包 / 特殊端",
        17 to "支持 1.17 – 1.20.4",
        21 to "支持 1.20.5 – 1.21.x",
        25 to "支持 26.x 及以后"
    )

    fun javaForMc(mcVersion: String): Int {
        val p = mcVersion.split('.')
        val major = p.getOrNull(1)?.toIntOrNull() ?: 0
        val minor = p.getOrNull(2)?.toIntOrNull() ?: 0
        return when {
            mcVersion.startsWith("26.") -> 25
            major >= 21 || (major == 20 && minor >= 5) -> 21
            major >= 17 -> 17
            else -> 8
        }
    }

    /** proot 本体（随 APK 安装，位于 nativeLibraryDir） */
    fun prootBin(ctx: Context): File {
        val nd = File(ctx.applicationInfo.nativeLibraryDir)
        return File(nd, "libproot.so")
    }

    fun hasProot(ctx: Context): Boolean = prootBin(ctx).isFile

    /** 解压内置 rootfs（tar.gz，无需网络） */
    fun ensureRootfs(ctx: Context, cb: (String) -> Unit): Boolean {
        if (Store.hasRootfs()) return true
        cb("解压内置容器 …")
        val tmp = File(Store.runDir, "rootfs-a64.bin")
        if (!tmp.isFile) {
            ctx.assets.open("rootfs/rootfs-a64.bin").use { ins ->
                tmp.parentFile?.mkdirs()
                tmp.outputStream().use { ins.copyTo(it) }
            }
        }
        cb("解包容器 rootfs …")
        Dl.extractTar(tmp, Store.rootfsDir(), { n -> cb("解包容器 rootfs … $n 个文件") })
        tmp.delete()
        File(Store.rootfsDir(), ".minehost-rootfs").writeText("1")
        return Store.hasRootfs()
    }

    fun downloadJdk(v: Int, cb: (String) -> Unit): Boolean {
        if (Store.hasJdk(v)) return true
        // 旧版本装在 runDir/jdk<v>（目录改名前）：直接迁移进容器，避免重下 45MB
        val legacy = File(Store.runDir, "jdk$v")
        if (File(legacy, "bin/java").isFile) {
            cb("迁移已下载的 Java $v …")
            val dst = Store.jdkDir(v)
            dst.parentFile?.mkdirs(); dst.deleteRecursively()
            if (!legacy.renameTo(dst)) { legacy.copyRecursively(dst, true); legacy.deleteRecursively() }
            File(dst, "bin/java").setExecutable(true)
            if (Store.hasJdk(v)) return true
        }
        val tmp = File(Store.runDir, "jdk$v.tar.gz")
        cb("下载 Java $v（约 45MB）…")
        // 1) 清华 JRE（45MB，优先） 2) Adoptium JRE 3) gh-proxy
        var pkgName = ""
        try {
            val html = Dl.fetchText(TUNA_DIR.format(v))
            val rx = Regex("OpenJDK${v}U-(?:jre|jdk)_aarch64_linux_hotspot_[0-9][0-9a-zA-Z._-]*\\.tar\\.gz")
            pkgName = rx.findAll(html).map { it.value }.distinct().lastOrNull()
                ?: throw RuntimeException("no pkg")
            Dl.download(TUNA_DIR.format(v) + name, tmp) { cur, tot ->
                cb("下载 Java $v ${fmt(cur)}" + (if (tot > 0) " / ${fmt(tot)}" else ""))
            }
        } catch (e: Exception) {
            // 清华失败 → 本仓库 jre-assets（Temurin 官方包镜像）
            if (pkgName.isEmpty()) throw RuntimeException("清华列表不可用")
            try {
                cb("清华失败，尝试本仓库 JRE 源 …")
                Dl.download("https://github.com/likesugar/MC_fuwuqi/releases/download/jre-assets/$pkgName", tmp) { cur, tot ->
                    cb("下载 Java $v ${fmt(cur)}" + (if (tot > 0) " / ${fmt(tot)}" else ""))
                }
            } catch (e3: Exception) {
                cb("本仓库失败，尝试 Adoptium …")
                try {
                    Dl.download(String.format(ADOPTIUM, v), tmp) { cur, tot ->
                        cb("下载 Java $v ${fmt(cur)}" + (if (tot > 0) " / ${fmt(tot)}" else ""))
                    }
                } catch (e2: Exception) {
                    Dl.download("https://gh-proxy.com/" + String.format(ADOPTIUM, v), tmp) { cur, tot ->
                        cb("下载 Java $v ${fmt(cur)}" + (if (tot > 0) " / ${fmt(tot)}" else ""))
                    }
                }
            }
        }
cb("解包 Java $v …")
        // 参照物同款：strip 顶层目录直接解进 rootfs/opt/jdk-<v>
        val dst = Store.jdkDir(v)
        dst.deleteRecursively(); dst.mkdirs()
        Dl.extractTar(tmp, dst, { n -> cb("解包 Java $v … $n 个文件") }, stripComponents = 1)
        if (!Store.hasJdk(v)) {
            // 兜底：结构异常时全量再扫一次
            var found: File? = null
            for (l1 in dst.listFiles() ?: emptyArray()) {
                if (File(l1, "bin/java").isFile) { found = l1; break }
                for (l2 in l1.listFiles() ?: emptyArray()) if (File(l2, "bin/java").isFile) { found = l2; break }
                if (found != null) break
            }
            if (found != null && found != dst) {
                val tmpD = File(dst.parentFile, "jdk${v}_m")
                found.renameTo(tmpD); dst.deleteRecursively(); tmpD.renameTo(dst)
            }
        }
        if (!Store.hasJdk(v)) {
            dst.deleteRecursively(); tmp.delete()
            throw RuntimeException("解包后未找到 bin/java（包可能损坏）")
        }
        File(dst, "bin/java").setExecutable(true)
        tmp.delete()
        return true

    }

    fun fmt(b: Long): String = if (b >= 1_000_000) "${"%.1f".format(b / 1_000_000f)}MB" else "${b / 1024}KB"

    /** 设置页状态：容器 + 5 个 JRE */
    fun statusJson(ctx: Context): String {
        val o = org.json.JSONObject()
        o.put("proot", hasProot(ctx))
        o.put("rootfs", Store.hasRootfs())
        val j = JSONArray()
        for (v in intArrayOf(8, 11, 17, 21, 25)) {
            val jo = org.json.JSONObject()
            jo.put("v", v)
            jo.put("installed", Store.hasJdk(v))
            j.put(jo)
        }
        o.put("jdks", j)
        return o.toString()
    }
}
