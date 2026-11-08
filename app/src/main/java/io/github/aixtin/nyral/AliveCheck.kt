package io.github.aixtin.nyral

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
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
 * 存活回执 + 建议收集(2026-11-08)。
 *
 * - 触发: 版本更新后首次进入主界面, 首次安装不打扰; 同一版本只问一次。
 * - 交互: 「检测活人」主按钮确认 -> 再来一屏「提个建议」输入框(可取消)。
 * - 上报: 复用 crash 通道(atin.asia/crash, 同一 token 与 install id), 报文以
 *   [ALIVE] / [FEEDBACK] 前缀区分, 服务端落库后按前缀识别即可, 无需改服务端。
 * - 隐私: 只回传版本号/版本码/系统版本/时间戳/安装随机 ID; 建议内容仅在用户
 *   主动点「提交」时上传, 且弹窗内已明示。崩溃上报开关关闭时整体不弹。
 */
object AliveCheck {

    private const val TAG = "Nyral"
    private const val PREFS = "alive_check"
    private const val K_LAST_VERSION = "last_version"
    private const val K_ANSWERED = "answered_version"
    private const val ENDPOINT = "https://atin.asia/crash"
    private const val INSTALL_PREFS = "crash_install"
    private const val K_INSTALL_ID = "install_id"
    private const val TIMEOUT_MS = 8000
    private const val MAX_SUGGEST_LEN = 500
    private const val MAX_RETRY = 3
    private const val RETRY_DELAY_MS = 1500L
    private const val UPGRADE_GAP_MS = 1_000L

    /** 调用方(MainActivity)首屏稳定后再问, 避免与首帧渲染/更新检查抢焦点。 */
    const val START_DELAY_MS = 3000L
    private const val GAP_DP = 12
    private const val GAP_TIGHT_DP = 10
    /** 弱化取消按钮的上间距: 视觉上更贴近主按钮, 减少存在感 */
    private const val GHOST_GAP_DP = 2
    private const val SUGGEST_MIN_LINES = 3
    private const val SUGGEST_MAX_LINES = 6

    /** 主入口: 在 MainActivity onCreate 尾部延迟调用。attempt 为内部重试计数。 */
    fun maybePrompt(act: Activity, attempt: Int = 0) {
        if (act.isFinishing || act.isDestroyed) return
        val prefs = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cur = BuildConfig.VERSION_NAME
        val last = prefs.getString(K_LAST_VERSION, null)
        val answered = prefs.getString(K_ANSWERED, null) == cur
        // last_version 自 v2.9.11 才引入, 老用户升级上来时必然为 null;
        // 因此 null 不等同于首装: "安装/更新时间有差"(真实升级) 或 "本机早已跑过旧版"
        // 任一成立即视为升级, 只有"两个时间戳完全相同 且 从未运行过本 App"才算首装。
        val upgraded = wasUpgraded(act)
        val veteran = hasRunBefore(act)
        val firstInstall = last == null && !upgraded && !veteran
        Log.i(TAG, "alive_check: cur=" + cur + " last=" + last + " answered=" + answered +
                " upgraded=" + upgraded + " veteran=" + veteran + " focus=" + act.hasWindowFocus())
        when {
            // 首次安装(或本版已装过): 只记账, 不打扰
            firstInstall || last == cur ->
                prefs.edit().putString(K_LAST_VERSION, cur).apply()
            // 本版已问过 / 用户关了上报: 不弹
            answered || !CrashReportConfig.enabled(act) ->
                prefs.edit().putString(K_LAST_VERSION, cur).apply()
            // 已有其它弹窗(如更新提示)在前台时让位, 稍后重试
            !act.hasWindowFocus() && attempt < MAX_RETRY -> retryLater(act, attempt)
            else -> {
                showAlive(act, prefs, cur)
                prefs.edit().putString(K_LAST_VERSION, cur).apply()
            }
        }
    }

    /** 是否为升级安装: 首装时 firstInstallTime 与 lastUpdateTime 相同。清除数据不改变这两个值, 不会误判。 */
    private fun wasUpgraded(act: Activity): Boolean = try {
        val pi = act.packageManager.getPackageInfo(act.packageName, 0)
        pi.lastUpdateTime - pi.firstInstallTime > UPGRADE_GAP_MS
    } catch (e: Exception) {
        false
    }

    /** 本机是否早就跑过本 App(旧版本没有 alive_check, 但崩溃上报的 install_id 早已生成)。 */
    private fun hasRunBefore(act: Activity): Boolean = try {
        act.getSharedPreferences(INSTALL_PREFS, Context.MODE_PRIVATE).contains(K_INSTALL_ID)
    } catch (e: Exception) {
        false
    }

    /** 重试次数用尽后本次放弃, 且不写已答标记(下次冷启动还有机会问)。 */
    private fun retryLater(act: Activity, attempt: Int) {
        if (attempt >= MAX_RETRY) return
        act.window.decorView.postDelayed({ maybePrompt(act, attempt + 1) }, RETRY_DELAY_MS)
    }

    private fun showAlive(act: Activity, prefs: SharedPreferences, cur: String) {
        val (d, box) = Ui.dialog(act, "检测活人")
        box.addView(Ui.dialogText(act, "还在用 Nyral 吗？点一下我就知道。\n" +
                "只回传一行匿名回执（版本号、系统版本、安装随机 ID），不含任何聊天内容。"))
        // 确认/取消/点弹窗外都算一次活人证据: 都回执, 用 answer 区分态度, 不再把取消静默丢掉
        var sent = false
        fun report(answer: String) {
            if (sent) return
            sent = true
            markAnswered(prefs, cur)
            upload(act, meta("PULSE") + "answer: " + answer + "\n")
        }
        box.addView(Ui.primaryBtn(act, "是的，还在用") {
            report("yes")
            d.dismiss()
            showSuggest(act)
        }, gapParams(act, GAP_DP))
        // 取消走弱化文字按钮: 无底色无边框、小字灰字, 让第一眼只落在确认按钮上
        box.addView(Ui.ghostBtn(act, "取消") {
            report("cancel")
            d.dismiss()
        }, wrapGapParams(act, GHOST_GAP_DP))
        // 点弹窗外关闭同样按取消记账(上报 + 本版不再追问); Activity 正在销毁时不记, 留给下次冷启动
        d.setOnDismissListener {
            if (!sent && !act.isFinishing && !act.isDestroyed) report("cancel")
        }
        d.show()
    }

    private fun showSuggest(act: Activity) {
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

    private fun markAnswered(prefs: SharedPreferences, cur: String) {
        prefs.edit().putString(K_ANSWERED, cur).apply()
    }

    private fun meta(type: String): String = buildString {
        append("type: ").append(type).append('\n')
        append("time: ").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            .format(java.util.Date())).append('\n')
        append("app: ").append(BuildConfig.VERSION_NAME)
        append(" (vc ").append(BuildConfig.VERSION_CODE).append(")\n")
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
                    Log.i(TAG, "存活回执已发送 http=" + conn.responseCode)
                } finally {
                    conn.disconnect()
                }
            } catch (e: java.io.IOException) {
                Log.w(TAG, "存活回执发送失败: " + e.message)
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
