package com.mc.fuwuqi

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** UI 小工具 */
@SuppressLint("StaticFieldLeak")
object Ui {
    fun px(a: Activity, dp: Int): Int = (a.resources.displayMetrics.density * dp).toInt()

    fun linear(a: Activity, vert: Boolean, pad: Int = 0): LinearLayout {
        val l = LinearLayout(a)
        l.orientation = if (vert) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        if (pad > 0) l.setPadding(px(a, pad), px(a, pad), px(a, pad), px(a, pad))
        return l
    }

    fun text(a: Activity, s: String, size: Float, color: Int, bold: Boolean = false): TextView {
        val t = TextView(a)
        t.text = s
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, size)
        t.setTextColor(color)
        if (bold) t.paint.isFakeBoldText = true
        return t
    }

    fun card(a: Activity): LinearLayout {
        val l = linear(a, true)
        l.setBackgroundResource(R.drawable.bg_card)
        return l
    }

    fun lp(w: Int, h: Int): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h)

    fun lp(w: Int, h: Int, weight: Float): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight)

    fun toast(a: Activity, s: String) = Toast.makeText(a, s, Toast.LENGTH_SHORT).show()

    /** 沉浸式：隐藏状态栏和导航栏，下滑临时呼出 */
    fun immersive(a: Activity) {
        Store.initInternal(a.applicationContext)
        val w = a.window
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(w, false)
            val content = a.findViewById<android.view.ViewGroup>(android.R.id.content)
            content.setOnApplyWindowInsetsListener { v, insets ->
                val wi = insets as android.view.WindowInsets
                val ime = wi.getInsets(android.view.WindowInsets.Type.ime()).bottom
                v.setPadding(0, 0, 0, ime)
                insets
            }
            val c = androidx.core.view.WindowInsetsControllerCompat(w, w.decorView)
            c.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars() or androidx.core.view.WindowInsetsCompat.Type.navigationBars())
            c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            w.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    fun scroll(a: Activity): android.widget.ScrollView {
        val s = android.widget.ScrollView(a)
        s.isFillViewport = true
        s.scrollBarStyle = View.SCROLLBARS_INSIDE_OVERLAY
        return s
    }

    /** 只判断不跳转 */
    fun needStorageSilent(a: Activity): Boolean =
        Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    fun needStorage(a: Activity): Boolean {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            try {
                a.startActivity(android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:${a.packageName}")))
            } catch (e: Exception) {
                a.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
            toast(a, "需要「所有文件访问权限」存放服务器文件")
            return false
        }
        return true
    }

    fun notifGranted(a: Activity): Boolean =
        Build.VERSION.SDK_INT < 33 || a.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun topBar(a: Activity, title: String, sub: String = "", right: Pair<String, View.OnClickListener>? = null): View {
        val bar = linear(a, false)
        bar.setPadding(px(a, 20), px(a, 28), px(a, 20), px(a, 16))
        bar.gravity = Gravity.CENTER_VERTICAL
        val back = text(a, "←", 24f, a.getColor(R.color.text))
        back.setPadding(0, 0, px(a, 16), 0)
        back.setOnClickListener { a.finish() }
        val col = linear(a, true)
        col.addView(text(a, title, 24f, a.getColor(R.color.text), true))
        if (sub.isNotEmpty()) {
            val st = text(a, sub, 13f, a.getColor(R.color.text_sub))
            st.setPadding(0, px(a, 2), 0, 0)
            col.addView(st)
        }
        val mid = linear(a, false)
        mid.addView(col)
        bar.addView(back)
        bar.addView(mid, lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (right != null) {
            val r = text(a, right.first, 18f, a.getColor(R.color.green), true)
            r.setPadding(px(a, 14), px(a, 6), px(a, 6), px(a, 6))
            r.setOnClickListener(right.second)
            bar.addView(r)
        }
        return bar
    }
}
