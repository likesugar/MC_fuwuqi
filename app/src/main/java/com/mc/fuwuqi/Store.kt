package com.mc.fuwuqi

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 全局路径与服务器注册表 */
object Store {
    val base: File get() = File(Environment.getExternalStorageDirectory(), "MCFuwuqi")
    val serversDir: File get() = File(base, "servers")
    /** proot/rootfs/JRE 走应用内部存储（无权限干扰，参照物同款 getFilesDir） */
    @Volatile private var internalRun: File? = null
    fun initInternal(ctx: android.content.Context) {
        if (internalRun == null) internalRun = File(ctx.filesDir, "linux").apply { mkdirs() }
    }
    val runDir: File get() = internalRun ?: File(Environment.getExternalStorageDirectory(), "MCFuwuqi/runtime").apply { mkdirs() }
    val registryFile: File get() = File(base, "servers.json")

    fun ensureDirs() {
        base.mkdirs(); serversDir.mkdirs(); runDir.mkdirs()
    }

    fun jdkDir(v: Int): File = File(File(rootfsDir(), "opt"), "jdk-$v")
    fun rootfsDir(): File = File(runDir, "debian")
    fun prootFile(): File = File(runDir, "proot")

    fun hasJdk(v: Int): Boolean = File(jdkDir(v), "bin/java").isFile

    fun hasRootfs(): Boolean = File(rootfsDir(), "bin/sh").isFile
    fun hasProot(): Boolean = prootFile().isFile && prootFile().length() > 300_000

    fun containerReady(): Boolean = hasProot() && hasRootfs()

    @Synchronized
    fun loadServers(): JSONArray {
        ensureDirs()
        if (!registryFile.exists()) return JSONArray()
        return try { JSONArray(registryFile.readText()) } catch (e: Exception) { JSONArray() }
    }

    @Synchronized
    fun saveServers(arr: JSONArray) {
        ensureDirs()
        registryFile.writeText(arr.toString(2))
        UiBus.post()
    }

    fun serverDir(id: String): File = File(serversDir, id)

    fun findServer(id: String): JSONObject? {
        val arr = loadServers()
        for (i in 0 until arr.length()) if (arr.getJSONObject(i).optString("id") == id) return arr.getJSONObject(i)
        return null
    }

    fun updateServer(id: String, patch: JSONObject) {
        val arr = loadServers()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("id") == id) {
                for (k in patch.keys()) o.put(k, patch.get(k))
            }
        }
        saveServers(arr)
    }

    fun newId(): String = "s" + System.currentTimeMillis().toString(36)
}
