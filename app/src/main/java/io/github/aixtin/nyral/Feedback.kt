package io.github.aixtin.nyral

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID

/**
 * 意见反馈(2026-11-09 由 AliveCheck 拆分)。
 *
 * - 入口: 设置-关于页「意见反馈」行, 用户主动点击才弹输入框; 不再随启动/更新自动弹出。
 * - 上报: 复用 crash 通道(atin.asia/crash, 同一 token 与 install id), 报文以
 *   [FEEDBACK] 前缀区分, 服务端落库后按前缀识别即可, 无需改服务端。
 * - 隐私: 只回传版本号/版本码/系统版本/时间戳/安装随机 ID, 建议内容仅在用户点
 *   「提交」时上传, 且弹窗内已明示。
 */
object Feedback {

    private const val TAG = "Nyral"
    private const val ENDPOINT = "https://atin.asia/crash"
    private const val INSTALL_PREFS = "crash_install"
    private const val K_INSTALL_ID = "install_id"
    private const val TIMEOUT_MS = 8000
    private const val MAX_SUGGEST_LEN = 500
    private const val GAP_DP = 12
    /** 弱化取消按钮的上间距: 视觉上更贴近主按钮, 减少存在感 */
    private const val GHOST_GAP_DP = 2
    private const val SUGGEST_MIN_LINES = 3
    private const val SUGGEST_MAX_LINES = 6

    /** 关于页入口: 弹出「提个建议」输入框。 */
    fun showSuggest(act: Activity) {
        if (act.isFinishing || act.isDestroyed) return
        val (d, box) = Ui.dialog(act, "提个建议")
        box.addView(Ui.dialogText(act, "想改什么、哪里别扭，写下来。\n内容会原样发给我，最长 " +
                MAX_SUGGEST_LEN + " 字。"))
        val edit = Ui.input(act, "一句话也行")
        edit.isSingleLine = false
        edit.minLines = SUGGEST_MIN_LINES
        edit.maxLines = SUGGEST_MAX_LINES
        edit.gravity = Gravity.TOP or Gravity.START
        box.addView(edit, gapParams(act, GAP_DP))
        box.addView(Ui.primaryBtn(act, "提交") {
            val text = edit.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(act, "还没写内容", Toast.LENGTH_SHORT).show()
            } else {
                val body = text.take(MAX_SUGGEST_LEN)
                upload(act, meta("FEEDBACK") + "len: " + body.length + "\ntext:\n" + body)
                d.dismiss()
                Toast.makeText(act, "收到了，谢谢", Toast.LENGTH_SHORT).show()
            }
        }, gapParams(act, GAP_DP))
        box.addView(Ui.ghostBtn(act, "取消") { d.dismiss() }, wrapGapParams(act, GHOST_GAP_DP))
        d.show()
    }

    private fun meta(type: String): String = buildString {
        append("type: ").append(type).append('\n')
        append("time: ").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            .format(java.util.Date())).append('\n')
        append("app: ").append(BuildConfig.VERSION_NAME)
        append(" (vc ").append(BuildConfig.VERSION_CODE).append(")\n")
        append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n')
        append("android: ").append(Build.VERSION.RELEASE)
        append(" / SDK ").append(Build.VERSION.SDK_INT).append('\n')
    }

    /**
     * 复用崩溃通道发送一条纯文本, 单次尝试不重试(交互型回执不值得退避)。
     * token 为空(本地占位构建)时直接跳过, 避免白跑一次 401。
     */
    private fun upload(ctx: Context, body: String) {
        val token = BuildConfig.CRASH_TOKEN
        if (token.isBlank()) return
        val id = installId(ctx)
        Thread {
            try {
                val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.connectTimeout = TIMEOUT_MS
                    conn.readTimeout = TIMEOUT_MS
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                    conn.setRequestProperty("X-Auth-Token", token)
                    conn.setRequestProperty("X-Install-Id", id)
                    conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                    Log.i(TAG, "反馈已发送 http=" + conn.responseCode)
                } finally {
                    conn.disconnect()
                }
            } catch (e: java.io.IOException) {
                Log.w(TAG, "反馈发送失败: " + e.message)
            }
        }.start()
    }

    /** 与 NyralApp 共用同一个 crash_install/install_id, 服务端配额按此维度计数。 */
    private fun installId(ctx: Context): String {
        val p = ctx.getSharedPreferences(INSTALL_PREFS, Context.MODE_PRIVATE)
        return p.getString(K_INSTALL_ID, null) ?: UUID.randomUUID().toString().also {
            p.edit().putString(K_INSTALL_ID, it).apply()
        }
    }

    /** 宽度自适应并水平居中的上间距布局参数(给弱化按钮用) */
    private fun wrapGapParams(act: Activity, topDp: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = Ui.dp(act, topDp)
            gravity = Gravity.CENTER_HORIZONTAL
        }

    private fun gapParams(act: Activity, topDp: Int): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Ui.dp(act, topDp) }
}
