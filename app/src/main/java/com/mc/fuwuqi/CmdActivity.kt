package com.mc.fuwuqi

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** CMD */
class CmdActivity : androidx.appcompat.app.AppCompatActivity() {
    private lateinit var out: TextView
    private lateinit var scroll: ScrollView

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#2B2B2B"))

        val bar = Ui.linear(this, false)
        bar.setPadding(Ui.px(this, 20), Ui.px(this, 36), Ui.px(this, 20), Ui.px(this, 12))
        bar.gravity = Gravity.CENTER_VERTICAL
        val back = Ui.text(this, "←", 24f, Color.WHITE)
        back.setOnClickListener { finish() }
        back.setPadding(0, 0, Ui.px(this, 14), 0)
        bar.addView(back)
        bar.addView(Ui.text(this, "执行指令", 22f, Color.WHITE, true), Ui.lp(0, -2, 1f))
        root.addView(bar)
        val inRow = Ui.linear(this, false)
        inRow.setPadding(Ui.px(this, 20), Ui.px(this, 6), Ui.px(this, 20), Ui.px(this, 6))
        inRow.gravity = Gravity.CENTER_VERTICAL
        val input = EditText(this)
        input.setText("adb logcat -b crash -d")
        input.setTextColor(Color.WHITE)
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 15f)
        input.setTypeface(android.graphics.Typeface.MONOSPACE)
        input.setBackgroundResource(android.R.color.transparent)
        input.setSingleLine(true)
        inRow.addView(input, Ui.lp(0, -2, 1f))
        val send = Ui.text(this, "➤", 22f, Color.WHITE, true)
        send.setPadding(Ui.px(this, 12), 0, 0, 0)
        inRow.addView(send)
        root.addView(inRow)

        out = TextView(this)
        out.setTextColor(Color.parseColor("#B9BDBB"))
        out.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13f)
        out.setTypeface(android.graphics.Typeface.MONOSPACE)
        out.setPadding(Ui.px(this, 20), Ui.px(this, 12), Ui.px(this, 20), Ui.px(this, 20))
        scroll = Ui.scroll(this)
        scroll.addView(out, ViewGroup.LayoutParams(-1, -2))
        root.addView(scroll, Ui.lp(-1, 0, 1f))
        send.setOnClickListener {
            val cmd = input.text?.toString()?.trim() ?: return@setOnClickListener
            out.append("\n$ " + cmd + "\n")
            Thread {
                try {
                    val p = ProcessBuilder("sh", "-c", cmd).start()
                    val o = p.inputStream.bufferedReader().use { it.readText() }
                    val e2 = p.errorStream.bufferedReader().use { it.readText() }
                    p.waitFor()
                    runOnUiThread {
                        out.append(o)
                        if (e2.isNotBlank()) out.append(e2)
                        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
                    }
                } catch (e: Exception) {
                    runOnUiThread { out.append("ERR: " + e.message + "\n") }
                }
            }.start()
        }
        setContentView(root)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Ui.immersive(this)
    }
}
