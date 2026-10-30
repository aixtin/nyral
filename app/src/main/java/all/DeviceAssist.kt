package io.github.aixtin.nyral

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * 设备面板 ⇄ 会话 绑定(2026-10-30 老板拍定)。
 *
 * 「问 AI」不每次新开会话: 固定绑定一条普通会话「设备分析」——点一次就跳到那条会话, 并带上当前真实读数提问。
 * 上下文策略: 每轮只带静态画像 + 本次快照 + 最近几轮(历史本就在会话里), 不注入趋势原始序列、不继承全历史。
 * 会话被用户删掉时静默重建同名会话, 不报错。
 */
object DeviceAssist {

    const val TITLE = "设备分析"
    const val EXTRA_SESSION = "panel_session_id"
    const val EXTRA_ASK = "panel_ask_text"

    /**
     * 取(或静默重建)绑定的会话 id; 失败返回 0。
     *
     * 用会话 id 存 SharedPreferences 而不是按标题找: 会话发完第一条消息后标题会被自动改写
     * (实测会变成设备型号那类摘要), 按标题找会在第二次点击时新建一条, 变成每点一次开一条。
     */
    fun ensureSession(c: Context): Long {
        return try {
            val sp = c.getSharedPreferences("device_panel", Context.MODE_PRIVATE)
            val saved = sp.getLong("sid", 0L)
            val db = MemoryDb(c)
            if (saved > 0L && db.sessionTitleOf(saved) != null) return saved
            val sid = db.saveSession(TITLE, emptyList(), ModeConfig.modeValue(), MemoryDb.KIND_NORMAL)
            if (sid > 0L) sp.edit().putLong("sid", sid).apply()
            sid
        } catch (e: Throwable) {
            android.util.Log.w("DeviceAssist", "绑定会话失败: " + e.message)
            0L
        }
    }

    /** 点击「问 AI」: 带会话 id + 本次快照提问唤醒主界面 */
    /** 是否为设备面板绑定的那条会话(用于把它切到只读能力域) */
    fun isPanelSession(c: Context, id: Long): Boolean =
        id > 0L && c.getSharedPreferences("device_panel", Context.MODE_PRIVATE).getLong("sid", 0L) == id

    fun ask(act: Activity) {
        val sid = ensureSession(act)
        // 标题自愈(2026-10-30): 首条消息会让标题自动变成 "[设备] 22041216UC ..." 这类摘要,
        // 抽屉里认不出来; 只要还是自动标题就改回固定名, 用户手动改过的不动。
        try {
            val db = MemoryDb(act)
            val t = db.sessionTitleOf(sid).orEmpty()
            if (t.isEmpty() || t.startsWith("[设备]") || t == "新会话" || t.contains("手机")) {
                db.renameSession(sid, TITLE)
            }
        } catch (e: Throwable) { }
        if (sid <= 0L) {
            Toast.makeText(act, "会话创建失败, 无法带读数提问", Toast.LENGTH_SHORT).show()
            return
        }
        val text = try { DeviceMetrics.dumpText(act) } catch (e: Throwable) { "" }
        val ask = (if (text.isBlank()) "" else text + "\n") +
                "以上是我手机当前的真实读数(设备面板实时采集)。请判断: 1) 有没有异常或异常趋势; " +
                "2) 最可能的原因(基于这些数字, 不要泛泛而谈); " +
                "3) 要确认得再查什么(可用 device_probe 追单个包/进程, device_snapshot 取最新读数)。"
        val i = Intent(act, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            putExtra(EXTRA_SESSION, sid)
            putExtra(EXTRA_ASK, ask)
        }
        act.startActivity(i)
    }
}
