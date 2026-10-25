package com.mc.fuwuqi

import android.app.Application
import android.content.Context

class App : Application() {
    companion object { lateinit var inst: Context; private set }
    override fun onCreate() { super.onCreate(); inst = this }
}
