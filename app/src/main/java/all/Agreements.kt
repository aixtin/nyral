package io.github.aixtin.nyral

import android.app.Activity
import android.app.Dialog
import android.content.Context
import io.github.aixtin.nyral.R
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 免责声明 / 隐私政策 全文常量 + 通用阅读弹窗。
 * 与仓库根目录 DISCLAIMER.md / PRIVACY.md 同口径。
 * 引导页用带"滑到底才可同意"的强制弹窗；设置-关于页用只读展示弹窗。
 */
object Agreements {

    /** 免责声明（与仓库 DISCLAIMER.md 同口径的 App 内简版，文案见 strings.xml） */
    fun disclaimerText(ctx: Context): String = ctx.getString(R.string.agr_disclaimer)

    /** 隐私政策（与仓库 PRIVACY.md 同口径的 App 内简版，文案见 strings.xml） */
    fun privacyText(ctx: Context): String = ctx.getString(R.string.agr_privacy)

    /**
     * 只读协议展示弹窗（设置-关于页入口使用）：不强制滑动，底部"知道了"关闭。
     */
    fun showReadonly(activity: Activity, title: String, body: String) {
        val dlg = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)
        dlg.setCancelable(true)
        dlg.setCanceledOnTouchOutside(false)
        val d = activity.resources.displayMetrics.density
        fun dpf(v: Int) = (v * d).toInt()
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.SURFACE, 16, activity)
        }
        panel.addView(TextView(activity).apply {
            text = title
            textSize = 18f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Ui.TEXT)
            gravity = Gravity.CENTER
            setPadding(dpf(20), dpf(20), dpf(20), dpf(12))
        })
        val sv = ScrollView(activity).apply { isFillViewport = true }
        sv.addView(TextView(activity).apply {
            textSize = 14f
            setTextColor(Ui.TEXT)
            setLineSpacing(dpf(3).toFloat(), 1f)
            text = body
            setPadding(dpf(20), dpf(2), dpf(20), dpf(14))
        })
        panel.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val okBtn = TextView(activity).apply {
            text = activity.getString(R.string.agr_got_it)
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            background = Ui.rounded(Ui.PRIMARY, 18, activity)
            setPadding(dpf(6), dpf(13), dpf(6), dpf(13))
            setOnClickListener { dlg.dismiss() }
        }
        val bottomRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dpf(16), dpf(6), dpf(16), dpf(18))
        }
        bottomRow.addView(okBtn, LinearLayout.LayoutParams(dpf(156), ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(bottomRow)
        dlg.setContentView(panel)
        dlg.window?.let { w ->
            val lp = w.attributes
            lp.width = (activity.resources.displayMetrics.widthPixels * 0.9).toInt()
            lp.height = (activity.resources.displayMetrics.heightPixels * 0.8).toInt()
            lp.gravity = Gravity.CENTER
            w.attributes = lp
            w.setBackgroundDrawable(ColorDrawable())
        }
        dlg.show()
    }
}
