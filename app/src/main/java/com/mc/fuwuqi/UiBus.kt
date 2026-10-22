package com.mc.fuwuqi

/** 参照物 StateFlow 的等价物：任何状态变更 post，订阅页自动刷新 */
object UiBus {
    private val listeners = mutableListOf<() -> Unit>()

    fun subscribe(cb: () -> Unit) { synchronized(listeners) { listeners.add(cb) } }
    fun unsubscribe(cb: () -> Unit) { synchronized(listeners) { listeners.remove(cb) } }

    @Volatile private var lastPost = 0L
    fun post() {
        // 300ms 抖动合并，避免日志刷屏拖垮 UI
        val now = System.currentTimeMillis()
        if (now - lastPost < 300) return
        lastPost = now
        synchronized(listeners) { listeners.toList().forEach { it() } }
    }
}
